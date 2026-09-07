#!/bin/sh
# PodSteroid runtime tweaks driven by kernel cmdline markers set by the
# Android app (QemuEngine.buildCommand):
#   podsteroid.zram=<MB>      configure compressed swap
#   podsteroid.virtiofs=1     hint (no-op; virtio-fs sharing is mounted below)
#   podsteroid.virgl=1        hint for the X11 desktop to use virtual GPU
#   podsteroid.distro=<name>  informational
set -e

cmdline=$(cat /proc/cmdline 2>/dev/null || true)

# ── ZRAM swap ────────────────────────────────────────────────
zram=$(echo "$cmdline" | sed -n 's/.*podsteroid\.zram=\([0-9]*\).*/\1/p')
if [ -n "$zram" ] && [ "$zram" -gt 0 ]; then
    modprobe zram num_devices=1 2>/dev/null || true
    if [ -b /dev/zram0 ]; then
        echo "$((zram * 1024 * 1024))" > /sys/block/zram0/disksize 2>/dev/null || true
        mkswap /dev/zram0 2>/dev/null && swapon -p 100 /dev/zram0 2>/dev/null || true
    fi
fi

# ── Host file share (QEMU virtio-9p, mount_tag=podsteroid-share) ─
mount_tag="podsteroid-share"
if grep -q " 9p " /proc/filesystems 2>/dev/null; then
    mkdir -p /mnt/podsteroid-share
    mount -t 9p -o trans=virtio,version=9p2000.L,access=any "$mount_tag" /mnt/podsteroid-share 2>/dev/null || true
fi

exit 0
