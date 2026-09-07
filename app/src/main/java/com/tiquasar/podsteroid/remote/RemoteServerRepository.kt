/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Durable store for registered remote servers (DataStore, JSON blob).
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

internal val Context.remoteServersDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "remote_servers",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class RemoteServerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val data = context.remoteServersDataStore
    private val key = stringPreferencesKey("servers")

    val servers: Flow<List<RemoteServer>> = data.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> RemoteServer.listFromJson(prefs[key] ?: "") }

    suspend fun snapshot(): List<RemoteServer> = servers.first()

    suspend fun get(id: String): RemoteServer? = snapshot().firstOrNull { it.id == id }

    suspend fun put(server: RemoteServer) {
        val list = snapshot().filter { it.id != server.id } + server
        data.edit { it[key] = RemoteServer.listToJson(list) }
    }

    suspend fun remove(id: String) {
        val list = snapshot().filter { it.id != id }
        data.edit { it[key] = RemoteServer.listToJson(list) }
    }
}
