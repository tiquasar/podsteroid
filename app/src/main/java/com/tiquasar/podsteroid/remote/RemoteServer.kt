/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * A registered libvirt/KVM server the user can drive from the app. Secrets are
 * NOT stored here — only a [AuthKind] selector; the actual password / private
 * key / passphrase live encrypted in the Android Keystore under aliases derived
 * from [id] (see RemoteServerManager). The host key, once pinned by
 * accept-first-use, is stored inline so re-connections can verify it.
 */
package com.tiquasar.podsteroid.remote

import org.json.JSONObject

enum class AuthKind { PASSWORD, KEY }

/** What kind of host a [RemoteServer] is. [LIBVIRT] assumes a hypervisor is
 *  already present; [LINUX] is a plain Linux box the app provisions into a
 *  libvirt/KVM host (installs the stack) so VMs can be created on it. */
enum class ServerKind { LIBVIRT, LINUX }

data class RemoteServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authKind: AuthKind = AuthKind.PASSWORD,
    /** Guest CPU architecture — selects the matching cloud image + Tailscale binary. */
    val arch: String = "amd64",
    /** libvirt network the VM attaches to. */
    val network: String = "default",
    /** [ServerKind.LINUX] servers are provisioned into libvirt/KVM on first use. */
    val kind: ServerKind = ServerKind.LIBVIRT,
    /** True when PodSteroid installed the libvirt stack itself (so Delete can revert it). */
    val provisionedByUs: Boolean = false,
    /** Encrypted secret (password, or private-key PEM) — ciphertext token from [SecretStore]. */
    val secretToken: String? = null,
    /** Encrypted key passphrase (KEY auth only). */
    val passphraseToken: String? = null,
    /** Pinned host-key fingerprint (SHA256:....), set after first successful connect. */
    val hostKeyFingerprint: String? = null,
    /** Optional override of the base cloud image used by createVm. */
    val baseImageUrl: String? = null,
    /** Index into the fixed server-badge palette; VMs inherit this badge color. */
    val colorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authKind", authKind.name)
        put("arch", arch)
        put("network", network)
        put("kind", kind.name)
        put("provisionedByUs", provisionedByUs)
        put("secretToken", secretToken)
        put("passphraseToken", passphraseToken)
        put("hostKeyFingerprint", hostKeyFingerprint)
        put("baseImageUrl", baseImageUrl)
        put("colorIndex", colorIndex)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): RemoteServer = RemoteServer(
            id = o.optString("id"),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            username = o.optString("username"),
            authKind = runCatching { AuthKind.valueOf(o.optString("authKind", "PASSWORD")) }
                .getOrDefault(AuthKind.PASSWORD),
            arch = o.optString("arch", "amd64"),
            network = o.optString("network", "default"),
            kind = runCatching { ServerKind.valueOf(o.optString("kind", "LIBVIRT")) }
                .getOrDefault(ServerKind.LIBVIRT),
            provisionedByUs = o.optBoolean("provisionedByUs", false),
            secretToken = o.optString("secretToken").takeIf { it.isNotBlank() },
            passphraseToken = o.optString("passphraseToken").takeIf { it.isNotBlank() },
            hostKeyFingerprint = o.optString("hostKeyFingerprint").takeIf { it.isNotBlank() },
            baseImageUrl = o.optString("baseImageUrl").takeIf { it.isNotBlank() },
            colorIndex = o.optInt("colorIndex", 0),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )

        fun listToJson(list: List<RemoteServer>): String =
            org.json.JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String): List<RemoteServer> = runCatching {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
