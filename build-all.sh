#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# PodSteroid Unified Build & Deploy Script
# Coordinates kernel, initramfs, rootfs, QEMU, and APK builds.
# (libtermux.so is no longer built here — the vendored terminal-emulator
#  module compiles it via AGP's NDK build using src/main/jni/Android.mk.)
# ─────────────────────────────────────────────────────────────────────────────
set -eEuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNILIBS="${SCRIPT_DIR}/app/src/main/jniLibs/arm64-v8a"
ASSETS="${SCRIPT_DIR}/app/src/main/assets"

# ── Build temp root ──────────────────────────────────────────────────────────
# Point Gradle caches/wrapper, the Android SDK, and scratch temp at a dedicated
# disk (default /opt/nas) so a full build never eats the home folder. Override
# by exporting NAS_ROOT=/path/to/elsewhere before running this script.
NAS_ROOT="${NAS_ROOT:-/opt/nas}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-${NAS_ROOT}/gradle-home}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${NAS_ROOT}/android-sdk}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
TMPDIR="${TMPDIR:-${NAS_ROOT}/tmp}"

mkdir -p "$GRADLE_USER_HOME" "$ANDROID_SDK_ROOT" "$TMPDIR"
export GRADLE_USER_HOME ANDROID_SDK_ROOT ANDROID_HOME TMPDIR

# AGP reads sdk.dir from local.properties; write it (gitignored) so the APK
# build finds the SDK even when the env above isn't propagated.
if [ -d "$ANDROID_SDK_ROOT" ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "${SCRIPT_DIR}/local.properties"
fi

# ── Colors ────────────────────────────────────────────────────────────────────
BLUE='\033[1;34m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
NC='\033[0m' # No Color

log() { printf "${BLUE}==>${NC} %s\n" "$*"; }
warn() { printf "${YELLOW}WARNING:${NC} %s\n" "$*"; }
error() { printf "${RED}ERROR:${NC} %s\n" "$*"; exit 1; }
success() { printf "${GREEN}SUCCESS:${NC} %s\n" "$*"; }

# ── Build timing ───────────────────────────────────────────────────────────────
# Record the start time up front and print start/end + total elapsed on every
# exit (success or failure) via an EXIT trap, so long builds are easy to gauge.
BUILD_START_TS=$(date +%s)
log "Build started at $(date -d "@${BUILD_START_TS}" '+%Y-%m-%d %H:%M:%S')"

print_build_timing() {
    local end_ts
    end_ts=$(date +%s)
    local elapsed=$((end_ts - BUILD_START_TS))
    local h=$((elapsed / 3600))
    local m=$(((elapsed % 3600) / 60))
    local s=$((elapsed % 60))
    printf "${GREEN}==>${NC} Build finished at $(date -d "@${end_ts}" '+%Y-%m-%d %H:%M:%S')\n"
    printf "${GREEN}==>${NC} Total build time: %02d:%02d:%02d (hh:mm:ss)\n" "$h" "$m" "$s"
}
trap print_build_timing EXIT

# ── Docker garbage collection ─────────────────────────────────────────────────
# Every `docker build` accumulates: dangling images (re-tagging the same name),
# BuildKit layer cache, and stopped extract containers. Prune them before a
# build (leftovers from the previous run) and again on any failure, so repeated
# builds don't pile up gigabytes of waste on the build disk.
prune_docker() {
    log "Pruning stale Docker build cache + dangling images..."
    docker builder prune -f >/dev/null 2>&1 || true
    docker image prune -f >/dev/null 2>&1 || true
    docker container prune -f >/dev/null 2>&1 || true
    log "Docker prune complete."
}
trap 'prune_docker' ERR

# ── arm64 QEMU emulation (for the arm64 rootfs-builder stages) ─────────────────
# The rootfs stages build an aarch64 Alpine image on an x86_64 host, so they need
# QEMU user-mode emulation registered in binfmt_misc. A `docker prune` or reboot
# can drop that registration, after which the build dies with a cryptic
# "exec /bin/sh: exec format error" instead of running apk. Re-register on demand.
ensure_arm64_emulation() {
    if [ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
        return 0
    fi
    log "Registering arm64 QEMU emulation (binfmt_misc)..."
    if docker run --rm --privileged multiarch/qemu-user-static --reset -p yes >/dev/null 2>&1; then
        return 0
    fi
    warn "Could not register arm64 emulation automatically; the rootfs build may fail with 'exec format error'."
}

# ── Help ──────────────────────────────────────────────────────────────────────
show_help() {
    cat <<EOF
PodSteroid Unified Build Tool

Usage: $0 [command] [options]

Commands:
  all           Build everything (Kernel, Initramfs, Rootfs, QEMU, APK)
  kernel        Build custom kernel only (podsteroid_kernel.config + Linux source)
  initramfs     Build custom kernel + Alpine VM initramfs (vmlinuz + initrd)
  rootfs        Build Alpine rootfs squashfs (alpine-rootfs.squashfs)
  qemu          Build QEMU + podsteroid-bridge + podsteroid-launcher
  apk           Build the Android APK (also builds libtermux.so via Gradle NDK)
  deploy        Build APK, uninstall old version, and install to device
  test          Perform full build, install, and automated boot validation
  clean         Remove build artifacts and temporary containers

Options:
  --fast        Skip QEMU native builds if binaries already exist
  --help        Show this help message

EOF
}

# ── NDK Detection ─────────────────────────────────────────────────────────────
find_ndk() {
    if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
        echo "$ANDROID_NDK_ROOT"
    elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
        ls -d "${ANDROID_HOME}/ndk/"* 2>/dev/null | sort -V | tail -1
    elif [ -d "$HOME/Android/Sdk/ndk" ]; then
        ls -d "$HOME/Android/Sdk/ndk/"* 2>/dev/null | sort -V | tail -1
    else
        return 1
    fi
}

# ── Verification Helpers ──────────────────────────────────────────────────────
verify_16kb_align() {
    local lib="$1"
    python3 - "$lib" << 'EOF'
import struct, sys
path = sys.argv[1]
with open(path, 'rb') as f:
    data = f.read()
e_phoff = struct.unpack_from('<Q', data, 32)[0]
e_phentsize = struct.unpack_from('<H', data, 54)[0]
e_phnum = struct.unpack_from('<H', data, 56)[0]
aligns = []
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    if struct.unpack_from('<I', data, off)[0] == 1:
        aligns.append(struct.unpack_from('<Q', data, off + 48)[0])
ok = all(a >= 16384 for a in aligns)
if not ok:
    print(f"FAILED: {path} is not 16KB page aligned!")
    sys.exit(1)
EOF
}

# ── Build Functions ───────────────────────────────────────────────────────────

build_kernel() {
    local kernel_ver
    kernel_ver=$(grep -E '^podsteroidKernelVersion=' "${SCRIPT_DIR}/gradle.properties" | cut -d= -f2)
    log "Building custom kernel ${kernel_ver} for aarch64 (Docker)..."
    docker build \
        --build-arg "KERNEL_VERSION=${kernel_ver}" \
        -t podsteroid-kernel-builder --target kernel-builder "$SCRIPT_DIR"
    log "Extracting kernel artifact..."
    docker rm -f podsteroid-kernel-extract 2>/dev/null || true
    docker create --name podsteroid-kernel-extract podsteroid-kernel-builder
    mkdir -p "$ASSETS"
    docker cp podsteroid-kernel-extract:/output/vmlinuz-virt "$ASSETS/vmlinuz-virt"
    docker rm podsteroid-kernel-extract >/dev/null
    success "Custom kernel ready."
}

build_initramfs() {
    local kernel_ver
    kernel_ver=$(grep -E '^podsteroidKernelVersion=' "${SCRIPT_DIR}/gradle.properties" | cut -d= -f2)
    log "Building custom kernel + Alpine Initramfs (Docker)..."
    docker build \
        --build-arg "KERNEL_VERSION=${kernel_ver}" \
        -t podsteroid-builder --target packer "$SCRIPT_DIR"

    log "Extracting initramfs artifacts..."
    docker rm podsteroid-extract 2>/dev/null || true
    docker create --name podsteroid-extract podsteroid-builder /bin/true
    mkdir -p "$ASSETS"
    docker cp podsteroid-extract:/output/vmlinuz-virt "$ASSETS/vmlinuz-virt"
    docker cp podsteroid-extract:/output/initrd.img "$ASSETS/initrd.img"
    docker rm podsteroid-extract >/dev/null
    success "Kernel + initramfs ready."
}

build_rootfs() {
    local sysver
    sysver=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "${SCRIPT_DIR}/app/build.gradle.kts" | grep -oE '[0-9]+' | head -1)

    log "Building Alpine rootfs squashfs..."
    docker build -f "${SCRIPT_DIR}/build-rootfs/Dockerfile.rootfs" \
        -t podsteroid-rootfs:latest \
        --build-arg "SYSTEM_VERSION=${sysver:-0}" \
        --output type=local,dest="${ASSETS}" \
        "${SCRIPT_DIR}/build-rootfs/"
    success "Built ${ASSETS}/alpine-rootfs.squashfs ($(du -h "${ASSETS}/alpine-rootfs.squashfs" | cut -f1)), system-version ${sysver:-0}"

    log "Building Debian rootfs squashfs..."
    docker build -f "${SCRIPT_DIR}/build-rootfs/Dockerfile.debian" \
        -t podsteroid-debian-rootfs:latest \
        --build-arg "SYSTEM_VERSION=${sysver:-0}" \
        --output type=local,dest="${ASSETS}" \
        "${SCRIPT_DIR}/build-rootfs/"
    success "Built ${ASSETS}/debian-rootfs.squashfs ($(du -h "${ASSETS}/debian-rootfs.squashfs" | cut -f1)), system-version ${sysver:-0}"
}

build_qemu() {
    local qemu_ver
    qemu_ver=$(grep -E '^podsteroidQemuVersion=' "${SCRIPT_DIR}/gradle.properties" | cut -d= -f2)
    log "Building QEMU ${qemu_ver} for Android ARM64 (Docker)..."
    
    docker build --build-arg "QEMU_VERSION=${qemu_ver}" \
        -t podsteroid-qemu-builder --target final "${SCRIPT_DIR}"
        
    log "Extracting QEMU artifacts..."
    docker rm -f podsteroid-qemu-extract 2>/dev/null || true
    docker create --name podsteroid-qemu-extract podsteroid-qemu-builder /bin/true
    
    mkdir -p "$JNILIBS" "$ASSETS/qemu/keymaps"
    docker cp podsteroid-qemu-extract:/libqemu-system-aarch64.so "$JNILIBS/"
    docker cp podsteroid-qemu-extract:/libslirp.so               "$JNILIBS/"
    docker cp podsteroid-qemu-extract:/libpodsteroid-bridge.so      "$JNILIBS/"
    docker cp podsteroid-qemu-extract:/libpodsteroid-launcher.so    "$JNILIBS/"
    docker cp podsteroid-qemu-extract:/qemu/efi-virtio.rom        "$ASSETS/qemu/"
    docker cp podsteroid-qemu-extract:/qemu/keymaps/.             "$ASSETS/qemu/keymaps/"
    docker rm podsteroid-qemu-extract >/dev/null
    
    verify_16kb_align "$JNILIBS/libqemu-system-aarch64.so"
    success "QEMU and bridge ready."
}

build_apk() {
    log "Building APK via Gradle..."
    ./gradlew assembleDebug
    success "APK built: app/build/outputs/apk/debug/app-debug.apk"
}

deploy_apk() {
    log "Deploying to device..."

    # Pick the first connected device (adb install/uninstall take -s too,
    # but a bare default keeps the old behavior for single-device setups).
    local serial
    serial=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
    [ -n "$serial" ] || error "No device connected via ADB."
    local adb_cmd="adb -s $serial"

    # Uninstall first (fresh state) — keep going when it wasn't installed.
    $adb_cmd uninstall com.tiquasar.podsteroid.debug || warn "Uninstall failed (likely not installed)."

    # The APK is ~1GB; `adb install` streams it with no progress feedback and
    # can look hung on slow (Tailscale/wireless) links. Push with a percentage
    # progress line instead, then install from the device-local copy, which
    # also avoids the package-manager streaming path.
    local apk="app/build/outputs/apk/debug/app-debug.apk"
    local remote="/data/local/tmp/app-debug.apk"
    [ -f "$apk" ] || error "APK not found at $apk (run: $0 apk)"

    local size size_mb
    size=$(stat -c%s "$apk")
    size_mb=$((size / 1024 / 1024))

    # Progress callback: adb already writes its own status line on completion;
    # this adds a live percentage while pushing.
    $adb_cmd push "$apk" "$remote" &
    local push_pid=$!
    local pushed=0
    while kill -0 "$push_pid" 2>/dev/null; do
        # Query how much of the file landed on the device so far.
        pushed=$($adb_cmd shell stat -c%s "$remote" 2>/dev/null || echo 0)
        local pct=$(( pushed * 100 / (size == 0 ? 1 : size) ))
        printf "\r    Pushing APK: %3d%% (%d/%d MB)" "$pct" "$((pushed / 1024 / 1024))" "$size_mb"
        sleep 2
    done
    wait "$push_pid" || error "adb push failed."
    printf "\r    Pushing APK: 100%% (%d/%d MB)\n" "$size_mb" "$size_mb"

    $adb_cmd shell pm install -r -d "$remote"
    $adb_cmd shell rm -f "$remote"
    success "Deployed and ready."
}

run_boot_test() {
    local pkg="com.tiquasar.podsteroid.debug"
    local activity="com.tiquasar.podsteroid.MainActivity"
    local timeout=60
    
    log "Starting Automated Boot Test..."
    
    # Check for device
    adb devices 2>/dev/null | grep -q 'device$' || error "No device connected via ADB."
    
    # Build and Install
    build_apk
    deploy_apk
    
    # Reset State
    log "Resetting VM storage for clean test..."
    adb shell am force-stop "$pkg" 2>/dev/null || true
    adb shell run-as "$pkg" rm -f files/storage.img 2>/dev/null || true
    adb shell run-as "$pkg" rm -f files/console.log 2>/dev/null || true
    
    # Launch
    log "Launching App..."
    adb shell am start -n "$pkg/$activity" >/dev/null 2>&1
    
    echo -e "${YELLOW}>>> PLEASE PRESS 'Start Podman' IN THE APP NOW <<<${NC}"
    
    # Poll console log
    log "Waiting for VM to boot (timeout: ${timeout}s)..."
    local boot_ok=false
    for i in $(seq 1 "$timeout"); do
        local console
        console=$(adb shell run-as "$pkg" cat files/console.log 2>/dev/null || echo "")
        if echo "$console" | grep -q "Ready!"; then
            boot_ok=true
            break
        fi
        printf "."
        sleep 1
    done
    echo ""
    
    if [ "$boot_ok" = false ]; then
        error "VM failed to boot within ${timeout}s. Check 'adb logcat'."
    fi
    
    # Validation
    log "Validating boot output..."
    local console
    console=$(adb shell run-as "$pkg" cat files/console.log 2>/dev/null || echo "")
    
    local errors=0
    local checks=("PodSteroid - Alpine Linux" "IP:" "Ready!" "Loading kernel modules")
    for check in "${checks[@]}"; do
        if echo "$console" | grep -q "$check"; then
            success "Check passed: $check"
        else
            warn "Check FAILED: $check"
            errors=$((errors + 1))
        fi
    done
    
    if [ "$errors" -eq 0 ]; then
        success "Automated Boot Test PASSED."
    else
        error "Automated Boot Test FAILED with $errors errors."
    fi
}

# ── Main Logic ────────────────────────────────────────────────────────────────

[ $# -eq 0 ] && { show_help; exit 1; }

FAST=false
for arg in "$@"; do [ "$arg" == "--fast" ] && FAST=true; done

case "$1" in
    kernel)    ensure_arm64_emulation; prune_docker && build_kernel ;;
    initramfs) ensure_arm64_emulation; prune_docker && build_initramfs ;;
    rootfs)    ensure_arm64_emulation; prune_docker && build_rootfs ;;
    qemu)      ensure_arm64_emulation; prune_docker && build_qemu ;;
    apk)       build_apk ;;
    deploy)    build_apk && deploy_apk ;;
    test)      ensure_arm64_emulation; prune_docker && run_boot_test ;;
    all)
        ensure_arm64_emulation
        prune_docker
        build_initramfs
        build_rootfs
        build_qemu
        build_apk
        ;;
    clean)
        log "Cleaning up..."
        ./gradlew clean
        docker rmi podsteroid-builder podsteroid-qemu-builder podsteroid-rootfs:latest 2>/dev/null || true
        success "Cleaned."
        ;;
    *)
        show_help
        exit 1
        ;;
esac
