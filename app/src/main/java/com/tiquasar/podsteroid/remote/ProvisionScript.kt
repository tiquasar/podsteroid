package com.tiquasar.podsteroid.remote

/** Builds the distro-agnostic libvirt provisioning shell script for [server]. */
internal fun buildProvisionScript(server: RemoteServer): String {
    return """
        set -e
        SUDO=""
        if [ "${'$'}(id -u)" != "0" ]; then SUDO="sudo"; fi
        . /etc/os-release 2>/dev/null || true
        DISTRO="${'$'}{ID:-linux}"
        echo "==> Detected distro: ${'$'}DISTRO"
        # Never let debconf prompt — there is no interactive terminal on this path
        # and any prompt blocks the whole provision forever.
        export DEBIAN_FRONTEND=noninteractive
        export DEBIAN_PRIORITY=critical
        case "${'$'}DISTRO" in
          debian|ubuntu|linuxmint|raspbian)
            echo "==> apt-get update (can take a minute)…"
            ${'$'}SUDO apt-get update -y
            echo "==> Installing qemu-kvm + libvirt + virtinst + cloud-init (several minutes)…"
            ${'$'}SUDO apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold qemu-kvm qemu-utils libvirt-daemon-system libvirt-clients virtinst cloud-init
            ;;
          fedora)
            ${'$'}SUDO dnf -y install @virtualization virt-install cloud-init
            ;;
          centos|rhel|rocky|alma)
            ${'$'}SUDO dnf -y install @virt virt-install cloud-init
            ;;
          opensuse*|sles)
            ${'$'}SUDO zypper -n install -y libvirt qemu-kvm qemu-tools virt-install cloud-init
            ;;
          arch)
            ${'$'}SUDO pacman -S --noconfirm libvirt qemu virt-install cloud-init
            ;;
          alpine)
            ${'$'}SUDO apk add -U libvirt-daemon qemu-system-x86_64 qemu-img virt-install cloud-init-openrc
            ;;
          *)
            echo "Unsupported distro: ${'$'}DISTRO" >&2; exit 1 ;;
        esac
        echo "==> Enabling libvirt services"
        if command -v systemctl >/dev/null 2>&1; then
          ${'$'}SUDO systemctl enable --now libvirtd 2>/dev/null || true
          ${'$'}SUDO systemctl enable --now virtlogd 2>/dev/null || true
        else
          ${'$'}SUDO service libvirtd start 2>/dev/null || true
          ${'$'}SUDO libvirtd -d 2>/dev/null || true
        fi
        echo "==> Preparing default network"
        sleep 2
        ${'$'}SUDO virsh net-start default 2>/dev/null || {
          if [ ! -f /usr/share/libvirt/networks/default.xml ]; then
            ${'$'}SUDO mkdir -p /usr/share/libvirt/networks
            ${'$'}SUDO tee /usr/share/libvirt/networks/default.xml >/dev/null <<'NETXML'
<network>
  <name>default</name>
  <forward mode='nat'/>
  <bridge name='virbr0' stp='on' delay='0'/>
  <ip address='192.168.122.1' netmask='255.255.255.0'>
    <dhcp>
      <range start='192.168.122.2' end='192.168.122.254'/>
    </dhcp>
  </ip>
</network>
NETXML
          fi
          ${'$'}SUDO virsh net-define /usr/share/libvirt/networks/default.xml
          ${'$'}SUDO virsh net-start default
        }
        ${'$'}SUDO virsh net-autostart default 2>/dev/null || true
        # Allow the SSH user to talk to qemu:///system without sudo.
        if [ -n "${'$'}{SUDO_USER:-}" ] && [ "${'$'}{SUDO_USER:-}" != "root" ]; then
          ${'$'}SUDO usermod -aG libvirt "${'$'}{SUDO_USER:-}" 2>/dev/null || true
          echo "==> Added ${'$'}{SUDO_USER:-} to libvirt group"
        fi
        echo "==> Granting libvirt group passwordless system access (polkit)"
        ${'$'}SUDO mkdir -p /etc/polkit-1/rules.d
        ${'$'}SUDO tee /etc/polkit-1/rules.d/49-podsteroid-libvirt.rules >/dev/null <<'POLKIT'
polkit.addRule(function(action, subject) {
    if (subject.isInGroup("libvirt") &&
        (action.id == "org.libvirt.unix.manage" || action.id == "org.libvirt.unix.monitor")) {
        return polkit.Result.YES;
    }
});
POLKIT
        ${'$'}SUDO systemctl restart polkit 2>/dev/null || ${'$'}SUDO service polkit restart 2>/dev/null || true
        echo "==> Pre-caching default cloud image"
        ARCH_IMG="${'$'}(echo ${LibvirtRemoteProvider.defaultBaseImageUrl(server.arch)} | sed 's/[^a-zA-Z0-9:\/._-]//g')"
        ARCH_BASE="/var/lib/libvirt/images/base-$(echo "${'$'}ARCH_IMG" | md5sum | cut -c1-12).qcow2"
        if [ ! -f "${'$'}ARCH_BASE" ]; then
          ( ${'$'}SUDO curl -fSL -o "${'$'}ARCH_BASE" "${'$'}ARCH_IMG" || ${'$'}SUDO wget -O "${'$'}ARCH_BASE" "${'$'}ARCH_IMG" ) || echo "WARN: base image prefetch failed (will retry on first VM create)"
        fi
        echo "==> Done. libvirt is ready."
    """.trimIndent()
}
