/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 */
package com.tiquasar.podsteroid.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshClientTest {

    private val client = SshClient()

    @Test
    fun `fingerprint is deterministic and well-formed`() {
        val blob = "ssh-ed25519 AAAA...".toByteArray(Charsets.UTF_8)
        val a = client.fingerprint(blob)
        val b = client.fingerprint(blob)
        assertEquals(a, b)
        assertTrue(a.startsWith("SHA256:"))
        // SHA256 hex is 64 chars after the prefix.
        assertEquals(64, a.removePrefix("SHA256:").length)
    }

    @Test
    fun `different blobs yield different fingerprints`() {
        val x = client.fingerprint("alpha".toByteArray())
        val y = client.fingerprint("beta".toByteArray())
        assertEquals(false, x == y)
    }
}
