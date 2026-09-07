#!/usr/bin/env bash
# build.sh — manual full rebuild with live output (no log file).
#
# Registers arm64 emulation (needed by the Alpine vsock-builder stage and the
# emulated Debian debootstrap), prunes Docker caches, then runs the complete
# pipeline (kernel → initramfs → both rootfs → QEMU → Termux → APK) streaming
# everything to the terminal.
set -e
cd "$(dirname "$0")"

# ── timing ───────────────────────────────────────────────────────────────────
BUILD_START=$(date +%s)
echo "==> build.sh start: $(date '+%Y-%m-%d %H:%M:%S')"
trap 'BUILD_END=$(date +%s); echo "==> build.sh end:   $(date "+%Y-%m-%d %H:%M:%S")"; echo "==> total time:     $(( (BUILD_END - BUILD_START) / 60 ))m $(( (BUILD_END - BUILD_START) % 60 ))s"' EXIT

# ── arm64 emulation for the rootfs builds ────────────────────────────────────
docker run --privileged --rm tonistiigi/binfmt --install arm64

# ── clean Docker caches ──────────────────────────────────────────────────────
docker system prune -a -f
docker builder prune -a -f
docker volume prune -f

# ── SDK / Gradle env ────────────────────────────────────────────────────────
export PATH=/opt/nas/android-sdk/platform-tools:$PATH
export GRADLE_USER_HOME=/opt/nas/gradle-home
export ANDROID_HOME=/opt/nas/android-sdk
export ANDROID_SDK_ROOT=/opt/nas/android-sdk

# ── full build, live ─────────────────────────────────────────────────────────
./build-all.sh all
