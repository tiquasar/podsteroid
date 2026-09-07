/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Remote libvirt/KVM provider. All parsers are pure (unit-testable without a
 * server); the command strings are buildable in isolation; the SSH-dependent
 * methods take an injected [SshExecutor] so the whole provider can be exercised
 * against a fake. Drives the server over `virsh`/`virt-install`/`qemu-img`.
 */
package com.tiquasar.podsteroid.remote

import android.util.Log

private const val TAG = "LibvirtRemote"

/** Abstraction over a single remote shell command. */
fun interface SshExecutor {
    suspend fun exec(command: String): Result<ExecResult>
    /** Like [exec] but streams each output line to [onLine] as it arrives. */
    suspend fun execStream(command: String, onLine: (String) -> Unit): Result<ExecResult> =
        exec(command)
}

data class VirshDomain(val id: String?, val name: String, val rawState: String, val uuid: String? = null)
data class DomainStats(val cpuTimeMs: Double, val maxMemKb: Long, val memKb: Long)

object LibvirtRemoteProvider {

    // Alpine "nocloud" cloud-init images are the smallest usable guests (~60 MB,
    // ~50 MB RAM idle) — chosen over Ubuntu jammy to keep host load minimal.
    const val DEFAULT_BASE_IMAGE_AMD64 =
        "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/cloud/nocloud_alpine-3.22.0-x86_64-bios-cloudinit-r0.qcow2"
    const val DEFAULT_BASE_IMAGE_ARM64 =
        "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/cloud/nocloud_alpine-3.22.0-aarch64-uefi-cloudinit-r0.qcow2"

    /** Picks the default cloud image for the server's guest architecture. */
    fun defaultBaseImageUrl(arch: String): String =
        if (arch == "arm64") DEFAULT_BASE_IMAGE_ARM64 else DEFAULT_BASE_IMAGE_AMD64

    /**
     * Credentials baked into every cloud-init image we build.
     *
     * These were previously the bare string "podsteroid" scattered across five
     * call sites in two files, so changing the guest password meant hunting
     * literals. Named here (and referenced by RemoteServerManager for guest SSH)
     * so there is exactly one place to change them.
     *
     * NOTE: these are intentionally well-known — the guest is a throwaway VM the
     * user owns. Do not reuse this pattern for host credentials.
     */
    const val GUEST_USER = "podsteroid"
    const val GUEST_PASSWORD = "podsteroid"

    // ── pure parsers ──────────────────────────────────────────────

    fun parseVirshList(output: String): List<VirshDomain> {
        val domains = mutableListOf<VirshDomain>()
        var inHeader = true
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (inHeader) {
                // header line contains "Id" and "Name" and "State"
                if (line.contains("Id") && line.contains("Name") && line.contains("State")) {
                    inHeader = false
                }
                continue
            }
            if (line.all { it == '-' }) continue // separator row
            val tokens = line.split(Regex("\\s+"))
            if (tokens.size < 3) continue
            val id = if (tokens[0] == "-") null else tokens[0]
            val name = tokens[1]
            // With `--name-state-uuid` the layout is: id  name  state  uuid
            // Without it: id  name  state...  (state may contain spaces)
            val uuid = if (tokens.size >= 4) tokens.last().takeIf { it != "-" } else null
            val stateEnd = if (uuid != null) tokens.size - 1 else tokens.size
            val state = tokens.subList(2, stateEnd).joinToString(" ")
            domains.add(VirshDomain(id, name, state, uuid))
        }
        return domains
    }

    fun statusFromState(raw: String): RemoteVmState = when {
        raw.contains("running", ignoreCase = true) -> RemoteVmState.RUNNING
        raw.contains("paused", ignoreCase = true) -> RemoteVmState.PAUSED
        raw.contains("shut", ignoreCase = true) ||
            raw.contains("off", ignoreCase = true) -> RemoteVmState.SHUTOFF
        raw.contains("idle", ignoreCase = true) ||
            raw.contains("destroy", ignoreCase = true) -> RemoteVmState.OTHER
        else -> RemoteVmState.UNKNOWN
    }

    fun parseDomstats(output: String): Map<String, DomainStats> {
        val result = mutableMapOf<String, DomainStats>()
        var current: String? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("Domain-")) {
                // "Domain-1 name" -> name is the 2nd token
                current = trimmed.split(Regex("\\s+")).getOrNull(1)
                continue
            }
            val sp = trimmed.indexOf(' ')
            if (sp < 0) continue
            val key = trimmed.substring(0, sp)
            val value = trimmed.substring(sp + 1).trim()
            val name = current ?: continue
            val stats = result.getOrPut(name) { DomainStats(0.0, 0, 0) }
            result[name] = when (key) {
                "cpu.time" -> stats.copy(cpuTimeMs = value.toDoubleOrNull()?.times(1000) ?: 0.0)
                "balloon.maximum" -> stats.copy(maxMemKb = value.toLongOrNull() ?: 0)
                "balloon.current" -> stats.copy(memKb = value.toLongOrNull() ?: 0)
                else -> stats
            }
        }
        return result
    }

    /** Extracts the first IPv4 address from `virsh domifaddr --domain <name>` output. */
    fun parseGuestIp(output: String): String? {
        for (line in output.lineSequence()) {
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size >= 4 && tokens.contains("ipv4")) {
                val addr = tokens.last()
                val ip = addr.substringBefore('/')
                if (ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return ip
            }
        }
        return null
    }

    // ── command builders ──────────────────────────────────────────

    // Unprivileged virsh defaults to qemu:///session, which is a DIFFERENT,
    // always-empty libvirt instance from the system one virt-install (via sudo)
    // uses. Pin every call to the system URI.
    const val VIRSH = "virsh -c qemu:///system"

    fun virshListCmd(): String = "$VIRSH list --all --name-state-uuid"
    fun domstatsCmd(): String = "$VIRSH domstats"
    fun domifaddrCmd(name: String): String = "$VIRSH domifaddr --domain $name"
    fun startCmd(name: String): String = "$VIRSH start $name"
    fun shutdownCmd(name: String): String = "$VIRSH shutdown $name"
    fun destroyCmd(name: String): String = "$VIRSH destroy $name"
    fun undefineCmd(name: String): String = "$VIRSH undefine $name --remove-all-storage"
    fun domUuidCmd(name: String): String = "$VIRSH domuuid $name"
    fun domStateCmd(name: String): String = "$VIRSH domstate $name"

    /** Builds the multi-step create command (base image cache + clone + virt-install). */
    fun createVmCmd(
        server: RemoteServer,
        spec: RemoteVmSpec,
        baseImageUrl: String = server.baseImageUrl ?: defaultBaseImageUrl(server.arch),
    ): String {
        val imgPath = "/var/lib/libvirt/images/${spec.name}.qcow2"
        val basePath = "/var/lib/libvirt/images/base-$(echo $baseImageUrl | md5sum | cut -c1-12).qcow2"
        val userData = buildCloudInit(server, spec)
        val virtType = if (spec.accelerator == Accelerator.TCG) "qemu" else "kvm"
        val netFlag = if (spec.networkMode == NetworkMode.BRIDGE) {
            "bridge=${spec.network}"
        } else {
            "network=${spec.network}"
        }
        return """
            echo "==> Creating VM ${spec.name}"
            echo "==> virtType=$virtType netFlag=$netFlag"
            . /etc/os-release 2>/dev/null || true
            DISTRO="${'$'}{ID:-linux}"
            VIRSH="virsh -c qemu:///system"
            # Self-heal access rights (idempotent): SSH user must be able to talk
            # to qemu:///system without a polkit agent.
            if [ -n "${'$'}{SUDO_USER:-}" ] && [ "${'$'}{SUDO_USER:-}" != "root" ]; then
              usermod -aG libvirt "${'$'}{SUDO_USER:-}" 2>/dev/null || true
            fi
            if [ ! -f /etc/polkit-1/rules.d/49-podsteroid-libvirt.rules ]; then
              mkdir -p /etc/polkit-1/rules.d
              cat > /etc/polkit-1/rules.d/49-podsteroid-libvirt.rules <<'POLKIT'
polkit.addRule(function(action, subject) {
    if (subject.isInGroup("libvirt") &&
        (action.id == "org.libvirt.unix.manage" || action.id == "org.libvirt.unix.monitor")) {
        return polkit.Result.YES;
    }
});
POLKIT
              systemctl restart polkit 2>/dev/null || service polkit restart 2>/dev/null || true
              echo "==> Granted libvirt group passwordless system access"
            fi
            echo "==> Ensuring default network"
            if ! ${'$'}VIRSH net-info default >/dev/null 2>&1; then
              cat > /tmp/ps-default-net.xml <<'PSNET'
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
PSNET
              ${'$'}VIRSH net-define /tmp/ps-default-net.xml || true
              rm -f /tmp/ps-default-net.xml
            fi
            ${'$'}VIRSH net-start default >/dev/null 2>&1 || true
            ${'$'}VIRSH net-autostart default >/dev/null 2>&1 || true
            echo "==> Checking base image at $basePath"
            if [ ! -f $basePath ]; then
              echo "==> Downloading base image from $baseImageUrl"
              if command -v curl >/dev/null 2>&1; then
                curl -fSL -o $basePath $baseImageUrl || { echo "curl failed: \$?" >&2; exit 1; }
              else
                wget -O $basePath $baseImageUrl || { echo "wget failed: \$?" >&2; exit 1; }
              fi
              echo "==> Base image downloaded"
            else
              echo "==> Base image already exists"
            fi
            echo "==> Creating overlay disk $imgPath"
            qemu-img create -f qcow2 -F qcow2 -b $basePath $imgPath ${spec.diskGb}G || { echo "qemu-img failed: \$?" >&2; exit 1; }
            echo "==> Writing cloud-init user-data"
            cat > /tmp/ud-${spec.name}.yaml <<'PODSTER0ID'
$userData
PODSTER0ID
            echo "==> Running virt-install"
            echo "==> Detecting osinfo OS variant (required by newer virt-install)"
            detect_osv() {
              case "$baseImageUrl" in
                *alpine*) osinfo-query os -f short-id 2>/dev/null | grep -oE 'alpinelinux[0-9.]+' | sort -V | tail -1 ;;
                *ubuntu*|*jammy*|*noble*) echo "ubuntu22.04" ;;
                *) echo "generic" ;;
              esac
            }
            OSV="${'$'}(detect_osv)"
            if [ -z "${'$'}OSV" ] && ! command -v osinfo-query >/dev/null 2>&1; then
              echo "==> Installing libosinfo-bin for accurate OS detection"
              case "${'$'}DISTRO" in
                debian|ubuntu|linuxmint|raspbian) DEBIAN_FRONTEND=noninteractive apt-get install -y libosinfo-bin >/dev/null 2>&1 || true ;;
                fedora|centos|rhel|rocky|alma) dnf -y install osinfo-db-tools >/dev/null 2>&1 || true ;;
              esac
              OSV="${'$'}(detect_osv)"
            fi
            [ -z "${'$'}OSV" ] && OSV="generic"
            echo "==> Using --os-variant ${'$'}OSV"
            # aarch64 hosts need AAVMF firmware or virt-install dies with
            # "ACPI requires UEFI on this architecture"; UEFI NICs additionally
            # need the iPXE EFI ROM (efi-virtio.rom) from ipxe-qemu.
            HOSTARCH="${'$'}(uname -m)"
            if [ "${'$'}HOSTARCH" = "aarch64" ]; then
              if ! ls /usr/share/AAVMF/AAVMF_CODE.fd /usr/share/qemu-efi-aarch64/QEMU_EFI.fd >/dev/null 2>&1 || \
                 ! ls /usr/share/qemu/efi-virtio.rom /usr/lib/ipxe/qemu/efi-virtio.rom >/dev/null 2>&1; then
                echo "==> Installing aarch64 VM firmware + NIC ROMs"
                case "${'$'}DISTRO" in
                  debian|ubuntu|linuxmint|raspbian)
                    DEBIAN_FRONTEND=noninteractive apt-get install -y qemu-efi-aarch64 ipxe-qemu || true ;;
                  fedora|centos|rhel|rocky|alma)
                    dnf -y install edk2-aarch64 ipxe-roms-qemu || true ;;
                  alpine)
                    apk add edk2-aarch64 ipxe-qemu || true ;;
                esac
              fi
            fi
            virt-install --import \
              --name ${spec.name} \
              --memory ${spec.memMb} \
              --vcpus ${spec.vcpus} \
              --virt-type $virtType \
              --disk path=$imgPath \
              --network $netFlag \
              --os-variant ${'$'}OSV \
              --cloud-init user-data=/tmp/ud-${spec.name}.yaml \
              --noautoconsole || { echo "virt-install failed: \$?" >&2; exit 1; }
            echo "==> virt-install complete"
            echo "==> UUID"
            ${'$'}VIRSH domuuid ${spec.name}
        """.trimIndent()
    }

    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Extracts the libvirt domain UUID from mixed script output. */
    fun parseDomainUuid(output: String): String? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.matches(UUID_RE) }

    // ── execution (SSH-dependent) ──────────────────────────────────

    suspend fun listDomains(exec: SshExecutor): Result<List<VirshDomain>> {
        // Prefer name+state; fall back to plain --all if needed.
        val out = exec.exec("$VIRSH list --all").getOrElse { return Result.failure(it) }
        val domains = parseVirshList(out.stdout)
        return Result.success(domains)
    }

    suspend fun domainStats(exec: SshExecutor, name: String): Result<DomainStats?> {
        val out = exec.exec(domstatsCmd()).getOrElse { return Result.failure(it) }
        return Result.success(parseDomstats(out.stdout)[name])
    }

    /** Bulk: one `virsh domstats` returns stats for every defined domain. */
    suspend fun allDomainStats(exec: SshExecutor): Result<Map<String, DomainStats>> {
        val out = exec.exec(domstatsCmd()).getOrElse { return Result.failure(it) }
        return Result.success(parseDomstats(out.stdout))
    }

    suspend fun guestIp(exec: SshExecutor, name: String): Result<String?> {
        val out = exec.exec(domifaddrCmd(name)).getOrElse { return Result.failure(it) }
        return Result.success(parseGuestIp(out.stdout))
    }

    suspend fun start(exec: SshExecutor, name: String): Result<Unit> =
        exec.exec(startCmd(name)).map {}

    suspend fun shutdown(exec: SshExecutor, name: String): Result<Unit> =
        exec.exec(shutdownCmd(name)).map {}

    suspend fun destroy(exec: SshExecutor, name: String): Result<Unit> =
        exec.exec(destroyCmd(name)).map {}

    suspend fun undefine(exec: SshExecutor, name: String): Result<Unit> =
        exec.exec(undefineCmd(name)).map {}

    /** Creates the VM and returns its libvirt UUID. */
    suspend fun createVm(exec: SshExecutor, server: RemoteServer, spec: RemoteVmSpec): Result<String> {
        val cmd = createVmCmd(server, spec)
        Log.d(TAG, "createVm command (${cmd.length} chars):\n$cmd")
        val create = exec.exec(cmd).getOrElse {
            Log.e(TAG, "createVm exec failed", it)
            return Result.failure(it)
        }
        Log.d(TAG, "createVm exit=${create.exitCode} stdout=${create.stdout.take(500)} stderr=${create.stderr.take(500)}")
        if (create.exitCode != 0) {
            return Result.failure(
                IllegalStateException("VM creation failed (${create.exitCode}): ${(create.stderr.ifBlank { create.stdout }).take(800)}"),
            )
        }
        val uuid = parseDomainUuid(create.stdout)
            ?: return Result.failure(IllegalStateException("could not read domain UUID from output:\n${create.stdout.take(400)}"))
        return Result.success(uuid)
    }

    suspend fun domainUuid(exec: SshExecutor, name: String): Result<String> {
        val out = exec.exec(domUuidCmd(name)).getOrElse { return Result.failure(it) }
        val uuid = out.stdout.trim().takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
        return if (uuid != null) Result.success(uuid) else Result.failure(
            IllegalStateException("no UUID for $name: ${out.stdout}"),
        )
    }

    /** Returns the live state of a single domain, or null if the domain is gone. */
    suspend fun domState(exec: SshExecutor, name: String): Result<RemoteVmState?> {
        val out = exec.exec(domStateCmd(name)).getOrElse { return Result.failure(it) }
        val raw = out.stdout.trim()
        if (raw.isEmpty() || out.exitCode != 0) return Result.success(null)
        return Result.success(statusFromState(raw))
    }

    /**
     * Pure merge of a discovered domain with its stored record. Extracted so the
     * mapping (state, specs, discovered IPs) can be unit-tested without SSH.
     */
    fun mergeVm(
        server: RemoteServer,
        stored: RemoteVm?,
        domain: VirshDomain,
        stats: DomainStats?,
        guestIp: String?,
        uuid: String,
    ): RemoteVm = RemoteVm(
        serverId = server.id,
        uuid = uuid,
        name = domain.name,
        state = statusFromState(domain.rawState),
        guestDistro = stored?.guestDistro ?: "debian",
        vcpus = stored?.vcpus ?: 2,
        memMb = stored?.memMb ?: (stats?.maxMemKb?.toInt() ?: 2048),
        diskGb = stored?.diskGb ?: 20,
        sshEnabled = stored?.sshEnabled ?: true,
        containerRuntime = stored?.containerRuntime ?: ContainerRuntime.BOTH,
        tailscaleEnabled = stored?.tailscaleEnabled ?: true,
        tailscaleAuthKeyRef = stored?.tailscaleAuthKeyRef,
        guestIp = guestIp,
        tailscaleIp = stored?.tailscaleIp,
        magicDns = stored?.magicDns,
        createdAt = stored?.createdAt ?: System.currentTimeMillis(),
    )

    /** Reads the guest's Tailscale IP via `tailscale ip -4` (run inside the guest). */
    suspend fun fetchTailscaleIp(exec: SshExecutor): Result<String?> {
        val out = exec.exec("tailscale ip -4 2>/dev/null || true").getOrElse { return Result.failure(it) }
        val ip = out.stdout.trim().lines().firstOrNull { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) }
        return Result.success(ip)
    }

    /** cloud-init user-data that provisions container runtime + Tailscale + root pw. */
    fun buildCloudInit(server: RemoteServer, spec: RemoteVmSpec): String {
        val baseUrl = server.baseImageUrl ?: defaultBaseImageUrl(server.arch)
        val isAlpine = baseUrl.contains("alpine")
        val runtime = when (spec.containerRuntime) {
            ContainerRuntime.DOCKER ->
                if (isAlpine) "apk update && apk add docker && rc-update add docker default && rc-service docker start || true"
                else "apt-get update && apt-get install -y docker.io"
            ContainerRuntime.K3S ->
                // K3S auto-detects the node IP from the first non-loopback
                // interface. Previously pinned to \${ETH0_IP:-127.0.0.1}, which
                // was never set — every agent registered with 127.0.0.1 and
                // cluster join failed. Drop the flag; k3s picks the right one.
                "curl -sfL https://get.k3s.io | sh -s -"
            ContainerRuntime.BOTH ->
                if (isAlpine)
                    "apk update && apk add docker && rc-update add docker default && rc-service docker start || true; curl -sfL https://get.k3s.io | sh -s -"
                else
                    "apt-get update && apt-get install -y docker.io; curl -sfL https://get.k3s.io | sh -s -"
            ContainerRuntime.NONE -> "true"
        }
        val tailscale = if (spec.tailscaleEnabled) {
            if (isAlpine) {
                "apk update && apk add tailscale && rc-update add tailscaled default && rc-service tailscaled start || true"
            } else {
                // Use the official Tailscale install script so the version tracks
                // upstream. The pinned "tailscale_X.Y.Z" tarball was always out
                // of date within a few months. apt repository would be the
                // even-cleaner long-term fix.
                "curl -sfL https://tailscale.com/install.sh | sh"
            }
        } else "true"
        // Alpine's admin group is `wheel` and the base image has no bash.
        val adminGroup = if (isAlpine) "wheel" else "sudo"
        val shell = if (isAlpine) "/bin/ash" else "/bin/bash"
        return """
            #cloud-config
            hostname: ${spec.name}
            manage_etc_hosts: true
            ssh_pwauth: true
            users:
              - name: root
                lock_passwd: false
                plain_text_passwd: ${GUEST_PASSWORD}
              - name: ${GUEST_USER}
                lock_passwd: false
                plain_text_passwd: ${GUEST_PASSWORD}
                groups: $adminGroup
                sudo: ALL=(ALL) NOPASSWD:ALL
                shell: $shell
            runcmd:
              - $runtime
              - $tailscale
        """.trimIndent()
    }
}

/** How the guest is accelerated on the host. */
enum class Accelerator { AUTO, KVM, TCG }

/** Guest network attachment mode. */
enum class NetworkMode { NAT, BRIDGE }

/** Immutable spec for createVm (the persisted [RemoteVm] is built from this + the UUID). */
data class RemoteVmSpec(
    val name: String,
    val guestDistro: String = "debian",
    val vcpus: Int = 2,
    val memMb: Int = 2048,
    val diskGb: Int = 20,
    val sshEnabled: Boolean = true,
    val containerRuntime: ContainerRuntime = ContainerRuntime.BOTH,
    val tailscaleEnabled: Boolean = true,
    /** CPU acceleration. AUTO probes /dev/kvm and falls back to TCG. */
    val accelerator: Accelerator = Accelerator.AUTO,
    /** NAT = libvirt virtual network; BRIDGE = attach to a host bridge (LAN). */
    val networkMode: NetworkMode = NetworkMode.NAT,
    /** Network name (NAT) or bridge name (BRIDGE), e.g. "default" / "br0". */
    val network: String = "default",
)
