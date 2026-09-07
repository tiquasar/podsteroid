/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Container runtime selection for a remote VM. Mirrors the on-device VM's
 * container story: Docker, k3s, or both.
 */
package com.tiquasar.podsteroid.remote

enum class ContainerRuntime {
    NONE,
    DOCKER,
    K3S,
    BOTH,
    ;

    companion object {
        fun fromToken(token: String?): ContainerRuntime = when (token) {
            "NONE" -> NONE
            "DOCKER" -> DOCKER
            "K3S" -> K3S
            "BOTH" -> BOTH
            else -> BOTH
        }
    }
}
