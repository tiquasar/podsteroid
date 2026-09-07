/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Per-VM source of truth for "server mode" (headless). Both the guest bridge
 * (HEADLESS on/off) and the in-app toggle write here, keyed by VM id;
 * MainActivity observes the SELECTED VM's flag to draw the black overlay.
 * App-scoped singleton so the map is shared across VMs and the UI.
 */
package com.tiquasar.podsteroid.engine.hostbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeadlessModeManager @Inject constructor() {
    private val _active = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val active: StateFlow<Map<String, Boolean>> = _active.asStateFlow()

    fun setActive(vmId: String, value: Boolean) {
        _active.value = if (value) _active.value + (vmId to true) else _active.value - vmId
    }

    fun isActive(vmId: String): Boolean = _active.value[vmId] == true
}
