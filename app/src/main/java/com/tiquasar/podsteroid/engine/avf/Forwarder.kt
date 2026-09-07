/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Common lifecycle for the AVF per-rule forwarders so AvfEngine can hold TCP
 * stream forwarders and UDP datagram forwarders in one map keyed by vsock port.
 */
package com.tiquasar.podsteroid.engine.avf

internal interface Forwarder {
    fun start()
    fun close()
}
