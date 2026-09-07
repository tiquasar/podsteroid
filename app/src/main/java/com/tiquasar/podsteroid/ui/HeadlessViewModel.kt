/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Exposes the shared server-mode state to MainActivity so it can draw the black
 * overlay and drop window brightness, and lets the overlay turn it off.
 */
package com.tiquasar.podsteroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.hostbridge.HeadlessModeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HeadlessViewModel @Inject constructor(
    private val headlessModeManager: HeadlessModeManager,
    private val registry: VmRegistry,
) : ViewModel() {
    val active: StateFlow<Boolean> = combine(
        registry.selectedId, headlessModeManager.active,
    ) { id, map -> id?.let { map[it] == true } ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun disable() {
        registry.selectedId.value?.let { headlessModeManager.setActive(it, false) }
    }
}
