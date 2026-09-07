# Remote-Server Feature Code Review — Findings & Suggestions

Review scope: `app/src/main/java/com/tiquasar/podsteroid/remote/` and
`app/src/main/java/com/tiquasar/podsteroid/ui/screens/servers/`.

Findings ordered by severity.

## Critical

### 1. `ServerAliveCountMax` never set — JSch default is 1, so one missed 15s keepalive kills the session
`SshClient.kt:83,133,213` set `ServerAliveInterval=15` but never `ServerAliveCountMax`.
JSch's default is **1**: a single 15-second network stall (constant on a flaky
Tailscale link) drops the whole session mid-operation. This is the single
biggest contributor to `session died / transport gone / Pipe closed` in every
log captured.

**Fix:** `session.setConfig("ServerAliveCountMax", "4")` (60s tolerance).

### 2. No watchdog on `session.connect()` — a half-open TCP link hangs the entire flow
`withSession` (SshClient.kt:290) calls `session.connect(30_000)`, but this JSch
build does not bound key exchange on a half-dead socket — the file's own
comment (lines 258-263) documents this for *second* handshakes. Channel-open
watchdogs exist (SshClient.kt:359, 516) but nothing guards session connect.

**Symptom:** network link drops mid-Create → `connect()` blocks → 600s
ViewModel timeout → "stuck at Uploading script". The silence watchdogs never
engage because the hang happens *before* any channel.

**Fix:** force-disconnect watchdog around `session.connect()` (same pattern as
the channel-open watchdogs).

### 3. Exec-channel race: reader thread starts after `channel.connect()` returns
`execOnce` (SshClient.kt:383-420) connects, *then* starts the reader. For fast
commands dropbear can deliver data + EOF before the reader's first `read()`;
observed probe output lost `KVM/LIBVIRT/DISK` lines (3 of 6 arrived) proving
buffered bytes get dropped. Marker/PSRC/silence patches mitigate but do not fix
the ordering.

**Fix:** construct and start the reader before `channel.connect()`, or drain
JSch's internal buffer explicitly on close.

### 4. Retry-once reuses channels on the same (possibly poisoned) session
`exec()` (SshClient.kt:342-352) and `execStream()` retry on a fresh channel of
the *same* session. Logs show the retry also fails (`channel closed after 0B`).
After one bad channel the session itself is unreliable — the retry must go
through `withServerSession`'s fresh-connection path, but exec-level failures
(`exit=-1`, empty body) never throw, so that retry logic never fires.
Silent-hang results surface as "checksum mismatch", which
`isRetryableSshError` (RemoteServerManager.kt:190) classifies as
non-retryable.

**Fix:** add "checksum"/"upload" to retryable keywords, or throw a typed
exception that the session retry recognizes.

### 5. Pre-existing libvirt gets marked `provisionedByUs=true` — Delete will purge the user's own stack
ServersViewModel.kt:190-192: when the host *already has* libvirt, the server
is saved with `provisionedByUs = true`. Then Delete Server runs
`deprovisionLibvirt(full=true)` → `apt-get purge qemu-kvm
libvirt-daemon-system...` (RemoteServerManager.kt:556). A user's
manually-installed hypervisor stack gets uninstalled.

**Fix:** this branch must set `provisionedByUs = false`.

### 6. `probePrereqs` = 5 independent flaky execs — random false negatives trigger re-provisioning
RemoteServerManager.kt:200-205 runs 5 separate exec calls (`virsh version`,
`virt-install --version`, ...). With per-exec reliability low on this host,
`virsh=true` can randomly read as missing → `setupServer` re-provisions an
already-provisioned host.

**Fix:** collapse into one script (like `probeHost` does) and parse
key=value lines.

## High

### 7. `refreshVms` drops VMs when `domuuid` fails
RemoteServerManager.kt:288: `domainUuid(exec, dom.name).getOrNull() ?:
return@mapNotNull null` — a single lost exec makes the VM vanish from the UI
(and is skipped from persistence). Also 3 execs per VM
(`domuuid`/`domstats`/`domifaddr`) multiplies flakiness.

**Fix:** `virsh list --all` already prints names+states; `domstats` (no args)
prints all domains in one call. Only `domifaddr` needs per-VM calls — and it
needs a guest agent or DHCP snooping that isn't configured, which is why the
guest IP never appears (`domifaddr` logs return empty headers).

### 8. Ghost VM records never cleaned
`RemoteVmRepository.putAll` (RemoteVmRepository.kt:53-57) merges *new* list
into *existing* snapshot without removing records absent from the fresh list.
A VM destroyed outside the app stays in the repo forever.

**Fix:** replace the server's slice with the fresh list instead of merging.

### 9. Two divergent exec implementations — guest ops use the broken legacy one
`SshClient.exec/execStream(target, ...)` (SshClient.kt:67-184) still use the
original `while (!channel.isClosed) sleep(100)` pattern with `readFully` — no
marker, no silence watchdog, no drain-join. These power `runInGuest`,
`fetchTailscaleIp`, `joinK3s` (RemoteServerManager.kt:373, 388-390). Same
dropbear, same flakiness, none of the hardening.

**Fix:** delete the target-based implementations or reimplement them on top
of `withSession`/`SessionContext`.

### 10. K3S always binds `node-ip=127.0.0.1`
`buildCloudInit` (LibvirtRemoteProvider.kt:373):
`--node-ip=\${ETH0_IP:-127.0.0.1}` — `ETH0_IP` is never set anywhere in
cloud-init, so k3s agents always register with 127.0.0.1 and cluster join
breaks.

**Fix:** resolve the IP first (`ip -4 addr show`) or drop `--node-ip`.

## Medium

### 11. `ExecResult` mutable diagnostics on a data class
SshClient.kt:34-39: `internal var markerFound/bytesReceived` live outside the
constructor — `copy()` and `equals()` silently drop them. Works today because
they're set immediately after construction, but it's a trap.

**Fix:** a private `ExecOutcome` wrapper returned internally, or constructor
params with defaults.

### 12. `createVm` opens 2 sessions + `probeHost` 1 more
ServersViewModel.kt:403 probes on one session, then `manager.createVm` opens
another (RemoteServerManager.kt:302). On a flaky link each handshake is a
failure lottery.

**Fix:** fold the probe into the create session's block.

### 13. `stopVm` force-destroy check depends on the lossy refresh
ServersViewModel.kt:463-470: if `refreshVms` drops the VM (finding 7),
`stillRunning` is false → no `destroyForce` even though the domain runs.

**Fix:** use the `virsh list` output already inside the refresh result, or
check `domstate` directly.

### 14. `destroyVm` ignores `destroy()` result
RemoteServerManager.kt:357-360: `LibvirtRemoteProvider.destroy(exec,
vm.name)` without `getOrThrow()` — a failed destroy proceeds to undefine,
leaving storage attached to a dead-but-defined domain.

**Fix:** check/propagate the destroy result before undefining.

### 15. Upload hardcodes `/tmp` and assumes gzip/base64 on host
`writeRemoteFile` (RemoteServerManager.kt:466+) — fine for Debian/Alpine, but
`mktemp` would be safer against a read-only /tmp on exotic distros the
provision script claims to support.

## Low / style

- `LibvirtRemoteProvider.kt:13` — self-import
  `com.tiquasar.podsteroid.remote.ContainerRuntime` (same package);
  `RemoteServerManager.kt:12` unused `Resources` import;
  `RemoteVmCreateDialog.kt:38` unused `DeviceResourcePolicy`.
- `buildCloudInit` pins `tailscale_2.28.6` (LibvirtRemoteProvider.kt:386) —
  hardcoded stale version; fetch latest or make it a named constant.
- `RemoteTerminalDialog` PTY echoes input (no `stty -echo`/size handling) —
  cosmetic doubling.
- `ServersViewModel.serverJobs` (line 79) is written but only consulted in
  delete paths; `createVm`/`provision` jobs aren't registered, so delete
  can't cancel a running create.
- `ServerEditDialog` accepts port `0`/`65536` and host without basic
  validation.
- `ServersScreen.kt:500-502` duplicates the `guest IP` line already shown in
  the expanded section.
- `RemoteVmCreateDialog` `memMb` default 2048 but parse-failure fallback is
  `?: 512` — inconsistent floor between dialog and `coerceAtLeast(256)`.

## What's actually good

- Secret handling: Keystore AES/GCM, ciphertext-only in DataStore,
  `runCatching` guards on decrypt (SecretStore.kt, credentialsFor).
- Host-key accept-first-use via custom `HostKeyRepository`;
  `HostKeyMismatchException` fails loudly on MITM.
- Pure parsers (`parseVirshList`, `parseDomstats`, `parseDomainUuid`) cleanly
  separated for unit testing.
- Two-phase server delete (revert host, then remove local entry) is the
  right UX.
- `writeRemoteFile`'s heredoc design (gzip + single command + md5 verify +
  underscore terminator that can't collide with base64) is correct — it fails
  only because the transport underneath (findings 1-4) is broken.

## Recommended fix order

1. `ServerAliveCountMax=4` + a session-connect watchdog (findings 1, 2) —
   small, likely eliminates most "stuck" reports
2. Fix `provisionedByUs` on already-provisioned hosts (finding 5) — prevents
   destructive deletions
3. Collapse `probePrereqs` into one script; make upload checksum failures
   session-retryable (findings 4, 6)
4. Rework `refreshVms` to 2 execs total and stop dropping VMs on `domuuid`
   failure (finding 7); purge ghost records (8)
5. Merge guest-op execs into the hardened session path (finding 9)
