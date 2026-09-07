/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 *
 * Per-VM identity that parameterizes a concrete engine instance. Every VM gets
 * its own working directory (disk + sockets + console log) and, for AVF, a
 * unique framework VM name. Shared read-only assets (kernel/initrd/rootfs/qemu
 * binaries) stay in the app's filesDir root and are resolved by the engine
 * independently of [workDir].
 */
package com.tiquasar.podsteroid.engine

import java.io.File

data class VmInstanceSpec(
    val id: String,
    val workDir: File,
    val avfName: String,
    val displayName: String,
)
