/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Single source of truth for the guest distros the app can boot. Both the
 * per-VM editor (HomeScreen) and the global default (SettingsScreen) iterate
 * this list so a new distro only needs to be added in one place.
 */
package com.tiquasar.podsteroid.engine

object GuestDistro {
    const val ALPINE = "alpine"
    const val DEBIAN = "debian"

    /** Token -> display label. The app is English-only (zh removed). */
    val OPTIONS: List<Pair<String, String>> = listOf(
        ALPINE to "Alpine",
        DEBIAN to "Debian",
    )

    fun isKnown(token: String): Boolean = token == ALPINE || token == DEBIAN
}
