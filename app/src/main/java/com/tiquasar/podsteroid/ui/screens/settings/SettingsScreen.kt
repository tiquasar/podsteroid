package com.tiquasar.podsteroid.ui.screens.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiquasar.podsteroid.BuildConfig
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.data.repository.MAX_PORT_FORWARD_RULES
import com.tiquasar.podsteroid.data.repository.PortForwardRepository
import com.tiquasar.podsteroid.data.repository.PortForwardRule
import com.tiquasar.podsteroid.engine.EngineSelection
import com.tiquasar.podsteroid.engine.GuestDistro
import com.tiquasar.podsteroid.engine.VmState
import com.tiquasar.podsteroid.engine.avf.AvfDiagnostics
import com.tiquasar.podsteroid.data.repository.VmDefinition
import com.tiquasar.podsteroid.x11.X11Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import com.tiquasar.podsteroid.ui.components.AdaptiveContainer
import com.tiquasar.podsteroid.ui.components.PodsteroidDestructiveButton
import com.tiquasar.podsteroid.ui.components.PodsteroidGhostButton
import com.tiquasar.podsteroid.ui.components.PodsteroidInlineAction
import com.tiquasar.podsteroid.ui.components.PodsteroidListRow
import com.tiquasar.podsteroid.ui.components.VmBandwidthChips
import com.tiquasar.podsteroid.ui.components.VmCpuChips
import com.tiquasar.podsteroid.ui.components.VmCpuLimitChips
import com.tiquasar.podsteroid.ui.components.VmRamChips
import com.tiquasar.podsteroid.ui.components.PodsteroidChipColors
import com.tiquasar.podsteroid.ui.components.PodsteroidSectionLabel
import com.tiquasar.podsteroid.ui.components.PodsteroidSwitch
import com.tiquasar.podsteroid.util.DeviceResourcePolicy
import com.tiquasar.podsteroid.ui.components.PodsteroidTopBar
import com.tiquasar.podsteroid.ui.theme.PodsteroidTokens
import com.tiquasar.podsteroid.data.repository.LanguageManager

@Composable
private fun languageDisplayName(language: String, systemDefaultLanguage: String): String {
    val effectiveLang = if (language == "auto") systemDefaultLanguage else language
    return when (effectiveLang) {
        LanguageManager.LANGUAGE_EN -> stringResource(R.string.language_en)
        else -> stringResource(R.string.system_default)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onThemeOrFontChanged: () -> Unit = {},
    onLanguageChanged: () -> Unit = {},
    onNavigateToContainerBackup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val portForwardRules by viewModel.portForwardRules.collectAsStateWithLifecycle()
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val exportError by viewModel.exportError.collectAsStateWithLifecycle()
    val usbPassthrough by viewModel.usbPassthroughEnabled.collectAsStateWithLifecycle()
    val cpuLimitPercent by viewModel.cpuLimitPercent.collectAsStateWithLifecycle()
    val vmPorts by viewModel.vmPorts.collectAsStateWithLifecycle()
    val biometricLock by viewModel.biometricLockEnabled.collectAsStateWithLifecycle()
    val qemuShare by viewModel.qemuShareEnabled.collectAsStateWithLifecycle()
    val guestDistro by viewModel.guestDistro.collectAsStateWithLifecycle()
    val virtioFs by viewModel.virtioFsEnabled.collectAsStateWithLifecycle()
    val virgl by viewModel.virglEnabled.collectAsStateWithLifecycle()
    val zramMb by viewModel.zramMb.collectAsStateWithLifecycle()
    val keepRunning by viewModel.keepRunningInBackground.collectAsStateWithLifecycle()

    var advancedExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var avfReportText by remember { mutableStateOf<String?>(null) }
    var avfRunning by remember { mutableStateOf(false) }
    var overrideLimits by remember { mutableStateOf(false) }
    // Reflect whether the selected VM currently holds any non-preset (overridden)
    // resource value, so the toggle is correct on open and stays consistent when a
    // non-preset value is picked from the extended chip set.
    LaunchedEffect(ui.vmRamMb, ui.vmCpus, ui.storageSizeGb) {
        overrideLimits = ui.vmRamMb !in DeviceResourcePolicy.RAM_OPTIONS_MB ||
            ui.vmCpus !in DeviceResourcePolicy.CPU_OPTIONS ||
            ui.storageSizeGb !in DeviceResourcePolicy.STORAGE_OPTIONS_GB
    }
    val avfScope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val vmNotRunning = vmState !is VmState.Running && vmState !is VmState.Starting

    // Memoize: both values are constant for the process lifetime / until a backend
    // swap, so there's no point re-running reflection on every recomposition.
    val isUsbPassthroughAvailable = remember { viewModel.isUsbPassthroughAvailable() }
    val activeBackendId = remember { viewModel.activeBackendId() }

    // Re-sync the persisted storageAccessEnabled flag against the real OS grant on
    // every resume (user may have denied all-files-access on the system screen we
    // sent them to, but the DataStore flag still reads true).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                viewModel.setStorageAccessEnabled(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(exportError) {
        val msg = exportError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearExportError()
    }

    Scaffold(
        topBar = {
            PodsteroidTopBar(
                title = stringResource(R.string.settings),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            maxWidth = if (isCompactHeight) 900 else 600,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodsteroidTokens.Spacing.XL),
            ) {
                // ── APPEARANCE ────────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.appearance))
                PodsteroidListRow(
                    label = stringResource(R.string.dark_theme),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = ui.darkTheme,
                            onCheckedChange = {
                                viewModel.setDarkTheme(it)
                                onThemeOrFontChanged()
                            },
                        )
                    },
                )
                PodsteroidListRow(
                    label = stringResource(R.string.dynamic_color),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = ui.dynamicColorEnabled,
                            onCheckedChange = {
                                viewModel.setDynamicColorEnabled(it)
                                onThemeOrFontChanged()
                            },
                        )
                    },
                )

                // ── SECURITY ──────────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.security))
                PodsteroidListRow(
                    label = stringResource(R.string.biometric_lock),
                    value = stringResource(R.string.biometric_lock_summary),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = biometricLock,
                            onCheckedChange = { viewModel.setBiometricLockEnabled(it) },
                        )
                    },
                )

                // ── BACKGROUND ──────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.background))
                PodsteroidListRow(
                    label = stringResource(R.string.keep_running_in_background),
                    value = stringResource(R.string.keep_running_in_background_summary),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = keepRunning,
                            onCheckedChange = { viewModel.setKeepRunningInBackground(it) },
                        )
                    },
                )

                // ── PERFORMANCE ──────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.performance))
                PodsteroidListRow(
                    label = stringResource(R.string.virtio_fs),
                    value = stringResource(R.string.virtio_fs_summary),
                    rightSlot = {
                        PodsteroidSwitch(checked = virtioFs, onCheckedChange = { viewModel.setVirtioFsEnabled(it) })
                    },
                )
                PodsteroidListRow(
                    label = stringResource(R.string.virgl),
                    value = stringResource(R.string.virgl_summary),
                    rightSlot = {
                        PodsteroidSwitch(checked = virgl, onCheckedChange = { viewModel.setVirglEnabled(it) })
                    },
                )
                var zramText by remember { androidx.compose.runtime.mutableStateOf(zramMb.toString()) }
                PodsteroidListRow(
                    label = stringResource(R.string.zram_label),
                    value = stringResource(R.string.zram_summary),
                    rightSlot = {
                        androidx.compose.material3.OutlinedTextField(
                            value = zramText,
                            onValueChange = {
                                zramText = it.filter { c -> c.isDigit() }
                                zramText.toIntOrNull()?.let { v -> viewModel.setZramMb(v) }
                            },
                            singleLine = true,
                            modifier = Modifier.widthIn(min = 72.dp, max = 96.dp),
                        )
                    },
                )

                // ── FILE SHARING (QEMU) ─────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.downloads_sharing))
                PodsteroidListRow(
                    label = stringResource(R.string.qemu_share),
                    value = stringResource(R.string.qemu_share_summary),
                    rightSlot = {
                        PodsteroidSwitch(checked = qemuShare, onCheckedChange = { viewModel.setQemuShareEnabled(it) })
                    },
                )

                // ── GUEST DISTRO ────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.guest_distro))
                Text(
                    text = stringResource(R.string.guest_distro_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                    verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.SM),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GuestDistro.OPTIONS.forEach { (key, label) ->
                        FilterChip(
                            selected = guestDistro == key,
                            onClick = { viewModel.setGuestDistro(key) },
                            label = { Text(label) },
                        )
                    }
                }

                // ── DEVELOPER TOOLS ────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.dev_tools))
                PodsteroidListRow(
                    label = stringResource(R.string.copy_ssh_cmd),
                    value = "ssh root@<phone-ip> -p 9922",
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("podsteroid-ssh", "ssh root@${viewModel.phoneIp}:9922"))
                        android.widget.Toast.makeText(context, context.getString(R.string.ssh_cmd_copied), android.widget.Toast.LENGTH_SHORT).show()
                    },
                )

                // ── LANGUAGE ───────────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.language_label))
                PodsteroidListRow(
                    label = stringResource(R.string.language_label),
                    value = languageDisplayName(ui.language, ui.systemDefaultLanguage),
                    onClick = { showLanguageDialog = true },
                )

                // ── VM RESOURCES ──────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.vm_resources))
                if (!vmNotRunning) {
                    Text(
                        text = stringResource(R.string.stop_vm_to_change),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = PodsteroidTokens.Spacing.SM),
                    )
                }
                PodsteroidListRow(
                    label = stringResource(R.string.load_balance),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = ui.loadBalanceEnabled,
                            onCheckedChange = { viewModel.setLoadBalanceEnabled(it) },
                            enabled = vmNotRunning,
                        )
                    },
                )
                VmRamChips(
                    currentMb = ui.vmRamMb,
                    onChange = viewModel::setVmRamMb,
                            enabled = vmNotRunning && overrideLimits,
                    overrideEnabled = overrideLimits,
                )
                VmCpuChips(
                    currentCpus = ui.vmCpus,
                    onChange = viewModel::setVmCpus,
                            enabled = vmNotRunning && overrideLimits,
                    overrideEnabled = overrideLimits,
                )
                VmCpuLimitChips(
                    currentPercent = cpuLimitPercent,
                    onChange = viewModel::setCpuLimitPercent,
                            enabled = vmNotRunning && overrideLimits,
                )
                VmBandwidthChips(
                    currentMbps = ui.bandwidthMbps,
                    onChange = viewModel::setBandwidthMbps,
                            enabled = vmNotRunning && overrideLimits,
                    overrideEnabled = overrideLimits,
                )
                PodsteroidListRow(
                    label = stringResource(R.string.override_limits),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = overrideLimits,
                            onCheckedChange = { overrideLimits = it },
                            enabled = vmNotRunning,
                        )
                    },
                    divider = false,
                )
                if (overrideLimits) {
                    Text(
                        text = stringResource(R.string.override_limits_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = PodsteroidTokens.Amber,
                        modifier = Modifier.padding(top = PodsteroidTokens.Spacing.XS),
                    )
                }
                PodsteroidListRow(
                    label = stringResource(R.string.storage),
                    value = "${ui.storageSizeGb} GB",
                )

                // ── NETWORK ───────────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.network))
                PodsteroidListRow(
                    label = stringResource(R.string.phone_ip),
                    value = viewModel.phoneIp,
                    mono = true,
                )
                PodsteroidListRow(
                    label = stringResource(R.string.ssh),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = ui.sshEnabled,
                            onCheckedChange = { viewModel.setSshEnabled(it) },
                            enabled = vmNotRunning,
                        )
                    },
                )
                PortForwardSection(
                    rules = portForwardRules,
                    sshEnabled = ui.sshEnabled,
                    sshHostPort = vmPorts?.first ?: VmDefinition.DEFAULT_SSH_HOST_PORT,
                    vncHostPort = vmPorts?.second ?: X11Constants.VNC_PORT,
                    audioHostPort = vmPorts?.third ?: X11Constants.AUDIO_PORT,
                    onAdd = { showAddDialog = true },
                    onRemove = { viewModel.removePortForward(it) },
                )

                // ── STORAGE / SHARING ─────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.storage))
                DownloadsSharingRow(
                    enabled = ui.storageAccessEnabled,
                    vmNotRunning = vmNotRunning,
                    onToggle = { viewModel.setStorageAccessEnabled(it) },
                )
                PodsteroidListRow(
                    label = stringResource(R.string.container_backup_title),
                    value = stringResource(R.string.container_backup_settings_subtitle),
                    trailing = "›",
                    onClick = onNavigateToContainerBackup,
                )
                UsbPassthroughRow(
                    enabled = usbPassthrough,
                    vmNotRunning = vmNotRunning,
                    available = isUsbPassthroughAvailable,
                    activeBackendId = activeBackendId,
                    onToggle = { viewModel.setUsbPassthroughEnabled(it) },
                )
                Spacer(Modifier.height(PodsteroidTokens.Spacing.MD))
                PodsteroidDestructiveButton(
                    text = stringResource(R.string.reset_vm),
                    onClick = { showResetDialog = true },
                )

                // ── ADVANCED ──────────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.advanced))
                Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                PodsteroidGhostButton(
                    text = if (advancedExpanded) stringResource(R.string.hide_advanced) else stringResource(R.string.show_advanced),
                    onClick = { advancedExpanded = !advancedExpanded },
                )
                if (advancedExpanded) {
                    PodsteroidSectionLabel(stringResource(R.string.backend))
                    Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = PodsteroidTokens.Spacing.MD),
                    ) {
                        EngineSelection.entries.forEach { sel ->
                            FilterChip(
                                selected = ui.engineSelection == sel,
                                onClick = { viewModel.setEngineSelection(sel) },
                                enabled = vmNotRunning,
                                label = {
                                    Text(
                                        when (sel) {
                                            EngineSelection.AUTO -> stringResource(R.string.auto)
                                            EngineSelection.AVF  -> stringResource(R.string.avf_kvm)
                                            EngineSelection.QEMU -> stringResource(R.string.qemu_tcg)
                                        },
                                        fontFamily = FontFamily.Monospace,
                                    )
                                },
                                shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                                colors = PodsteroidChipColors(),
                            )
                        }
                    }
                    Spacer(Modifier.height(PodsteroidTokens.Spacing.MD))
                    AdvancedFieldsBlock(
                        qemuExtraArgs = ui.qemuExtraArgs,
                        kernelExtraCmdline = ui.kernelExtraCmdline,
                        onQemuChange = viewModel::setQemuExtraArgs,
                        onKernelChange = viewModel::setKernelExtraCmdline,
                        onQemuReset = viewModel::resetQemuExtraArgs,
                        onKernelReset = viewModel::resetKernelExtraCmdline,
                        enabled = vmNotRunning,
                    )
                }

                // ── ABOUT ─────────────────────────────────────────────
                PodsteroidSectionLabel(stringResource(R.string.about))
                PodsteroidListRow(label = stringResource(R.string.version_label), value = "v${BuildConfig.VERSION_NAME}", mono = true)
                PodsteroidListRow(label = stringResource(R.string.qemu_label), value = "v${BuildConfig.QEMU_VERSION}", mono = true)
                PodsteroidListRow(label = stringResource(R.string.architecture), value = "AArch64", mono = true)
                PodsteroidListRow(label = stringResource(R.string.linux_distro), value = "Alpine 3.24", mono = true)
                Spacer(Modifier.height(PodsteroidTokens.Spacing.MD))
                PodsteroidGhostButton(
                    text = stringResource(R.string.export_diagnostic_log),
                    onClick = { viewModel.exportConsoleLogs() },
                )
                Spacer(Modifier.height(PodsteroidTokens.Spacing.SM))
                // Use lifecycle-aware collection to match the rest of the screen.
                val avfVerbose by viewModel.avfVerboseLogging.collectAsStateWithLifecycle()
                PodsteroidListRow(
                    label = stringResource(R.string.verbose_avf_logging),
                    rightSlot = {
                        PodsteroidSwitch(
                            checked = avfVerbose,
                            onCheckedChange = { viewModel.setAvfVerboseLogging(it) },
                        )
                    },
                )
                PodsteroidGhostButton(
                    text = if (avfRunning) stringResource(R.string.running_avf_diagnostic) else stringResource(R.string.avf_diagnostic),
                    onClick = {
                        if (avfRunning) return@PodsteroidGhostButton
                        avfRunning = true
                        avfReportText = ctx.getString(R.string.probing_avf)
                        avfScope.launch {
                            val probe = AvfDiagnostics.probe(ctx)
                            val smoke = if (probe.featureSupported && probe.managePermissionGranted) {
                                withContext(Dispatchers.IO) { AvfDiagnostics.runSmokeTest(ctx) }
                            } else null
                            avfReportText = probe.copy(
                                smokeTestResult = smoke,
                                activeBackend = activeBackendId,
                            ).pretty()
                            avfRunning = false
                        }
                    },
                )

                Spacer(Modifier.height(PodsteroidTokens.Spacing.XL2))
            }
        }
    }

    if (showAddDialog) {
        AddPortForwardDialog(
            onDismiss = { showAddDialog = false },
            tableFull = portForwardRules.size >= MAX_PORT_FORWARD_RULES,
            onAdd = { hostPort, guestPort, protocol ->
                // Only close the dialog when the rule was actually added.
                // addPortForward returns false if the (hostPort, protocol) pair
                // already exists; in that case the dialog stays open with an error.
                val added = viewModel.addPortForward(hostPort, guestPort, protocol)
                if (added) showAddDialog = false
                added
            },
        )
    }

    avfReportText?.let { report ->
        AlertDialog(
            onDismissRequest = { avfReportText = null },
            title = { Text(stringResource(R.string.avf_diagnostic)) },
            text = {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(PodsteroidTokens.Spacing.SM),
                    ) {
                        Text(
                            text = report,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { avfReportText = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_vm_description)) },
            text = {
                Text(stringResource(R.string.reset_vm_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetVm()
                    showResetDialog = false
                }) {
                    Text(stringResource(R.string.reset_everything), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "auto" to stringResource(R.string.system_default),
                        "en" to stringResource(R.string.language_en),
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = ui.language == code,
                            onClick = {
                                avfScope.launch {
                                    viewModel.setLanguage(code)
                                    showLanguageDialog = false
                                    onLanguageChanged()
                                }
                            },
                            label = { Text(label) },
                            shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                            colors = PodsteroidChipColors(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PortForwardSection(
    rules: List<PortForwardRule>,
    sshEnabled: Boolean,
    sshHostPort: Int,
    vncHostPort: Int,
    audioHostPort: Int,
    onAdd: () -> Unit,
    onRemove: (PortForwardRule) -> Unit,
) {
    PodsteroidListRow(
        label = stringResource(R.string.port_forwards_count, rules.size),
        rightSlot = { PodsteroidInlineAction(label = stringResource(R.string.add_btn), onClick = onAdd) },
    )

    // The forwards Podsteroid sets up itself. They never appear in the rule list
    // because they are not user rules, which left people reading netstat in the
    // guest and guessing what had claimed a port, or trying to add a rule on one
    // and getting a refusal with no visible reason.
    ReservedForwardRow(
        hostPort = sshHostPort,
        guestPort = 22,
        purpose = stringResource(
            if (sshEnabled) R.string.port_forward_purpose_ssh else R.string.port_forward_purpose_ssh_off
        ),
        dimmed = !sshEnabled,
    )
    ReservedForwardRow(
        hostPort = vncHostPort,
        guestPort = X11Constants.VNC_PORT,
        purpose = stringResource(R.string.port_forward_purpose_vnc),
        dimmed = false,
    )
    ReservedForwardRow(
        hostPort = audioHostPort,
        guestPort = X11Constants.AUDIO_PORT,
        purpose = stringResource(R.string.port_forward_purpose_audio),
        dimmed = false,
    )
    rules.forEach { rule ->
        key(rule.hostPort, rule.protocol) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PodsteroidTokens.Spacing.SM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${rule.hostPort} → ${rule.guestPort} (${rule.protocol})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = PodsteroidTokens.mono(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(rule) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.remove),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
            )
        }
    }
}

/** One of Podsteroid's own forwards: shown for reference, with no delete action. */
@Composable
private fun ReservedForwardRow(
    hostPort: Int,
    guestPort: Int,
    purpose: String,
    dimmed: Boolean,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = if (dimmed) 0.5f else 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PodsteroidTokens.Spacing.SM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$hostPort → $guestPort (tcp)",
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            fontFamily = PodsteroidTokens.mono(),
        )
        Text(
            text = purpose,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline,
        thickness = 1.dp,
    )
}

/**
 * Mirrors the setup wizard's storage-access toggle: turn it on and, if needed,
 * jump straight to the system MANAGE_EXTERNAL_STORAGE grant screen.
 *
 * Works on both backends: QEMU shares Downloads via in-process virtio-9p, AVF
 * via an in-process 9p2000.L server the guest mounts over vsock
 * (AvfDownloadsShare) — no SharedPath, no privileged system-app install needed.
 * Only gated on the VM being stopped, since the share is wired up at boot.
 */
@Composable
private fun DownloadsSharingRow(
    enabled: Boolean,
    vmNotRunning: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val canManageAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val writeStoragePermLauncher = rememberLauncherForActivityResult(RequestPermission()) { _ -> }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun openAllFilesAccessSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }

    PodsteroidListRow(
        label = stringResource(R.string.downloads_sharing),
        rightSlot = {
            PodsteroidSwitch(
                checked = enabled,
                onCheckedChange = { checked ->
                    onToggle(checked)
                    if (checked) {
                        if (canManageAllFiles && !Environment.isExternalStorageManager()) {
                            openAllFilesAccessSettings()
                        } else if (!canManageAllFiles &&
                            ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            writeStoragePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                },
                enabled = vmNotRunning,
            )
        },
    )
}

/**
 * Mirrors [DownloadsSharingRow] for USB device passthrough. Permission is asked
 * per-device at attach time (no upfront system grant), so this is just an opt-in
 * switch. Adds a USB controller to the QEMU launch line, so it's only editable
 * while the VM is stopped. Disabled on AVF: that backend has no QMP channel and
 * cannot pass a device through.
 */
@Composable
private fun UsbPassthroughRow(
    enabled: Boolean,
    vmNotRunning: Boolean,
    available: Boolean,
    activeBackendId: String,
    onToggle: (Boolean) -> Unit,
) {
    PodsteroidListRow(
        label = stringResource(R.string.usb_passthrough_settings_label),
        rightSlot = {
            PodsteroidSwitch(
                checked = enabled && available,
                onCheckedChange = onToggle,
                enabled = vmNotRunning && available,
            )
        },
    )
    Text(
        text = if (available) {
            stringResource(R.string.usb_passthrough_settings_description_available)
        } else {
            stringResource(R.string.usb_passthrough_settings_description_unavailable, activeBackendId)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = PodsteroidTokens.Spacing.MD,
            end = PodsteroidTokens.Spacing.MD,
            bottom = PodsteroidTokens.Spacing.SM,
        ),
    )
}

@Composable
private fun AdvancedFieldsBlock(
    qemuExtraArgs: String,
    kernelExtraCmdline: String,
    onQemuChange: (String) -> Unit,
    onKernelChange: (String) -> Unit,
    onQemuReset: () -> Unit,
    onKernelReset: () -> Unit,
    enabled: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PodsteroidTokens.Spacing.MD),
        modifier = Modifier.padding(
            top = PodsteroidTokens.Spacing.SM,
            bottom = PodsteroidTokens.Spacing.MD,
        ),
    ) {
        if (!enabled) {
            Text(
                text = stringResource(R.string.stop_vm_before_edit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        AdvancedTextSetting(
            label = stringResource(R.string.extra_qemu_args),
            helper = stringResource(R.string.qemu_args_helper),
            value = qemuExtraArgs,
            enabled = enabled,
            onValueChange = onQemuChange,
            onReset = onQemuReset,
            minLines = 4,
        )
        AdvancedTextSetting(
            label = stringResource(R.string.extra_kernel_cmdline),
            helper = stringResource(R.string.kernel_cmdline_helper),
            value = kernelExtraCmdline,
            enabled = enabled,
            onValueChange = onKernelChange,
            onReset = onKernelReset,
            minLines = 2,
        )
    }
}

@Composable
private fun AddPortForwardDialog(
    onDismiss: () -> Unit,
    // The repository refuses rules past its ceiling. Checking here too keeps the
    // message truthful: without it a refusal would surface as "already forwarded".
    tableFull: Boolean,
    // Returns true if the rule was added, false if it was a duplicate.
    // The dialog shows an error and stays open on false.
    onAdd: (hostPort: Int, guestPort: Int, protocol: String) -> Boolean,
) {
    var hostPort by remember { mutableStateOf("") }
    var guestPort by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("tcp") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidPortsMsg = stringResource(R.string.enter_valid_ports)
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_port_forward)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hostPort,
                    onValueChange = { hostPort = it; error = null },
                    label = { Text(stringResource(R.string.android_port)) },
                    placeholder = { Text(stringResource(R.string.e_g_8080)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = guestPort,
                    onValueChange = { guestPort = it; error = null },
                    label = { Text(stringResource(R.string.vm_port)) },
                    placeholder = { Text(stringResource(R.string.e_g_80)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tcp", "udp", "both").forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = { protocol = proto },
                            label = {
                                Text(
                                    proto.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            shape = RoundedCornerShape(PodsteroidTokens.Radius.Chip),
                            colors = PodsteroidChipColors(),
                        )
                    }
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hp = hostPort.toIntOrNull()
                val gp = guestPort.toIntOrNull()
                if (hp == null || gp == null || hp !in 1..65535 || gp !in 1..65535) {
                    error = invalidPortsMsg
                    return@TextButton
                }
                if (hp in PortForwardRepository.RESERVED_HOST_PORTS) {
                    error = context.getString(R.string.port_reserved, hp)
                    return@TextButton
                }
                if (tableFull) {
                    error = context.getString(R.string.port_forward_table_full, MAX_PORT_FORWARD_RULES)
                    return@TextButton
                }
                val added = onAdd(hp, gp, protocol)
                if (!added) error = context.getString(R.string.port_already_forwarded, hp, protocol.uppercase())
            }) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AdvancedTextSetting(
    label: String,
    helper: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    minLines: Int,
) {
    // Editing is local; we only persist when the field loses focus. Avoids
    // round-tripping every keystroke through DataStore on a multi-line config field.
    // Don't reset local edits on every external emit — only sync when the upstream
    // value actually drifts from what we have buffered (e.g. a Reset tap).
    val localState = remember { mutableStateOf(value) }
    var localValue by localState
    LaunchedEffect(value) {
        if (localValue != value) localValue = value
    }
    var hadFocus by remember { mutableStateOf(false) }

    // Commit a pending edit on dispose: persistence only happens on focus loss,
    // but pressing system back while the field is still focused disposes the
    // composable without a focus-change event, silently dropping the edit.
    // rememberUpdatedState so onDispose sees the latest buffered/upstream values.
    val currentUpstream by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    DisposableEffect(Unit) {
        onDispose {
            if (hadFocus && localState.value != currentUpstream) {
                currentOnValueChange(localState.value)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = localValue,
            onValueChange = { localValue = it },
            label = { Text(label) },
            enabled = enabled,
            singleLine = false,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus && localValue != value) {
                        onValueChange(localValue)
                    }
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset, enabled = enabled) {
                Text(stringResource(R.string.reset))
            }
        }
    }
}
