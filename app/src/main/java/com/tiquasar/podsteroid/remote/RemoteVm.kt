/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * A VM created on a remote libvirt server. Carries its persisted spec plus
 * (optionally) discovered network addresses. The uuid is the libvirt domain
 * UUID; live state is refreshed from `virsh list`/`domstats`.
 */
package com.tiquasar.podsteroid.remote

import org.json.JSONObject

enum class RemoteVmState { PENDING, RUNNING, PAUSED, SHUTOFF, OTHER, UNKNOWN }

data class RemoteVm(
    val serverId: String,
    val uuid: String,
    val name: String,
    val state: RemoteVmState = RemoteVmState.UNKNOWN,
    val guestDistro: String = "debian",
    val vcpus: Int = 2,
    val memMb: Int = 2048,
    val diskGb: Int = 20,
    val sshEnabled: Boolean = true,
    val containerRuntime: ContainerRuntime = ContainerRuntime.BOTH,
    val tailscaleEnabled: Boolean = true,
    /** SecretStore alias for an optional app-stored Tailscale auth key. */
    val tailscaleAuthKeyRef: String? = null,
    val guestIp: String? = null,
    val tailscaleIp: String? = null,
    val magicDns: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("serverId", serverId)
        put("uuid", uuid)
        put("name", name)
        put("state", state.name)
        put("guestDistro", guestDistro)
        put("vcpus", vcpus)
        put("memMb", memMb)
        put("diskGb", diskGb)
        put("sshEnabled", sshEnabled)
        put("containerRuntime", containerRuntime.name)
        put("tailscaleEnabled", tailscaleEnabled)
        put("tailscaleAuthKeyRef", tailscaleAuthKeyRef)
        put("guestIp", guestIp)
        put("tailscaleIp", tailscaleIp)
        put("magicDns", magicDns)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): RemoteVm = RemoteVm(
            serverId = o.optString("serverId"),
            uuid = o.optString("uuid"),
            name = o.optString("name"),
            state = runCatching { RemoteVmState.valueOf(o.optString("state", "UNKNOWN")) }
                .getOrDefault(RemoteVmState.UNKNOWN),
            guestDistro = o.optString("guestDistro", "debian"),
            vcpus = o.optInt("vcpus", 2),
            memMb = o.optInt("memMb", 2048),
            diskGb = o.optInt("diskGb", 20),
            sshEnabled = o.optBoolean("sshEnabled", true),
            containerRuntime = ContainerRuntime.fromToken(o.optString("containerRuntime", "BOTH")),
            tailscaleEnabled = o.optBoolean("tailscaleEnabled", true),
            tailscaleAuthKeyRef = o.optString("tailscaleAuthKeyRef").takeIf { it.isNotBlank() },
            guestIp = o.optString("guestIp").takeIf { it.isNotBlank() },
            tailscaleIp = o.optString("tailscaleIp").takeIf { it.isNotBlank() },
            magicDns = o.optString("magicDns").takeIf { it.isNotBlank() },
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )

        fun listToJson(list: List<RemoteVm>): String =
            org.json.JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String): List<RemoteVm> = runCatching {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
