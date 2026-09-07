#!/bin/sh
# build-rootfs/build-debian-rootfs.sh
#
# Runs INSIDE the arm64-emulated debian:bookworm builder (see Dockerfile.debian).
# The rootfs at /work/rootfs was created by `debootstrap bookworm`. Here we
# install the podsteroid package set, drop in the shared podsteroid-* OpenRC service
# layer, wire runlevels, and set busybox as PID 1.
set -eu

ROOTFS=/work/rootfs

# ── DNS for the chroot ───────────────────────────────────────────────────────
cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf" 2>/dev/null || true

# ── Install the podsteroid package set (glibc/apt equivalents of the Alpine set) ──
# Core set is required; the optional set tolerates missing/renamed packages so a
# single bad name doesn't abort the whole build.
chroot "$ROOTFS" /bin/bash -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y --no-install-recommends \
        openrc busybox login bash \
        podman crun docker.io \
        lxc dropbear \
        iptables nftables bridge-utils iproute2 \
        curl ca-certificates \
        uidmap libcap2-bin sudo slirp4netns \
        netavark aardvark-dns
'
chroot "$ROOTFS" /bin/bash -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get install -y --no-install-recommends \
        lxc-templates \
        tigervnc-standalone-server pulseaudio pulseaudio-utils \
        xfonts-base fonts-dejavu \
        fuse-overlayfs conntrack \
        gzip xz-utils \
        || true
'

# File capabilities for rootless podman newuidmap/newgidmap.
if command -v setcap >/dev/null 2>&1; then
    setcap cap_setuid+ep "$ROOTFS/usr/bin/newuidmap" 2>/dev/null || true
    setcap cap_setgid+ep "$ROOTFS/usr/bin/newgidmap" 2>/dev/null || true
fi

# sudo: wheel group may become root.
mkdir -p "$ROOTFS/etc/sudoers.d"
echo "%wheel ALL=(ALL) ALL" > "$ROOTFS/etc/sudoers.d/wheel"
chmod 0440 "$ROOTFS/etc/sudoers.d/wheel"

# Root password "podsteroid" (SHA-512, random salt) written directly into shadow.
ROOT_HASH=$(openssl passwd -6 podsteroid)
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"

# ── Strip docs to shrink the squashfs ────────────────────────────────────────
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info"

# ── Copy the shared podsteroid guest files ──────────────────────────────────────
mkdir -p "$ROOTFS/usr/local/bin"
cp /work/files/etc/init.d/podsteroid-bootstrap "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-network   "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-resize    "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-ready     "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-x11       "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-vsock     "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-hostd     "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-downloads "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-tweaks    "$ROOTFS/etc/init.d/"
cp /work/files/etc/init.d/podsteroid-migrate   "$ROOTFS/etc/init.d/"
chmod +x "$ROOTFS/etc/init.d/podsteroid-"*

# ── Replace Debian's sysv dropbear init with an OpenRC-native script ──────────
# Debian's dropbear .deb ships a sysvinit script (start-stop-daemon + LSB
# functions) that does not reliably start under OpenRC/busybox init. Overwrite
# it with the same OpenRC script Alpine uses so SSH comes up on boot.
cat > "$ROOTFS/etc/init.d/dropbear" <<'EOF'
#!/sbin/openrc-run
# OpenRC dropbear service (mirrors Alpine).

depend() {
	use logger dns
	need net
	after firewall
}

check_config() {
	if [ ! -e /etc/dropbear/dropbear_rsa_host_key ] ; then
		einfo "Generating RSA-Hostkey..."
		/usr/bin/dropbearkey -t rsa -f /etc/dropbear/dropbear_rsa_host_key
	fi
	if [ ! -e /etc/dropbear/dropbear_ecdsa_host_key ] ; then
		einfo "Generating ECDSA-Hostkey..."
		/usr/bin/dropbearkey -t ecdsa -f /etc/dropbear/dropbear_ecdsa_host_key
	fi
	if [ ! -e /etc/dropbear/dropbear_ed25519_host_key ] ; then
		einfo "Generating ED25519-Hostkey..."
		/usr/bin/dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key
	fi
}

start() {
	check_config || return 1
	ebegin "Starting dropbear"
	/usr/sbin/dropbear ${DROPBEAR_OPTS}
	eend $?
}

stop() {
	ebegin "Stopping dropbear"
	start-stop-daemon --stop --pidfile /var/run/dropbear.pid
	eend $?
}
EOF
chmod +x "$ROOTFS/etc/init.d/dropbear"

# ── Provide localmount (Debian's openrc package omits it) ────────────────────
# Alpine's openrc ships /etc/init.d/localmount; Debian's does not, so the shared
# podsteroid-bootstrap/podsteroid-migrate `need localmount` fails and the ENTIRE
# service tree (incl. dropbear + network) is skipped. init-podsteroid already
# mounted / read-write and stacked the overlay, so this is a thin no-op that
# simply satisfies the dependency and runs in the boot runlevel.
cat > "$ROOTFS/etc/init.d/localmount" <<'EOF'
#!/sbin/openrc-run
description="Mount local filesystems (PodSteroid Debian build)"

depend() {
	before net
	provide localmount
}

start() {
	ebegin "Mounting local filesystems"
	mount -o remount,rw / 2>/dev/null
	if [ -f /etc/fstab ]; then
		mount -a 2>/dev/null
	fi
	eend 0
}
EOF
chmod +x "$ROOTFS/etc/init.d/localmount"

cp /work/files/usr/local/bin/podsteroid-resize "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podsteroid-login  "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podsteroid-getty  "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podsteroid-backup "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/podsteroid-update-stats "$ROOTFS/usr/local/bin/"
cp /work/files/usr/local/bin/setup-k3s-agent.sh "$ROOTFS/usr/local/bin/"
chmod +x "$ROOTFS/usr/local/bin/setup-k3s-agent.sh"
cp /work/files/usr/local/bin/podsteroid-tweaks.sh "$ROOTFS/usr/local/bin/"
chmod +x "$ROOTFS/usr/local/bin/podsteroid-tweaks.sh"

# Static musl binaries from the vsock-builder stage (run fine on glibc).
chmod +x "$ROOTFS/usr/local/bin/podsteroid-vsock-agent" 2>/dev/null || true
chmod +x "$ROOTFS/usr/local/bin/podsteroid-hostd" 2>/dev/null || true
chmod +x "$ROOTFS/usr/local/bin/podsteroid-overlay-normalize" 2>/dev/null || true
ln -sf podsteroid-hostd "$ROOTFS/usr/local/bin/podsteroid-notify"
ln -sf podsteroid-hostd "$ROOTFS/usr/local/bin/podsteroid-forward"
ln -sf podsteroid-hostd "$ROOTFS/usr/local/bin/podsteroid-open"
ln -sf podsteroid-hostd "$ROOTFS/usr/local/bin/podsteroid-power"
ln -sf podsteroid-hostd "$ROOTFS/usr/local/bin/podsteroid-headless"
ln -sf podsteroid-hostd "$ROOTFS/usr/local/bin/podsteroid-server"
chmod +x "$ROOTFS/usr/local/bin/podsteroid-"*
# Pre-baked k3s/kubectl (from the Dockerfile download) keep their +x bit, but
# guard it in case the cross-arch COPY dropped the mode.
chmod +x "$ROOTFS/usr/local/bin/k3s" "$ROOTFS/usr/local/bin/kubectl" 2>/dev/null || true

mkdir -p "$ROOTFS/etc/conf.d"
cp /work/files/etc/conf.d/podsteroid "$ROOTFS/etc/conf.d/"
mkdir -p "$ROOTFS/etc/podsteroid"
cp /work/files/etc/podsteroid/forwards.conf "$ROOTFS/etc/podsteroid/forwards.conf"
chmod 0644 "$ROOTFS/etc/podsteroid/forwards.conf"
mkdir -p "$ROOTFS/etc/podsteroid/migrations"
cp /work/files/etc/podsteroid/migrations/README "$ROOTFS/etc/podsteroid/migrations/README"
printf '%s\n' "${SYSTEM_VERSION:-0}" > "$ROOTFS/etc/podsteroid/system-version"
chmod 0644 "$ROOTFS/etc/podsteroid/system-version"

cp /work/files/etc/inittab "$ROOTFS/etc/inittab"
cp /work/files/etc/rc.conf "$ROOTFS/etc/rc.conf"

mkdir -p "$ROOTFS/etc/profile.d"
cp /work/files/etc/profile.d/podsteroid-color.sh "$ROOTFS/etc/profile.d/"
cp /work/files/etc/profile.d/podsteroid-x11.sh   "$ROOTFS/etc/profile.d/"
cp /work/files/etc/profile.d/podsteroid-k3s.sh "$ROOTFS/etc/profile.d/"
chmod 0644 "$ROOTFS/etc/profile.d/podsteroid-color.sh" "$ROOTFS/etc/profile.d/podsteroid-x11.sh" "$ROOTFS/etc/profile.d/podsteroid-k3s.sh"
mkdir -p "$ROOTFS/etc/podsteroid"

mkdir -p "$ROOTFS/etc/containers"
cp /work/files/etc/containers/storage.conf "$ROOTFS/etc/containers/storage.conf"
chmod 0644 "$ROOTFS/etc/containers/storage.conf"

echo "podsteroid" > "$ROOTFS/etc/hostname"
echo "127.0.0.1 localhost podsteroid" > "$ROOTFS/etc/hosts"
echo "::1 localhost ip6-localhost" >> "$ROOTFS/etc/hosts"

cat > "$ROOTFS/etc/issue" <<'EOF'
Welcome to PodSteroid (Debian \S)
Kernel \r on \m (\l)

  Default login:  root  /  podsteroid
  Change root password:    passwd
  Create a regular user:   adduser -G wheel <name>

EOF

# ── Runlevels: busybox init → openrc; openrc reads /etc/runlevels ────────────
mkdir -p "$ROOTFS/etc/runlevels/sysinit" \
         "$ROOTFS/etc/runlevels/boot" \
         "$ROOTFS/etc/runlevels/default" \
         "$ROOTFS/etc/runlevels/shutdown"
# Debian's openrc lacks localmount; provide it in the boot runlevel so the
# shared podsteroid-* `need localmount` dependency resolves and the service
# tree (network, dropbear, host bridge, ...) actually starts.
ln -sf /etc/init.d/localmount "$ROOTFS/etc/runlevels/boot/localmount"
for svc in podsteroid-migrate podsteroid-bootstrap podsteroid-network podsteroid-resize dropbear docker lxc podsteroid-x11 podsteroid-vsock podsteroid-downloads podsteroid-hostd podsteroid-tweaks podsteroid-ready; do
    if [ -e "$ROOTFS/etc/init.d/$svc" ]; then
        ln -sf "/etc/init.d/$svc" "$ROOTFS/etc/runlevels/default/$svc"
    else
        echo "WARN: init script /etc/init.d/$svc missing, skipping runlevel symlink"
    fi
done

# ── PID 1 = busybox init (reads /etc/inittab, spawns getty + openrc) ──────────
ln -sf /bin/busybox "$ROOTFS/sbin/init"
# podsteroid-getty invokes /sbin/getty; provide it via busybox.
ln -sf /bin/busybox "$ROOTFS/sbin/getty"

echo "Debian rootfs staging complete."
