package com.tiquasar.podsteroid.ui.screens.status

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiquasar.podsteroid.data.repository.PortForwardRepository
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import com.tiquasar.podsteroid.engine.EngineSelection
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.VmState
import com.tiquasar.podsteroid.util.HostMetrics
import com.tiquasar.podsteroid.util.HostMetricsSnapshot
import com.tiquasar.podsteroid.util.NetworkUtils
import com.tiquasar.podsteroid.util.VmLoadSampler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class StatusUiState(
    val vmState: VmState = VmState.Idle,
    val backendId: String = "qemu",
    val engineSelection: EngineSelection = EngineSelection.AUTO,
    val uptimeLabel: String? = null,
    val phoneIp: String = "—",
    /** Every reachable IPv4 address, so a peer on a tether or second network is not left guessing. */
    val phoneAddresses: List<NetworkUtils.LocalAddress> = emptyList(),
    val vmRamMb: Int = 512,
    val vmCpus: Int = 2,
    val storageSizeGb: Int = 8,
    val bandwidthMbps: Int = 0,
    val loadBalanceEnabled: Boolean = false,
    val portForwardCount: Int = 0,
    val vmLoadPercent: Float? = null,
    val vmLoadHistory: List<Float> = emptyList(),
    val vmLoadGraphUnavailable: String? = null,
    val metrics: HostMetricsSnapshot = HostMetricsSnapshot(
        phoneTotalRamMb = 0,
        phoneAvailRamMb = 0,
        phoneCpuCores = 1,
        loadAvg1 = null,
        loadAvg5 = null,
        loadAvg15 = null,
        phoneStorageTotalGb = 0.0,
        phoneStorageAvailGb = 0.0,
        vmDiskImageBytes = 0L,
        emulatorRssMb = null,
    ),
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: VmRegistry,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
) : ViewModel() {

    private val vmState: StateFlow<VmState> = registry.selectedEngine
        .flatMapLatest { eng -> eng?.state ?: flowOf(VmState.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    private val _metrics = MutableStateFlow(defaultMetrics())
    private val _uptimeTick = MutableStateFlow(0L)
    private val _vmLoadPercent = MutableStateFlow<Float?>(null)
    private val _vmLoadHistory = MutableStateFlow<List<Float>>(emptyList())
    private val _vmLoadUnavailable = MutableStateFlow<String?>(null)
    private val loadSampler = VmLoadSampler()

    val uiState: StateFlow<StatusUiState> = combine(
        combine(
            vmState,
            settingsRepository.vmRamMb,
            settingsRepository.vmCpus,
            settingsRepository.storageSizeGb,
            settingsRepository.bandwidthMbps,
        ) { state, ram, cpus, storage, bandwidth ->
            arrayOf(state, ram, cpus, storage, bandwidth)
        },
        combine(
            settingsRepository.loadBalanceEnabled,
            settingsRepository.engineSelection,
            portForwardRepository.rules,
            _metrics,
            _uptimeTick,
        ) { loadBal, engineSel, rules, metrics, tick ->
            arrayOf(loadBal, engineSel, rules, metrics, tick)
        },
        combine(
            _vmLoadPercent,
            _vmLoadHistory,
            _vmLoadUnavailable,
        ) { pct, history, unavailable ->
            arrayOf(pct, history, unavailable)
        },
    ) { a, b, c ->
        val state = a[0] as VmState
        val tick = b[4] as Long
        StatusUiState(
            vmState = state,
            backendId = registry.selectedInstance.value?.backendId ?: "qemu",
            engineSelection = b[1] as EngineSelection,
            uptimeLabel = uptimeLabel(state, tick),
            phoneIp = NetworkUtils.localIpv4(context),
            phoneAddresses = NetworkUtils.allLocalIpv4(context),
            vmRamMb = a[1] as Int,
            vmCpus = a[2] as Int,
            storageSizeGb = a[3] as Int,
            bandwidthMbps = a[4] as Int,
            loadBalanceEnabled = b[0] as Boolean,
            portForwardCount = (b[2] as List<*>).size,
            vmLoadPercent = c[0] as Float?,
            vmLoadHistory = c[1] as List<Float>,
            vmLoadGraphUnavailable = c[2] as String?,
            metrics = b[3] as HostMetricsSnapshot,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatusUiState())

    init {
        refreshMetrics()
        viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                refreshMetrics()
                sampleVmLoad()
                if (vmState.value is VmState.Running) {
                    _uptimeTick.value = System.currentTimeMillis()
                }
            }
        }
    }

    private val selectedEngine get() = registry.selectedEngine.value

    fun refreshMetrics() {
        val storageImg = File(storageDir(), "storage.img")
        val rss = if (vmState.value is VmState.Running) selectedEngine?.emulatorRssMb() else null
        _metrics.value = HostMetrics.snapshot(context, storageImg, rss)
    }

    private fun storageDir(): File = registry.selectedInstance.value?.workDir ?: context.filesDir

    private fun sampleVmLoad() {
        if (vmState.value !is VmState.Running) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            _vmLoadHistory.value = emptyList()
            _vmLoadUnavailable.value = null
            return
        }

        val pid = selectedEngine?.emulatorPid()
        if (pid == null) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            _vmLoadHistory.value = emptyList()
            _vmLoadUnavailable.value = "avf"
            return
        }

        _vmLoadUnavailable.value = null
        val pct = loadSampler.sampleCpuPercent(pid, uiState.value.vmCpus) ?: return
        _vmLoadPercent.value = pct
        _vmLoadHistory.value = (_vmLoadHistory.value + pct).takeLast(VmLoadSampler.MAX_SAMPLES)
    }

    private fun defaultMetrics(): HostMetricsSnapshot {
        val storageImg = File(storageDir(), "storage.img")
        return HostMetrics.snapshot(context, storageImg, null)
    }

    private fun uptimeLabel(state: VmState, tick: Long): String? {
        if (state !is VmState.Running) return null
        val since = registry.selectedInstance.value?.runningSinceMs ?: return null
        val secs = ((tick.takeIf { it > 0 } ?: System.currentTimeMillis()) - since) / 1000
        if (secs < 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
