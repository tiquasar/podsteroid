/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Orchestrates a remote libvirt server: builds SSH credentials from the
 * encrypted secrets, drives the provider over SSH, and persists discovered
 * VMs + pinned host keys. Keeps no long-lived state — the ViewModel owns flows.
 */
package com.tiquasar.podsteroid.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tiquasar.podsteroid.R
import androidx.annotation.RawRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PrereqReport(
    val virsh: Boolean,
    val virtInstall: Boolean,
    val qemuImg: Boolean,
    val cloudInit: Boolean,
    val defaultNet: Boolean,
    val details: String,
    val hostKeyFingerprint: String? = null,
)

/** Result of a lightweight host capability probe used before VM creation. */
data class HostProbe(
    val kvm: Boolean,
    val libvirt: Boolean,
    val totalMemMb: Int,
    val freeDiskGb: Int,
    val distro: String,
    val tool: String,
)

@Singleton
class RemoteServerManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val ssh: SshClient,
    private val secretStore: SecretStore,
    private val serverRepo: RemoteServerRepository,
    private val vmRepo: RemoteVmRepository,
) {

    private fun alias(id: String) = "remote-$id"
    private fun passAlias(id: String) = "remote-$id-pass"

    /**
     * Per-host scratch directory on the remote side.
     *
     * Every script and password file used to live at a single shared path such as
     * `/tmp/ps-provision.sh` and `/tmp/ps-pw`, regardless of which server it
     * belonged to. Provisioning two hosts at once meant they overwrote each
     * other's script — and worse, one host's sudo password could be sitting in
     * `/tmp/ps-pw` while another host's `sudo -S` read it back. Namespacing by
     * server id removes both the race and the cross-host password bleed.
     */
    private fun scratchDir(serverId: String) = "/tmp/podsteroid-$serverId"

    /** Absolute scratch path for [name] belonging to [serverId]. */
    private fun scratchPath(serverId: String, name: String) = "${scratchDir(serverId)}/$name"

    /** mkdir -p the scratch dir, chmod 700 (it can hold a password file). */
    private fun ensureScratch(serverId: String) =
        "mkdir -p ${scratchDir(serverId)} && chmod 700 ${scratchDir(serverId)}"

    /** Encrypts the raw secret(s), persists the server, and returns the stored copy. */
    suspend fun saveServer(
        server: RemoteServer,
        secret: String,
        passphrase: String? = null,
    ): RemoteServer {
        Log.d(TAG, "saveServer: start id=${server.id}")
        val secretToken = try {
            withContext(Dispatchers.IO) { secretStore.encrypt(alias(server.id), secret) }
        } catch (e: Exception) {
            // Keystore failures (locked, corrupted, hardware-backed key gone) throw
            // generic javax.crypto exceptions. Wrap with a hint that points the
            // user at the device PIN / lock screen rather than the bare cause.
            throw IllegalStateException(
                "Could not store secret for ${server.name}. The Android Keystore may be locked or unavailable.",
                e,
            )
        }
        Log.d(TAG, "saveServer: secret encrypted")
        val passToken = passphrase?.let { p ->
            try {
                withContext(Dispatchers.IO) { secretStore.encrypt(passAlias(server.id), p) }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Could not store key passphrase for ${server.name}. The Android Keystore may be locked or unavailable.",
                    e,
                )
            }
        }
        Log.d(TAG, "saveServer: passphrase encrypted")
        val saved = server.copy(secretToken = secretToken, passphraseToken = passToken)
        serverRepo.put(saved)
        Log.d(TAG, "saveServer: persisted")
        return saved
    }

    suspend fun deleteServer(id: String) {
        secretStore.delete(alias(id))
        secretStore.delete(passAlias(id))
        vmRepo.snapshot().filter { it.serverId == id }.forEach { vmRepo.remove(it.serverId, it.uuid) }
        serverRepo.remove(id)
    }

    /** Persists a change to a server's [ServerKind] (e.g. after provisioning). */
    suspend fun saveServerKind(server: RemoteServer, kind: ServerKind, provisionedByUs: Boolean = false): RemoteServer {
        val existing = serverRepo.get(server.id)
        val fp = if (server.hostKeyFingerprint.isNullOrBlank()) existing?.hostKeyFingerprint else server.hostKeyFingerprint
        val updated = server.copy(kind = kind, provisionedByUs = provisionedByUs, hostKeyFingerprint = fp)
        serverRepo.put(updated)
        return updated
    }

    /**
     * Lightweight capability probe of a remote host: KVM availability, whether
     * libvirt is installed, total RAM, free disk in the libvirt images dir, the
     * distro id, and which download tool exists. Used to give clear pre-flight
     * feedback before creating a VM (and to pick KVM vs TCG).
     */
    suspend fun probeHost(server: RemoteServer): Result<HostProbe> = withServerSession(server) { _, exec ->
        val script = """
            echo "KVM=$([ -c /dev/kvm ] && echo 1 || echo 0)"
            echo "LIBVIRT=$(command -v virsh >/dev/null 2>&1 && echo 1 || echo 0)"
            echo "MEM=$(free -m | awk '/^Mem:/{print ${'$'}2}')"
            echo "DISK=$(df -BG /var/lib/libvirt 2>/dev/null | awk 'NR==2{gsub(/G/,""); print ${'$'}4}')"
            echo "DISTRO=$( (. /etc/os-release 2>/dev/null; echo ${'$'}ID) )"
            echo "TOOL=$(command -v curl >/dev/null 2>&1 && echo curl || (command -v wget >/dev/null 2>&1 && echo wget || echo none))"
        """.trimIndent()
        val res = exec.exec(script).getOrThrow()
        val map = res.stdout.lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i < 0) null else l.substring(0, i) to l.substring(i + 1).trim()
        }.toMap()
        HostProbe(
            kvm = map["KVM"] == "1",
            libvirt = map["LIBVIRT"] == "1",
            totalMemMb = map["MEM"]?.toIntOrNull() ?: 0,
            freeDiskGb = map["DISK"]?.toIntOrNull() ?: 0,
            distro = map["DISTRO"].orEmpty(),
            tool = map["TOOL"].orEmpty(),
        )
    }

    private fun credentialsFor(server: RemoteServer): SshCredentials? {
        val token = server.secretToken ?: return null
        return when (server.authKind) {
            AuthKind.PASSWORD -> SshCredentials(
                password = runCatching { secretStore.decrypt(alias(server.id), token) }.getOrNull(),
            )
            AuthKind.KEY -> SshCredentials(
                privateKeyPem = runCatching { secretStore.decrypt(alias(server.id), token) }.getOrNull(),
                passphrase = server.passphraseToken?.let {
                    runCatching { secretStore.decrypt(passAlias(server.id), it) }.getOrNull()
                },
            )
        }
    }

    /**
     * Builds an [SshExecutor] for [server]. The host-key store records the
     * accepted fingerprint and surfaces it via [onHostKeyAccepted] so the caller
     * can persist it. Returns null if the secret isn't available.
     */
    /**
     * Opens ONE SSH session to [server] and runs [block] over it (every command
     * in [block] shares the connection — critical on flaky links where a second
     * handshake stalls). Accepts unknown host keys first-use and persists the
     * fingerprint afterwards.
     */
    private suspend fun <R> withServerSession(
        server: RemoteServer,
        connectTimeoutMs: Int = 30_000,
        execTimeoutMs: Int = 30_000,
        block: suspend (SshClient.SessionContext, SshExecutor) -> R,
    ): Result<R> {
        val creds = credentialsFor(server) ?: return Result.failure(
            IllegalStateException("Missing stored secret for ${server.name}"),
        )
        val target = SshTarget(server.host, server.port, server.username)
        var acceptedFp: String? = null
        var lastErr: Throwable? = null
        // Retry the whole session on transient transport errors (e.g. flaky
        // Tailscale/VPN links where the TCP socket occasionally fails to open).
        // Logic/auth errors are not retried.
        repeat(3) { attempt ->
            Log.d(TAG, "withServerSession attempt ${attempt + 1}/3 -> ${server.username}@${server.host}:${server.port}")
            val holder = HostKeyHolder(server.host, server.hostKeyFingerprint) { acceptedFp = it }
            val res = ssh.withSession(
                target,
                creds,
                holder,
                connectTimeoutMs = connectTimeoutMs,
                execTimeoutMs = execTimeoutMs,
            ) { sessionCtx ->
                val exec = object : SshExecutor {
                    override suspend fun exec(command: String): Result<ExecResult> =
                        try { Result.success(sessionCtx.exec(command)) } catch (e: Exception) { Result.failure(e) }
                    override suspend fun execStream(command: String, onLine: (String) -> Unit): Result<ExecResult> =
                        try { Result.success(sessionCtx.execStream(command, onLine)) } catch (e: Exception) { Result.failure(e) }
                }
                block(sessionCtx, exec)
            }
            if (res.isSuccess) {
                acceptedFp?.let { fp -> serverRepo.put(server.copy(hostKeyFingerprint = fp)) }
                return res
            }
            lastErr = res.exceptionOrNull()
            Log.e(TAG, "attempt ${attempt + 1} failed: ${lastErr?.message}")
            val err = lastErr
            if (err != null && !isRetryableSshError(err)) return res
            if (attempt < 2) kotlinx.coroutines.delay(2000L * (attempt + 1))
        }
    return Result.failure(lastErr ?: IllegalStateException("SSH session failed"))
}

/** Transport/network errors worth retrying; auth and logic errors are not. */
private fun isRetryableSshError(e: Throwable): Boolean {
    val msg = e.message?.lowercase() ?: ""
    // "checksum mismatch" and "Failed to upload" come from writeRemoteFile —
    // they indicate the transport lost bytes, so a fresh session is worth trying.
    return msg.contains("socket") || msg.contains("connection") || msg.contains("timeout") ||
        msg.contains("refused") || msg.contains("reset") || msg.contains("unreachable") ||
        msg.contains("jsch") || msg.contains("broken pipe") || msg.contains("session is down") ||
        msg.contains("transport") || msg.contains("eof") ||
        msg.contains("channel closed") || msg.contains("no output for") ||
        msg.contains("checksum") || msg.contains("failed to upload") ||
        e is java.net.SocketException || e is java.net.UnknownHostException || e is java.net.ConnectException
}

    private suspend fun probePrereqs(exec: SshExecutor): PrereqReport {
        // One exec returns KEY=value lines for all five prereqs. With five
        // independent execs, dropbear races (channel open + immediately close
        // on a half-dead socket) caused random false negatives that triggered
        // spurious re-provisioning of already-provisioned hosts.
        val script = """
            echo "VIRSH=$(command -v virsh >/dev/null 2>&1 && ${LibvirtRemoteProvider.VIRSH} version >/dev/null 2>&1 && echo 1 || echo 0)"
            echo "VIRT_INSTALL=$(command -v virt-install >/dev/null 2>&1 && echo 1 || echo 0)"
            echo "QEMU_IMG=$(command -v qemu-img >/dev/null 2>&1 && echo 1 || echo 0)"
            echo "CLOUD_INIT=$(virt-install --help 2>&1 | grep -q -- '--cloud-init' && echo 1 || echo 0)"
            echo "DEFAULT_NET=$(${LibvirtRemoteProvider.VIRSH} net-info default >/dev/null 2>&1 && echo 1 || echo 0)"
        """.trimIndent()
        val res = exec.exec(script).getOrNull()
        val map = res?.stdout?.lines()
            ?.mapNotNull { l -> l.indexOf('=').takeIf { it >= 0 }?.let { i -> l.substring(0, i) to l.substring(i + 1).trim() } }
            ?.toMap()
            .orEmpty()
        fun b(k: String) = map[k] == "1"
        val virsh = b("VIRSH")
        val vi = b("VIRT_INSTALL")
        val qi = b("QEMU_IMG")
        val cloudInit = b("CLOUD_INIT")
        val defaultNet = b("DEFAULT_NET")
        return PrereqReport(
            virsh = virsh, virtInstall = vi, qemuImg = qi, cloudInit = cloudInit, defaultNet = defaultNet,
            details = "virsh=$virsh virt-install=$vi qemu-img=$qi cloud-init=$cloudInit default-net=$defaultNet",
            hostKeyFingerprint = null,
        )
    }

    data class SetupResult(val report: PrereqReport, val provisioned: Boolean, val vms: List<RemoteVm>)

    /**
     * The full "add a server" flow on a SINGLE SSH session: probe prereqs, and if
     * libvirt is missing, provision it (the app is the provisioner), then list
     * existing VMs — all over one connection so there is never a second
     * handshake that can stall. Streams per-step progress via [onStep] and
     * install output via [onLine].
     */
    suspend fun setupServer(
        server: RemoteServer,
        onStep: (String) -> Unit = {},
        onLine: (String) -> Unit = {},
    ): Result<SetupResult> = withServerSession(server, connectTimeoutMs = 30_000, execTimeoutMs = 1_800_000) { ctx, exec ->
        onStep("Connected to ${server.username}@${server.host}:${server.port}")
        val report = probePrereqs(exec)
        onStep("virsh version: ${if (report.virsh) "present" else "missing"}")
        onStep("virt-install: ${if (report.virtInstall) "present" else "missing"}")
        onStep("qemu-img: ${if (report.qemuImg) "present" else "missing"}")
        onStep("cloud-init support: ${if (report.cloudInit) "present" else "missing"}")
        onStep("default network: ${if (report.defaultNet) "present" else "missing"}")
        onStep("Connection check complete.")
        var provisioned = false
        if (!(report.virsh && report.virtInstall && report.qemuImg && report.cloudInit && report.defaultNet)) {
            onStep("Prerequisites not met — provisioning host…")
            val script = buildProvisionScript(server)
            val pw = credentialsFor(server)?.password
            val pwB64 = if (!pw.isNullOrBlank()) Base64.encodeToString(pw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else ""
            Log.d(TAG, "setupServer: provision cmd uses sudo=${pwB64.isNotEmpty()}")
            val provisionScript = scratchPath(server.id, "provision.sh")
            val pwFile = scratchPath(server.id, "pw")
            writeRemoteFile(ctx, exec, server.id, provisionScript, script)
            val res = if (pwB64.isNotEmpty()) {
                val cmd = "${ensureScratch(server.id)} && " +
                    "echo '$pwB64' | base64 -d > $pwFile && chmod 600 $pwFile && " +
                    "sudo -S sh $provisionScript < $pwFile"
                if (onLine != null) exec.execStream(cmd, onLine).getOrThrow() else exec.exec(cmd).getOrThrow()
            } else {
                if (onLine != null) exec.execStream("sh $provisionScript", onLine).getOrThrow() else exec.exec("sh $provisionScript").getOrThrow()
            }
            provisioned = res.exitCode == 0
            onStep(if (provisioned) "Provision complete." else "Provision exited ${res.exitCode}")
        } else {
            onStep("Host already has libvirt.")
        }
        val vms = try {
            val domains = LibvirtRemoteProvider.listDomains(exec).getOrThrow()
            val stored = vmRepo.snapshot().filter { it.serverId == server.id }
            domains.mapNotNull { dom ->
                val uuid = LibvirtRemoteProvider.domainUuid(exec, dom.name).getOrNull() ?: return@mapNotNull null
                val existing = stored.firstOrNull { it.uuid == uuid }
                val stats = LibvirtRemoteProvider.domainStats(exec, dom.name).getOrNull()
                val ip = LibvirtRemoteProvider.guestIp(exec, dom.name).getOrNull()
                LibvirtRemoteProvider.mergeVm(server, existing, dom, stats, ip, uuid)
            }
        } catch (_: Exception) { emptyList() }
        SetupResult(report, provisioned, vms)
    }

    suspend fun testConnection(
        server: RemoteServer,
        onStep: (String) -> Unit = {},
    ): Result<PrereqReport> = withServerSession(server) { _, exec ->
        onStep("Connected to ${server.username}@${server.host}:${server.port}")
        val report = probePrereqs(exec)
        onStep("virsh version: ${if (report.virsh) "present" else "missing"}")
        onStep("virt-install: ${if (report.virtInstall) "present" else "missing"}")
        onStep("qemu-img: ${if (report.qemuImg) "present" else "missing"}")
        onStep("cloud-init support: ${if (report.cloudInit) "present" else "missing"}")
        onStep("default network: ${if (report.defaultNet) "present" else "missing"}")
        onStep("Connection check complete.")
        report
    }

    /** Lists domains + bulk stats, merges with stored VMs, and persists.
     *  Two execs total (down from 1 + 3N), and a VM is no longer dropped from
     *  the UI when one per-VM exec fails. `domifaddr` (guest IP) is skipped
     *  here because the guest agent isn't installed in these minimal Alpine
     *  guests — it returned empty headers and only multiplied the flakiness. */
    suspend fun refreshVms(server: RemoteServer): Result<List<RemoteVm>> = withServerSession(server) { _, exec ->
        val domains = LibvirtRemoteProvider.listDomains(exec).getOrThrow()
        val stats = LibvirtRemoteProvider.allDomainStats(exec).getOrNull().orEmpty()
        val stored = vmRepo.snapshot().filter { it.serverId == server.id }
        val merged = domains.mapNotNull { dom ->
            val uuid = dom.uuid
                ?: LibvirtRemoteProvider.domainUuid(exec, dom.name).getOrNull()
                ?: return@mapNotNull null
            val existing = stored.firstOrNull { it.uuid == uuid }
            LibvirtRemoteProvider.mergeVm(server, existing, dom, stats[dom.name], null, uuid)
        }
        vmRepo.putAll(merged)
        merged
    }

    suspend fun createVm(
        server: RemoteServer,
        spec: RemoteVmSpec,
        onLine: ((String) -> Unit)? = null,
    ): Result<RemoteVm> = withServerSession(server, connectTimeoutMs = 30_000, execTimeoutMs = 600_000) { ctx, exec ->
        Log.d(TAG, "createVm starting: name=${spec.name} vcpus=${spec.vcpus} mem=${spec.memMb} disk=${spec.diskGb}")
        val pw = credentialsFor(server)?.password
        val pwB64 = if (!pw.isNullOrBlank()) Base64.encodeToString(pw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else ""
        // The create script writes into /var/lib/libvirt/images and talks to the
        // system libvirtd socket — both need root. Run it via sudo (same pattern
        // as provisioning); fall back to a direct run when no password is stored.
        val uuid = if (pwB64.isNotEmpty()) {
            val script = LibvirtRemoteProvider.createVmCmd(server, spec)
            onLine?.invoke("==> Uploading create script…")
            val createScript = scratchPath(server.id, "create.sh")
            val pwFile = scratchPath(server.id, "pw")
            writeRemoteFile(ctx, exec, server.id, createScript, script)
            val cmd = "${ensureScratch(server.id)} && " +
                "echo '$pwB64' | base64 -d > $pwFile && chmod 600 $pwFile && " +
                "{ sudo -S sh $createScript < $pwFile; rc=\$?; rm -f $pwFile $createScript; exit \$rc; }"
            val res = if (onLine != null) exec.execStream(cmd) { line ->
                if (line.isNotBlank()) onLine(line)
            }.getOrThrow() else exec.exec(cmd).getOrThrow()
            Log.d(TAG, "createVm: sudo run exit=${res.exitCode} stdout='${res.stdout.take(400)}'")
            if (res.exitCode != 0) {
                throw IllegalStateException("VM creation failed (${res.exitCode}): ${res.stdout.take(800)}")
            }
            LibvirtRemoteProvider.parseDomainUuid(res.stdout)
                ?: throw IllegalStateException("could not read domain UUID from output:\n${res.stdout.take(400)}")
        } else {
            LibvirtRemoteProvider.createVm(exec, server, spec).getOrThrow()
        }
        Log.d(TAG, "createVm success: uuid=$uuid")
        val vm = RemoteVm(
            serverId = server.id,
            uuid = uuid,
            name = spec.name,
            state = RemoteVmState.PENDING,
            guestDistro = spec.guestDistro,
            vcpus = spec.vcpus,
            memMb = spec.memMb,
            diskGb = spec.diskGb,
            sshEnabled = spec.sshEnabled,
            containerRuntime = spec.containerRuntime,
            tailscaleEnabled = spec.tailscaleEnabled,
            createdAt = System.currentTimeMillis(),
        )
        vmRepo.put(vm)
        vm
    }

    suspend fun startVm(server: RemoteServer, name: String): Result<Unit> =
        withServerSession(server) { _, exec -> LibvirtRemoteProvider.start(exec, name).getOrThrow() }

    suspend fun stopVm(server: RemoteServer, name: String): Result<Unit> =
        withServerSession(server) { _, exec -> LibvirtRemoteProvider.shutdown(exec, name).getOrThrow() }

    /** Hard power-off; the domain stays defined so it can be started again. */
    suspend fun destroyForce(server: RemoteServer, name: String): Result<Unit> =
        withServerSession(server) { _, exec -> LibvirtRemoteProvider.destroy(exec, name).getOrThrow() }

    /** Live state of one domain — used to confirm a graceful shutdown actually stopped it. */
    suspend fun domainState(server: RemoteServer, name: String): Result<RemoteVmState?> =
        withServerSession(server) { _, exec -> LibvirtRemoteProvider.domState(exec, name).getOrThrow() }

    suspend fun destroyVm(server: RemoteServer, vm: RemoteVm): Result<Unit> =
        withServerSession(server) { _, exec ->
            // Propagate failures: a failed destroy leaves the domain running, so
            // skipping undefine (and the local remove) keeps the VM visible and
            // the user can retry. Previously both exec results were ignored.
            LibvirtRemoteProvider.destroy(exec, vm.name).getOrThrow()
            LibvirtRemoteProvider.undefine(exec, vm.name).getOrThrow()
            vmRepo.remove(vm.serverId, vm.uuid)
        }

    /**
     * Pulls the guest's Tailscale IP by SSHing into the guest (root/podsteroid)
     * and running `tailscale ip -4`. Guest keys are not pinned, so we accept
     * whatever we get (the guest is the user's own machine).
     */
    suspend fun fetchTailscaleIp(server: RemoteServer, vm: RemoteVm): Result<String?> = withContext(Dispatchers.IO) {
        val address = vm.tailscaleIp ?: vm.guestIp
        if (address.isNullOrBlank()) return@withContext Result.success(null)
        val target = SshTarget(address, 22, "root")
        val creds = SshCredentials(password = LibvirtRemoteProvider.GUEST_PASSWORD)
        val exec = SshExecutor { cmd -> ssh.exec(target, creds, cmd, hostKeyStore = PermissiveHostKey) }
        val ip = LibvirtRemoteProvider.fetchTailscaleIp(exec).getOrElse { return@withContext Result.failure(it) }
        if (ip != null) vmRepo.put(vm.copy(tailscaleIp = ip))
        Result.success(ip)
    }

    /** Runs an arbitrary command inside the guest as the `podsteroid` user
     *  (passwordless sudo). Used by the remote terminal and k3s cluster join. */
    suspend fun runInGuest(server: RemoteServer, vm: RemoteVm, command: String): Result<ExecResult> =
        withContext(Dispatchers.IO) {
            val address = vm.tailscaleIp ?: vm.guestIp
            if (address.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Guest ${vm.name} has no reachable IP"))
            }
            val target = SshTarget(address, 22, LibvirtRemoteProvider.GUEST_USER)
            val creds = SshCredentials(password = LibvirtRemoteProvider.GUEST_PASSWORD)
            val exec = SshExecutor { cmd -> ssh.exec(target, creds, cmd, hostKeyStore = PermissiveHostKey) }
            exec.exec(command)
        }

    /** Reads the k3s server node token from a control-plane guest. */
    suspend fun fetchK3sToken(server: RemoteServer, vm: RemoteVm): Result<String> =
        runInGuest(server, vm, "sudo cat /var/lib/rancher/k3s/server/node-token 2>/dev/null").map { out ->
            out.stdout.trim().lineSequence().firstOrNull { it.isNotBlank() }
                ?: throw IllegalStateException("no k3s token (is this a server node?)")
        }

    /** Joins a guest to a k3s cluster as a worker by pushing and running the
     *  bundled setup-k3s-agent.sh (installs the agent, creates an OpenRC
     *  service, enables it at boot, and starts it). */
    suspend fun joinK3s(server: RemoteServer, vm: RemoteVm, controlUrl: String, token: String): Result<ExecResult> {
        val script = loadRawScript(R.raw.setup_k3s_agent)
        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = "echo '$b64' | base64 -d > /tmp/setup_k3s_agent.sh && sudo sh /tmp/setup_k3s_agent.sh '${controlUrl}' '${token}'"
        return runInGuest(server, vm, cmd)
    }

    /** Initializes a control node as a k3s *server* (installs the binary and
     *  starts it in the background). Best-effort: the server will not survive a
     *  guest reboot without a matching OpenRC service. Runs as root via sudo. */
    suspend fun initK3sServer(server: RemoteServer, vm: RemoteVm): Result<ExecResult> {
        val cmd = "sudo sh -c 'curl -sfL https://get.k3s.io | INSTALL_K3S_SKIP_START=true sh -s - server " +
            "--disable=traefik --write-kubeconfig-mode=644' && " +
            "sudo nohup k3s server --disable=traefik --write-kubeconfig-mode=644 " +
            ">/var/log/k3s-server.log 2>&1 &"
        return runInGuest(server, vm, cmd)
    }

    /**
     * Turns a plain Linux server into a libvirt/KVM host by installing the
     * virtualization stack (qemu-kvm, libvirt, virt-install, cloud-init) and
     * bringing up the `default` network. Distro-agnostic (apt/dnf/yum/zypper/
     * pacman/apk). Best-effort: reports the install log on success or failure.
     */
    suspend fun provisionLibvirt(
        server: RemoteServer,
        onLine: ((String) -> Unit)? = null,
    ): Result<ExecResult> {
        val script = buildProvisionScript(server)
        val pw = credentialsFor(server)?.password
        val pwB64 = if (!pw.isNullOrBlank()) Base64.encodeToString(pw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else ""
        Log.d(TAG, "provisionLibvirt: cmd uses sudo=${pwB64.isNotEmpty()}")
        return withServerSession(server, connectTimeoutMs = 30_000, execTimeoutMs = 1_800_000) { ctx, exec ->
            val who = exec.exec("id -u").getOrThrow()
            Log.d(TAG, "provisionLibvirt: id -u exit=${who.exitCode} stdout='${who.stdout}' stderr='${who.stderr}'")
            onLine?.invoke("==> Running as uid=${who.stdout.trim()}")

            onLine?.invoke("==> Uploading provision script…")
            val provisionScript = scratchPath(server.id, "provision.sh")
            val pwFile = scratchPath(server.id, "pw")
            writeRemoteFile(ctx, exec, server.id, provisionScript, script)
            onLine?.invoke("==> Wrote provision script to $provisionScript")

            val res = if (pwB64.isNotEmpty()) {
                val cmd = "${ensureScratch(server.id)} && " +
                    "echo '$pwB64' | base64 -d > $pwFile && chmod 600 $pwFile && " +
                    "sudo -S sh $provisionScript < $pwFile"
                if (onLine != null) exec.execStream(cmd, onLine).getOrThrow() else exec.exec(cmd).getOrThrow()
            } else {
                if (onLine != null) exec.execStream("sh $provisionScript", onLine).getOrThrow() else exec.exec("sh $provisionScript").getOrThrow()
            }
            Log.d(TAG, "provisionLibvirt: run exit=${res.exitCode} stdout='${res.stdout.take(400)}' stderr='${res.stderr.take(400)}'")
            res.stdout.lines().forEach { if (it.isNotBlank()) onLine?.invoke(it) }
            res.stderr.lines().forEach { if (it.isNotBlank()) onLine?.invoke("STDERR: $it") }
            res
        }
    }

    /** Per-process count of consecutive SFTP failures per server. Reset on the
     *  first SFTP success so a transient blip doesn't permanently flip the
     *  server to the slower shell-upload path. */
    private val sftpFailures = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())

    /**
     * Writes [content] to [path] on the host. Prefers SFTP on the shared
     * session (one reliable binary channel); falls back to small base64
     * chunks via exec, since one-shot `echo <huge-blob>` commands reliably
     * kill dropbear sessions once the command string exceeds a few KB.
     */
    private suspend fun writeRemoteFile(
        ctx: SshClient.SessionContext,
        exec: SshExecutor,
        serverId: String,
        path: String,
        content: String,
    ) {
        val priorFailures = sftpFailures[serverId] ?: 0
        if (priorFailures < SFTP_FAILURE_THRESHOLD) {
            val ok = ctx.sftpWrite(path, content)
            if (ok) {
                sftpFailures.remove(serverId)
                return
            }
            sftpFailures[serverId] = priorFailures + 1
            Log.w(TAG, "SFTP write failed (${priorFailures + 1}/$SFTP_FAILURE_THRESHOLD) for ${serverId.take(8)}…")
            if (priorFailures + 1 >= SFTP_FAILURE_THRESHOLD) {
                Log.w(TAG, "SFTP marked unavailable on ${serverId.take(8)}… — using chunked shell upload until first success")
            }
            // A failed sftp subsystem request can take the session down; if so,
            // abort so withServerSession retries on a fresh connection.
            if (!ctx.isAlive()) throw IllegalStateException("session dropped during SFTP attempt")
        }
        // Fallback: shell-based upload. The whole payload is delivered in a
        // SINGLE exec call via a remote heredoc — rapid channel open/close on a
        // single dropbear session is unreliable (channel-ID races lose bytes),
        // and chunked approaches used to make this call hang for 15+ minutes.
        // One channel, one command, one md5 verify, one retry on mismatch.
        val raw = content.toByteArray(Charsets.UTF_8)
        val gz = gzip(raw)
        val b64 = Base64.encodeToString(gz, Base64.NO_WRAP)
        // Heredoc terminator: b64 alphabet is [A-Za-z0-9+/=], so any token
        // containing an underscore is guaranteed not to appear in the payload.
        val eof = "PSUP_EOF_${System.nanoTime().toString(16)}"
        val uploadCmd = buildString {
            append("cat > '$path.b64' << '$eof'\n")
            append(b64)
            append("\n$eof\n")
            append("base64 -d '$path.b64' | gunzip -c > '$path' && rm -f '$path.b64' && md5sum '$path'")
        }
        val localMd5 = md5Hex(raw)
        var lastErr: String? = null
        repeat(2) { attempt ->
            val res = exec.exec(uploadCmd)
            if (res.isFailure) {
                lastErr = res.exceptionOrNull()?.message ?: "exec failed"
            } else {
                val r = res.getOrThrow()
                if (r.exitCode != 0) {
                    lastErr = "exit=${r.exitCode} stdout=${r.stdout.take(200)}"
                } else {
                    val remoteMd5 = r.stdout.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                    if (remoteMd5.equals(localMd5, ignoreCase = true)) {
                        Log.i(TAG, "upload ok: $path (${raw.size}B, md5=$localMd5, attempt $attempt)")
                        return
                    }
                    lastErr = "checksum mismatch (remote=$remoteMd5 local=$localMd5)"
                }
            }
            Log.w(TAG, "upload attempt $attempt failed for $path: $lastErr")
        }
        throw IllegalStateException("Failed to upload $path: $lastErr")
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun md5Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }

    suspend fun vmNamesForServer(serverId: String): List<String> =
        vmRepo.snapshot().filter { it.serverId == serverId }.map { it.name }

    /**
     * Best-effort teardown of everything PodSteroid put on a host, so deleting a
     * server leaves no trace of this app:
     *  - always: stop + undefine every VM PodSteroid created for this server, and
     *    remove the pre-cached cloud image;
     *  - when [full] (the host was provisioned by PodSteroid): also destroy the
     *    default network, purge the virtualization packages, and stop libvirtd.
     * Runs as a streamed shell script so callers can surface progress. Each step
     * is best-effort (`|| true`) so a partial teardown still makes progress.
     */
    suspend fun deprovisionLibvirt(
        server: RemoteServer,
        vmNames: List<String> = emptyList(),
        full: Boolean = false,
        onLine: ((String) -> Unit)? = null,
    ): Result<ExecResult> {
        val safeNames = vmNames.map { it.replace(Regex("[^A-Za-z0-9._-]"), "_") }
        val vmBlock = if (safeNames.isEmpty()) {
            "echo \"==> No PodSteroid VMs to remove\""
        } else {
            safeNames.joinToString("\n") { n ->
                """  echo "==> Removing VM: $n"
  ${'$'}SUDO virsh destroy "$n" 2>/dev/null || true
  ${'$'}SUDO virsh undefine "$n" --remove-all-storage 2>/dev/null || true"""
            }
        }
        val fullBlock = if (full) """
            echo "==> Tearing down default network"
            ${'$'}SUDO virsh net-destroy default 2>/dev/null || true
            ${'$'}SUDO virsh net-undefine default 2>/dev/null || true
            echo "==> Removing virtualization packages"
            case "${'$'}DISTRO" in
              debian|ubuntu|linuxmint|raspbian)
                ${'$'}SUDO apt-get purge -y qemu-kvm libvirt-daemon-system libvirt-clients virtinst cloud-init libvirt-network 2>/dev/null || true
                ${'$'}SUDO apt-get autoremove -y 2>/dev/null || true
                ;;
              fedora)
                ${'$'}SUDO dnf -y remove @virtualization virt-install cloud-init 2>/dev/null || true
                ;;
              centos|rhel|rocky|alma)
                ${'$'}SUDO dnf -y remove @virt virt-install cloud-init 2>/dev/null || true
                ;;
              opensuse*|sles)
                ${'$'}SUDO zypper -n remove -y libvirt qemu-kvm virt-install cloud-init 2>/dev/null || true
                ;;
              arch)
                ${'$'}SUDO pacman -R --noconfirm libvirt qemu virt-install cloud-init 2>/dev/null || true
                ;;
              alpine)
                ${'$'}SUDO apk del libvirt-daemon qemu-system-x86_64 virt-install cloud-init-openrc 2>/dev/null || true
                ;;
              *) echo "WARN: unknown distro ${'$'}DISTRO — skipping package removal" ;;
            esac
            echo "==> Stopping libvirtd"
            if command -v systemctl >/dev/null 2>&1; then
              ${'$'}SUDO systemctl disable --now libvirtd 2>/dev/null || true
              ${'$'}SUDO systemctl disable --now virtlogd 2>/dev/null || true
            else
              ${'$'}SUDO service libvirtd stop 2>/dev/null || true
            fi""" else ""
        val script = """
            SUDO=""
            if [ "${'$'}(id -u)" != "0" ]; then SUDO="sudo"; fi
            . /etc/os-release 2>/dev/null || true
            DISTRO="${'$'}{ID:-linux}"
            echo "==> Detected distro: ${'$'}DISTRO"
            echo "==> Tearing down PodSteroid VMs"
$vmBlock
            echo "==> Removing pre-cached cloud image"
            ${'$'}SUDO rm -f /var/lib/libvirt/images/base-*.qcow2 2>/dev/null || true
$fullBlock
            echo "==> Done. Host reverted."
        """.trimIndent()
        val pw = credentialsFor(server)?.password
        val pwB64 = if (!pw.isNullOrBlank()) Base64.encodeToString(pw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else ""
        Log.d(TAG, "deprovisionLibvirt: cmd uses sudo=${pwB64.isNotEmpty()}")
        return withServerSession(server, connectTimeoutMs = 30_000, execTimeoutMs = 600_000) { ctx, exec ->
            val deprovisionScript = scratchPath(server.id, "deprovision.sh")
            val pwFile = scratchPath(server.id, "pw")
            writeRemoteFile(ctx, exec, server.id, deprovisionScript, script)
            if (pwB64.isNotEmpty()) {
                val cmd = "${ensureScratch(server.id)} && " +
                    "echo '$pwB64' | base64 -d > $pwFile && chmod 600 $pwFile && " +
                    "sudo -S sh $deprovisionScript < $pwFile"
                if (onLine != null) exec.execStream(cmd, onLine).getOrThrow() else exec.exec(cmd).getOrThrow()
            } else {
                if (onLine != null) exec.execStream("sh $deprovisionScript", onLine).getOrThrow() else exec.exec("sh $deprovisionScript").getOrThrow()
            }
        }
    }

    private fun loadRawScript(@RawRes resId: Int): String {
        return appContext.resources.openRawResource(resId).bufferedReader().use { it.readText() }
    }

    /** Opens an interactive shell to a guest. */
    suspend fun shellToGuest(
        server: RemoteServer,
        vm: RemoteVm,
        onData: (String) -> Unit,
        onClosed: () -> Unit,
    ): Result<SshClient.ShellSession> {
        val address = vm.tailscaleIp ?: vm.guestIp
            ?: return Result.failure(IllegalStateException("Guest ${vm.name} has no reachable IP"))
        val target = SshTarget(address, 22, LibvirtRemoteProvider.GUEST_USER)
        val creds = SshCredentials(password = LibvirtRemoteProvider.GUEST_PASSWORD)
        return ssh.shell(target, creds, hostKeyStore = PermissiveHostKey, onData = onData, onClosed = onClosed)
    }

    /** In-memory accept-first-use store; surfaces acceptance through a callback. */
    private class HostKeyHolder(
        private val host: String,
        initial: String?,
        private val onAccept: (String) -> Unit,
    ) : HostKeyStore {
        private val map = initial?.let { mutableMapOf(host to it) } ?: mutableMapOf()
        override fun get(host: String): String? = map[host]
        override fun put(host: String, fingerprint: String) {
            map[host] = fingerprint
            onAccept(fingerprint)
        }
    }

    /** Accepts any guest host key (guests are the user's own machines). */
    private object PermissiveHostKey : HostKeyStore {
        override fun get(host: String): String? = null
        override fun put(host: String, fingerprint: String) = Unit
    }

    companion object {
        private const val TAG = "RemoteServerManager"
        /** Per-process count of consecutive SFTP failures per server before we
         *  permanently flip to the shell-upload path. */
        private const val SFTP_FAILURE_THRESHOLD = 3
    }
}
