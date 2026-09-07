/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * The multi-VM manager. Owns one VmInstance (engine + runtime) per defined VM,
 * reconciles the DataStore definition list against the live instance map, and
 * routes start/stop/create/delete/select. Replaces the old single-VM
 * EngineHolder. Instance-map mutations run on a single-threaded scope so they
 * never race.
 */
package com.tiquasar.podsteroid.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tiquasar.podsteroid.PodsteroidApplication
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.data.repository.PortForwardRule
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import com.tiquasar.podsteroid.data.repository.VmDefinition
import com.tiquasar.podsteroid.data.repository.VmRepository
import com.tiquasar.podsteroid.engine.hostbridge.AndroidNotificationPoster
import com.tiquasar.podsteroid.engine.hostbridge.HeadlessModeManager
import com.tiquasar.podsteroid.engine.hostbridge.HostProtocol
import com.tiquasar.podsteroid.util.NetworkUtils
import com.tiquasar.podsteroid.util.DeviceResourcePolicy
import com.tiquasar.podsteroid.x11.X11Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class VmRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineFactory: EngineFactory,
    private val vmRepository: VmRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationPoster: AndroidNotificationPoster,
    private val headlessModeManager: HeadlessModeManager,
) {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _instances = MutableStateFlow<Map<String, VmInstance>>(emptyMap())
    val instances: StateFlow<Map<String, VmInstance>> = _instances.asStateFlow()

    // Definitions are published ONLY after syncInstances has created their live
    // instances, so a freshly-created VM is startable the instant it appears in
    // the Home list (no UI/instance race).
    private val _definitions = MutableStateFlow<List<VmDefinition>>(emptyList())
    val definitions: StateFlow<List<VmDefinition>> = _definitions.asStateFlow()

    // In-memory selected id so selection is synchronous for UI/terminal binding;
    // the DataStore is the backing store, seeded once below and written on select.
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val selectedInstance: StateFlow<VmInstance?> = combine(
        selectedId, _instances,
    ) { id, map -> id?.let { map[it] } }
        .stateIn(syncScope, SharingStarted.Eagerly, null)

    val selectedEngine: StateFlow<VmEngine?> = selectedInstance
        .map { it?.engine }
        .stateIn(syncScope, SharingStarted.Eagerly, null)

    /** Live per-VM state map, keyed by VM id. Drives the service notification. */
    val allStates: StateFlow<Map<String, VmState>> = _instances
        .flatMapLatest { map ->
            if (map.isEmpty()) flowOf(emptyMap<String, VmState>())
            else combine(map.values.map { inst -> inst.state.map { inst.id to it } }) { arr ->
                arr.associate { it.first to it.second }
            }
        }
        .stateIn(syncScope, SharingStarted.Eagerly, emptyMap())

    /** Live per-VM boot-stage map, keyed by VM id. */
    val bootStages: StateFlow<Map<String, String>> = _instances
        .flatMapLatest { map ->
            if (map.isEmpty()) flowOf(emptyMap<String, String>())
            else combine(map.values.map { inst -> inst.bootStage.map { inst.id to it } }) { arr ->
                arr.associate { it.first to it.second }
            }
        }
        .stateIn(syncScope, SharingStarted.Eagerly, emptyMap())

    init {
        syncScope.launch {
            runCatching { vmRepository.ensureSeeded() }
                .onFailure { Log.w(TAG, "seed failed", it) }
            _selectedId.value = runCatching { vmRepository.selectedSnapshot() }.getOrNull()
            vmRepository.definitions.collect { defs ->
                syncInstances(defs)
                _definitions.value = defs
            }
        }
    }

    private fun workDirFor(id: String): File =
        if (id == VmRepository.DEFAULT_VM_ID) context.filesDir
        else File(context.filesDir, "vms/$id").apply { mkdirs() }

    private fun avfNameFor(id: String): String =
        if (id == VmRepository.DEFAULT_VM_ID) "podsteroid"
        else "podsteroid-" + id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun resolvedBackendId(sel: EngineSelection): String =
        if (engineFactory.resolvesToAvf(sel)) "avf" else "qemu"

    /** Ensure the live instance map mirrors the persisted definition list. */
    private fun syncInstances(defs: List<VmDefinition>) {
        val byId = defs.associateBy { it.id }
        val current = _instances.value.toMutableMap()

        for (id in current.keys.toList()) {
            if (!byId.containsKey(id)) current.remove(id)?.dispose()
        }

        for (def in defs) {
            val existing = current[def.id]
            val desired = resolvedBackendId(def.backend)
            val active = existing?.state?.value is VmState.Running ||
                existing?.state?.value is VmState.Starting
            // Recreate the engine when the backend selection resolves to a
            // different concrete backend. Deferred while the VM is active so a
            // running VM is never killed by a settings edit; the swap lands on
            // the next start.
            val needsRecreate = existing != null && !active && existing.backendId != desired
            if (existing == null || needsRecreate) {
                current[def.id]?.dispose()
                val spec = VmInstanceSpec(
                    id = def.id,
                    workDir = workDirFor(def.id),
                    avfName = avfNameFor(def.id),
                    displayName = def.name,
                )
                val engine = engineFactory.create(spec, def.backend)
                current[def.id] = VmInstance(def.id, engine, def, spec.workDir, buildDeps(def.id))
            } else {
                existing.updateDefinition(def)
            }
        }
        _instances.value = current
    }

    private fun buildDeps(id: String): VmRuntimeDeps = VmRuntimeDeps(
        notificationPoster = notificationPoster,
        headlessModeManager = headlessModeManager,
        appContext = context,
        openUrl = { url -> openUrl(url) },
        power = { action -> power(id, action) },
        persistForwards = { rules -> persistForwards(id, rules) },
    )

    private suspend fun openUrl(url: String): String {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https")) {
            return HostProtocol.err("only http/https URLs are allowed")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            HostProtocol.ok()
        } catch (e: Throwable) {
            HostProtocol.err("no app available to open this URL")
        }
    }

    private suspend fun persistForwards(id: String, rules: List<PortForwardRule>) {
        val def = vmRepository.get(id) ?: return
        vmRepository.update(def.copy(portForwards = rules))
    }

    private suspend fun power(id: String, action: String): String {
        val instance = _instances.value[id] ?: return HostProtocol.err("no such VM")
        return when (action) {
            "status" -> HostProtocol.ok(when (instance.state.value) {
                is VmState.Idle -> "idle"
                is VmState.Starting -> "starting"
                is VmState.Running -> "running"
                is VmState.Stopped -> "stopped"
                is VmState.Error -> "error"
            })
            "stop" -> {
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ stop(id) }, 300)
                HostProtocol.ok()
            }
            "restart" -> { scheduleRestart(id); HostProtocol.ok() }
            else -> HostProtocol.err("usage: stop|restart|status")
        }
    }

    private fun scheduleRestart(id: String) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed({
            stop(id)
            var tries = 0
            val poll = object : Runnable {
                override fun run() {
                    val s = _instances.value[id]?.state?.value
                    val terminal = s is VmState.Stopped || s is VmState.Error
                    when {
                        terminal -> startService(id)
                        tries++ >= 40 -> {
                            Log.w(TAG, "restart: VM $id did not reach stopped; starting anyway")
                            startService(id)
                        }
                        else -> main.postDelayed(this, 250)
                    }
                }
            }
            main.postDelayed(poll, 500)
        }, 300)
    }

    // ── Public imperative API ──────────────────────────────────────────────

    suspend fun create(
        name: String,
        backend: EngineSelection,
        ramMb: Int,
        cpus: Int,
        storageSizeGb: Int,
        sshEnabled: Boolean,
        storageAccessEnabled: Boolean,
        usbPassthroughEnabled: Boolean,
        bandwidthMbps: Int,
        cpuLimitPercent: Int = 0,
        guestDistro: String = "alpine",
    ): String {
        val id = vmRepository.newId()
        val (ssh, vnc, audio) = vmRepository.nextPortSet()
        val def = VmDefinition(
            id = id,
            name = name,
            backend = backend,
            ramMb = ramMb,
            cpus = cpus,
            cpuLimitPercent = cpuLimitPercent.coerceIn(0, 99),
            storageSizeGb = storageSizeGb,
            sshEnabled = sshEnabled,
            storageAccessEnabled = storageAccessEnabled,
            usbPassthroughEnabled = usbPassthroughEnabled,
            bandwidthMbps = bandwidthMbps,
            qemuExtraArgs = settingsRepository.getQemuExtraArgsSnapshot(),
            kernelExtraCmdline = settingsRepository.getKernelExtraCmdlineSnapshot(),
            x11Dpi = settingsRepository.getX11DpiSnapshot(),
            guestDistro = guestDistro,
            sshHostPort = ssh,
            vncHostPort = vnc,
            audioHostPort = audio,
            portForwards = emptyList(),
        )
        vmRepository.add(def)
        return id
    }

    suspend fun update(def: VmDefinition) = vmRepository.update(def)

    suspend fun rename(id: String, name: String) {
        val def = vmRepository.get(id) ?: return
        vmRepository.update(def.copy(name = name))
    }

    /** Duplicate a VM: same config, fresh id + host ports, empty port-forwards. */
    suspend fun clone(id: String): String? {
        val src = vmRepository.get(id) ?: return null
        val newId = vmRepository.newId()
        val (ssh, vnc, audio) = vmRepository.nextPortSet()
        val copy = src.copy(
            id = newId,
            name = "${src.name} (copy)",
            sshHostPort = ssh,
            vncHostPort = vnc,
            audioHostPort = audio,
            portForwards = emptyList(),
        )
        vmRepository.add(copy)
        return newId
    }

    suspend fun delete(id: String) {
        stop(id)
        vmRepository.remove(id)
        withContext(Dispatchers.IO) { workDirFor(id).deleteRecursively() }
    }

    suspend fun select(id: String) {
        _selectedId.value = id // synchronous, so terminal/X11 bind to the right engine immediately
        vmRepository.select(id)
    }

    /**
     * Start a VM: await asset extraction, build VmConfig from its definition,
     * launch. Returns null on success, or a user-facing failure message.
     *
     * VMs are fully concurrent: each VM runs its own engine against its own
     * working directory, port set, and (for AVF) unique framework name. On
     * memory-constrained devices the guest may OOM or the AVF framework may
     * reject a second VM — that surfaces as an Error on that VM, not here.
     */
    suspend fun start(id: String): String? {
        var instance = _instances.value[id]
        if (instance == null) {
            // Defensive: recreate the instance map from the persisted list in case
            // the sync collector hasn't caught up yet (freshly seeded VM).
            syncInstances(vmRepository.snapshot())
            instance = _instances.value[id]
        }
        if (instance == null) return context.getString(R.string.vm_not_found)
        val def = instance.definition
        // Overcommit guard: refuse to start when this VM's RAM plus the RAM of
        // every already-running VM would exceed the device's physical RAM — a
        // QEMU guest that OOMs the phone kills the whole app, not just the VM.
        val committedMb = _instances.value.values
            .filter { it.id != id }
            .filter { it.state.value is VmState.Running || it.state.value is VmState.Starting }
            .sumOf { it.definition.ramMb }
        val totalMb = DeviceResourcePolicy.deviceTotalRamMb(context)
        if (committedMb + def.ramMb > totalMb) {
            Log.w(TAG, "refusing overcommit: ${committedMb + def.ramMb}MB > ${totalMb}MB device RAM")
            return context.getString(R.string.vm_overcommit, def.ramMb, totalMb - committedMb)
        }
        if (engineFactory.resolvesToAvf(def.backend) &&
            _instances.value.any { (oid, o) -> oid != id && o.backendId == "avf" &&
                (o.state.value is VmState.Running || o.state.value is VmState.Starting) }
        ) {
            Log.i(TAG, "starting an AVF VM while another AVF VM is active (resource-limited)")
        }
        val app = context.applicationContext as? PodsteroidApplication
        app?.awaitAssetsReady()
        // Fail loudly (not a guest-less boot) if assets never extracted.
        app?.assetError()?.let { return context.getString(R.string.asset_extraction_failed, it) }
        if (!File(context.filesDir, "vmlinuz-virt").exists() ||
            !File(context.filesDir, "initrd.img").exists()) {
            return context.getString(R.string.asset_extraction_missing)
        }

        val rules = def.portForwards.toMutableList()
        if (def.sshEnabled && rules.none { it.hostPort == def.sshHostPort }) {
            rules.add(PortForwardRule(def.sshHostPort, 22, "tcp"))
        }
        if (rules.none { it.hostPort == def.vncHostPort }) {
            rules.add(PortForwardRule(def.vncHostPort, X11Constants.VNC_PORT, "tcp", loopbackOnly = true))
        }
        if (rules.none { it.hostPort == def.audioHostPort }) {
            rules.add(PortForwardRule(def.audioHostPort, X11Constants.AUDIO_PORT, "tcp", loopbackOnly = true))
        }

        val config = VmConfig(
            ramMb = def.ramMb,
            cpus = def.cpus,
            sshEnabled = def.sshEnabled,
            androidIp = NetworkUtils.localIpv4(context),
            storageSizeGb = def.storageSizeGb,
            storageAccessEnabled = def.storageAccessEnabled,
            qemuExtraArgs = def.qemuExtraArgs,
            kernelExtraCmdline = def.kernelExtraCmdline,
            verboseLogging = def.verboseLogging,
            x11Dpi = def.x11Dpi,
            guestDistro = def.guestDistro,
            usbPassthroughEnabled = def.usbPassthroughEnabled,
            bandwidthMbps = def.bandwidthMbps,
            cpuLimitPercent = def.cpuLimitPercent,
        )
        return try {
            instance.start(rules, config)
            null
        } catch (e: Throwable) {
            Log.e(TAG, "VM start failed", e)
            context.getString(R.string.vm_start_failed, e.message ?: e.toString())
        }
    }

    fun stop(id: String) {
        _instances.value[id]?.stop()
    }

    /** Start a VM via the foreground service (handles notification + wakelock). */
    private fun startService(id: String) {
        com.tiquasar.podsteroid.service.PodsteroidService.start(context, id)
    }

    fun instance(id: String): VmInstance? = _instances.value[id]

    fun engine(id: String): VmEngine? = _instances.value[id]?.engine

    companion object {
        private const val TAG = "VmRegistry"
    }
}
