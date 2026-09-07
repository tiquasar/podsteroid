# PodSteroid — Session Changes (readme)

This document records every change made in the remote-server VM management + k3s
clustering work, plus the subsequent theme and UI fixes. It is a change log, not
the upstream project README.

- Package: `com.tiquasar.podsteroid` (debug: `com.tiquasar.podsteroid.debug`)
- Build: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- SSH lib: `com.jcraft.jsch` / `mwiede JSch` (declared via `libs.jsch` in `app/build.gradle.kts:146`)
- Secrets: Android Keystore (`SecretStore`, AES/GCM), host-key pinning accept-first-use

---

## P0 — Foundation (local distro + SSH + secrets)

**`engine/QemuEngine.kt`** — `resolveEffectiveDistro()` now maps `unknown → alpine`
and `debian → debian` only when a debian rootfs is present, else `alpine`.

**`ui/theme/` + `GuestDistro.kt`** — `GuestDistro.OPTIONS` is a single shared
constant used by both Home and Settings (was duplicated).

**`remote/SecretStore.kt`** (new) — encrypts/decrypts secrets in the Android
Keystore (AES/GCM). Used for server SSH passwords/keys and passphrases.

**`remote/SshClient.kt`** (new) — JSch wrapper:
- `exec(target, creds, cmd, hostKeyStore)` — single command over SSH.
- `shell(target, creds, hostKeyStore, onData, onClosed)` — interactive PTY shell.
- `HostKeyStore` interface + `HostKeyMismatchException` + `fingerprint()` so the
  app can pin/accept guest host keys.

## P1 — Data model

- **`remote/ContainerRuntime.kt`** (new): `Docker`, `K3s`, `Both`, `None`.
- **`remote/RemoteServer.kt`** (new): `@Serializable` model — `id, name, host,
  port, authKind (PASSWORD/KEY), user, secretToken, passphraseToken, distro,
  arch, network, tailscaleEnabled, ...` with full JSON round-trip.
- **`remote/RemoteVm.kt`** (new): `@Serializable` model — `serverId, uuid,
  name, state, guestIp, tailscaleIp, guestDistro, containerRuntime, arch, ...`.
- **`data/repository/RemoteServerRepository.kt`** + **`RemoteVmRepository.kt`**
  (new): DataStore-backed persistence for servers and discovered VMs.

## P2 — Remote provider + manager (libvirt/KVM over SSH)

- **`remote/LibvirtRemoteProvider.kt`** (new): parses `virsh list`,
  `virsh domiflist`, `virsh net-dhcp-leases`; builds `virt-install` commands;
  `buildCloudInit` (user `root`, password `podsteroid`, `runcmd` installs the
  chosen container runtime, optional Tailscale + k3s); arch-aware base image
  selection; `mergeVm`/`domainUuid`; `fetchTailscaleIp` via lease scan.
- **`remote/RemoteServerManager.kt`** (new, `@Singleton`):
  - `saveServer` / `deleteServer` (encrypts secrets, persists).
  - `testConnection` — verifies `virsh`, `virt-install`, `qemu-img`, cloud-init,
    `default` network (the prereqs the guest needs).
  - `refreshVms` — discovers real VMs by UUID (merges provider list with repo).
  - `createVm` / `startVm` / `stopVm` / `destroyVm`.
  - `runInGuest` — runs a command in a guest as `root`/`podsteroid` over SSH.
  - `fetchK3sToken` — reads the server node token
    (`/var/lib/rancher/k3s/server/node-token`).
  - `joinK3s` — **now pushes and runs the bundled `setup-k3s-agent.sh`**
    (see P6) instead of the bare `curl get.k3s.io`.
  - `initK3sServer` — installs + starts a k3s *server* on the control node.
  - `shellToGuest` — interactive shell to a guest.
  - `PermissiveHostKey` — accepts any guest key (guests are the user's own machines).
  - Now takes `@ApplicationContext` to load the bundled raw script resource.

## P3 — Feature UI (Servers)

- **`ui/navigation/NavGraph.kt`** — new `Routes.SERVERS` + composable.
- **`ui/screens/home/HomeScreen.kt`** — added a **Servers** nav button.
- **`ui/screens/servers/ServersScreen.kt`** + **`ServersViewModel.kt`** (new):
  lists registered servers + their VMs, exposes `manager`, busy indicator,
  per-VM **Terminal** button (opens `RemoteTerminalDialog`), and VM lifecycle
  actions.
- **`ServerEditDialog.kt`** — add/edit server (host, port, auth kind, user,
  secret, **arch**, **network**).
- **`RemoteVmCreateDialog.kt`** — create a VM on a server (name, **arch**,
  **network**, **container runtime**).

## P4 — Fleet (unified local + remote view)

- **`remote/FleetEntry.kt`** (new): `kind = LOCAL | REMOTE`, `serverName,
  guestDistro, containerRuntime, tailscaleIp, guestIp, magicDns, stateLabel`.
- **`ui/screens/fleet/FleetViewModel.kt`** (new): combines `VmRegistry`
  (local definitions + states) with `vmRepo`/`serverRepo` (remote) into a single
  `fleet` flow; `refreshFleet`, `getToken`, `join`, `initServer`,
  `fetchTailscaleIp`. (Replaces the planned standalone `FleetRegistry`.)
- **`ui/screens/fleet/FleetScreen.kt`** (new): `FleetCard` + `ClusterDialog`.
- **`ui/navigation/NavGraph.kt`** — `Routes.FLEET` + composable; **HomeScreen**
  gains an **Fleet** button (`onNavigateToFleet`).
- **Guest → Android NETINFO push**: `engine/hostbridge/HostProtocol.kt`
  (`NETINFO` verb) + `HostRequestDispatcher.kt` handler parse
  `NETINFO <vmId> <b64json>` and feed the local VM's address into Fleet.

## P5 — Remote terminal

- **`remote/SshClient.shell`** — JSch `ChannelShell` with PTY.
- **`ui/screens/servers/RemoteTerminalDialog.kt`** (new): interactive terminal
  to a guest, wired into `ServersScreen`'s per-VM **Terminal** button.

## P6 — k3s clustering + your setup script

- **`app/src/main/res/raw/setup_k3s_agent`** (new): your `setup-k3s-agent.sh`
  (installs the k3s agent, writes `/etc/rancher/k3s/k3s-agent.env`, removes stale
  node identity, creates an **OpenRC** service, enables it at boot, starts it).
- **`ClusterDialog`** (in `FleetScreen.kt`) now shows a clear, step-by-step
  prompt instead of a silent join:
  1. **Init control node (server)** button → `manager.initK3sServer`.
  2. **Get token** → `manager.getToken` (reads the server token; clearer
     error if no server is running).
  3. After the token arrives, **Step 2** explains `setup-k3s-agent.sh` runs on
     each selected worker, the worker selector appears, and **Join selected**
     → `manager.join` → `joinK3s` pushes + runs your script.
- `FleetViewModel.initServer` added to drive the control-node init.

## Theme — AMOLED → normal Material dark

- **`ui/theme/Color.kt`**: dark palette no longer pure black. Now
  `background #121212`, `surface #1E1E1E`, `surface2 #242424`,
  `border #3A3A3A`, `text #E3E3E3`.
- **`ui/theme/Theme.kt`**: comment updated (fixed Material dark, not AMOLED).

## UI fix — vertical "Virtual machines" text

- **`ui/screens/home/HomeScreen.kt`**: the Home header put the
  `weight(1f)` "Virtual machines" label in one `Row` with five action buttons
  (Observability/Servers/Fleet/Cluster/Backup). On a phone the buttons alone
  exceed the screen width, so the weighted label collapsed to ~0 width and the
  text wrapped one character per line. Fixed by splitting into a `Column`:
  the label on its own line, the action buttons in a `horizontalScroll` row.

## Known gaps / notes

- **Default login still `podsteroid` (not `podsteroid`).** The guest root password
  is baked into the Alpine squashfs at build time. `build-rootfs/build-rootfs.sh:76`
  already sets it to `podsteroid`, but the embedded
  `app/src/main/assets/alpine-rootfs.squashfs` (dated Aug 17 21:24) is a stale
  prebuilt. Flipping it on-device requires `./build-all.sh rootfs` + APK rebuild
  (deferred at user request). The app code never sets the password.
- **Control-node k3s server is best-effort.** `initK3sServer` installs and starts
  the server in the background; it does **not** yet create a persistent OpenRC
  service, so it will not survive a guest reboot. (Worker agents use your script,
  which *does* persist via OpenRC.)
- **Rootfs-side extras not done**: guest Tailscale/k3s boot integration and the
  `podsteroid-netinfo` push are app-side only; local-VM Tailscale discovery still
  shows "no routable address yet" until the rootfs is updated.
- **libssh not bundled**: P5 uses JSch `shell` rather than a `libssh.so`.

## File map (new/changed this session)

```
app/build.gradle.kts                         # JSch dependency
app/src/main/res/raw/setup_k3s_agent         # your k3s agent script (P6)
app/src/main/res/values/strings.xml          # (existing) section titles
app/src/main/java/com/tiquasar/podsteroid/
  remote/SecretStore.kt                      # P0
  remote/SshClient.kt                        # P0/P5
  remote/ContainerRuntime.kt                 # P1
  remote/RemoteServer.kt                     # P1
  remote/RemoteVm.kt                         # P1
  data/repository/RemoteServerRepository.kt  # P1
  data/repository/RemoteVmRepository.kt      # P1
  remote/LibvirtRemoteProvider.kt            # P2
  remote/RemoteServerManager.kt              # P2/P6
  ui/screens/servers/ServersScreen.kt       # P3
  ui/screens/servers/ServersViewModel.kt    # P3
  ui/screens/servers/ServerEditDialog.kt    # P3
  ui/screens/servers/RemoteVmCreateDialog.kt# P3
  ui/screens/servers/RemoteTerminalDialog.kt# P5
  ui/screens/fleet/FleetEntry.kt             # P4
  ui/screens/fleet/FleetViewModel.kt        # P4/P6
  ui/screens/fleet/FleetScreen.kt           # P4/P6 (ClusterDialog)
  ui/screens/home/HomeScreen.kt             # P3/P4 + vertical-text fix
  ui/navigation/NavGraph.kt                 # P3/P4 routes
  ui/theme/Color.kt                         # theme
  ui/theme/Theme.kt                         # theme
  engine/hostbridge/HostProtocol.kt         # P4 NETINFO
  engine/hostbridge/HostRequestDispatcher.kt# P4 NETINFO
  engine/QemuEngine.kt                      # P0 distro resolve
```
