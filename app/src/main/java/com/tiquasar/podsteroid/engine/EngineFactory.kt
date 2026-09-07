/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Builds a concrete VmEngine for one VM instance. Engines are no longer
 * singletons: each VM gets its own QemuEngine/AvfEngine parameterized by a
 * VmInstanceSpec. The AVF capability probe (a binder IPC) is memoized once per
 * process so creating N instances costs a single probe.
 */
package com.tiquasar.podsteroid.engine

import android.content.Context
import com.tiquasar.podsteroid.data.repository.SettingsRepository
import com.tiquasar.podsteroid.engine.avf.AvfCapabilities
import com.tiquasar.podsteroid.engine.avf.AvfDiagnostics
import com.tiquasar.podsteroid.engine.avf.AvfEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val probe by lazy { AvfDiagnostics.probe(context) }
    private val capsChoice by lazy { AvfCapabilities.choose(probe.capabilitiesRaw) }

    private val avfUsable by lazy {
        probe.featureSupported &&
            probe.managePermissionGranted &&
            probe.customPermissionGranted &&
            probe.serviceReachable &&
            probe.customVmConfigSupported &&
            capsChoice !is AvfCapabilities.ProtectedVmChoice.Unsupported
    }

    /** True when the requested selection resolves to the AVF backend here. */
    fun resolvesToAvf(selection: EngineSelection): Boolean = when {
        selection == EngineSelection.QEMU -> false
        selection == EngineSelection.AVF -> avfUsable
        else -> avfUsable // AUTO
    }

    /**
     * Construct a fresh engine for [spec]. Never null; falls back to QEMU when
     * AVF is requested but unavailable (mirrors the old EngineHolder.pick logic).
     */
    fun create(spec: VmInstanceSpec, selection: EngineSelection): VmEngine {
        val useAvf = resolvesToAvf(selection)
        if (useAvf) {
            android.util.Log.i(TAG, "engine: ${spec.displayName} (${spec.id}) → avf (${spec.avfName})")
            return AvfEngine(context, settingsRepository, spec)
        }
        android.util.Log.i(TAG, "engine: ${spec.displayName} (${spec.id}) → qemu")
        return QemuEngine(context, settingsRepository, spec)
    }

    companion object {
        private const val TAG = "EngineFactory"
    }
}
