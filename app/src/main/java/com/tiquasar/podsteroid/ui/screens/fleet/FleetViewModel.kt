package com.tiquasar.podsteroid.ui.screens.fleet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiquasar.podsteroid.data.repository.VmDefinition
import com.tiquasar.podsteroid.engine.GuestDistro
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.VmState
import com.tiquasar.podsteroid.remote.ContainerRuntime
import com.tiquasar.podsteroid.remote.FleetEntry
import com.tiquasar.podsteroid.remote.FleetKind
import com.tiquasar.podsteroid.remote.RemoteServer
import com.tiquasar.podsteroid.remote.RemoteServerManager
import com.tiquasar.podsteroid.remote.RemoteServerRepository
import com.tiquasar.podsteroid.remote.RemoteVm
import com.tiquasar.podsteroid.remote.RemoteVmRepository
import com.tiquasar.podsteroid.remote.RemoteVmState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FleetViewModel @Inject constructor(
    private val vmRegistry: VmRegistry,
    private val serverRepo: RemoteServerRepository,
    private val vmRepo: RemoteVmRepository,
    private val manager: RemoteServerManager,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val vmsFlow = vmRepo.vms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val serversFlow = serverRepo.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val localFlow: StateFlow<List<FleetEntry>> = combine(
        vmRegistry.definitions,
        vmRegistry.allStates,
    ) { defs: List<VmDefinition>, states: Map<String, VmState> ->
        defs.map { def ->
            val st = states[def.id]
            val (label, mapped) = when (st) {
                is VmState.Running -> "Running" to RemoteVmState.RUNNING
                is VmState.Starting -> "Starting" to RemoteVmState.PENDING
                is VmState.Error -> "Error" to RemoteVmState.OTHER
                is VmState.Stopped -> "Stopped" to RemoteVmState.SHUTOFF
                else -> "Idle" to RemoteVmState.SHUTOFF
            }
            FleetEntry(
                id = "local:${def.id}",
                label = def.name,
                kind = FleetKind.LOCAL,
                serverName = null,
                stateLabel = label,
                state = mapped,
                guestDistro = def.guestDistro,
                containerRuntime = ContainerRuntime.NONE,
                guestIp = null,
                tailscaleIp = null,
                magicDns = null,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fleet: StateFlow<List<FleetEntry>> = combine(localFlow, vmsFlow, serversFlow) { local, remoteVms, servers ->
        val byServer = servers.associateBy { it.id }
        val remote = remoteVms.map { vm ->
            val server = byServer[vm.serverId]
            FleetEntry(
                id = "remote:${vm.serverId}:${vm.uuid}",
                label = vm.name,
                kind = FleetKind.REMOTE,
                serverName = server?.name,
                stateLabel = vm.state.name,
                state = vm.state,
                guestDistro = vm.guestDistro,
                containerRuntime = vm.containerRuntime,
                guestIp = vm.guestIp,
                tailscaleIp = vm.tailscaleIp,
                magicDns = vm.magicDns,
            )
        }
        (local + remote).sortedWith(compareBy({ it.kind.name }, { it.label }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshFleet() {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                vmsFlow.value.forEach { vm ->
                    val server = serversFlow.value.firstOrNull { it.id == vm.serverId } ?: return@forEach
                    if (vm.state == RemoteVmState.RUNNING) manager.fetchTailscaleIp(server, vm)
                }
            }
            _busy.value = false
        }
    }

    fun lookupRemote(serverId: String, uuid: String): Pair<RemoteServer, RemoteVm>? {
        val server = serversFlow.value.firstOrNull { it.id == serverId } ?: return null
        val vm = vmsFlow.value.firstOrNull { it.serverId == serverId && it.uuid == uuid } ?: return null
        return server to vm
    }

    fun getToken(control: FleetEntry, onResult: (token: String?, controlUrl: String?) -> Unit) {
        val (server, vm) = parseRemoteId(control.id) ?: return onResult(null, null)
        viewModelScope.launch {
            val token = manager.fetchK3sToken(server, vm).getOrNull()
            val url = vm.tailscaleIp?.let { "https://$it:6443" }
            onResult(token, url)
        }
    }

    fun join(agent: FleetEntry, controlUrl: String, token: String, onResult: (result: com.tiquasar.podsteroid.remote.ExecResult) -> Unit) {
        val (server, vm) = parseRemoteId(agent.id) ?: return
        viewModelScope.launch {
            manager.joinK3s(server, vm, controlUrl, token).onSuccess { onResult(it) }
        }
    }

    fun initServer(control: FleetEntry, onResult: (result: com.tiquasar.podsteroid.remote.ExecResult?) -> Unit) {
        val (server, vm) = parseRemoteId(control.id) ?: return onResult(null)
        viewModelScope.launch {
            onResult(manager.initK3sServer(server, vm).getOrNull())
        }
    }

    /** FleetEntry.id for remote nodes is "remote:<serverId>:<uuid>". */
    private fun parseRemoteId(id: String): Pair<RemoteServer, RemoteVm>? {
        val parts = id.split(":")
        if (parts.size != 3 || parts[0] != "remote") return null
        return lookupRemote(parts[1], parts[2])
    }
}
