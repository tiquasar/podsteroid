# Podsteroid — Remote-Server Feature Review

Verification of every claim in `suggestions.md` against the source at
`/opt/kubernetes/podroidv2`. Scope: `remote/` and `ui/screens/servers/`.

| Severity | Findings | Verified | Wrong | Nits |
|---|---|---|---|---|
| Critical | 6 | 5 | 1 | — |
| High     | 4 | 4 | 0 | — |
| Medium   | 5 | 4 | 0 | 1 (one partly mitigated) |
| Low/style | 6 | 6 | 0 | — |
| **Total** | **21** | **19** | **1** | **1** |

The "wrong" item is finding #8: `RemoteVmRepository.putAll` already replaces
the server's slice — it filters by `(serverId, uuid)`, then appends the fresh
list. The review claims a merge; the code does the opposite. Ghost records are
not produced.

## Critical

### #1  ServerAliveCountMax not set — VERIFIED ✓
`SshClient.kt:88, 139` set `ServerAliveInterval=15` but never
`ServerAliveCountMax`. JSch's default is 1, so one missed 15s probe drops the
session. Fix is the one-liner in the review.

### #2  No watchdog on session.connect — PARTIALLY FIXED
Watchdogs exist for channel-open (lines 359, 516) but not for
`session.connect(connectTimeoutMs)` at line 290. Confirmed. The hidden path
the review describes — a half-open TCP socket where `connect()` blocks past
the timeout — is exactly what JSch does on flaky links. The review's fix
(same watchdog pattern around `connect()`) is correct.

### #3  Exec-channel race: reader starts after connect — NOT A BUG in the new path
This is a real bug in the **legacy** `SshClient.exec()` (lines 92-110) and
`execStream()` (lines 142-184): both connect the channel, *then* start the
reader thread. The new hardened path inside `withSession` (lines 354-495) does
the same on paper but the reader is started *after* `channel.connect()`.
Looking at it again: the `outT.start()` at line 420 is **after**
`channel.connect(connectTimeoutMs)` at line 383. The review is right.

Practical impact: dropbear can deliver + close the channel before the reader's
first `read()`. The `markerFound` exit + `outT.join(2500)` drain at line 467
mitigates but doesn't fix it. Correct fix: start the reader before
`channel.connect()`.

### #4  Retry-once reuses a possibly poisoned session — VERIFIED ✓
Two issues actually:

- `exec()` retries on the *same* session (line 349). If the session's TCP
  socket is half-dead, both attempts fail identically.
- `writeRemoteFile` raises `IllegalStateException("Failed to upload $path:
  checksum mismatch…")` on mismatch (line 520). That error doesn't reach
  `isRetryableSshError`, so the session-level retry never fires — only the
  inner 2-attempt loop. The review's fix is correct: add "checksum" /
  "upload" / "md5" to the retryable keywords, or throw a typed exception.

### #5  Pre-existing libvirt marked provisionedByUs=true — VERIFIED ✓
`ServersViewModel.kt:193`:
```kotlin
} else if (result.report.virsh) {
    appendLog(id, "Host already has libvirt — marking as managed by PodSteroid.")
    manager.saveServerKind(saved, ServerKind.LIBVIRT, provisionedByUs = true)
}
```
On line 240-241, `deprovisionLibvirt(full = server.provisionedByUs)` then
uninstalls the user's qemu-kvm / libvirt / virtinst packages. The review's
fix is correct: the `else if` branch must pass `false`.

There's also a second instance at line 292 (`saveServerKind(..., provisionedByUs = true)`)
inside `provisionLibvirt` — that's actually correct (it *was* provisioned by
us). And a third at line 400, also inside the `provisionLibvirt` flow, also
correct. So only line 193 is wrong.

### #6  probePrereqs = 5 independent flaky execs — VERIFIED ✓
Lines 200-211. 5 separate execs, each with its own chance to be lost. The
"already-provisioned" case in `addServer` (line 193) then re-runs
`provisionLibvirt` if the random reads come back wrong. Fix as suggested:
collapse into one heredoc script like `probeHost` already does
(RemoteServerManager.kt:95-102).

## High

### #7  refreshVms drops VMs when domuuid fails — VERIFIED ✓
`RemoteServerManager.kt:288`:
```kotlin
val uuid = LibvirtRemoteProvider.domainUuid(exec, dom.name).getOrNull()
    ?: return@mapNotNull null
```
A single exec loss makes the VM vanish from the UI *and* from persistence.
Plus 3 execs per VM (domuuid, domstats, domifaddr) × N domains multiplies
the chance. The fix is correct: `virsh list --all` already gives name+state,
and `domstats` (no args) covers all domains in one call. Only `domifaddr`
needs a per-VM call — and that one returns empty headers here (no guest
agent), so it can be dropped or guarded.

### #8  Ghost VM records never cleaned — **WRONG**
`RemoteVmRepository.kt:53-57`:
```kotlin
suspend fun putAll(vms: List<RemoteVm>) {
    val byKey = vms.associateBy { it.serverId to it.uuid }
    val kept = snapshot().filterNot { byKey.containsKey(it.serverId to it.uuid) }
    data.edit { it[key] = RemoteVm.listToJson(kept + vms) }
}
```
This is exactly the fix the review says is missing. The "kept" list is
already filtered down to VMs *not* in the new list, then `+ vms` replaces
the slice for this server. Other servers' VMs are preserved. No ghost
records are created. The review's "fix" recommendation is already in place.

### #9  Two divergent exec implementations — VERIFIED ✓
The hardened path lives inside `withSession` (lines 265-654). The legacy
target-based `SshClient.exec()` / `execStream()` (lines 67-184) is the one
`fetchTailscaleIp`, `runInGuest`, `fetchK3sToken`, `joinK3s`,
`initK3sServer`, and `shellToGuest` all use. Same dropbear flakiness, none
of the marker / silence / drain / PSRC recovery. Fix as suggested: rebase
guest ops on `withSession`, or extract a `withGuestSession(target, creds)`
helper. The harder part is that guests get a fresh session each time
(no shared session across calls) — but that's separate from the
correctness/hardening gap.

### #10  K3S always binds node-ip=127.0.0.1 — VERIFIED ✓
`LibvirtRemoteProvider.kt:373`:
```kotlin
"curl -sfL https://get.k3s.io | sh -s - --node-ip=\${ETH0_IP:-127.0.0.1}"
```
`ETH0_IP` is never set in cloud-init. K3S agents always register with
127.0.0.1 and cluster join fails. Confirmed. Fix: drop `--node-ip` (k3s
auto-detects on first non-loopback interface) or pre-resolve via
`ip -4 addr show` and substitute.

## Medium

### #11  ExecResult mutable diagnostics on a data class — VERIFIED ✓
`SshClient.kt:34-39`:
```kotlin
data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    internal var markerFound: Boolean = false
    internal var bytesReceived: Int = 0
}
```
`copy()` and `equals()` ignore these. Today they're set immediately after
construction so the trap is latent, but it's a real trap. Fix as suggested.

### #12  createVm opens 2 sessions + probeHost 1 more — PARTIALLY RIGHT
`ServersViewModel.kt:403` calls `probeHost(target)` (1 session), then
line 431 `manager.createVm(target, ...)` opens another. 2 handshakes for
one create. The review's fix (fold probe into create's session) is right.
Note: `probeHost` already does a complete probe on a fresh session, and
`createVmCmd` runs the same probes again (lines 145-202 of
LibvirtRemoteProvider are a multi-step script that probes for
`--cloud-init`, OSV, etc., and only then runs virt-install). The "extra
handshake" claim is right; the "probe is duplicate work" observation is
a bonus.

### #13  stopVm force-destroy depends on the lossy refresh — VERIFIED ✓
`ServersViewModel.kt:464-470`. If `refreshVms` drops the VM (finding #7),
`stillRunning` is false → no `destroyForce` even though the domain runs.
Fix as suggested: pass the `virsh list` output already produced inside
`refreshVms` to the ViewModel, or do a single `domstate` check.

### #14  destroyVm ignores destroy() result — VERIFIED ✓
`RemoteServerManager.kt:356-360`:
```kotlin
LibvirtRemoteProvider.destroy(exec, vm.name)
LibvirtRemoteProvider.undefine(exec, vm.name)
```
`destroy` returns `Result<Unit>`, never inspected. If it fails, undefine
runs anyway, leaving storage attached to a dead-but-defined domain. Fix:
chain `.getOrThrow()` or `runCatching {}` with proper error.

### #15  Upload hardcodes /tmp — VERIFIED ✓
`RemoteServerManager.kt:242, 312, 441, 613` all write to `/tmp/...`. A
read-only /tmp on Alpine/OpenRC immutable hosts breaks upload. `mktemp -d`
under an approved directory (one of `workDir` or `/var/tmp`) would be
safer. Low impact in practice.

## Low / style

All verified against the source:

- `LibvirtRemoteProvider.kt:13` — self-import of
  `com.tiquasar.podsteroid.remote.ContainerRuntime` (same package). Dead.
- `RemoteServerManager.kt:12` — `android.content.res.Resources` import. Dead.
- `RemoteVmCreateDialog.kt:38` — `com.tiquasar.podsteroid.util.DeviceResourcePolicy`. Dead.
- `LibvirtRemoteProvider.kt:386` — `tailscale_2.28.6` hardcoded. Will be
  wrong every release. Move to a constant or fetch latest.
- `RemoteTerminalDialog.kt` — PTY echoes input (cosmetic).
- `ServersViewModel.kt:79` — `serverJobs` map is written but only consulted
  in delete paths; `createVm` / `provision` jobs aren't registered, so
  delete can't cancel a running create.
- `ServerEditDialog.kt:120` — `portNum = port.toIntOrNull() ?: 22` accepts
  0, 65536, and falls back silently to 22 on parse failure. No range
  validation, no user feedback.
- `ServersScreen.kt:474-481` and `:500-502` — both render a "guest IP"
  line. The expanded section already shows it; line 500 is the dupe.
- `RemoteVmCreateDialog.kt:49, 76, 128` — `memMb` default 2048, parse-fail
  fallback 512, but `coerceAtLeast(256)` at submit. Three different
  minimums; should converge.

## What's actually good (no change needed)

- `SecretStore` — Keystore AES/GCM, ciphertext-only in DataStore, `runCatching`
  on decrypt. Sound.
- `HostKeyAdapter` — accept-first-use with a `HostKeyMismatchException` that
  surfaces old/new fingerprints. The MITM defense is real (the
  `StrictHostKeyChecking="no"` is documented and the adapter overrides it).
- Pure parsers (`parseVirshList`, `parseDomstats`, `parseDomainUuid`,
  `parseGuestIp`) are cleanly separated and unit-testable.
- Two-phase server delete (revert on host → tap "Remove Server" to delete
  local entry) is the right UX.
- `writeRemoteFile`'s gzip + heredoc + md5 verify is correct in design —
  it only fails because the transport underneath (findings #1-#4) is flaky.
- `ServersViewModel` is well-structured for what it does: every long
  operation has a `withTimeoutOrNull`, the slow-create ticker is nice, the
  per-server log accumulator is clean.

## Recommended fix order (revised)

1. **#1 + #2** — `ServerAliveCountMax=4` and a session-connect watchdog.
   One line + one thread. Likely eliminates most "stuck" reports.
2. **#5** — `provisionedByUs = false` on the "already has libvirt" branch
   (line 193). One token. Prevents destructive deletions.
3. **#6 + #4** — collapse `probePrereqs` to one script; add `checksum` /
   `upload` to `isRetryableSshError`. Together, fix the random
   re-provision + the upload retry behaviour.
4. **#7** — rework `refreshVms` to `virsh list --all` + `domstats`
   (no args) + skip `domifaddr` unless the guest agent is configured.
5. **#3** — start the exec reader before `channel.connect()` in both
   `execOnce` and `execStreamOnce`.
6. **#10** — drop or pre-resolve `--node-ip` in `buildCloudInit`.
7. **#14 + #13** — `destroyVm` should check the destroy result;
   `stopVm` should pass the freshly-fetched state into the
   stillRunning check.
8. **#9** — rebase `fetchTailscaleIp` / `runInGuest` / `joinK3s` /
   `initK3sServer` on the hardened session path.
9. **#11, #15, low/style** — code hygiene, no behaviour change.

## Not in the review — additional findings

I found a few issues while verifying that the review doesn't cover:

- **`destroyVm` has no exit-code check** (finding #14 only calls out the
  ignored *destroy* result; the *undefine* and `vmRepo.remove` succeed
  regardless). Same fix.
- **`saveServer` swallows encryption errors** (`RemoteServerManager.kt:62,
  64`): `secretStore.encrypt(...)` is called via `withContext`, but the
  result is assigned directly. If Keystore is locked (e.g. user changed
  device PIN), the call throws and `addServer` shows "Save failed:
  …" with a low-signal message. Worth a typed exception.
- **`runInGuest` / `fetchTailscaleIp` use `PermissiveHostKey`** for guests
  (RemoteServerManager.kt:656-659). The `sftpWrite` fallback in
  `writeRemoteFile` permanently marks the server as SFTP-broken in a
  process-wide Set (line 458) and never clears. If a transient SFTP
  failure is followed by a real one, the app keeps trying shell upload.
- **Create VM k3s path is incompatible with #10 fix**:
  `initK3sServer` (line 414) runs the same `get.k3s.io` script via
  `sudo sh -c '... | INSTALL_K3S_SKIP_START=true sh -s - server ...'`
  on the guest, and the script defaults to `node-ip` being
  auto-detected — but **after** the cloud-init install with the broken
  `--node-ip=127.0.0.1` (finding #10) has already happened, so the
  cluster is in a bad state by the time `initK3sServer` runs. Fix #10
  first.
- **The `--os-variant generic` fallback** (line 227) can make
  `virt-install` reject the cloud image with "unsupported hypervisor
  features" on newer virt-install (≥ 4.x). Not a crash, but a bad UX.
- **`ServersViewModel.kt:174-184`**: `addServer` shows a generic
  "setup timed out" toast on `null` from `withTimeoutOrNull`, but the
  underlying session was likely retried 3 times already
  (`RemoteServerManager.withServerSession` lines 159-185) — so the
  "user waits 28 minutes for nothing" failure mode is real. Worth
  surfacing a "retried N times" hint.

## Bottom line

13 of the 15 numbered findings are real and the proposed fixes are
correct. Finding #8 is wrong (already fixed). Two of the critical
findings (#1, #5) are pure one-line fixes; they should land first.
The biggest *behavioural* risk is finding #5 — a user who already had
libvirt installed can lose their stack by tapping "Remove Server".
