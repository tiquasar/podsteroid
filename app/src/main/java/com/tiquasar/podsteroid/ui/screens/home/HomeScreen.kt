package com.tiquasar.podsteroid.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiquasar.podsteroid.BuildConfig
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.data.repository.VmDefinition
import com.tiquasar.podsteroid.engine.EngineSelection
import com.tiquasar.podsteroid.engine.GuestDistro
import com.tiquasar.podsteroid.engine.VmState
import com.tiquasar.podsteroid.ui.components.AdaptiveContainer
import com.tiquasar.podsteroid.ui.components.PodsteroidDestructiveButton
import com.tiquasar.podsteroid.ui.components.PodsteroidGhostButton
import com.tiquasar.podsteroid.ui.components.PodsteroidListRow
import com.tiquasar.podsteroid.ui.components.PodsteroidPrimaryButton
import com.tiquasar.podsteroid.ui.components.PodsteroidSectionLabel
import com.tiquasar.podsteroid.ui.components.PodsteroidStatus
import com.tiquasar.podsteroid.ui.components.PodsteroidStatusColors
import com.tiquasar.podsteroid.ui.components.PodsteroidAppear
import com.tiquasar.podsteroid.ui.components.PodsteroidCard
import com.tiquasar.podsteroid.ui.components.PodsteroidDialog
import com.tiquasar.podsteroid.ui.components.PodsteroidEmptyState
import com.tiquasar.podsteroid.ui.components.PodsteroidTopBar
import com.tiquasar.podsteroid.ui.components.VmCpuChips
import com.tiquasar.podsteroid.ui.components.VmCpuLimitChips
import com.tiquasar.podsteroid.ui.components.VmRamChips
import com.tiquasar.podsteroid.ui.components.VmStorageChips
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens
import com.tiquasar.podsteroid.util.DeviceResourcePolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VmCard(
    row: VmRow,
    index: Int = 0,
    onSelect: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTerminal: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onSnapshot: () -> Unit,
    onRestore: () -> Unit,
) {
    val def = row.definition
    val state = row.state
    val isRunning = state is VmState.Running
    val isStarting = state is VmState.Starting
    val isError = state is VmState.Error
    PodsteroidAppear(delayMillis = index * 60) {
        Card(
            onClick = onDetails,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (row.selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ),
            border = BorderStroke(
                1.dp,
                if (row.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(PodsteroidTokens.Spacing.MD),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.MD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(PodsteroidTokens.Radius.Button),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        def.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${stringResource(R.string.backend)}: ${row.backendId.uppercase()} · " +
                            "${def.ramMb} MB · ${def.cpus} CPU · ${def.storageSizeGb} GB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PodsteroidStatus(
                    label = when {
                        isRunning -> stringResource(R.string.status_running)
                        isStarting -> stringResource(R.string.status_starting)
                        isError -> stringResource(R.string.error_title)
                        else -> stringResource(R.string.status_stopped)
                    },
                    pulsing = isRunning,
                    dotColor = when {
                        isRunning -> PodsteroidStatusColors.Running
                        isStarting -> PodsteroidStatusColors.Starting
                        isError -> MaterialTheme.colorScheme.error
                        else -> PodsteroidStatusColors.Stopped
                    },
                )
            }
            if (isStarting && row.bootStage.isNotBlank()) {
                Text(
                    text = row.bootStage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (isRunning) {
                formatUptime(row.runningSinceMs)?.let { uptime ->
                    Text(
                        text = uptime,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (isError) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isRunning -> {
                        PodsteroidGhostButton(text = stringResource(R.string.open_terminal), onClick = onOpenTerminal, modifier = Modifier.weight(1f))
                        PodsteroidDestructiveButton(text = stringResource(R.string.stop), onClick = onStop, modifier = Modifier.weight(1f))
                    }
                    isStarting -> PodsteroidDestructiveButton(text = stringResource(R.string.stop), onClick = onStop, modifier = Modifier.fillMaxWidth())
                    else -> {
                        PodsteroidPrimaryButton(text = stringResource(R.string.start_vm), onClick = onStart, modifier = Modifier.weight(1f))
                        PodsteroidGhostButton(text = stringResource(R.string.open_terminal), onClick = onOpenTerminal, modifier = Modifier.weight(1f))
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            ) {
                TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth(0.31f)) { Text(stringResource(R.string.vm_details)) }
                TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth(0.31f)) { Text(stringResource(R.string.vm_edit)) }
                TextButton(onClick = onRename, modifier = Modifier.fillMaxWidth(0.31f)) { Text(stringResource(R.string.vm_rename)) }
                TextButton(onClick = onClone, modifier = Modifier.fillMaxWidth(0.31f)) { Text(stringResource(R.string.vm_clone)) }
                if (!isRunning && !isStarting) {
                    TextButton(onClick = onSnapshot, modifier = Modifier.fillMaxWidth(0.31f)) { Text(stringResource(R.string.vm_snapshot)) }
                    TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth(0.31f)) { Text(stringResource(R.string.vm_restore)) }
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(0.31f),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete_label)) }
            }
        }
    }
    }
}

@Composable
private fun formatUptime(runningSinceMs: Long?): String? {
    if (runningSinceMs == null) return null
    val secs = ((System.currentTimeMillis() - runningSinceMs) / 1000).coerceAtLeast(0)
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return when {
        h > 0 -> "Up ${h}h ${m}m"
        m > 0 -> "Up ${m}m ${s}s"
        else -> "Up ${s}s"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VmFormDialog(
    title: String,
    initial: VmDefinition?,
    defaultDistro: String = "alpine",
    onDismiss: () -> Unit,
    onSave: (String, EngineSelection, Int, Int, Int, Boolean, Int, String) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var backend by remember(initial?.id) { mutableStateOf(initial?.backend ?: EngineSelection.AUTO) }
    var ram by remember(initial?.id) { mutableStateOf(initial?.ramMb ?: 512) }
    var cpus by remember(initial?.id) { mutableStateOf(initial?.cpus ?: 2) }
    var storage by remember(initial?.id) { mutableStateOf(initial?.storageSizeGb ?: 2) }
    var ssh by remember(initial?.id) { mutableStateOf(initial?.sshEnabled ?: true) }
    var cpuLimit by remember(initial?.id) { mutableStateOf(initial?.cpuLimitPercent ?: 0) }
    var guestDistro by remember(initial?.id) { mutableStateOf(initial?.guestDistro ?: defaultDistro) }
    var overrideLimits by remember(initial?.id) {
        mutableStateOf(
            initial?.let {
                it.ramMb !in DeviceResourcePolicy.RAM_OPTIONS_MB ||
                    it.cpus !in DeviceResourcePolicy.CPU_OPTIONS ||
                    it.storageSizeGb !in DeviceResourcePolicy.STORAGE_OPTIONS_GB
            } ?: false
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(PodsteroidTokens.Radius.Large),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.9f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PodsteroidTokens.Spacing.LG),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = PodsteroidTokens.Spacing.MD),
                )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.vm_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                    ) {
                        listOf(EngineSelection.AUTO, EngineSelection.AVF, EngineSelection.QEMU).forEach { sel ->
                            androidx.compose.material3.FilterChip(
                                selected = backend == sel,
                                onClick = { backend = sel },
                                label = { Text(sel.name) },
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.XS)) {
                        Text(stringResource(R.string.guest_distro), style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                        ) {
                            GuestDistro.OPTIONS.forEach { (value, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = guestDistro == value,
                                    onClick = { guestDistro = value },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.override_limits), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.Switch(checked = overrideLimits, onCheckedChange = { overrideLimits = it })
                    }
                    if (overrideLimits) {
                        Text(
                            text = stringResource(R.string.override_limits_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    VmRamChips(
                        currentMb = ram,
                        onChange = { ram = it },
                        showDivider = false,
                        overrideEnabled = overrideLimits,
                    )
                    VmCpuChips(
                        currentCpus = cpus,
                        onChange = { cpus = it },
                        showDivider = false,
                        overrideEnabled = overrideLimits,
                    )
                    VmCpuLimitChips(currentPercent = cpuLimit, onChange = { cpuLimit = it }, showDivider = false)
                    VmStorageChips(
                        currentGb = storage,
                        onChange = { storage = it },
                        showDivider = false,
                        overrideEnabled = overrideLimits,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.XS)) {
                        PodsteroidListRow(
                            label = stringResource(R.string.ssh_access),
                            rightSlot = { androidx.compose.material3.Switch(checked = ssh, onCheckedChange = { ssh = it }) },
                            divider = false,
                        )
                        Text(
                            text = stringResource(R.string.ssh_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(PodsteroidTokens.Spacing.SM))
                    TextButton(
                        onClick = { onSave(name.trim().ifEmpty { "VM" }, backend, ram, cpus, storage, ssh, cpuLimit, guestDistro) },
                    ) { Text(if (initial == null) stringResource(R.string.vm_create) else stringResource(R.string.vm_save)) }
                }
            }
        }
    }
}

@Composable
private fun RenameVmDialog(
    current: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    PodsteroidDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vm_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.vm_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim().ifEmpty { current }) }) {
                Text(stringResource(R.string.vm_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VmDetailsDialog(
    row: VmRow,
    onDismiss: () -> Unit,
) {
    val def = row.definition
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceIp = com.tiquasar.podsteroid.util.NetworkUtils.localIpv4(context)
    PodsteroidDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vm_details)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            ) {
                Text(def.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${stringResource(R.string.backend)}: ${row.backendId.uppercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "RAM: ${def.ramMb} MB · CPU: ${def.cpus} · Storage: ${def.storageSizeGb} GB",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${stringResource(R.string.vm_details_device_ip)}: $deviceIp",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = PodsteroidTokens.mono(),
                )
                Text(
                    text = "${stringResource(R.string.vm_port_ssh)}: 127.0.0.1:${def.sshHostPort} → 22 (tcp)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = PodsteroidTokens.mono(),
                )
                Text(
                    text = "${stringResource(R.string.vm_port_vnc)}: 127.0.0.1:${def.vncHostPort} → 5900 (tcp)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = PodsteroidTokens.mono(),
                )
                Text(
                    text = "${stringResource(R.string.vm_port_audio)}: 127.0.0.1:${def.audioHostPort} → 4713 (tcp)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = PodsteroidTokens.mono(),
                )
                Text(
                    text = stringResource(R.string.vm_details_loopback_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (def.cpuLimitPercent > 0) {
                    Text(
                        text = "${stringResource(R.string.vm_details_cpu_limit)}: ${def.cpuLimitPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (row.state is VmState.Running) {
                    formatUptime(row.runningSinceMs)?.let {
                        Text("Uptime: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.vm_details_close)) }
        },
    )
}

@Composable
private fun AvfHintBanner(onDismiss: () -> Unit) {
    PodsteroidCard(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
        ) {
            Text(stringResource(R.string.avf_available), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.avf_hint_needs_pc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.avf_grant_commands),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStatus: () -> Unit,
    onNavigateToContainerBackup: () -> Unit,
    onNavigateToCluster: () -> Unit,
    onNavigateToObservability: () -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToFleet: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val showAvfHint by viewModel.showAvfHint.collectAsStateWithLifecycle()
    val defaultDistro by viewModel.defaultGuestDistro.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VmDefinition?>(null) }
    var editingVm by remember { mutableStateOf<VmDefinition?>(null) }
    var renamingVm by remember { mutableStateOf<VmDefinition?>(null) }
    var detailsVm by remember { mutableStateOf<VmRow?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // no-op: rows are flow-driven
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    updateInfo?.let { info ->
        PodsteroidDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.update_available)) },
            title = { Text(stringResource(R.string.update_available)) },
            text = { Text(stringResource(R.string.version_available, info.latestVersion, BuildConfig.VERSION_NAME)) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    }.onFailure {
                        android.widget.Toast.makeText(
                            context, context.getString(R.string.update_open_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    viewModel.dismissUpdate()
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) { Text(stringResource(R.string.later)) }
            },
        )
    }

    if (showCreateDialog) {
        VmFormDialog(
            title = stringResource(R.string.vm_new),
            initial = null,
            defaultDistro = defaultDistro,
            onDismiss = { showCreateDialog = false },
            onSave = { name, backend, ram, cpus, storage, ssh, cpuLimit, guestDistro ->
                viewModel.create(name, backend, ram, cpus, storage, ssh, cpuLimit, guestDistro = guestDistro)
                showCreateDialog = false
            },
        )
    }

    editingVm?.let { def ->
        VmFormDialog(
            title = stringResource(R.string.vm_edit),
            initial = def,
            defaultDistro = defaultDistro,
            onDismiss = { editingVm = null },
            onSave = { name, backend, ram, cpus, storage, ssh, cpuLimit, guestDistro ->
                viewModel.update(def.id, name, backend, ram, cpus, storage, ssh, cpuLimit, guestDistro = guestDistro)
                editingVm = null
            },
        )
    }

    renamingVm?.let { def ->
        RenameVmDialog(
            current = def.name,
            onDismiss = { renamingVm = null },
            onRename = { name ->
                viewModel.rename(def.id, name)
                renamingVm = null
            },
        )
    }

    detailsVm?.let { row ->
        VmDetailsDialog(
            row = row,
            onDismiss = { detailsVm = null },
        )
    }

    pendingDelete?.let { def ->
        PodsteroidDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.vm_delete_title)) },
            text = { Text(stringResource(R.string.vm_delete_message, def.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(def.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete_label)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            PodsteroidTopBar(
                title = stringResource(R.string.app_name),
                gradient = false,
                actions = {
                    FilledTonalButton(onClick = { showCreateDialog = true }) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(PodsteroidTokens.Spacing.XS))
                        Text(stringResource(R.string.vm_new))
                    }
                    IconButton(onClick = onNavigateToStatus) {
                        Icon(Icons.Default.MonitorHeart, contentDescription = stringResource(R.string.status_page_title))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            maxWidth = 700,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodsteroidTokens.Spacing.XL, vertical = PodsteroidTokens.Spacing.LG),
                verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.MD),
            ) {
                if (showAvfHint) {
                    AvfHintBanner(onDismiss = { viewModel.dismissAvfHint() })
                }

                PodsteroidAppear {
                    HomeHeroCard(
                        running = rows.count { it.state is VmState.Running },
                        total = rows.size,
                    )
                }

                PodsteroidSectionLabel(stringResource(R.string.home_quick_actions))
                PodsteroidAppear(delayMillis = 60) {
                    HomeQuickActions(
                        onStatus = onNavigateToStatus,
                        onContainers = onNavigateToObservability,
                        onCluster = onNavigateToCluster,
                        onBackup = onNavigateToContainerBackup,
                        onServers = onNavigateToServers,
                        onFleet = onNavigateToFleet,
                    )
                }

                PodsteroidSectionLabel(stringResource(R.string.vm_list_title))
                if (rows.isEmpty()) {
                    PodsteroidEmptyState(
                        icon = Icons.Default.Memory,
                        title = stringResource(R.string.home_hero_idle),
                        message = stringResource(R.string.empty_vms_message),
                        actionLabel = stringResource(R.string.vm_new),
                        onAction = { showCreateDialog = true },
                    )
                } else {
                    rows.forEachIndexed { i, row ->
                        VmCard(
                            row = row,
                            index = i,
                            onSelect = { viewModel.select(row.definition.id) },
                            onStart = { viewModel.start(row.definition.id) },
                            onStop = { viewModel.stop(row.definition.id) },
                            onOpenTerminal = { viewModel.openTerminal(row.definition.id, onNavigateToTerminal) },
                            onEdit = { editingVm = row.definition },
                            onRename = { renamingVm = row.definition },
                            onClone = { viewModel.clone(row.definition.id) },
                            onDelete = { pendingDelete = row.definition },
                            onDetails = { detailsVm = row },
                            onSnapshot = { viewModel.snapshot(row.definition.id) },
                            onRestore = { viewModel.restoreSnapshot(row.definition.id) },
                        )
                    }
                }
                Spacer(Modifier.height(PodsteroidTokens.Spacing.XL))
            }
        }
    }
}

@Composable
private fun HomeHeroCard(running: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PodsteroidTokens.Radius.Large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PodsteroidTokens.brandBrush())
                .padding(PodsteroidTokens.Spacing.XL),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.XS)) {
                Text(
                    text = if (total == 0) {
                        stringResource(R.string.home_hero_idle)
                    } else {
                        stringResource(R.string.home_hero_running, running, total)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = stringResource(R.string.vm_list_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun HomeQuickActions(
    onStatus: () -> Unit,
    onContainers: () -> Unit,
    onCluster: () -> Unit,
    onBackup: () -> Unit,
    onServers: () -> Unit,
    onFleet: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
            PodsteroidActionTile(
                title = stringResource(R.string.status_page_title),
                icon = Icons.Default.MonitorHeart,
                onClick = onStatus,
                modifier = Modifier.weight(1f),
            )
            PodsteroidActionTile(
                title = stringResource(R.string.home_action_observability),
                icon = Icons.Default.Storage,
                onClick = onContainers,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
            PodsteroidActionTile(
                title = stringResource(R.string.home_action_cluster),
                icon = Icons.Default.ViewModule,
                onClick = onCluster,
                modifier = Modifier.weight(1f),
            )
            PodsteroidActionTile(
                title = stringResource(R.string.home_action_backup),
                icon = Icons.Default.Save,
                onClick = onBackup,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM)) {
            PodsteroidActionTile(
                title = stringResource(R.string.home_action_servers),
                icon = Icons.Default.DesktopWindows,
                onClick = onServers,
                modifier = Modifier.weight(1f),
            )
            PodsteroidActionTile(
                title = stringResource(R.string.home_action_fleet),
                icon = Icons.Default.Cloud,
                onClick = onFleet,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PodsteroidActionTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(PodsteroidTokens.Radius.Card),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PodsteroidTokens.Spacing.MD),
            verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
            horizontalAlignment = Alignment.Start,
        ) {
            Surface(
                color = Color.Transparent,
                contentColor = Color.White,
                shape = RoundedCornerShape(PodsteroidTokens.Radius.Button),
                modifier = Modifier
                    .size(40.dp)
                    .background(PodsteroidTokens.brandBrush(), RoundedCornerShape(PodsteroidTokens.Radius.Button)),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = Color.White)
                }
            }
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
