/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Shared wire helpers for the guest -> Android host bridge. Free-text fields
 * (notification title/body, the forward listing, error messages) are standard
 * base64 so UTF-8, spaces, and newlines survive the line-oriented protocol.
 * java.util.Base64 (not android.util.Base64) keeps the dispatcher unit-testable.
 */
package com.tiquasar.podsteroid.engine.hostbridge

import java.util.Base64

object HostProtocol {
    const val PRIO_LOW = "low"
    const val PRIO_NORMAL = "normal"
    const val PRIO_HIGH = "high"
    val VALID_PRIORITIES = setOf(PRIO_LOW, PRIO_NORMAL, PRIO_HIGH)

    /** Guest -> Android push of the guest's network identity (Tailscale IP, etc.). */
    const val NETINFO = "netinfo"

    /** Network identity pushed by a guest via `NETINFO`. */
    data class NetInfo(
        val vmId: String,
        val tailscaleIp: String?,
        val magicDns: String?,
        val guestIp: String?,
    )

    /** A sink that receives `NETINFO` pushes, keyed by VM id. The local-VM
     *  fleet collector registers here so the dispatcher can route pushes without
     *  a hard dependency on the fleet code. */
    object NetInfoRegistry {
        private val sinks = mutableMapOf<String, (NetInfo) -> Unit>()
        fun register(vmId: String, sink: (NetInfo) -> Unit) { sinks[vmId] = sink }
        fun unregister(vmId: String) { sinks.remove(vmId) }
        fun dispatch(info: NetInfo) { sinks[info.vmId]?.invoke(info) }
    }

    /**
     * Parses `NETINFO <vmId> <b64json>`. The JSON is intentionally a flat object
     * parsed with regex (no JSON dep) of the form
     * `{"tailscaleIp":"...","magicDns":"...","guestIp":"..."}` (empty string = absent).
     */
    fun parseNetInfo(parts: List<String>): NetInfo? {
        if (parts.size < 3) return null
        val vmId = parts[1]
        val raw = dec(parts[2]) ?: return null
        fun pick(key: String): String? {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
            return m?.ifBlank { null }
        }
        return NetInfo(
            vmId = vmId,
            tailscaleIp = pick("tailscaleIp"),
            magicDns = pick("magicDns"),
            guestIp = pick("guestIp"),
        )
    }

    fun enc(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** Decodes base64; returns null on malformed input rather than throwing. */
    fun dec(s: String): String? = try {
        String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun ok(payload: String? = null): String = if (payload == null) "OK" else "OK $payload"
    fun err(message: String): String = "ERR ${enc(message)}"
}
