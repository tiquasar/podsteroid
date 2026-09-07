/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Durable store for the set of VM definitions. Backed by a dedicated
 * preferences DataStore ("vms") holding a JSON blob of VmDefinition plus the
 * currently-selected VM id. Port-forward rules are per-VM (stored inside each
 * definition) rather than in the legacy global PortForwardRepository, which now
 * only seeds the default VM and new-VM defaults.
 */
package com.tiquasar.podsteroid.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.vmDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vms",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class VmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
) {
    companion object {
        private val KEY_SELECTED_ID = stringPreferencesKey("selected_vm_id")
        private val KEY_DEFS = stringPreferencesKey("vm_definitions")
        private val KEY_SEEDED = booleanPreferencesKey("vms_seeded")

        /** Legacy/default VM keeps id "default" so its filesDir root paths survive. */
        const val DEFAULT_VM_ID = "default"
        const val DEFAULT_VM_NAME = "PodSteroid"

        private const val SSH_BASE = 9922
        private const val VNC_BASE = 5900
        private const val AUDIO_BASE = 4713
        private const val PORT_STEP = 10
    }

    private val data = context.vmDataStore

    val definitions: Flow<List<VmDefinition>> = data.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> VmDefinition.listFromJson(prefs[KEY_DEFS] ?: "") }

    val selectedId: Flow<String?> = data.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_SELECTED_ID] }

    suspend fun snapshot(): List<VmDefinition> = definitions.first()

    suspend fun get(id: String): VmDefinition? = snapshot().firstOrNull { it.id == id }

    suspend fun selectedSnapshot(): String? = selectedId.first()

    /**
     * Seed a single default VM on first run, capturing the legacy global
     * settings/port-forward values so an existing install's disk + config carry
     * over unchanged. Idempotent (guarded by KEY_SEEDED).
     */
    suspend fun ensureSeeded() {
        val seeded = data.data.first()[KEY_SEEDED] ?: false
        if (seeded) return
        data.edit { prefs ->
            // Do not pre-create a default VM on a fresh install; the user creates
            // VMs explicitly from the UI. Just record that seeding has run.
            prefs[KEY_SEEDED] = true
        }
    }

    suspend fun add(def: VmDefinition) {
        data.edit { prefs ->
            val current = VmDefinition.listFromJson(prefs[KEY_DEFS] ?: "")
            prefs[KEY_DEFS] = VmDefinition.listToJson(current.filterNot { it.id == def.id } + def)
            if (prefs[KEY_SELECTED_ID] == null) prefs[KEY_SELECTED_ID] = def.id
        }
    }

    suspend fun update(def: VmDefinition) = add(def)

    suspend fun remove(id: String) {
        data.edit { prefs ->
            val current = VmDefinition.listFromJson(prefs[KEY_DEFS] ?: "")
            prefs[KEY_DEFS] = VmDefinition.listToJson(current.filterNot { it.id == id })
            if (prefs[KEY_SELECTED_ID] == id) {
                val next = current.firstOrNull { it.id != id }?.id
                if (next != null) prefs[KEY_SELECTED_ID] = next else prefs.remove(KEY_SELECTED_ID)
            }
        }
    }

    suspend fun select(id: String) {
        data.edit { prefs -> prefs[KEY_SELECTED_ID] = id }
    }

    suspend fun newId(): String = UUID.randomUUID().toString()

    /**
     * Allocate a unique (ssh, vnc, audio) host-port triple that no existing VM
     * uses, so VMs can run simultaneously without host-port collisions. The
     * default VM occupies the base ports; each additional VM steps by PORT_STEP.
     */
    suspend fun nextPortSet(): Triple<Int, Int, Int> {
        val used = snapshot().flatMap { listOf(it.sshHostPort, it.vncHostPort, it.audioHostPort) }.toSet()
        fun firstFree(base: Int): Int {
            var p = base
            while (p in used) p += PORT_STEP
            return p
        }
        return Triple(firstFree(SSH_BASE), firstFree(VNC_BASE), firstFree(AUDIO_BASE))
    }
}
