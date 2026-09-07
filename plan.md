# PodSteroid — Remote Servers · Configurable VMs · Container Readiness · Tailscale Mesh

> Status: plan v2 (reviewed). **No code changes yet.** Implementation starts at P0 only on approval.
> Review fixes incorporated: A1 (distro normalize), A3 (shared distro constant), JSch fork,
> first-login Tailscale UX, remote port-forward reality, k3s clustering, out-of-scope, QA.

## 0. Decisions (locked)
- Tailscale auth: **both** — manual `authkey` paste at first terminal login (default) **and**
  optional app-stored key auto-injected on first boot. Keys in Android Keystore.
- Tailscale source: **pinned static binary** prebaked into both rootfs builds (required: Debian
  bookworm main ships no `tailscale` package).
- Server tech: **libvirt/KVM** driven with `virsh`/`virt-install`/`qemu-img` **over SSH** — no
  libvirtd TCP exposure needed.
- SSH library: **mwiede JSch fork** (`com.github.mwiede:jsch`) — see §4.
- Remote terminal: bundled static `ssh` client (`libssh.so`, Dropbear `dbclient`).
- Multiple servers supported. UI: separate **Servers** + **Fleet** destinations.
- Guest credentials everywhere: `root` / `podsteroid` (both Alpine and Debian rootfs builds
  pre-hash it; remote guests get the same via cloud-init).

## 1. Architecture
Parallel provider; local `VmEngine`/`VmRegistry` **untouched**.

```
┌─ Local VMs ──────────── VmRegistry / VmInstance (QEMU | AVF)      [existing, unchanged]
├─ Remote Servers ─────── RemoteServerManager
│   ├─ RemoteServerRepository (DataStore)   RemoteVmRepository (DataStore)
│   ├─ SecretStore (Android Keystore)
│   └─ LibvirtRemoteProvider ── SshClient (JSch) ── virsh/virt-install/qemu-img
└─ FleetRegistry ── merges local + all servers + per-VM Tailscale status
```

New package: `com.tiquasar.podsteroid.remote/` (`SshClient`, `LibvirtRemoteProvider`,
`RemoteServerManager`, `FleetRegistry`, models). Tests in `app/src/test/.../remote/`.

## 2. Data Model
- `RemoteServer(id, name, host, port = 22, username, auth, hostKeyFingerprint?)` where
  `auth = Password(passRef) | Key(keyRef, passphraseRef?)`.
- `RemoteVm(serverId, uuid, name, state, guestDistro, vcpus, memMb, diskGb, sshEnabled,
  containerRuntime, tailscaleEnabled = true, tailscaleAuthKeyRef?, guestIp?, tailscaleIp?,
  magicDns?, createdAt?)`.
- `ContainerRuntime = NONE | DOCKER | K3S | BOTH` (default **BOTH**).
- Local `VmDefinition` gains `tailscaleEnabled: Boolean = true` + `tailscaleAuthKeyRef: String?`
  (JSON round-trip; defaults keep old definitions valid).
- Remote port forwards: **deferred** — libvirt NAT has no hostfwd equivalent; v1 accesses guest
  services via the **Tailscale IP** directly. Optional later: iptables DNAT on the server.

## 3. Persistence
- `RemoteServerRepository`, `RemoteVmRepository` (DataStore, mirror `VmRepository` patterns:
  JSON round-trip, seeded-empty).
- `SecretStore` (Android Keystore AES/GCM, alias per server/VM id):
  `encrypt(plaintext): String`, `decrypt(token): String`, plus `Bytes` variants for private keys.
- `hostKeyFingerprint` persisted per server for accept-first-use pinning.

## 4. SSH + Provider
- **`com.github.mwiede:jsch`** (maintained fork). Stock JSch 0.1.55 is unmaintained and fails
  against OpenSSH ≥ 8.8 servers (no `rsa-sha2-256`/`ed25519`, no modern KEX). The fork is a
  drop-in `com.jcraft.jsch` package — same API, modern algorithms.
- `SshClient.exec(host, port, username, auth, cmd, timeoutSec): Result(exit, stdout, stderr)`.
  **Accept-first-use host-key pinning**: first connect stores the fingerprint, later mismatches
  abort + surface a warning. Never `StrictHostKeyChecking=no`.
- `LibvirtRemoteProvider` (pure functions + virsh wrappers, unit-tested parsers):
  - `checkPrereqs`: `virsh`, `virt-install` (+ `--cloud-init`/`libosinfo` support), `qemu-img`,
    outbound internet — report what's missing.
  - list/info via `virsh list --all` + `domstats` + `domifaddr`; start/stop/destroy/undefine.
  - **`createVm`** = (a) ensure base **cloud image** on server (one-time cached download into
    `/var/lib/libvirt/images`), (b) `qemu-img create -f qcow2 -F qcow2 -b base.qcow2 vm.qcow2 <diskGb>G`,
    (c) `virt-install --import --cloud-init user-data=…` where user-data installs Docker + K3s
    (+Tailscale static binary), enables SSH, sets root pw `podsteroid`, unique hostname.
- **No libvirtd reconfiguration required** — everything rides the SSH connection.

## 5. Cross-VM Discovery (corrected, asymmetric)
- **Local VMs have no routable IP** (QEMU SLIRP `10.0.2.x`; AVF is DHCP but still not reachable
  from other hosts). The reachable address for **any** VM is its **Tailscale IP** (`100.x.y.z`).
  Local `guestIp`/`localIp` fields are **informational only** — never shown as "reachable".
- **Local push**: extend the host bridge with a new verb `netinfo <base64-json>` (fits the
  existing line protocol in `HostProtocol` — base64 free-text survives UTF-8). Guest sends
  `{tailscaleIp, magicDns, state}` after `tailscaled` reports; `HostRequestDispatcher` routes it
  to that VM's `VmInstance` → DataStore. New guest helper `podsteroid-netinfo` invoked by an OpenRC
  hook / on tailscale state change.
- **Remote pull**: no host bridge exists on libvirt guests → app SSHes **into the guest**
  (`root`@`tailscaleIp` or `@guestIp` pre-join) running `tailscale ip -4` / `tailscale status --json`.
- **Auth key never on the kernel cmdline** (`/proc/cmdline` is world-readable in-guest):
  - Local: app hands the key to the guest over the host bridge (`hvc2`/vsock) post-boot.
  - Remote: cloud-init user-data (visible only to cloud-init on the server).
- **First-login UX (local + remote)**: guest ships `podsteroid-tailscale` wrapper. If
  `/var/lib/tailscale` has no state and a marker `/root/.podsteroid-tailscale-done` is absent, the
  login shell (motd hook on hvc0/SSH) prompts once for an auth key (or "press enter to skip").
  With an app-stored key present, join happens silently on first boot instead.
- **tailscaled runs at boot regardless** (enabled OpenRC service / systemd unit) so `tailscale up`
  works instantly; joining is the only gated step.
- **MagicDNS is best-effort** (requires MagicDNS enabled in the tailnet admin console) — Fleet UI
  shows `tailscaleIp` as primary, MagicDNS as secondary.
- **SLIRP caveat**: local VMs can't do UDP-41641 NAT-traversal through SLIRP → Tailscale uses
  **DERP relays** (TCP 443). Works, but relayed/slower; remote VMs on real networks go direct.
- Remote guests without `/dev/net/tun`: `tailscaled --tun=userspace-networking` fallback (flag in
  cloud-init).

## 6. k3s clustering across the fleet
- Any VM with `containerRuntime = K3S|BOTH` + Tailscale joined can be a cluster node.
- First node: `k3s server --tls-san <tailscaleIp>`; additional nodes:
  `k3s agent --server https://<server-tailscaleIp>:6443 --token <node-token>`.
- FleetScreen "Make cluster" flow: pick server node, app fetches `/var/lib/rancher/k3s/server/node-token`
  via SSH (remote) / terminal-injected command (local), then joins selected agents over Tailscale.
- Optional: fetch merged `kubeconfig` to Downloads via the existing backup/9p paths (remote: SFTP over JSch).

## 7. UI
- `Routes.SERVERS`, `Routes.FLEET` in `NavGraph`; Home gets **Servers** + **Fleet** cards
  (`PodsteroidListRow`).
- `ServersScreen` + `ServersViewModel`: server list, add/edit/remove, **Test connection**
  (SSH + prereqs), per-server VM list with lifecycle buttons + live state/`domstats`.
- `ServerEditDialog`: name, host, port, username, password|key(+passphrase), test button.
- `RemoteVmCreateDialog`: remote twin of `VmFormDialog` (name/distro/vcpus/mem/disk/ssh) +
  container-runtime selector + Tailscale toggle/authkey field.
- `FleetScreen` + `FleetViewModel`: unified directory of every VM (local + remote) with state,
  reachable `tailscaleIp`, MagicDNS, runtime badges; pull-to-refresh; staleness TTL (~60 s) marks
  entries "last seen …".
- English-only strings; `values` only (zh removed). Reuse `Tokens` loading/error/empty states.

## 8. Remote Terminal
- Bundled `libssh.so` = Dropbear **`dbclient`** static build (16 KB-page-aligned), extracted by
  `PodsteroidApplication` like `qemu/`.
- Remote VM "Open terminal" → `TerminalSession` runs `dbclient -y -y root@<tailscaleIp|guestIp>`
  (`-y -y` = accept-first-use host key, consistent with §4 pinning). Reuses the existing Termux
  PTY path; resize/resize-notify unchanged.
- Fallback when the guest isn't IP-reachable from the phone: `virsh console` over the server SSH
  (lower fidelity; documented).

## 9. Build / Rootfs
- `app/build.gradle.kts`: `com.github.mwiede:jsch:<pinned>`; `libssh.so` in
  `jniLibs/arm64-v8a` (built in `build-all.sh` + 16 KB ELF check).
- Prebake **k3s + kubectl + tailscale** (pinned versions; tailscale from
  `https://pkgs.tailscale.com/stable/tailscale_<ver>_arm64.tgz`, SHA256-checked) in both
  `Dockerfile.rootfs` and `Dockerfile.debian`; remote cloud-init fetches the same pinned binary.
- New guest files: `podsteroid-tailscale` wrapper, `podsteroid-netinfo` (host-bridge reporter),
  tailscaled OpenRC service (runlevel symlinked at build time).
- Fix **A1** (QEMU `resolveEffectiveDistro` normalize) and **A3** (shared `DISTRO_OPTIONS`
  constant) as part of P0 hygiene.

## 10. Phased (de-risked) implementation
1. **P0** Foundation + hygiene: mwiede-JSch dep, `SecretStore`, `SshClient` (+ pinning, + tests),
   fix A1/A3.
2. **P1** Data: models + repositories (+ JSON round-trip tests).
3. **P2** Provider + Manager: `LibvirtRemoteProvider` incl. `createVm` base-image step; parser tests.
4. **P3** Servers UI: routes, Home cards, dialogs, lifecycle, live status.
5. **P5** Remote terminal (`libssh.so`) — proves SSH end-to-end; manual Tailscale config path lands here.
6. **P4** Tailscale mesh: rootfs prebake, `podsteroid-netinfo` push, remote pull, `FleetRegistry`,
   FleetScreen, auto-inject auth key.
7. **P6** k3s clustering + polish/QA: cluster flow, staleness TTL, timeouts,
   `./gradlew :app:testDebugUnitTest`, walkthrough against a real libvirt server + Tailscale tailnet.

## 11. Non-negotiables (before/during implementation)
1. Fix **A1**; extract shared **DISTRO_OPTIONS** (A3).
2. SLIRP IP = informational; Tailscale IP = reachable. Never present `10.0.2.x` as connectable.
3. Asymmetric discovery: local **push** (host bridge `netinfo`), remote **pull** (SSH-into-guest).
4. Auth key via host bridge / cloud-init — **never** kernel cmdline.
5. `createVm` includes base-cloud-image caching + qcow2 clone + cloud-init user-data.
6. mwiede JSch + accept-first-use host-key pinning (both JSch and dbclient).
7. `tailscaled` enabled at boot; only **join** is gated.
8. Pin + SHA256 all GitHub/tailscale downloads in rootfs builds (A6).

## 12. Risks
- Remote runtime install needs server-side internet at create → surface "VM created, runtime
  install failed (retry)" rather than failing create.
- Tailscale: `/dev/net/tun` present locally; remote guests may need userspace-networking fallback;
  local VMs ride DERP relays over SLIRP (slower).
- `virt-install --cloud-init` + libosinfo must exist server-side → prereq check reports it.
- Guest reachability for terminal: prefer Tailscale IP; `virsh console` fallback documented.
- End-to-end unvalidated until run against a real libvirt server + tailnet (same risk class as the
  Debian rootfs, which also remains runtime-unvalidated).

## 13. Out of scope (v1)
- Remote X11/VNC, remote backups, mDNS/LAN discovery (Theme 2, user-excluded).
- Remote port forwards via iptables DNAT (deferred; Tailscale IP is the access path).
- Tailscale ACL management, MagicDNS auto-enable, multi-user tailnets.
- Windows/macOS servers (libvirt on Linux only).

---

## Appendix — Code review of implemented work (this session)
### Real bug
- **A1**: `QemuEngine.resolveEffectiveDistro` returns the requested (possibly unknown) distro when
  the alpine squashfs exists → unknown value boots alpine but reports e.g. `podsteroid.distro=ubuntu`.
  AVF is correct (derives from chosen file). Fix: normalize unknown → `alpine`, debian →
  debian-if-present-else-alpine.

### Behavioral / UX notes
- **A2**: two distro selectors (global Settings default + per-VM dialog) — consistent with the
  RAM/CPU default+override pattern; optionally grey Debian when its squashfs isn't bundled.
- **A3**: distro option list duplicated in `HomeScreen` and `SettingsScreen` → extract
  `DISTRO_OPTIONS`.
- **A4**: stale `"zh"` DataStore pref persists harmlessly (LanguageManager clamps to EN/AUTO).
- **A5**: `build.sh` runs `docker system prune -a -f` — destructive to all host Docker data;
  confirm intent / add comment.
- **A6**: rootfs build now depends on GitHub/dl.k8s.io for k3s/kubectl prebake → pin + SHA256.

### Verified correct
- `PodsteroidApplication` extraction: `listOfNotNull` + `assets.list("")` presence check → missing
  `debian-rootfs.squashfs` no longer poisons the version stamp (no re-extract-every-launch).
- `AvfEngine` fallback (`squashfs` File + `effectiveDistro` + `addDisk`) consistent; cmdline uses
  `effectiveDistro`.
- Per-VM `guestDistro` plumbing (`VmDefinition`/`VmConfig`/`VmRegistry`/`HomeViewModel`/`HomeScreen`)
  complete: JSON round-trip, create/update, seeded default from global setting.
- Guest root pw `podsteroid` in **both** rootfs builds (`openssl passwd -6` → `/etc/shadow`).
- Chinese fully removed; `compileDebugKotlin` green.
