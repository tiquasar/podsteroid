package com.tiquasar.podsteroid.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiquasar.podsteroid.BuildConfig
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import com.tiquasar.podsteroid.data.repository.UpdateInfo
import com.tiquasar.podsteroid.data.repository.UpdateRepository
import com.tiquasar.podsteroid.data.repository.VmDefinition
import com.tiquasar.podsteroid.engine.EngineSelection
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.VmState
import com.tiquasar.podsteroid.service.PodsteroidService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** One VM row as rendered by HomeScreen. */
data class VmRow(
    val definition: VmDefinition,
    val state: VmState,
    val backendId: String,
    val selected: Boolean,
    val bootStage: String = "",
    val runningSinceMs: Long? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: VmRegistry,
    private val settingsRepository: SettingsRepository,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    val definitions: StateFlow<List<VmDefinition>> = registry.definitions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedId: StateFlow<String?> = registry.selectedId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val states: StateFlow<Map<String, VmState>> = registry.allStates
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val bootStages: StateFlow<Map<String, String>> = registry.bootStages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // 1 Hz ticker so per-VM uptime labels refresh while any VM is Running.
    private val ticker: StateFlow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(1000) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    val rows: StateFlow<List<VmRow>> = kotlinx.coroutines.flow.combine(
        definitions, states, selectedId, bootStages, ticker,
    ) { defs, statesMap, sel, stages, _ ->
        defs.map { def ->
            VmRow(
                definition = def,
                state = statesMap[def.id] ?: VmState.Idle,
                backendId = registry.instance(def.id)?.backendId ?: "qemu",
                selected = def.id == sel,
                bootStage = stages[def.id] ?: "",
                runningSinceMs = registry.instance(def.id)?.runningSinceMs,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _avfProbe = com.tiquasar.podsteroid.engine.avf.AvfDiagnostics.probe(context)
    val showAvfHint: StateFlow<Boolean> = settingsRepository.avfHintDismissed
        .map { dismissed ->
            _avfProbe.featureSupported && !_avfProbe.managePermissionGranted && !dismissed
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val info = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@launch
                if (!updateRepository.isDismissed(info.latestVersion)) _updateInfo.value = info
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                android.util.Log.w("HomeViewModel", "update check failed", e)
            }
        }
    }

    fun dismissUpdate() {
        val version = _updateInfo.value?.latestVersion ?: return
        _updateInfo.value = null
        viewModelScope.launch { updateRepository.dismissUpdate(version) }
    }

    fun dismissAvfHint() {
        viewModelScope.launch { settingsRepository.setAvfHintDismissed(true) }
    }

    fun select(id: String) {
        viewModelScope.launch { registry.select(id) }
    }

    fun start(id: String) = PodsteroidService.start(context, id)

    fun stop(id: String) = PodsteroidService.stop(context, id)

    fun create(
        name: String,
        backend: EngineSelection,
        ramMb: Int,
        cpus: Int,
        storageSizeGb: Int,
        sshEnabled: Boolean,
        cpuLimitPercent: Int = 0,
        guestDistro: String = "alpine",
    ) {
        viewModelScope.launch {
            registry.create(
                name = name,
                backend = backend,
                ramMb = ramMb,
                cpus = cpus,
                storageSizeGb = storageSizeGb,
                sshEnabled = sshEnabled,
                storageAccessEnabled = settingsRepository.getStorageAccessEnabledSnapshot(),
                usbPassthroughEnabled = settingsRepository.getUsbPassthroughEnabledSnapshot(),
                bandwidthMbps = settingsRepository.getBandwidthMbpsSnapshot(),
                cpuLimitPercent = cpuLimitPercent,
                guestDistro = guestDistro,
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { registry.delete(id) }
    }

    fun update(
        id: String,
        name: String,
        backend: EngineSelection,
        ramMb: Int,
        cpus: Int,
        storageSizeGb: Int,
        sshEnabled: Boolean,
        cpuLimitPercent: Int,
        guestDistro: String = "alpine",
    ) {
        viewModelScope.launch {
            val def = registry.definitions.value.firstOrNull { it.id == id } ?: return@launch
            registry.update(
                def.copy(
                    name = name,
                    backend = backend,
                    ramMb = ramMb,
                    cpus = cpus,
                    storageSizeGb = storageSizeGb,
                    sshEnabled = sshEnabled,
                    cpuLimitPercent = cpuLimitPercent.coerceIn(0, 99),
                    guestDistro = guestDistro,
                )
            )
        }
    }

    /** Global default distro, surfaced as the initial selection for new VMs. */
    val defaultGuestDistro: kotlinx.coroutines.flow.StateFlow<String> =
        settingsRepository.guestDistro.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            "alpine",
        )

    fun rename(id: String, name: String) {
        viewModelScope.launch { registry.rename(id, name) }
    }

    fun clone(id: String) {
        viewModelScope.launch { registry.clone(id) }
    }

    /**
     * Offline snapshot: copies the VM's storage.img to snapshots/snap-<ts>.img.
     * Only meaningful while the VM is stopped (the caller hides the action
     * otherwise); copying a live disk would capture a torn image.
     */
    fun snapshot(id: String) {
        viewModelScope.launch {
            val inst = registry.instance(id) ?: return@launch
            val src = java.io.File(inst.workDir, "storage.img")
            if (!src.exists()) return@launch
            val snapDir = java.io.File(inst.workDir, "snapshots").apply { mkdirs() }
            val dest = java.io.File(snapDir, "snap-${System.currentTimeMillis()}.img")
            src.copyTo(dest, overwrite = false)
        }
    }

    /** Restore the most recent snapshot back over storage.img. Requires the VM to be stopped. */
    fun restoreSnapshot(id: String) {
        viewModelScope.launch {
            val inst = registry.instance(id) ?: return@launch
            val snapDir = java.io.File(inst.workDir, "snapshots")
            if (!snapDir.exists()) return@launch
            val latest = snapDir.listFiles { f ->
                f.name.startsWith("snap-") && f.extension == "img"
            }?.maxByOrNull { it.name } ?: return@launch
            val dest = java.io.File(inst.workDir, "storage.img")
            latest.copyTo(dest, overwrite = true)
        }
    }

    fun openTerminal(id: String, onNavigate: () -> Unit) {
        viewModelScope.launch {
            registry.select(id)
            onNavigate()
        }
    }
}
