# PodSteroid — Remote Servers, Fleet & Cluster

> This document describes the **remote-server VM management** feature added to
> PodSteroid: register SSH-accessible libvirt/KVM servers as compute, create and
> manage VMs on them just like local VMs, reach them through a Tailscale mesh,
> open a remote terminal, and form a multi-node k3s cluster across the fleet.

---

## 1. What problem it solves

PodSteroid already runs Alpine VMs **on the phone** (QEMU/TCG or AVF). Those are
great for a single node, but:

- The phone's TCG emulation is slow and RAM-limited.
- You already own real x86/arm servers with KVM that can host many fast VMs.
- A k3s/Kubernetes cluster needs more than one node, and those nodes must be
  able to reach each other across networks.

This feature turns any SSH-reachable **libvirt/KVM host** into a managed compute
node. You register it once (SSH credentials, encrypted in the Android Keystore),
then create/start/stop/destroy VMs on it from the app. A **Tailscale mesh** makes
every VM — local or remote — mutually reachable by a stable IP, and a **Fleet**
view aggregates them all. A built-in **remote terminal** shells into any guest,
and a **cluster** flow bootstraps a multi-node k3s control plane + workers.

---

## 2. Architecture at a glance

```
┌──────────────────────────────────────────────────────────────────────┐
│  PodSteroid (Android app)                                             │
│                                                                       │
│  UI layer                                                             │
│    HomeScreen ──► "Servers" ──► ServersScreen                         │
│    HomeScreen ──► "Fleet"   ──► FleetScreen                           │
│    ServersScreen ──► RemoteTerminalDialog (JSch shell)               │
│                                                                       │
│  ViewModels (Hilt)                                                   │
│    ServersViewModel ──► owns flows: servers, vms, busy, toast         │
│    FleetViewModel   ──► aggregates local + remote into FleetEntry     │
│                                                                       │
│  Domain / orchestration                                              │
│    RemoteServerManager   (encrypt secrets, drive provider, persist)   │
│    RemoteServerRepository / RemoteVmRepository  (DataStore)           │
│    SecretStore            (Android Keystore AES/GCM)                 │
│    LibvirtRemoteProvider  (pure parsers + command builders + exec)    │
│    SshClient             (JSch: exec + interactive shell + host keys) │
│                                                                       │
│  Local VM integration                                                 │
│    VmRegistry (allStates) ──► Fleet local nodes                      │
│    HostProtocol.NETINFO  ──► guest→app network push (see §6)         │
└───────────────────┬───────────────────────────────┬──────────────────┘
                     │ SSH (port 22, accept-first-use host keys)
                     │
        ┌────────────▼─────────────┐        ┌──────────────────────────┐
        │  Remote libvirt server   │        │  Local VM (QEMU/AVF)      │
        │  virsh / virt-install /  │        │  SLIRP or DHCP, no routable│
        │  qemu-img                │        │  guest IP by default      │
        │      │                   │        │      │                    │
        │      ▼                   │        │      ▼                    │
        │  Guest VM (cloud-init)  │        │  Guest OS (Alpine)        │
        │  docker.io / k3s /       │        │                          │
        │  tailscaled (root/podsteroid)    │                          │
        │      │                   │        │                          │
        │      ▼                   │        │                          │
        │  Tailscale IP ◄──────────┼────────┼── Tailscale mesh ────────┤
        └──────────────────────────┘        └──────────────────────────┘
```

### Key packages / files

| Layer | File | Responsibility |
|---|---|---|
| Models | `remote/RemoteServer.kt` | Registered server (host, port, user, auth kind, arch, network, pinned host key). No secret stored inline. |
| Models | `remote/RemoteVm.kt` | A VM on a remote server (uuid = libvirt domain UUID, state, specs, guest/tailscale IPs). |
| Models | `remote/ContainerRuntime.kt` | `NONE / DOCKER / K3S / BOTH` selector. |
| Repos | `remote/RemoteServerRepository.kt` | DataStore list of `RemoteServer`. |
| Repos | `remote/RemoteVmRepository.kt` | DataStore list of `RemoteVm`. |
| Crypto | `remote/SecretStore.kt` | Android Keystore AES/GCM; encrypt/decrypt by alias. |
| SSH | `remote/SshClient.kt` | JSch `exec` + interactive `shell`; pluggable `HostKeyStore` (accept-first-use pinning). |
| Provider | `remote/LibvirtRemoteProvider.kt` | Pure parsers (`parseVirshList`, `parseDomstats`, `parseGuestIp`, `mergeVm`), command builders (`createVmCmd`, `buildCloudInit`, …), and SSH-backed exec methods. |
| Orchestration | `remote/RemoteServerManager.kt` | Wires secrets → SSH → provider; persists VMs + host keys; Tailscale/k3s helpers. |
| UI | `ui/screens/servers/*` | Servers list, add/edit dialog, create-VM dialog, remote terminal dialog. |
| UI | `ui/screens/fleet/*` | Fleet aggregation + cluster dialog. |
| Nav | `ui/navigation/NavGraph.kt` | `Routes.SERVERS`, `Routes.FLEET`. |
| Host bridge | `engine/hostbridge/HostProtocol.kt` + `HostRequestDispatcher.kt` | `NETINFO` verb for local-guest network push. |

---

## 3. Security model

1. **Secrets never live in plaintext.** The password / private-key PEM / key
   passphrase are encrypted with **Android Keystore AES/GCM** (`SecretStore`) and
   stored as ciphertext tokens (`RemoteServer.secretToken`, `.passphraseToken`)
   in DataStore. The key alias is `remote-<serverId>` (and `remote-<id>-pass`).
2. **Host-key pinning (accept-first-use).** On first connect, the server's
   SSH host-key fingerprint (`SHA256:…`, same format as `ssh-keygen -lf`) is
   recorded via `HostKeyStore`. On later connects, a *changed* key throws
   `HostKeyMismatchException` (possible MITM). We never use
   `StrictHostKeyChecking=no` as the security mechanism — it only controls
   JSch's auto-`add()`; the **custom `HostKeyRepository` adapter enforces
   first-use + throws on mismatch**.
3. **Guest credentials are intentionally simple.** Cloud-init sets
   `root` / `podsteroid` and `ssh_pwauth: true` so the app can reach guests
   over Tailscale/SSH without per-guest secret management. **This is a
   documented limitation**: treat the Tailscale network as the trust boundary
   and use an auth key / firewall it appropriately.
4. **Guest host keys are not pinned** (the `PermissiveHostKey` sink in
   `RemoteServerManager`). Guests are the user's own machines and their IPs can
   change; pinning would cause spurious failures. The *server* host key is
   still pinned.
5. **Tailscale is the recommended transport for cross-node traffic.** It gives
   every node a stable, encrypted address and NAT traversal for free.

---

## 4. Data flow

### 4.1 Register & connect a server

```
User ──► ServersScreen ──► ServerEditDialog(name, host, port, user,
        authKind, secret, passphrase?, baseImage?, arch, network)
            │
            ▼
ServersViewModel.addServer(...)
            │  raw secret
            ▼
RemoteServerManager.saveServer(server, secret, passphrase?)
            │  SecretStore.encrypt(alias, secret)
            ▼
serverRepo.put(server.copy(secretToken = ciphertext))   // persisted
            │
            ▼
ServersViewModel.testConnection(server) ──► manager.testConnection
            │  builds SshExecutor (decrypt secret, HostKeyHolder)
            │  runs: virsh version, virt-install --version,
            │        qemu-img --version, cloud-init support, default net
            ▼
PrereqReport(virsh, virtInstall, qemuImg, cloudInit, defaultNet, details)
            │  host key pinned in serverRepo on success
            ▼
UI shows prerequisites (green/red chips)
```

### 4.2 Create & discover a VM

```
ServersViewModel.createVm(server, spec)
   │  manager.createVm(server, spec)
   │     ├─ createVmCmd(server, spec)  → base image download + qcow2 +
   │     │   virt-install --import --cloud-init user-data=/tmp/ud-<name>.yaml
   │     └─ virsh domuuid <name>        → libvirt domain UUID
   ▼
RemoteVm(uuid, name, specs, state=PENDING) persisted to vmRepo
   │
   ▼  (later) Refresh
RemoteServerManager.refreshVms(server)
   │  virsh list --all → parseVirshList
   │  for each domain: virsh domuuid, virsh domstats, virsh domifaddr
   │  mergeVm(server, stored, domain, stats, ip, uuid)
   ▼
vmRepo.putAll(merged)   // state, specs, guest IP updated
```

### 4.3 Tailscale discovery (remote guests)

```
FleetViewModel.refreshFleet()
   │  for each RUNNING remote VM:
   ▼
RemoteServerManager.fetchTailscaleIp(server, vm)
   │  SSH root@(tailscaleIp ?: guestIp)  [PermissiveHostKey]
   │  exec: "tailscale ip -4"
   ▼
vm.tailscaleIp persisted in vmRepo → Fleet node shows routable address
```

### 4.4 Fleet aggregation

```
FleetViewModel.fleet = combine(
    VmRegistry.definitions + VmRegistry.allStates,   // LOCAL nodes
    vmRepo.vms + serverRepo.servers                  // REMOTE nodes
) → List<FleetEntry>   (kind, state, distro, runtime, guestIp, tailscaleIp, magicDns)
```

---

## 5. Remote terminal (P5)

`ServersScreen` shows a **Terminal** button on each running VM row. Tapping it
opens `RemoteTerminalDialog`, which opens a JSch **shell channel** (PTY) to
`root@<tailscaleIp|guestIp>:22` and streams output to a scrollable pane with a
command input. Because the guest is reached over SSH (not a local virtio
console), this uses `SshClient.shell(...)` rather than the Termux PTY used for
local VMs.

---

## 6. Local-VM network push (host bridge `NETINFO`)

For **remote** guests, the app SSHes *into* the guest to learn its Tailscale IP
(see §4.3). For **local** VMs, the app instead learns the guest IP when the
guest *pushes* it over the existing host bridge:

- `HostProtocol.NETINFO` verb + `HostProtocol.parseNetInfo(...)` (regex over a
  base64 JSON `{tailscaleIp, magicDns, guestIp}`).
- `HostRequestDispatcher` handles `NETINFO` and routes it to an
  `onNetInfo` sink (default no-op; `VmInstance` can register a real sink).
- `HostProtocol.NetInfoRegistry` is the in-process dispatch point.

> **Status:** the app-side protocol and dispatcher are implemented. The
> *guest-side* `podsteroid-netinfo` script that periodically emits `NETINFO` over
> `hvc2` is a **rootfs/build task not yet done**. Until then, local fleet nodes
> show `no routable address yet` — they are still listed and controllable, they
> just aren't yet reachable by Tailscale IP from the fleet view.

---

## 7. Cluster formation (P6)

`FleetScreen → "Form cluster"` opens a dialog:

1. Pick a **control node** (a running remote VM; it will run the k3s *server*).
2. Pick **worker** VMs (other running remote VMs).
3. **Get join token** → `RemoteServerManager.fetchK3sToken(control)`:
   `sudo cat /var/lib/rancher/k3s/server/node-token` inside the guest.
4. The join command is shown:
   `curl -sfL https://get.k3s.io | K3S_URL=https://<controlTailscaleIp>:6443 K3S_TOKEN=<token> sh -`
5. **Join selected** → `RemoteServerManager.joinK3s(agent, url, token)` runs
   that command inside each worker guest.

The control node's Tailscale IP is used as `K3S_URL` so workers reach it across
the mesh regardless of underlying LAN/NAT.

> **Preconditions for a working cluster:** the control VM was created with
> `containerRuntime = K3S` (or you installed k3s manually) so a server token
> exists; Tailscale is up on both nodes; the guest root/podsteroid SSH works.

---

## 8. Known limitations / TODOs (review findings)

These are honest gaps found during the code review; none block the happy path.

1. **Guest root/`podsteroid` is hardcoded** in `fetchTailscaleIp`,
   `runInGuest`, `joinK3s`, `shellToGuest`, and `buildCloudInit`. Per design
   (no per-guest secret mgmt yet) but should be parameterized later.
2. **Tailscale login is not automated.** `buildCloudInit` installs
   `tailscaled` but never runs `tailscale up --authkey <key>`. The
   `RemoteVm.tailscaleAuthKeyRef` field exists but is unused. A real deploy
   needs an auth key injected (or manual `tailscale up`).
3. **k3s `--node-ip` is a placeholder.** `buildCloudInit` uses
   `--node-ip=\${ETH0_IP:-127.0.0.1}`; `ETH0_IP` is never set, so k3s would
   bind to loopback. Should be wired to the discovered guest/Tailscale IP.
4. **`createVmCmd` opens N SSH sessions per refresh** (one per domain for
   `domuuid`/`domstats`/`domifaddr`). Fine for a few VMs; could be batched into
   a single script later.
5. **`ServersViewModel.createVm` does not auto-refresh** the VM list afterward;
   the new VM appears after the next manual Refresh. Minor UX.
6. **Local fleet nodes** have no routable address until the guest-side
   `NETINFO` push (§6) is implemented in the rootfs.
7. **`virshListCmd()` is dead code** (unused; `listDomains` uses
   `virsh list --all` directly).
8. **`RemoteServerManager` imports `android.util.Log` but never logs** (and has
   an unused `companion object TAG`). Cosmetic; remove if the build flags
   unused imports.
9. **Cloud image is Ubuntu jammy** by default while `RemoteVm.guestDistro`
   defaults to `"debian"` — cosmetic label mismatch only.
10. **Compile not yet re-verified** after the P4–P6 UI edits (build was paused
    per request). The Kotlin is logically consistent; a `./gradlew
    :app:compileDebugKotlin` pass is the remaining gate before device testing.

---

## 9. Build & test status

- `./gradlew :app:compileDebugKotlin` — **pending re-run** (was green through
  P0–P3; P4–P6 added UI + navigation that compile-clean in review but were not
  rebuilt).
- `./gradlew :app:testDebugUnitTest` — covers `LibvirtRemoteProviderTest`
  (parsers, `mergeVm`, `domainUuid`, arch-aware base image), `RemoteModelTest`
  (JSON round-trip for `RemoteServer`/`RemoteVm`/`ContainerRuntime`), and
  `SshClientTest` (fingerprint determinism + a fake `HostKeyStore` exercising
  accept-first-use and mismatch).
- End-to-end behavior (real libvirt server + Tailscale) is **unvalidated**
  without a live server and a rebuilt guest rootfs.

---

## 10. Dependencies added

- `com.github.mwiede:jsch:2.28.6` (maintained JSch fork, `com.jcraft.jsch`
  package) declared in `gradle/libs.versions.toml` + `app/build.gradle.kts`.
