/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Durable store for remote VMs the user has created (DataStore, JSON blob).
 * Live state is overlaid at runtime from `virsh list` / `domstats`.
 */
package com.tiquasar.podsteroid.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.remoteVmsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "remote_vms",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class RemoteVmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val data = context.remoteVmsDataStore
    private val key = stringPreferencesKey("vms")

    val vms: Flow<List<RemoteVm>> = data.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> RemoteVm.listFromJson(prefs[key] ?: "") }

    suspend fun snapshot(): List<RemoteVm> = vms.first()

    suspend fun get(serverId: String, uuid: String): RemoteVm? =
        snapshot().firstOrNull { it.serverId == serverId && it.uuid == uuid }

    suspend fun put(vm: RemoteVm) {
        val list = snapshot().filterNot { it.serverId == vm.serverId && it.uuid == vm.uuid } + vm
        data.edit { it[key] = RemoteVm.listToJson(list) }
    }

    suspend fun putAll(vms: List<RemoteVm>) {
        val byKey = vms.associateBy { it.serverId to it.uuid }
        val kept = snapshot().filterNot { byKey.containsKey(it.serverId to it.uuid) }
        data.edit { it[key] = RemoteVm.listToJson(kept + vms) }
    }

    suspend fun remove(serverId: String, uuid: String) {
        val list = snapshot().filterNot { it.serverId == serverId && it.uuid == uuid }
        data.edit { it[key] = RemoteVm.listToJson(list) }
    }
}
