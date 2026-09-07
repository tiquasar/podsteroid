/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Quick Settings tile that toggles the default VM (starts it if stopped,
 * stops it if running). Reflects live state from the registry.
 */
package com.tiquasar.podsteroid.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.tiquasar.podsteroid.R
import com.tiquasar.podsteroid.data.repository.VmRepository
import com.tiquasar.podsteroid.engine.VmRegistry
import com.tiquasar.podsteroid.engine.VmState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class VmTileService : TileService() {

    @Inject lateinit var registry: VmRegistry

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        val id = VmRepository.DEFAULT_VM_ID
        if (isRunning(id)) {
            PodsteroidService.stop(this, id)
        } else {
            PodsteroidService.start(this, id)
        }
        // Optimistic: real state arrives on the next listening cycle.
        refresh()
    }

    private fun isRunning(id: String): Boolean {
        val s = registry.allStates.value[id] ?: VmState.Idle
        return s == VmState.Running || s == VmState.Starting
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = isRunning(VmRepository.DEFAULT_VM_ID)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_power)
        tile.updateTile()
    }
}
