/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Settings ViewModel for Podsteroid.
 */
package com.tiquasar.podsteroid.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiquasar.podsteroid.BuildConfig
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.data.repository.LanguageManager
import com.tiquasar.podsteroid.data.repository.PortForwardRepository
import com.tiquasar.podsteroid.data.repository.PortForwardRule
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import com.tiquasar.podsteroid.data.repository.VmDefinition
import com.tiquasar.podsteroid.di.ApplicationScope
import com.tiquasar.podsteroid.engine.EngineSelection
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.VmState
import com.tiquasar.podsteroid.util.DeviceResourcePolicy
import com.tiquasar.podsteroid.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Snapshot of the form-style settings rows that SettingsScreen reads together.
 * Combined into one StateFlow so an emit on any one source produces a single
 * recomposition instead of one per source. Does *not* include flows with
 * different update cadences or consumers (vmState, portForwardRules, updateInfo,
 * terminal-only theme/font/size) — those stay separate.
 */
data class SettingsUiState(
    val vmRamMb: Int = 512,
    val vmCpus: Int = 1,
    val storageSizeGb: Int = 2,
    val sshEnabled: Boolean = false,
    val storageAccessEnabled: Boolean = false,
    val qemuExtraArgs: String = SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS,
    val kernelExtraCmdline: String = SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE,
    val darkTheme: Boolean = false,
    val dynamicColorEnabled: Boolean = false,
    val engineSelection: EngineSelection = EngineSelection.AUTO,
    val language: String = "auto",
    val systemDefaultLanguage: String = "auto",
    val loadBalanceEnabled: Boolean = false,
    val bandwidthMbps: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val registry: VmRegistry,
    private val languageManager: LanguageManager,
    @ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {

    /** The currently-selected VM definition, or null before seeding completes. */
    private val selectedDef: StateFlow<VmDefinition?> = combine(
        registry.selectedId, registry.definitions,
    ) { id, defs -> id?.let { i -> defs.firstOrNull { it.id == i } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The selected VM's implicit (SSH, VNC, audio) host ports, or null before seed. */
    val vmPorts: StateFlow<Triple<Int, Int, Int>?> = selectedDef
        .map { it?.let { d -> Triple(d.sshHostPort, d.vncHostPort, d.audioHostPort) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun updateSelected(transform: (VmDefinition) -> VmDefinition) {
        val def = selectedDef.value ?: return
        registry.update(transform(def))
    }

    /**
     * A VM-specific setting read from the SELECTED VM, falling back to the
     * global default (which also seeds new VMs) only while no VM is selected
     * yet. Writing is routed through [updateSelected], so the Settings screen
     * edits the selected VM's real configuration — not a detached template.
     */
    private fun <T> vmSetting(
        extract: (VmDefinition) -> T,
        fallback: kotlinx.coroutines.flow.Flow<T>,
        initial: T,
    ): StateFlow<T> = combine(selectedDef, fallback) { def, f -> def?.let(extract) ?: f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val vmRamMb: StateFlow<Int> = vmSetting({ it.ramMb }, settingsRepository.vmRamMb, 512)

    val vmCpus: StateFlow<Int> = vmSetting({ it.cpus }, settingsRepository.vmCpus, 1)

    val cpuLimitPercent: StateFlow<Int> =
        vmSetting({ it.cpuLimitPercent }, kotlinx.coroutines.flow.flowOf(0), 0)

    val storageSizeGb: StateFlow<Int> = vmSetting({ it.storageSizeGb }, settingsRepository.storageSizeGb, 2)

    val sshEnabled: StateFlow<Boolean> = vmSetting({ it.sshEnabled }, settingsRepository.sshEnabled, false)

    val storageAccessEnabled: StateFlow<Boolean> =
        vmSetting({ it.storageAccessEnabled }, settingsRepository.storageAccessEnabled, false)

    val usbPassthroughEnabled: StateFlow<Boolean> =
        vmSetting({ it.usbPassthroughEnabled }, settingsRepository.usbPassthroughEnabled, false)

    val bandwidthMbps: StateFlow<Int> = vmSetting({ it.bandwidthMbps }, settingsRepository.bandwidthMbps, 0)

    val loadBalanceEnabled: StateFlow<Boolean> =
        vmSetting({ it.loadBalanceEnabled }, settingsRepository.loadBalanceEnabled, false)

    /** App-wide lock for the Terminal / X11 screens (device credential / biometric). */
    val biometricLockEnabled: StateFlow<Boolean> =
        settingsRepository.biometricLockEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBiometricLockEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setBiometricLockEnabled(value) }
    }

    /** QEMU-only host file share over virtio-9p. */
    val qemuShareEnabled: StateFlow<Boolean> =
        settingsRepository.qemuShareEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Guest acceleration hints (virtio-fs / virgl) carried via kernel cmdline. */
    val virtioFsEnabled: StateFlow<Boolean> =
        settingsRepository.virtioFsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val virglEnabled: StateFlow<Boolean> =
        settingsRepository.virglEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val zramMb: StateFlow<Int> =
        settingsRepository.zramMb.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val guestDistro: StateFlow<String> =
        settingsRepository.guestDistro.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "alpine")

    /** When true, the app + its running VMs survive the app being removed from recents. */
    val keepRunningInBackground: StateFlow<Boolean> =
        settingsRepository.keepRunningInBackground.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setQemuShareEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setQemuShareEnabled(value) }
    }

    fun setVirtioFsEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setVirtioFsEnabled(value) }
    }

    fun setVirglEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setVirglEnabled(value) }
    }

    fun setZramMb(value: Int) {
        viewModelScope.launch { settingsRepository.setZramMb(value) }
    }

    fun setGuestDistro(value: String) {
        viewModelScope.launch { settingsRepository.setGuestDistro(value) }
    }

    fun setKeepRunningInBackground(value: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepRunningInBackground(value) }
    }

    val engineSelection: StateFlow<EngineSelection> =
        vmSetting({ it.backend }, settingsRepository.engineSelection, EngineSelection.AUTO)

    val avfVerboseLogging: StateFlow<Boolean> =
        vmSetting({ it.verboseLogging }, settingsRepository.avfVerboseLogging, false)

    val qemuExtraArgs: StateFlow<String> =
        vmSetting({ it.qemuExtraArgs }, settingsRepository.qemuExtraArgs, SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS)

    val kernelExtraCmdline: StateFlow<String> =
        vmSetting({ it.kernelExtraCmdline }, settingsRepository.kernelExtraCmdline, SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE)

    /**
     * Single combined stream of the 8 form-style rows. SettingsScreen can collect
     * this once with collectAsStateWithLifecycle instead of subscribing 8 times.
     * The original per-flow StateFlows above are kept so callers that want one
     * value (e.g. the About section reading storageSizeGb) don't pay for the
     * combined object on every emit.
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            vmRamMb,
            vmCpus,
            storageSizeGb,
            sshEnabled,
            loadBalanceEnabled,
        ) { ram, cpus, storage, ssh, loadBal ->
            arrayOf(ram, cpus, storage, ssh, loadBal)
        },
        combine(
            storageAccessEnabled,
            qemuExtraArgs,
            kernelExtraCmdline,
            settingsRepository.darkTheme,
            settingsRepository.dynamicColorEnabled,
        ) { storageAccess, qemu, kernel, dark, dyn ->
            arrayOf(storageAccess, qemu, kernel, dark, dyn)
        },
        combine(
            bandwidthMbps,
            engineSelection,
            settingsRepository.language,
            languageManager.language,
        ) { bandwidth, engineSel, lang, sysLang ->
            arrayOf(bandwidth, engineSel, lang, sysLang)
        },
    ) { a, b, c ->
        SettingsUiState(
            vmRamMb = a[0] as Int,
            vmCpus = a[1] as Int,
            storageSizeGb = a[2] as Int,
            sshEnabled = a[3] as Boolean,
            storageAccessEnabled = b[0] as Boolean,
            qemuExtraArgs = b[1] as String,
            kernelExtraCmdline = b[2] as String,
            darkTheme = b[3] as Boolean,
            dynamicColorEnabled = b[4] as Boolean,
            engineSelection = c[1] as EngineSelection,
            language = c[2] as String,
            systemDefaultLanguage = c[3] as String,
            loadBalanceEnabled = a[4] as Boolean,
            bandwidthMbps = c[0] as Int,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    // The Advanced VM-args fields (AdvancedTextSetting) buffer edits locally and
    // commit them on focus loss or composable disposal. Disposal coincides with
    // this ViewModel being cleared on back-navigation, which cancels viewModelScope
    // before a viewModelScope.launch could finish the DataStore write — silently
    // dropping the edit (issue #46). Persist on the application-lifetime scope so
    // the write outlives the screen teardown.
    fun setQemuExtraArgs(value: String) {
        externalScope.launch { updateSelected { it.copy(qemuExtraArgs = value) } }
    }

    fun setKernelExtraCmdline(value: String) {
        externalScope.launch { updateSelected { it.copy(kernelExtraCmdline = value) } }
    }

    fun resetQemuExtraArgs() {
        externalScope.launch { updateSelected { it.copy(qemuExtraArgs = SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS) } }
    }

    fun resetKernelExtraCmdline() {
        externalScope.launch { updateSelected { it.copy(kernelExtraCmdline = SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE) } }
    }

    fun setSshEnabled(value: Boolean) {
        viewModelScope.launch { updateSelected { it.copy(sshEnabled = value) } }
    }

    fun setStorageAccessEnabled(value: Boolean) {
        viewModelScope.launch { updateSelected { it.copy(storageAccessEnabled = value) } }
    }

    val portForwardRules: StateFlow<List<PortForwardRule>> = selectedDef
        .map { it?.portForwards ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Eagerly (like HomeViewModel): WhileSubscribed replays the initial Idle on
    // re-subscription, flashing a "stopped" indicator for a frame when the screen
    // is reopened within the stop-timeout window.
    val vmState: StateFlow<VmState> = registry.selectedEngine
        .flatMapLatest { eng -> eng?.state ?: flowOf(VmState.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(value) }
    }

    fun setDynamicColorEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(value) }
    }

    fun setEngineSelection(value: EngineSelection) {
        viewModelScope.launch { updateSelected { it.copy(backend = value) } }
    }

    fun setAvfVerboseLogging(value: Boolean) {
        viewModelScope.launch { updateSelected { it.copy(verboseLogging = value) } }
    }

    fun setUsbPassthroughEnabled(value: Boolean) {
        viewModelScope.launch { updateSelected { it.copy(usbPassthroughEnabled = value) } }
    }

    suspend fun setLanguage(value: String) {
        Log.d(TAG, "setLanguage: $value")
        languageManager.setLanguage(value)
        Log.d(TAG, "setLanguage done")
    }

    fun setVmRamMb(value: Int) {
        viewModelScope.launch {
            updateSelected { it.copy(ramMb = value, loadBalanceEnabled = false) }
        }
    }

    fun setVmCpus(value: Int) {
        viewModelScope.launch {
            updateSelected { it.copy(cpus = value, loadBalanceEnabled = false) }
        }
    }

    fun setCpuLimitPercent(value: Int) {
        viewModelScope.launch {
            updateSelected { it.copy(cpuLimitPercent = value.coerceIn(0, 99)) }
        }
    }

    fun setBandwidthMbps(value: Int) {
        viewModelScope.launch {
            updateSelected { it.copy(bandwidthMbps = value, loadBalanceEnabled = false) }
        }
    }

    fun setLoadBalanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val def = selectedDef.value
            if (def == null) {
                // No VM selected yet (pre-seed): fall back to the legacy global writes.
                settingsRepository.setLoadBalanceEnabled(enabled)
                if (enabled) {
                    val profile = DeviceResourcePolicy.balancedProfile(context)
                    settingsRepository.setVmRamMb(profile.ramMb)
                    settingsRepository.setVmCpus(profile.cpus)
                    settingsRepository.setBandwidthMbps(profile.bandwidthMbps)
                    val current = settingsRepository.getStorageSizeGbSnapshot()
                    settingsRepository.setStorageSizeGb(maxOf(profile.storageGb, current))
                }
                return@launch
            }
            if (enabled) {
                val profile = DeviceResourcePolicy.balancedProfile(context)
                // The overlay image is grown and never shrunk, so floor balanced
                // storage at the current size.
                registry.update(
                    def.copy(
                        loadBalanceEnabled = true,
                        ramMb = profile.ramMb,
                        cpus = profile.cpus,
                        bandwidthMbps = profile.bandwidthMbps,
                        storageSizeGb = maxOf(profile.storageGb, def.storageSizeGb),
                    )
                )
            } else {
                registry.update(def.copy(loadBalanceEnabled = false))
            }
        }
    }

    fun setTerminalFontSize(value: Int) {
        viewModelScope.launch { settingsRepository.setTerminalFontSize(value) }
    }

    fun setTerminalColorTheme(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalColorTheme(value) }
    }

    fun setTerminalFont(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalFont(value) }
    }

    // "both" expands into separate TCP + UDP rules. Returns true if at least one
    // rule was added; false if every expansion was already present (duplicate).
    // The caller uses the return value to show feedback instead of silently
    // closing the dialog on a duplicate.
    fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp"): Boolean {
        // Backstop the dialog's reserved-port check: these host ports back the
        // implicit loopback-bound X11 forwards and must never be user-bound to
        // 0.0.0.0 (the dialog rejects them with a dedicated message first).
        if (hostPort in PortForwardRepository.RESERVED_HOST_PORTS) return false
        val protos = if (protocol == "both") listOf("tcp", "udp") else listOf(protocol)
        val existing = portForwardRules.value.toSet()
        val toAdd = protos.filter { proto ->
            existing.none { it.hostPort == hostPort && it.protocol == proto }
        }
        if (toAdd.isEmpty()) return false
        viewModelScope.launch {
            updateSelected { def ->
                def.copy(portForwards = def.portForwards + toAdd.map { PortForwardRule(hostPort, guestPort, it) })
            }
        }
        return true
    }

    /** Current LAN IP of the Android device — shown next to port forward rules. Cached for the VM lifetime. */
    val phoneIp: String by lazy { NetworkUtils.localIpv4(context) }

    /** Returns the ID string of the currently-selected VM's backend (e.g. "qemu"/"avf"). */
    fun activeBackendId(): String = registry.selectedInstance.value?.backendId ?: "qemu"

    /**
     * USB passthrough rides the QEMU QMP control socket (add-fd + device_add
     * usb-host); the AVF backend has no QMP channel, so it can never pass a
     * device through. QEMU-only.
     */
    fun isUsbPassthroughAvailable(): Boolean =
        (registry.selectedInstance.value?.backendId ?: "qemu") == "qemu"

    private val _exportError = MutableStateFlow<String?>(null)
    /** One-shot export failure message; clear after showing with [clearExportError]. */
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun clearExportError() { _exportError.value = null }

    fun removePortForward(rule: PortForwardRule) {
        viewModelScope.launch {
            updateSelected { def ->
                def.copy(portForwards = def.portForwards.filterNot {
                    it.hostPort == rule.hostPort && it.protocol == rule.protocol
                })
            }
        }
    }

    fun resetVm() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.clearApplicationUserData()
            }
        }
    }

    /**
     * Build a single log.txt containing everything a maintainer would need
     * to triage a user bug report:
     *   - App + device info
     *   - Current settings snapshot
     *   - VM state + boot stage
     *   - Port forward rules
     *   - App logcat (filtered to this process — only Podsteroid tags)
     *   - QEMU console.log (serial output from the VM, if the VM has run)
     *
     * Written to filesDir/log.txt and shared via the system share sheet.
     */
    fun exportConsoleLogs() {
        viewModelScope.launch {
            try {
                val logFile = withContext(Dispatchers.IO) {
                    val file = File(context.filesDir, "log.txt")
                    file.writeText(buildDiagnosticLog())
                    file
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    logFile,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_diagnostic_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_diagnostic_chooser)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                Log.w(TAG, "Export failed", e)
                _exportError.value = context.getString(
                    R.string.export_diagnostic_failed,
                    e.message ?: context.getString(R.string.unknown_error),
                )
            }
        }
    }

    private suspend fun buildDiagnosticLog(): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val def = selectedDef.value
        val ram = def?.ramMb ?: -1
        val cpus = def?.cpus ?: -1
        val storage = def?.storageSizeGb ?: -1
        val ssh = def?.sshEnabled ?: false
        val storageAccess = def?.storageAccessEnabled ?: false
        val theme = runCatching { settingsRepository.getTerminalColorThemeSnapshot() }.getOrDefault("default")
        val font = runCatching { settingsRepository.getTerminalFontSnapshot() }.getOrDefault("default")
        val qemuExtras = def?.qemuExtraArgs ?: ""
        val kernelExtras = def?.kernelExtraCmdline ?: ""
        val rules = def?.portForwards ?: emptyList()

        buildString {
            appendLine("=== Podsteroid Diagnostic Log ===")
            appendLine("Generated: $timestamp")
            appendLine()

            appendLine("=== App ===")
            appendLine("Version:      ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build type:   ${BuildConfig.BUILD_TYPE}")
            appendLine("App ID:       ${BuildConfig.APPLICATION_ID}")
            appendLine("QEMU version: ${BuildConfig.QEMU_VERSION}")
            appendLine()

            appendLine("=== Device ===")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model:        ${Build.MODEL}")
            appendLine("Device:       ${Build.DEVICE}")
            appendLine("Product:      ${Build.PRODUCT}")
            appendLine("Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs:         ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Fingerprint:  ${Build.FINGERPRINT}")
            appendLine()

            appendLine("=== Settings ===")
            appendLine("VM RAM:             $ram MB")
            appendLine("VM CPUs:            $cpus")
            appendLine("Storage size:       $storage GB")
            appendLine("SSH enabled:        $ssh")
            appendLine("Downloads sharing:  $storageAccess")
            appendLine("Terminal theme:     $theme")
            appendLine("Terminal font:      $font")
            appendLine("QEMU extra args:    $qemuExtras")
            appendLine("Kernel extra cmd:   $kernelExtras")
            appendLine()

            appendLine("=== VM State ===")
            val eng = registry.selectedEngine.value
            appendLine("State:       ${eng?.state?.value ?: "(none selected)"}")
            appendLine("Boot stage:  ${eng?.bootStage?.value?.ifEmpty { "(none)" } ?: "(none)"}")
            val storageFile = File(registry.selectedInstance.value?.workDir ?: context.filesDir, "storage.img")
            appendLine(
                "Storage img: " + if (storageFile.exists())
                    "${storageFile.absolutePath} (${storageFile.length() / (1024 * 1024)} MB)"
                else "(not created)"
            )
            appendLine()

            appendLine("=== Port Forward Rules (${rules.size}) ===")
            if (rules.isEmpty()) {
                appendLine("(none)")
            } else {
                rules.forEach { rule ->
                    appendLine("${rule.protocol.uppercase()}  localhost:${rule.hostPort} -> VM:${rule.guestPort}")
                }
            }
            appendLine()

            val avf = com.tiquasar.podsteroid.engine.avf.AvfDiagnostics.probe(context)
                .copy(activeBackend = activeBackendId())
            val cpus = def?.cpus ?: 1
            val topology = if (cpus <= 1) "ONE_CPU" else "MATCH_HOST (all host cores)"
            appendLine("== AVF ==")
            appendLine("cpu setting = $cpus -> topology $topology")
            append(avf.pretty())
            appendLine("verboseLogging = ${def?.verboseLogging ?: false}")
            appendLine()

            appendLine("=== Engine Diagnostics ===")
            val engineDiag = runCatching { eng?.diagnosticsReport() ?: "" }.getOrDefault("")
            append(if (engineDiag.isBlank()) "(none)\n" else engineDiag)
            appendLine()

            appendLine("=== App Logcat (this process) ===")
            append(captureAppLogcat())
            appendLine()

            appendLine("=== VM Console Log (backend=${activeBackendId()}) ===")
            val consoleFile = File(registry.selectedInstance.value?.workDir ?: context.filesDir, "console.log")
            if (consoleFile.exists() && consoleFile.length() > 0) {
                val text = consoleFile.readText()
                append(text)
                if (!text.endsWith("\n")) appendLine()
            } else {
                appendLine("(no console.log — VM has not been started this session)")
            }
            appendLine()

            appendLine("=== End of Log ===")
        }
    }

    /**
     * Dump this process's own logcat, filtered to Podsteroid's tags (+ `*:S` to
     * silence everything else). Apps can only read their own logs, but that
     * per-pid buffer is otherwise ~95% framework noise (ImeTracker, Surface,
     * InsetsController, ...) that pushes the engine/vsock/boot lines out of the
     * window before we can capture them; the allowlist keeps the signal.
     */
    private fun captureAppLogcat(): String {
        val pid = android.os.Process.myPid().toString()
        val proc = try {
            ProcessBuilder(
                listOf("logcat", "-d", "-v", "time", "--pid=$pid") +
                    APP_LOG_TAGS.map { "$it:V" } + "*:S"
            )
                // Merge stderr into stdout. The old code never read errorStream
                // at all: once logcat filled that pipe buffer the child blocked
                // on write, and the unconditional waitFor() below never returned —
                // hanging diagnostics export entirely.
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return "(failed to capture logcat: ${e.message})\n"
        }
        return try {
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            // Bound the wait. `logcat -d` should exit on its own, but this runs on
            // a caller's coroutine and must never block indefinitely.
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
            val lines = output.trimEnd().lines().filter { it.isNotBlank() }
            when {
                lines.isEmpty() -> "(no Podsteroid-tagged logcat lines)\n"
                lines.size <= MAX_LOGCAT_LINES -> lines.joinToString("\n") + "\n"
                else -> (listOf("... (${lines.size - MAX_LOGCAT_LINES} earlier lines trimmed) ...") +
                    lines.takeLast(MAX_LOGCAT_LINES)).joinToString("\n") + "\n"
            }
        } catch (e: Exception) {
            "(failed to capture logcat: ${e.message})\n"
        } finally {
            // The process was previously never destroyed — it leaked on every
            // diagnostics export.
            runCatching { proc.destroy() }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val MAX_LOGCAT_LINES = 1500
        /**
         * Podsteroid's own logcat tags: the const TAGs plus the two literal-string
         * tags (AvfReflect, PodsteroidVM-err for QEMU stderr). Keep in sync when a
         * new component starts logging, or its lines won't reach the export.
         */
        private val APP_LOG_TAGS = listOf(
            "AudioStreamer", "AvfEngine", "AvfReflect", "ConsoleFanout",
            "EngineFactory", "PodsteroidApp", "PodsteroidService", "PodsteroidVM-err",
            "QemuEngine", "QmpClient", "SettingsViewModel", "TerminalVM",
            "VmInstance", "VmRegistry", "VsockControlChannel", "VsockPortForwarder",
        )
    }
}
