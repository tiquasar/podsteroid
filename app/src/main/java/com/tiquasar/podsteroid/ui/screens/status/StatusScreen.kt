package com.tiquasar.podsteroid.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.engine.EngineSelection
import com.tiquasar.podsteroid.engine.VmState
import com.tiquasar.podsteroid.ui.components.AdaptiveContainer
import com.tiquasar.podsteroid.ui.components.PodsteroidListRow
import com.tiquasar.podsteroid.ui.components.PodsteroidSectionLabel
import com.tiquasar.podsteroid.ui.components.PodsteroidTopBar
import com.tiquasar.podsteroid.ui.components.VmLoadGraph
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens
import com.tiquasar.podsteroid.util.HostMetrics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val metrics = ui.metrics
    val phoneRamUsedMb = (metrics.phoneTotalRamMb - metrics.phoneAvailRamMb).coerceAtLeast(0)
    val phoneStorageUsedGb = (metrics.phoneStorageTotalGb - metrics.phoneStorageAvailGb)
        .coerceAtLeast(0.0)

    Scaffold(
        topBar = {
            PodsteroidTopBar(
                title = stringResource(R.string.status_page_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodsteroidTokens.Spacing.XL, vertical = PodsteroidTokens.Spacing.LG),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.MD),
            ) {
                PodsteroidSectionLabel(stringResource(R.string.status_phone_section))
                StatusMetricBar(
                    label = stringResource(R.string.ram_label),
                    detail = "${HostMetrics.formatMb(phoneRamUsedMb)} / ${HostMetrics.formatMb(metrics.phoneTotalRamMb)}",
                    progress = HostMetrics.percent(phoneRamUsedMb, metrics.phoneTotalRamMb),
                )
                StatusMetricBar(
                    label = stringResource(R.string.storage),
                    detail = "${HostMetrics.formatGb(phoneStorageUsedGb)} / ${HostMetrics.formatGb(metrics.phoneStorageTotalGb)}",
                    progress = HostMetrics.percent(phoneStorageUsedGb, metrics.phoneStorageTotalGb),
                )
                PodsteroidListRow(
                    label = stringResource(R.string.cpu_cores),
                    value = "${metrics.phoneCpuCores}",
                )
                // /proc/loadavg is SELinux-blocked for third-party apps on modern
                // Android, so this is usually absent; hide the row rather than
                // show a permanent placeholder.
                if (metrics.loadAvg1 != null) {
                    PodsteroidListRow(
                        label = stringResource(R.string.status_load_average),
                        value = String.format(
                            "%.2f · %.2f · %.2f",
                            metrics.loadAvg1,
                            metrics.loadAvg5,
                            metrics.loadAvg15,
                        ),
                        mono = true,
                    )
                }

                Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                PodsteroidSectionLabel(stringResource(R.string.status_vm_section))
                PodsteroidListRow(
                    label = stringResource(R.string.vm_status),
                    value = vmStatusLabel(ui.vmState, ui.uptimeLabel),
                )
                PodsteroidListRow(
                    label = stringResource(R.string.backend),
                    value = backendLabel(ui.engineSelection, ui.backendId),
                    mono = true,
                )
                PodsteroidListRow(
                    label = stringResource(R.string.phone_ip),
                    value = ui.phoneIp,
                    mono = true,
                )
                // A phone can hold several addresses at once (mobile data plus a WiFi or
                // USB tether). Forwards listen on all of them, so listing the rest saves
                // the user guessing which one a given PC can actually reach.
                ui.phoneAddresses
                    .filter { it.address != ui.phoneIp }
                    .forEach { extra ->
                        PodsteroidListRow(
                            label = stringResource(R.string.phone_ip_also, extra.iface),
                            value = extra.address,
                            mono = true,
                        )
                    }

                Text(
                    text = stringResource(R.string.status_vm_load_graph_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = PodsteroidTokens.Spacing.SM),
                )
                VmLoadGraph(
                    samples = ui.vmLoadHistory,
                    currentPercent = ui.vmLoadPercent,
                    unavailableReason = when {
                        ui.vmState !is VmState.Running -> stringResource(R.string.status_vm_load_stopped)
                        ui.vmLoadGraphUnavailable == "avf" -> stringResource(R.string.status_vm_load_avf_only)
                        else -> null
                    },
                    modifier = Modifier.padding(bottom = PodsteroidTokens.Spacing.SM),
                )

                StatusMetricBar(
                    label = stringResource(R.string.status_vm_ram_allocated),
                    detail = HostMetrics.formatMb(ui.vmRamMb.toLong()),
                    progress = if (metrics.emulatorRssMb != null && ui.vmRamMb > 0) {
                        HostMetrics.percent(metrics.emulatorRssMb, ui.vmRamMb.toLong())
                    } else null,
                    sublabel = metrics.emulatorRssMb?.let {
                        stringResource(R.string.status_emulator_rss, HostMetrics.formatMb(it))
                    },
                )
                PodsteroidListRow(
                    label = stringResource(R.string.cpu_cores),
                    value = "${ui.vmCpus}",
                )
                StatusMetricBar(
                    label = stringResource(R.string.status_vm_disk),
                    detail = "${HostMetrics.formatGb(metrics.vmDiskImageBytes)} / ${ui.storageSizeGb} GB",
                    progress = HostMetrics.percent(
                        metrics.vmDiskImageBytes,
                        ui.storageSizeGb.toLong() * 1024L * 1024L * 1024L,
                    ),
                )
                PodsteroidListRow(
                    label = stringResource(R.string.bandwidth_limit),
                    value = if (ui.bandwidthMbps <= 0) {
                        stringResource(R.string.bandwidth_unlimited)
                    } else {
                        "${ui.bandwidthMbps} Mbps"
                    },
                )
                PodsteroidListRow(
                    label = stringResource(R.string.load_balance),
                    value = if (ui.loadBalanceEnabled) {
                        stringResource(R.string.on)
                    } else {
                        stringResource(R.string.off)
                    },
                )
                PodsteroidListRow(
                    label = stringResource(R.string.port_forwards),
                    value = "${ui.portForwardCount}",
                    divider = false,
                )

                Text(
                    text = stringResource(R.string.status_refresh_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PodsteroidTokens.Spacing.SM),
                )
            }
        }
    }
}

@Composable
private fun StatusMetricBar(
    label: String,
    detail: String,
    progress: Float?,
    sublabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PodsteroidTokens.Spacing.SM),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (progress != null) {
            Spacer(Modifier.height(PodsteroidTokens.Spacing.XS))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (sublabel != null) {
            Spacer(Modifier.height(PodsteroidTokens.Spacing.XS))
            Text(
                sublabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun vmStatusLabel(vmState: VmState, uptime: String?): String = when (vmState) {
    is VmState.Running -> uptime?.let { "${stringResource(R.string.status_running)} · $it" }
        ?: stringResource(R.string.status_running)
    is VmState.Starting -> stringResource(R.string.status_starting)
    is VmState.Error -> stringResource(R.string.status_error)
    else -> stringResource(R.string.status_stopped)
}

@Composable
private fun backendLabel(selection: EngineSelection, activeId: String): String = when (selection) {
    EngineSelection.AUTO -> "${stringResource(R.string.auto)} ($activeId)"
    EngineSelection.AVF -> stringResource(R.string.avf_kvm)
    EngineSelection.QEMU -> stringResource(R.string.qemu_tcg)
}
