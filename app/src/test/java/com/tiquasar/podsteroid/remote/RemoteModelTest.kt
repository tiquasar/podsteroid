/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 */
package com.tiquasar.podsteroid.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelTest {

    @Test
    fun `RemoteServer JSON round-trip preserves fields`() {
        val server = RemoteServer(
            id = "abc",
            name = "Lab",
            host = "192.168.1.5",
            port = 2222,
            username = "admin",
            authKind = AuthKind.KEY,
            secretToken = "iv:ct",
            passphraseToken = "iv2:ct2",
            hostKeyFingerprint = "SHA256:abc",
            baseImageUrl = "https://example.com/img.qcow2",
        )
        val json = server.toJson().toString()
        val back = RemoteServer.fromJson(org.json.JSONObject(json))
        assertEquals(server, back)
    }

    @Test
    fun `RemoteServer list round-trip`() {
        val list = listOf(
            RemoteServer(id = "1", name = "a", host = "h1", username = "u"),
            RemoteServer(id = "2", name = "b", host = "h2", username = "u", authKind = AuthKind.KEY),
        )
        val back = RemoteServer.listFromJson(RemoteServer.listToJson(list))
        assertEquals(list, back)
    }

    @Test
    fun `RemoteVm JSON round-trip preserves fields`() {
        val vm = RemoteVm(
            serverId = "s1",
            uuid = "1234-5678",
            name = "node1",
            state = RemoteVmState.RUNNING,
            guestDistro = "alpine",
            vcpus = 4,
            memMb = 4096,
            diskGb = 40,
            sshEnabled = true,
            containerRuntime = ContainerRuntime.BOTH,
            tailscaleEnabled = true,
            guestIp = "192.168.122.10",
            tailscaleIp = "100.64.1.2",
            magicDns = "node1.tailnet.ts.net",
        )
        val back = RemoteVm.fromJson(org.json.JSONObject(vm.toJson().toString()))
        assertEquals(vm, back)
    }

    @Test
    fun `ContainerRuntime token mapping`() {
        assertEquals(ContainerRuntime.BOTH, ContainerRuntime.fromToken("BOTH"))
        assertEquals(ContainerRuntime.K3S, ContainerRuntime.fromToken("K3S"))
        assertEquals(ContainerRuntime.BOTH, ContainerRuntime.fromToken(null))
    }

    @Test
    fun `default values survive round-trip`() {
        val vm = RemoteVm(serverId = "s", uuid = "u", name = "n")
        val back = RemoteVm.fromJson(org.json.JSONObject(vm.toJson().toString()))
        assertTrue(back.tailscaleEnabled)
        assertEquals(RemoteVmState.UNKNOWN, back.state)
    }
}
