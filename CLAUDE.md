# CLAUDE.md

Guidance for Claude Code (and any AI assistant or new contributor) working in this repository. This is the deep map: read it before touching anything. Keep it accurate when you change the code.

## What Is This

PodSteroid is an Android app that runs real **Alpine 3.24** Linux VMs on stock Android 8+ (arm64) to provide rootless **Podman / Docker / LXC** containers and an in-app **X11 desktop** - no root, no custom recovery.

- **Multiple independent VMs** run from one app. Each VM is a `VmDefinition` (its own disk image, RAM/CPU/storage/SSH config, backend, and port-forward table) managed by `VmRegistry`; VMs run **simultaneously** (each VM owns its own engine, working directory, and host-port set). The **Home** screen lists/creates/deletes/selects VMs; the terminal/X11/status/settings screens operate on the selected VM.
- **Two interchangeable VM backends** behind one interface (`VmEngine`), one instance per VM:
  - **QEMU (TCG)** - software emulation, the default, needs no special permission.
  - **AVF (pKVM)** - hardware-accelerated via the Android Virtualization Framework on Pixel-class devices, after a one-time `pm grant`.
- The guest is a standard Alpine root with **OpenRC as PID 1**. System services live in `/etc/init.d/podsteroid-*` on a read-only squashfs; a persistent ext4 overlay captures user changes (`apk add`, `rc-update add`).
- An embedded **Termux-based terminal**, an **X11/VNC viewer** (Xvnc + PulseAudio), and a **guest-to-Android bridge** (`podsteroid-notify` / `podsteroid-forward`) round it out.

## Key Facts

| | |
|---|---|
| Package | `com.tiquasar.podsteroid` (debug: `com.tiquasar.podsteroid.debug`) |
| Version | `versionName` / `versionCode` in `app/build.gradle.kts` |
| Min / target SDK | 26 (Android 8) / 36 |
| Architecture | arm64 (`aarch64`) only |
| Guest | Alpine 3.24 squashfs + persistent ext4 overlay, OpenRC PID 1 |
| Kernel | custom Linux, version pinned by `podsteroidKernelVersion` in `gradle.properties` |
| QEMU | version pinned by `podsteroidQemuVersion` in `gradle.properties` |
| UI | Jetpack Compose + Material 3, single Activity |
| DI | Hilt (constructor injection) |
| Async | Coroutines + StateFlow |
| Persistence | Jetpack DataStore (no database; Room is declared but unused) |
| Terminal | vendored Termux fork as local Gradle modules (`terminal-view`, `terminal-emulator`) |
| Languages | English + 中文 (Chinese); see `data/repository/LanguageManager.kt` + `res/values-zh/` |

## Build Commands

All native/VM components are coordinated by `build-all.sh` (Docker-cached):

```bash
./build-all.sh kernel      # custom kernel only (podsteroid_kernel.config)
./build-all.sh initramfs   # kernel + minimal initramfs
./build-all.sh rootfs      # Alpine squashfs -> app/src/main/assets/alpine-rootfs.squashfs
./build-all.sh qemu        # QEMU + native helpers via Docker (slow first time)
./build-all.sh termux      # terminal-emulator JNI for 16KB pages
./build-all.sh apk         # Android APK via Gradle
./build-all.sh all         # everything
./build-all.sh deploy      # all + install + launch
./build-all.sh test        # boot validation: installs, polls console.log for "Ready!"
```

**APK only:** `./gradlew assembleDebug` / `./gradlew installDebug`.

**Unit tests:** `./gradlew :app:testDebugUnitTest` (use the `:app:` form, not the bare task).

**Monitor VM boot:**
```bash
adb logcat -s PodSteroidQemu
adb shell run-as com.tiquasar.podsteroid.debug cat files/console.log   # debug build
```

**Release builds** are signed via `signingConfigs.release` (keystore `podsteroid-release.jks`), fed by the `PODSTEROID_RELEASE_STORE_FILE` / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` Gradle properties. Release `applicationId = com.tiquasar.podsteroid`; debug gets `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-debug"`. Any code comparing the local version against an upstream release tag must strip an optional `-debug` suffix (see `UpdateRepository.checkForUpdate`).

Native binaries require **16KB page alignment** (`-Wl,-z,max-page-size=16384`) - mandatory on Android 13+; verified by an ELF parser in `build-all.sh`.

## Architecture

### Engine abstraction (the most important thing to understand)

Everything VM-related goes through `engine/VmEngine.kt`, a single interface implemented by two backends and instantiated once per VM:

- **`QemuEngine.kt`** - QEMU/TCG. Software emulation, SLIRP user-mode networking, control via QMP and virtio-console Unix sockets. No special permission.
- **`engine/avf/AvfEngine.kt`** - AVF/pKVM. Uses the Android Virtualization Framework (reached by reflection in `AvfReflect.kt`); networking and control ride **vsock**. Hardware-accelerated; needs `MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE` granted via `pm grant`.
- **`VmRegistry.kt`** - the `@Singleton` multi-VM manager. Holds one `VmInstance` per `VmDefinition`, reconciles the DataStore definition list against the live instance map, and routes create/delete/rename/select/start/stop. Its `start()` builds a `VmConfig` from the VM's definition and injects the VM's implicit SSH/VNC/audio forwards.
- **`VmInstance.kt`** - the runtime for one VM: owns its concrete engine plus the per-VM concerns that used to be app-global (port-forward diff loop, guest host bridge, USB passthrough).
- **`EngineFactory.kt`** - constructs a fresh `QemuEngine`/`AvfEngine` for a `VmInstanceSpec` (per-VM working dir + AVF name); memoizes the AVF probe and falls back to QEMU when AVF is unavailable.

When you touch VM behavior, decide whether it is backend-neutral (put it behind `VmEngine` / `VmInstance`) or backend-specific (inside `QemuEngine` or `AvfEngine`). Per-VM state lives in a `VmDefinition`; the `VmEngine` interface itself stays single-VM.

### Data flow

```
Terminal UI (Compose)
    | TerminalSession (vendored Termux JNI)
libpodsteroid-bridge.so  <->  terminal.sock + ctrl.sock  <->  VM  <->  hvc0 + hvc1
boot monitor          <->  serial.sock (QEMU) / console stream (AVF)  ->  console.log + boot-stage detector
QmpClient             <->  qmp.sock (QEMU only)        -> runtime port forwards (netdev_add/remove)
HostRequestServer     <->  host.sock/hvc2 (QEMU) | vsock:9101 (AVF)    <- podsteroid-notify / podsteroid-forward
```

QEMU exposes these Unix sockets under the VM's **working directory** (`context.filesDir` for the default `"default"` VM, `filesDir/vms/<id>/` for others), each with one role:

- **`terminal.sock`** ↔ virtio-console `/dev/hvc0` - primary terminal I/O. getty runs on hvc0; `libpodsteroid-bridge.so` relays it to a Termux PTY.
- **`ctrl.sock`** ↔ virtio-console `/dev/hvc1` - resize channel. The bridge debounces SIGWINCH bursts and writes one `RESIZE rows cols\n`; a guest resize daemon `stty`s hvc0.
- **`serial.sock`** ↔ PL011 `/dev/ttyAMA0` - boot-log sink only. The boot monitor streams kernel + init output into `console.log` and the boot-stage detector.
- **`qmp.sock`** - QEMU Machine Protocol for runtime port forwarding and USB hot-plug.
- **`host.sock`** ↔ virtio-console `/dev/hvc2` - the guest→Android host bridge (see below).

On **AVF** there is no QMP and no PL011: the console is captured via `ConsoleFanout.kt`, control/resize go over `VsockControlChannel.kt`, port forwards over `VsockPortForwarder.kt`, and the host bridge over vsock port 9101.

### Boot pipeline

1. The engine launches the VM and starts the boot monitor (`QemuBootMonitor.kt` for QEMU; the console stream for AVF). `BootStageDetector.kt` scans the rolling console buffer (last ~1KB, not per-`read()` chunk - fast devices split markers like `Ready!` across reads).
2. **`init-podsteroid`** (initramfs, ~45 lines): mounts the persistent ext4 (`/dev/vda` → upper) and the read-only squashfs (`/dev/vdb` → lower), stacks an overlayfs, moves the mounts into the new root, and `switch_root`s into `/sbin/init` (busybox).
3. Busybox `/sbin/init` reads `/etc/inittab` and starts **OpenRC** (runlevels are pre-symlinked at build time - chroot-into-aarch64 doesn't work on an x86_64 builder).
4. OpenRC services on the squashfs do all system bringup:
   - `podsteroid-bootstrap` - kernel modules, cgroup v2, devpts/shm/mqueue, sysctl, ZRAM swap, `mount --make-rshared /`, container dirs.
   - `podsteroid-network` - eth0 up, addressing, default route, `/etc/resolv.conf`.
   - `podsteroid-resize` - reads `RESIZE rows cols` from `/dev/hvc1`, `stty`s `/dev/hvc0`.
   - `podsteroid-hostd` - the host-bridge daemon (both backends).
   - `podsteroid-vsock` - the AVF control/forward agent (AVF boots only).
   - `dropbear` - SSH, when enabled.
   - `podsteroid-ready` - emits `Starting SSH...` / `Almost ready...` / `Ready!`, the markers `BootStageDetector` matches; `Ready!` flips state to `Running` and auto-starts the terminal bridge.

**Why `switch_root`, not `chroot`:** an earlier version `chroot`ed into the overlay, which broke `podman exec -it` - `setns(MNT)` in `crun exec` resets `fs->root`, so the exec'd process saw raw kernel paths (`/mnt/overlay/proc`) instead of `/proc`. `switch_root` reorganizes the kernel mount tree itself, so namespace forks see a clean `/`.

### Guest → Android host bridge

Lets guest processes call back to Android. Guest side: `podsteroid-hostd` (multi-call C binary, `build-rootfs/host-bridge/podsteroid-hostd.c`) owns a guest-local `AF_UNIX` socket `/run/podsteroid-host.sock`; `argv[0]` dispatch also exposes it as `podsteroid-notify` and `podsteroid-forward` (symlinks). The daemon relays one request line / one response line to Android over a backend transport: `/dev/hvc2` on QEMU, `AF_VSOCK:9101` on AVF (chosen by the `podsteroid.backend=avf` cmdline marker). Android side: `engine/hostbridge/` - `HostRequestServer` reads requests over a `HostTransport` (`QemuHostTransport` via `LocalSocket` to `host.sock`, or `AvfHostTransport` via `connectVsock`), `HostRequestDispatcher` parses them, and routes to `NotificationPoster` (posts via `NotificationManagerCompat`) or the VM's own port-forward list (persisted back into that VM's `VmDefinition`, applied live by `VmInstance`). Each `VmInstance` starts/stops its own server over that VM's lifecycle. Free-text fields are base64 (`HostProtocol.kt`) so UTF-8 survives.

> **Gotcha:** `/dev/hvc2` is a virtio-console **TTY** that defaults to echo on. The daemon must `cfmakeraw()` it, or the TTY echoes Android's responses back and the protocol desyncs after the first request. AVF (a raw vsock socket) is unaffected.

### Downloads sharing over 9p (`engine/avf/AvfDownloadsShare.kt` + `engine/avf/ninep/`)

**AVF only.** Serves the real Android Downloads directory into the guest by running an in-process **9p2000.L** server (`Ninep2000LServer.kt`, ~886 LoC; wire format in `NinepCodec.kt`) over vsock. This is why the guest can read and write user files without the app holding broad storage permissions, and it is how `podsteroid-backup` archives land in `Downloads/PodSteroid/backups` for `ContainerBackupRepository` to list.

There is no QEMU equivalent, so any feature built on the share is backend-asymmetric by construction and must degrade cleanly on QEMU.

### USB passthrough (`engine/usb/UsbPassthroughManager.kt`)

QEMU backend only. An unprivileged app can't open `/dev/bus/usb`, so it takes the already-open fd from `UsbManager`/`UsbDeviceConnection` and streams it to QEMU over `qmp.sock` as SCM_RIGHTS (`add-fd`), then hot-plugs with `device_add usb-host,hostdevice=/dev/fdset/N`. A code-registered `BroadcastReceiver` (no manifest `device_filter.xml`) is live only while the VM is `Running`. One `UsbPassthroughManager` per VM (constructed by `VmInstance` against that VM's engine). Gated by the VM's `usbPassthroughEnabled` flag, which also makes `buildCommand()` emit `-device qemu-xhci`. Needs a libusb-enabled QEMU build.

### X11 viewer (`x11/` + `ui/screens/x11/`)

In-app viewer that talks RFB to `Xvnc` in the guest, with touch→mouse, soft-keyboard, external mouse/keyboard, fullscreen, rotation lock, resolution presets, and PCM audio over PulseAudio loopback. Backed by always-on implicit VNC/audio port forwards.

### Internationalization

`data/repository/LanguageManager.kt` + `res/values/` / `res/values-zh/`. `MainActivity.attachBaseContext` wraps the context for the saved locale (read synchronously from a cache file before Hilt is available); changing the language in Settings persists it and recreates the Activity. Don't reintroduce hardcoded user-facing strings - use `stringResource` and keep `values` / `values-zh` in sync.

### Navigation

Single-activity Compose app: `ui/navigation/NavGraph.kt` routes `setup → home → terminal / settings / x11`. `TerminalViewModel` is scoped outside the `NavHost` so the session survives screen changes. The setup wizard shows until setup is marked complete in DataStore.

## Project Structure

```
/
├── app/                              # Android module
│   └── src/main/
│       ├── java/com/tiquasar/podsteroid/
│       │   ├── MainActivity.kt           # single Activity, locale wrap, WindowSizeClass
│       │   ├── PodSteroidApplication.kt     # Hilt app, asset extraction on first run
│       │   ├── engine/
│       │   │   ├── VmEngine.kt           # backend interface (single-VM)
│       │   │   ├── EngineHolder.kt       # (removed) replaced by VmRegistry/VmInstance/EngineFactory
│       │   │   ├── VmRegistry.kt         # multi-VM manager: one VmInstance per VmDefinition
│       │   │   ├── VmInstance.kt         # per-VM runtime: engine + port-forward diff + host bridge + USB
│       │   │   ├── VmInstanceSpec.kt     # per-VM working dir + AVF name
│       │   │   ├── EngineFactory.kt      # builds QemuEngine/AvfEngine per instance; memoized AVF probe
│       │   │   ├── EngineSelection.kt, RuleDiff.kt
│       │   │   ├── QemuEngine.kt         # QEMU/TCG backend, buildCommand()
│       │   │   ├── QemuBootMonitor.kt, BootStageDetector.kt
│       │   │   ├── QmpClient.kt          # QMP: port forwards + USB add-fd
│       │   │   ├── ResizeNotifyingSession.kt, VmState.kt
│       │   │   ├── avf/                  # AVF/pKVM backend (engine, reflection, vsock, console)
│       │   │   │   ├── AvfDownloadsShare.kt  # live Downloads share, AVF only (see below)
│       │   │   │   ├── AvfDiagnostics.kt     # AVF diagnostic + smoke-test entry point
│       │   │   │   └── ninep/                # in-process 9p2000.L server (Ninep2000LServer, NinepCodec)
│       │   │   ├── hostbridge/           # guest->Android bridge (transport, server, dispatcher, notify)
│       │   │   │   └── HeadlessModeManager.kt # single source of truth for server (headless) mode
│       │   │   └── usb/                  # UsbPassthroughManager
│       │   ├── service/PodSteroidService.kt # foreground service; multi-VM lifecycle, wakelock, notification
│       │   ├── data/repository/          # VmDefinition, VmRepository, Settings, PortForward, Update, Language, ContainerBackup, ContainerStats (all DataStore)
│       │   ├── di/                       # Hilt module
│       │   ├── util/                     # NetworkUtils, ShellQuote, HostMetrics + VmLoadSampler (Status screen), DeviceResourcePolicy (load-balance sizing)
│       │   ├── x11/                      # X11/VNC viewer engine
│       │   └── ui/                       # Compose: navigation, theme, screens/{setup,home,terminal,settings,x11,status,backup}, components
│       ├── res/values, res/values-zh/    # strings (EN + Chinese)
│       ├── assets/                       # vmlinuz-virt, initrd.img, alpine-rootfs.squashfs (all gitignored, built locally),
│       │                                 # qemu/, colors/ (122), fonts/ (13), ui-fonts/
│       └── jniLibs/arm64-v8a/            # native executables (see below)
├── terminal-view/, terminal-emulator/   # vendored Termux fork (local Gradle modules)
├── init-podsteroid                         # initramfs bootstrap: overlay + switch_root
├── podsteroid-bridge.c                     # PTY <-> virtio-console relay -> libpodsteroid-bridge.so
├── podsteroid-launcher.c                   # exec wrapper that ties QEMU's lifetime to the app -> libpodsteroid-launcher.so
├── Dockerfile                           # kernel + initramfs + QEMU build
├── build-tools/                         # static assets used during Docker builds
│   └── cross-android-aarch64.ini        # Meson cross-compilation config for aarch64-android26
├── build-rootfs/
│   ├── Dockerfile.rootfs, build-rootfs.sh
│   ├── vsock-agent/                     # podsteroid-vsock-agent.c (AVF control/forward agent)
│   ├── host-bridge/                     # podsteroid-hostd.c (guest->Android bridge daemon + CLIs)
│   └── files/etc/                       # OpenRC services + configs baked into the squashfs
├── build-all.sh, gradle.properties, build.gradle.kts, settings.gradle.kts
└── README.md, CONTRIBUTING.md, CREDITS.md
```

## Native Binaries (`app/src/main/jniLibs/arm64-v8a/`)

ELF executables renamed `.so` for APK packaging; run via `ProcessBuilder` / `TerminalSession`, not loaded as JNI libraries. All 16KB-aligned.

| File | What it is |
|---|---|
| `libqemu-system-aarch64.so` | QEMU TCG emulator |
| `libpodsteroid-bridge.so` | PTY ↔ virtio-console relay (from `podsteroid-bridge.c`) |
| `libpodsteroid-launcher.so` | exec wrapper for QEMU process lifetime (from `podsteroid-launcher.c`) |
| `libslirp.so` | SLIRP user-mode networking (soname patched `.so.0`→`.so`) |

The terminal emulator JNI is built from the vendored `terminal-emulator` module (rebuilt for 16KB pages), not shipped as a prebuilt here.

## QEMU command construction (`QemuEngine.buildCommand()`)

- `-serial unix:serial.sock` (boot log) + a `virtio-serial-pci` bus carrying three virtconsoles: `terminal.sock` (hvc0), `ctrl.sock` (hvc1), `host.sock` (hvc2, the host bridge - **order matters**, the guest expects host bridge on hvc2).
- `-qmp unix:qmp.sock` for runtime port forwards + USB.
- RAM, CPU count, and user-editable extras (`-cpu`, `-accel`, RNG, etc.) from `SettingsRepository`.
- Two virtio block devices: `/dev/vda` ← `storage.img` (writable ext4 overlay upper, resized on first boot), `/dev/vdb` ← `alpine-rootfs.squashfs` (read-only zstd lower).
- SLIRP networking; `-device qemu-xhci` when USB passthrough is enabled.

## Build pipelines

- **`Dockerfile`** - custom Linux kernel (arm64 defconfig + `podsteroid_kernel.config` modules + `forced_builtin.config` forcing overlayfs / netfilter / bridge / veth / tun / FUSE / IPv6 etc. to `=y`) and QEMU cross-compiled against the NDK. A build-time check greps the resolved `.config` and **fails the build** if any critical option isn't `=y` (guards against silent Kconfig demotion from unmet tristate deps). QEMU needs `--enable-libusb` (for passthrough) and a few Android/Bionic patches; `build-all.sh qemu` may need `docker build --network=host`.
- **`build-rootfs/Dockerfile.rootfs`** - fetches the Alpine minirootfs, runs `build-rootfs.sh` (apk-installs alpine-base + openrc + podman + crun + fuse-overlayfs + docker + lxc + dropbear + iptables/nftables + bridge-utils, sets root password `podsteroid`, seals file caps on `newuidmap`/`newgidmap`, copies the OpenRC services and the cross-compiled `podsteroid-vsock-agent` + `podsteroid-hostd`, wires runlevels via direct symlinks), then `mksquashfs -comp zstd` (kernel ships `CONFIG_SQUASHFS_ZSTD=y`).

## Performance tuning (TCG path; KVM is impossible without root)

In `QemuEngine.buildCommand()`: `tcg,thread=multi`, larger `tb-size` for ≥2GB RAM, dedicated `iothread` on `virtio-blk-pci`, `-cpu max,pauth-impdef=on`. More vCPUs are not better here: on an 8-core phone, 8 measured slower than 4 on every metric. Guest cmdline: `mitigations=off` (safe inside TCG), per-device `mq-deadline`. `init-podsteroid`/bootstrap: ZRAM lz4 swap at half RAM. `CONFIG_EXT4_FS_SECURITY=y` + `CONFIG_SQUASHFS_XATTR=y` keep `security.capability` xattrs across the overlay (rootless podman's `newuidmap` needs them). What won't work without root: `io_uring` (seccomp), CPU affinity, KSM, TAP networking, host hugepages.

## Quirks & gotchas

- **`CONFIG_DEVTMPFS_MOUNT=y` pre-mounts `/dev` before `/init` runs.** `init-podsteroid` must use `mount ... || true` for the devtmpfs line — the kernel's pre-populated `/dev` is sufficient and a second `mount(2)` returns EBUSY. Also: `size=` is not valid for devtmpfs in kernels where it is ramfs-backed (causes EINVAL). Either failure exits util-linux with `MNT_EX_FAIL=32`; `set -e` propagates `exit(32)`, encoded as `exitcode=0x00002000` ("Attempted to kill init!"). Always keep the `|| true`.
- **util-linux `mount` stops option parsing at the first non-option argument** when `POSIXLY_CORRECT` is set. Put `-o` flags *before* device and mountpoint (`mount -t proc -o noexec,nosuid,nodev proc /proc`), not after. Options placed after the mountpoint are silently dropped, causing `mount(2)` to be called with `flags=0` and returning EPERM — which util-linux prints as "must be superuser to use mount" even for root.
- **`Dockerfile` heredoc gotcha.** Docker BuildKit's parser treats `[section]` lines inside `RUN ... << 'EOF'` heredocs as unknown Dockerfile instructions and aborts the parse. Instead of using shell heredocs in `RUN` for multi-line config files, use `COPY build-tools/<file>` from the build context. The Meson cross-compilation config lives in `build-tools/cross-android-aarch64.ini` for this reason.
- **Backend asymmetry is the #1 source of bugs.** QEMU = SLIRP + QMP + virtio-console (TTY); AVF = DHCP + vsock (raw sockets). Test both. The host bridge's `cfmakeraw` on hvc2 (above) is a concrete example.
- **Multi-VM.** Each VM's disk/sockets/console live under its working directory (`filesDir` for the default `"default"` VM, `filesDir/vms/<id>/` otherwise); shared assets (kernel/initrd/rootfs/qemu) stay in `filesDir`. Implicit SSH/VNC/audio host ports are allocated per-VM so VMs can run at once. VMs run concurrently — each has its own engine/process, working dir, and (for AVF) unique framework name — subject to device RAM: an overcommitted guest OOMs and the AVF framework may reject an additional VM, surfacing as an Error on that VM. Changing a VM's backend in Settings recreates its engine only when the VM is stopped (deferred while Running/Starting).
- **Boot detection** scans a rolling buffer, not per-read chunks (fast devices split markers).
- **Bridge stderr is silenced** (`dup2(/dev/null, STDERR)`): it runs as a `TerminalSession` subprocess whose stderr IS the PTY. Never add `fprintf(stderr, ...)` to `podsteroid-bridge.c`.
- **`forceUpdateSizeFromView` must not multiply by scaledDensity** - `TerminalView` passes the raw int textSize to `Paint`; mismatched math renders TUI apps in the wrong grid.
- **SLIRP has no ICMP** (`ping` fails in the guest) and its DNS forwarder is unreliable on Android (resolv.conf uses public DNS).
- **Privileged ports**: the app can't bind host ports < 1024 (no `CAP_NET_BIND_SERVICE`), so SSH is on 9922, not 22. Forward to a high host port.
- **Reflection into the vendored Termux fork** (`mTermSession`, `mEmulator`, `mCurrentDecSetFlags`) is kept by `app/proguard-rules.pro`.
- **Persistence is sacred**: DataStore keys and `filesDir` paths survive every release; renames need migration. Updates must install in place (same `applicationId` + signing key, monotonic `versionCode`).

## VM migration / upgrades (how the guest updates without a reset)

The guest system layer updates across app versions with **no VM reset and no data loss**, on both backends. The machinery:

- **Plain overlay (never re-add metacopy/index/redirect).** `init-podsteroid` mounts the rootfs overlay as `lowerdir=/mnt/lower,upperdir=/mnt/persist/upper,workdir=/mnt/persist/work` only. Plain overlayfs tolerates a swapped lower, so a new squashfs (re-extracted by `PodSteroidApplication` on every update) goes live on the next boot while the persistent upper is preserved. **Do not re-add `metacopy=on`/`index=on`/`redirect_dir=on`** - they bind the upper to a specific lower and reintroduce the corruption-on-update bug (the whole reason resets used to be needed).
- **Version anchor.** The squashfs ships `/etc/podsteroid/system-version` (baked from `versionCode` by `build-all.sh` -> `Dockerfile.rootfs` ARG -> `build-rootfs.sh`). The last-applied version persists at `/mnt/persist/.podsteroid/applied-version`.
- **One-time legacy normalization.** `init-podsteroid` runs `podsteroid-overlay-normalize` (shipped in the squashfs, invoked via `/mnt/lower`, before the overlay is stacked) once per device (guarded by `/mnt/persist/.podsteroid/normalized`) to strip pre-existing `metacopy`/`redirect`/`index` state from uppers created by the old metacopy overlay. No-op on fresh/normalized uppers.
- **Imperative hooks.** `podsteroid-migrate` (OpenRC, runs `before podsteroid-bootstrap`) executes `/etc/podsteroid/migrations/<v>.sh` for `applied < v <= system-version` in order, then advances `applied-version` atomically. **To ship a fixup in a new release** (e.g. enable a newly-added service): add `build-rootfs/files/etc/podsteroid/migrations/<versionCode>.sh`, idempotent, install it in `build-rootfs.sh`. Pure file additions/changes need NO script - the overlay union surfaces them.
- **Reliability:** the marker advances only after migration completes (crash -> idempotent re-run); nothing auto-wipes `/mnt/persist`; `init-podsteroid` keeps its `FATAL -> exec sh` recovery shell.

## Common tasks

- **Kotlin/UI change** → `./gradlew assembleDebug && ./gradlew installDebug`.
- **OpenRC service / package list / guest CLI change** → edit under `build-rootfs/`, `./build-all.sh rootfs`, rebuild APK. New/changed system files go live on the **next VM boot** with no reset (the plain-overlay union surfaces them; see "VM migration / upgrades"). The exception is a path the user already modified - it lives in the persistent upper and keeps the user's version until removed, so use a `/etc/podsteroid/migrations/<v>.sh` script for that case.
- **`init-podsteroid` change** → `./build-all.sh initramfs`, rebuild APK.
- **New boot stage** → emit the marker in the right OpenRC service + match it in `BootStageDetector`.
- **New VM setting** → add a field to `VmDefinition` (+ JSON round-trip) and surface it in the VM editor; it flows into `VmConfig` via `VmRegistry.start()`. **New app-wide setting** (theme, terminal, language, X11 viewer) → add a DataStore key + Flow in `SettingsRepository`, UI in `SettingsScreen`, setter in `SettingsViewModel`.
- **New user-facing string** → add to `res/values` AND `res/values-zh`, use `stringResource`.
- **Verify before "done"**: compile, run `:app:testDebugUnitTest`, and for behavior changes install and walk the flow on a device (unit tests don't catch DI/graph or backend-specific issues).

## Proving a change on a device

Unit tests cover pure logic only. Anything touching VM behavior, the engine boundary, or a user-visible flow is unproven until it runs on hardware, and **the two backends need two different devices**:

| Backend | Device requirement | Extra setup |
|---|---|---|
| QEMU | any arm64 Android 8+ device | none; this is the default path |
| AVF | a device reporting `android.software.virtualization_framework` (Pixel-class) | `pm grant` both `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE`, then force-stop the app |

Check AVF capability before assuming a device can exercise it:

```bash
adb shell pm list features | grep virtualization
```

A device without that feature can *never* run `AvfEngine`, no matter the `EngineSelection` setting. `EngineFactory.resolvesToAvf()` memoizes the probe once per process, so a fresh `pm grant` does not take effect until the app is force-stopped and relaunched.

**A change that touches both backends is unproven until it has been installed and walked on both.** Backend asymmetry is the #1 source of bugs (see Quirks & gotchas), and `AvfEngine.kt` is the most-churned file in the repository, so a QEMU-only test is the easiest way to ship a regression.

Reading the VM console depends on build type: `run-as` works on debug, but **release builds are not `run-as`-able**, so use `su` or **Settings → Diagnostics → Export Log** there.

```bash
adb shell run-as com.tiquasar.podsteroid.debug cat files/console.log   # debug only
```
