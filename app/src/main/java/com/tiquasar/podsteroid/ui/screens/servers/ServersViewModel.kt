/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Drives the Servers screen: registered libvirt servers, their VMs, lifecycle
 * actions, and connection testing. State lives in DataStore/repos; this VM only
 * orchestrates and caches the last-refreshed VM lists.
 */
package com.tiquasar.podsteroid.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.tiquasar.podsteroid.remote.AuthKind
import com.tiquasar.podsteroid.remote.Accelerator
import com.tiquasar.podsteroid.remote.ContainerRuntime
import com.tiquasar.podsteroid.remote.PrereqReport
import com.tiquasar.podsteroid.remote.ServerKind
import com.tiquasar.podsteroid.remote.RemoteServer
import com.tiquasar.podsteroid.remote.RemoteServerManager
import com.tiquasar.podsteroid.remote.RemoteVm
import com.tiquasar.podsteroid.remote.RemoteVmSpec
import com.tiquasar.podsteroid.remote.RemoteVmState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    val manager: RemoteServerManager,
    private val serverRepo: com.tiquasar.podsteroid.remote.RemoteServerRepository,
) : ViewModel() {

    private val TAG = "ServersViewModel"

    val servers: StateFlow<List<RemoteServer>> = serverRepo.servers.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    private val _vms = MutableStateFlow<Map<String, List<RemoteVm>>>(emptyMap())
    val vms: StateFlow<Map<String, List<RemoteVm>>> = _vms

    private val _serverBusy = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val serverBusy: StateFlow<Map<String, Boolean>> = _serverBusy

    private val _serverStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverStatus: StateFlow<Map<String, String>> = _serverStatus

    /** Last connection-prereq result per server, shown as a card expansion. */
    private val _serverResult = MutableStateFlow<Map<String, PrereqReport>>(emptyMap())
    val serverResult: StateFlow<Map<String, PrereqReport>> = _serverResult

    /** Global busy — true when ANY server is busy. Used by the top-level spinner. */
    val busy: StateFlow<Boolean> = combine(_serverBusy) { maps ->
        maps[0].values.any { it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _serverLog = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverLog: StateFlow<Map<String, String>> = _serverLog

    /** Connection state per server: online / offline / error / deleting / deleted. */
    private val _serverState = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverState: StateFlow<Map<String, String>> = _serverState

    fun toastConsumed() { _toast.value = null }

    /** Per-server tracked jobs, so we can cancel on delete. */
    private val serverJobs = mutableMapOf<String, Job>()

    // ── helpers ────────────────────────────────────────────────────────

    private fun setBusy(id: String, busy: Boolean) {
        _serverBusy.value = _serverBusy.value + (id to busy)
    }

    private fun setStatus(id: String, status: String) {
        _serverStatus.value = _serverStatus.value + (id to status)
    }

    private fun clearServerState(id: String) {
        _serverBusy.value = _serverBusy.value - id
        _serverStatus.value = _serverStatus.value - id
    }

    private fun appendLog(id: String, line: String) {
        _serverLog.value = _serverLog.value + (id to ((_serverLog.value[id] ?: "") + line + "\n"))
    }

    /** Persist a host-key fingerprint only if the server still exists. */
    private suspend fun persistFingerprintIfNeeded(server: RemoteServer, fp: String?) {
        if (fp.isNullOrBlank()) return
        val existing = serverRepo.get(server.id) ?: return
        serverRepo.put(existing.copy(hostKeyFingerprint = fp))
    }

    private fun serverAct(server: RemoteServer, status: String, block: suspend (RemoteServer) -> Unit) {
        val id = server.id
        val job = viewModelScope.launch {
            setBusy(id, true)
            setStatus(id, status)
            try {
                block(server)
            } catch (e: Exception) {
                appendLog(id, "ERROR: ${e.message ?: e.javaClass.simpleName}")
                _serverState.value = _serverState.value + (id to "error")
                _toast.value = "${status.trimEnd('.')}: ${e.message}"
            } finally {
                setBusy(id, false)
                clearServerState(id)
            }
        }
        serverJobs[id] = job
    }

    // ── server CRUD ────────────────────────────────────────────────────

    fun addServer(
        name: String,
        host: String,
        port: Int,
        username: String,
        authKind: AuthKind,
        secret: String,
        passphrase: String?,
        baseImageUrl: String?,
        arch: String,
        network: String,
        kind: ServerKind = ServerKind.LIBVIRT,
    ) {
        val id = java.util.UUID.randomUUID().toString()
        val server = RemoteServer(
            id = id,
            name = name.ifBlank { host },
            host = host,
            port = port,
            username = username,
            authKind = authKind,
            arch = arch,
            network = network,
            kind = kind,
            baseImageUrl = baseImageUrl?.takeIf { it.isNotBlank() },
            colorIndex = ServerBadge.randomIndex(),
        )
        viewModelScope.launch {
            Log.d(TAG, "addServer: start name=$name host=$host port=$port user=$username auth=$authKind")
            setBusy(id, true)
            setStatus(id, "Saving…")
            try {
                Log.d(TAG, "addServer: calling saveServer")
                val saved = manager.saveServer(server, secret, passphrase)
                Log.d(TAG, "addServer: saveServer returned id=${saved.id} hasToken=${saved.secretToken != null}")

                setStatus(id, "Connecting & provisioning…")
                _serverLog.value = _serverLog.value + (id to "")
                Log.d(TAG, "addServer: calling setupServer")
                val result = withTimeoutOrNull(1_700_000L) {
                    manager.setupServer(
                        saved,
                        onStep = { step -> appendLog(id, step) },
                        onLine = { line -> appendLog(id, line) },
                    ).getOrNull()
                }
                Log.d(TAG, "addServer: setupServer returned resultNull=${result == null}")
                if (result == null) {
                    // Say what actually happened: withServerSession already
                    // retried the whole SSH session 3 times before we got here,
                    // so the user spent the full budget on a host that kept
                    // failing. Previously this read as a single unexplained
                    // timeout and people just retried the same broken config.
                    appendLog(
                        id,
                        "ERROR: setup timed out after ${1_700_000L / 1000}s. The SSH session was retried " +
                            "3 times and never succeeded.\n" +
                            "Check host/port/firewall and that this device can reach " +
                            "${saved.username}@${saved.host}:${saved.port}.\n" +
                            "The most common causes are: key not in the remote authorized_keys, " +
                            "host unreachable over Tailscale/VPN, or libvirt provisioning stalled " +
                            "waiting on apt.",
                    )
                    _toast.value = "Setup timed out (3 SSH retries failed). Check host/port/key."
                    _serverState.value = _serverState.value + (id to "error")
                    clearServerState(id)
                    return@launch
                }
                _serverResult.value = _serverResult.value + (id to result.report)
                appendLog(id, "Connection OK. Prereqs: ${result.report.details}")
                if (result.provisioned) {
                    appendLog(id, "Host provisioned by PodSteroid.")
                    manager.saveServerKind(saved, ServerKind.LIBVIRT, provisionedByUs = true)
                } else if (result.report.virsh) {
                    // Pre-existing libvirt: do NOT set provisionedByUs. With that
                    // flag false, deprovisionLibvirt(full=false) only removes
                    // Podsteroid's own VMs and leaves the host's packages alone.
                    appendLog(id, "Host already has libvirt — keeping user's install. Remove Server will only delete Podsteroid VMs.")
                    manager.saveServerKind(saved, ServerKind.LIBVIRT, provisionedByUs = false)
                } else {
                    appendLog(id, "No libvirt detected — keeping as Linux server. Use 'Provision libvirt'.")
                }
                val finalServer = serverRepo.get(id) ?: saved
                _vms.value = _vms.value + (finalServer.id to result.vms)
                _serverState.value = _serverState.value + (id to "online")
                _toast.value = "Server ready: ${saved.name}"
            } catch (e: Exception) {
                appendLog(id, "ERROR: ${e.message ?: e.javaClass.simpleName}")
                _serverState.value = _serverState.value + (id to "error")
                _toast.value = "Save failed: ${e.message}"
            } finally {
                setBusy(id, false)
                clearServerState(id)
            }
        }
    }

    /**
     * Phase 1 of delete: revert any installation we made on the host (uninstall
     * the libvirt stack if we provisioned it), then mark the server "deleted"
     * so the card stays visible with a "Remove Server" button. The local entry
     * is only removed when the user confirms via [removeServer].
     */
    fun deleteServer(server: RemoteServer) {
        val id = server.id
        serverJobs.remove(id)?.cancel()
        viewModelScope.launch {
            setBusy(id, true)
            setStatus(id, "Reverting…")
            _serverState.value = _serverState.value + (id to "deleting")
            _serverLog.value = _serverLog.value + (id to "Reverting all PodSteroid changes on host ${server.host}…\n")
            val vmNames = manager.vmNamesForServer(id)
            if (vmNames.isNotEmpty()) {
                appendLog(id, "Found ${vmNames.size} PodSteroid VM(s) to remove: ${vmNames.joinToString()}")
            } else {
                appendLog(id, "No PodSteroid VMs recorded for this server.")
            }
            if (server.provisionedByUs) {
                appendLog(id, "Host was provisioned by PodSteroid — also removing libvirt stack + default network.")
            } else {
                appendLog(id, "Leaving the host's own libvirt/packages in place (only PodSteroid-managed VMs are removed).")
            }
            val res = withTimeoutOrNull(600_000L) {
                manager.deprovisionLibvirt(
                    server,
                    vmNames = vmNames,
                    full = server.provisionedByUs,
                    onLine = { line -> appendLog(id, line) },
                ).getOrNull()
            }
            if (res == null) {
                appendLog(id, "ERROR: revert timed out (continuing).")
            } else if (res.exitCode != 0) {
                appendLog(id, "Revert finished with issues (exit ${res.exitCode}).")
            } else {
                appendLog(id, "All PodSteroid changes reverted.")
            }
            _vms.value = _vms.value - id
            appendLog(id, "Host reverted. Tap 'Remove Server' to delete the local entry.")
            _serverState.value = _serverState.value + (id to "deleted")
            _toast.value = "Reverted ${server.name} — tap Remove Server to finish"
            setBusy(id, false)
        }
    }

    /** Phase 2: actually delete the local entry after the host was reverted. */
    fun removeServer(server: RemoteServer) {
        val id = server.id
        serverJobs.remove(id)?.cancel()
        viewModelScope.launch {
            manager.deleteServer(id)
            _vms.value = _vms.value - id
            _serverResult.value = _serverResult.value - id
            _serverState.value = _serverState.value - id
            _serverLog.value = _serverLog.value - id
            clearServerState(id)
            _toast.value = "Removed ${server.name}"
        }
    }

    // ── lifecycle actions ──────────────────────────────────────────────

    /** Installs the libvirt stack on the host (idempotent) and marks the server
     *  managed by PodSteroid. Returns true on success. Streams progress into the
     *  server's activity log. Does NOT manage busy/status (caller does). */
    private suspend fun runProvision(server: RemoteServer): Boolean {
        val id = server.id
        val res = withTimeoutOrNull(600_000L) {
            manager.provisionLibvirt(server, onLine = { line -> appendLog(id, line) }).getOrNull()
        }
        return when {
            res == null -> {
                appendLog(id, "ERROR: provision timed out (10 min)")
                false
            }
            res.exitCode == 0 -> {
                appendLog(id, "Provision complete.")
                manager.saveServerKind(server, ServerKind.LIBVIRT, provisionedByUs = true)
                true
            }
            else -> {
                appendLog(id, "Provision exited ${res.exitCode}\n${res.stdout.take(600)}")
                false
            }
        }
    }

    fun provision(server: RemoteServer) {
        val id = server.id
        viewModelScope.launch {
            setBusy(id, true)
            setStatus(id, "Provisioning libvirt…")
            _serverLog.value = _serverLog.value + (id to "")
            try {
                val ok = runProvision(server)
                _toast.value = if (ok) "Provisioned libvirt on ${server.name}" else "Provision failed — see details"
                if (ok) {
                    val updated = serverRepo.get(id) ?: server
                    refreshInternal(updated)
                }
            } catch (e: Exception) {
                appendLog(id, "ERROR: ${e.message}")
                _toast.value = "Provision error: ${e.message}"
            } finally {
                setBusy(id, false)
                clearServerState(id)
            }
        }
    }

    fun test(server: RemoteServer) {
        serverAct(server, "Testing connection…") { srv ->
            val id = srv.id
            _serverLog.value = _serverLog.value + (id to "")
            val report = withTimeoutOrNull(90_000L) {
                manager.testConnection(srv, onStep = { step -> appendLog(id, step) }).getOrElse {
                    throw IllegalStateException(it.message ?: "Connect failed")
                }
            }
            if (report == null) {
                appendLog(
                    id,
                    "ERROR: connection timed out. Check host/port/firewall and that this device can reach ${srv.username}@${srv.host}:${srv.port}.",
                )
                _serverState.value = _serverState.value + (id to "offline")
                _toast.value = "Connection timed out. Check host/port/firewall."
                return@serverAct
            }
            persistFingerprintIfNeeded(srv, report.hostKeyFingerprint)
            _serverResult.value = _serverResult.value + (srv.id to report)
            appendLog(id, "Result: ${report.details}")
            _serverState.value = _serverState.value + (id to "online")
            _toast.value = "Prereqs — ${report.details}"
            if (report.virsh) refreshInternal(srv)
        }
    }

    fun refresh(server: RemoteServer) {
        serverAct(server, "Refreshing VMs…") { srv ->
            val list = withTimeoutOrNull(60_000L) {
                manager.refreshVms(srv).getOrNull()
            }
            if (list == null) {
                _serverState.value = _serverState.value + (srv.id to "offline")
                _toast.value = "Refresh failed. Check connection."
                return@serverAct
            }
            _serverState.value = _serverState.value + (srv.id to "online")
            _vms.value = _vms.value + (srv.id to list)
        }
    }

    private suspend fun refreshInternal(server: RemoteServer) {
        val list = withTimeoutOrNull(60_000L) {
            manager.refreshVms(server).getOrElse {
                throw IllegalStateException(it.message ?: "Refresh failed")
            }
        }
        if (list == null) {
            _toast.value = "Refresh timed out. Check connection."
            return
        }
        _vms.value = _vms.value + (server.id to list)
    }

    fun refreshAll() {
        servers.value.forEach { refresh(it) }
    }

    fun createVm(server: RemoteServer, spec: RemoteVmSpec) {
        serverAct(server, "Creating VM…") { srv ->
            var target = srv
            if (target.kind == ServerKind.LINUX) {
                setStatus(target.id, "Provisioning libvirt…")
                val prov = withTimeoutOrNull(600_000L) {
                    manager.provisionLibvirt(target).getOrNull()
                }
                if (prov == null) {
                    _toast.value = "Provision timed out"
                    return@serverAct
                }
                if (prov.exitCode != 0) {
                    _toast.value = "Provision exited ${prov.exitCode}:\n${prov.stdout.take(400)}"
                    return@serverAct
                }
                target = manager.saveServerKind(target, ServerKind.LIBVIRT, provisionedByUs = true)
            }
            val warnings = mutableListOf<String>()
            val probe = withTimeoutOrNull(30_000L) { manager.probeHost(target).getOrNull() }
            var accel = spec.accelerator
            if (accel == Accelerator.AUTO) {
                accel = if (probe?.kvm == true) Accelerator.KVM else Accelerator.TCG
            }
            if (probe != null && !probe.kvm && accel == Accelerator.KVM) {
                accel = Accelerator.TCG
                warnings += "No /dev/kvm on host — using TCG (software emulation, slower)."
            }
            if (probe != null && probe.totalMemMb > 0 && spec.memMb > probe.totalMemMb) {
                warnings += "Requested ${spec.memMb} MB exceeds host RAM ${probe.totalMemMb} MB."
            }
            if (probe != null && probe.freeDiskGb > 0 && spec.diskGb + 2 > probe.freeDiskGb) {
                warnings += "Low disk on host (${probe.freeDiskGb} GB free)."
            }
            val resolved = spec.copy(accelerator = accel)
            setStatus(target.id, "Creating VM…")
            _serverLog.value = _serverLog.value + (target.id to "==> Creating VM ${spec.name}…\n")
            // Live elapsed counter so slow hosts/links never look frozen.
            val ticker = viewModelScope.launch {
                val start = System.currentTimeMillis()
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    setStatus(target.id, "Creating VM… (${(System.currentTimeMillis() - start) / 1000}s)")
                }
            }
            val vm = try {
                withTimeoutOrNull(600_000L) {
                    manager.createVm(target, resolved, onLine = { line -> appendLog(target.id, line) }).getOrNull()
                }
            } finally {
                ticker.cancel()
            }
            if (vm == null) {
                _toast.value = "Create timed out or failed — see details"
                return@serverAct
            }
            _vms.value = _vms.value + (target.id to ((_vms.value[target.id] ?: emptyList()) + vm))
            runCatching { refreshInternal(target) }
            _toast.value = (warnings + "Created ${vm.name}").joinToString(" ")
        }
    }

    fun startVm(server: RemoteServer, name: String) = serverAct(server, "Starting $name…") { s ->
        withTimeoutOrNull(30_000L) {
            manager.startVm(s, name).getOrElse {
                throw IllegalStateException(it.message ?: "Start failed")
            }
        } ?: throw IllegalStateException("Start timed out")
        refreshInternal(s)
    }

    fun stopVm(server: RemoteServer, name: String) = serverAct(server, "Stopping $name…") { s ->
        withTimeoutOrNull(30_000L) {
            manager.stopVm(s, name).getOrElse {
                throw IllegalStateException(it.message ?: "Stop failed")
            }
        } ?: throw IllegalStateException("Stop timed out")
        // Guests without an ACPI daemon (e.g. minimal Alpine under TCG) ignore
        // graceful shutdown — force-poweroff if it's still running.
        kotlinx.coroutines.delay(8_000)
        // Check the live state of THIS domain directly, instead of relying on
        // refreshVms() — the list-based refresh can drop the VM (UUID parse
        // fail) and report "not running" for a domain that is, in fact, still up.
        val liveState = withTimeoutOrNull(15_000L) { manager.domainState(s, name).getOrNull() }
        val stillRunning = liveState == RemoteVmState.RUNNING
        if (stillRunning == true) {
            appendLog(s.id, "Guest ignored ACPI shutdown — forcing power off")
            manager.destroyForce(s, name)
        }
        refreshInternal(s)
    }

    fun destroyVm(server: RemoteServer, vm: RemoteVm) = serverAct(server, "Destroying ${vm.name}…") { s ->
        withTimeoutOrNull(30_000L) {
            manager.destroyVm(s, vm).getOrElse {
                throw IllegalStateException(it.message ?: "Destroy failed")
            }
        } ?: throw IllegalStateException("Destroy timed out")
        val list = (_vms.value[server.id] ?: emptyList()).filterNot {
            it.serverId == vm.serverId && it.uuid == vm.uuid
        }
        _vms.value = _vms.value + (server.id to list)
    }

    companion object {
        val RUNTIME_OPTIONS = listOf(
            ContainerRuntime.BOTH to "Docker + k3s",
            ContainerRuntime.DOCKER to "Docker",
            ContainerRuntime.K3S to "k3s",
            ContainerRuntime.NONE to "None",
        )
    }
}
