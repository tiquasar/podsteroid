/*
 * Podsteroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podsteroid contributors
 */
package com.tiquasar.podsteroid.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibvirtRemoteProviderTest {

    @Test
    fun `parseVirshList handles header and separator`() {
        val out = """
            Id   Name                State
            ----------------------------------------------------
             1    podsteroid-node1    running
             -    podsteroid-node2    shut off
        """.trimIndent()
        val domains = LibvirtRemoteProvider.parseVirshList(out)
        assertEquals(2, domains.size)
        assertEquals("podsteroid-node1", domains[0].name)
        assertEquals("1", domains[0].id)
        assertEquals("running", domains[0].rawState)
        assertEquals(null, domains[1].id)
        assertEquals("shut off", domains[1].rawState)
    }

    @Test
    fun `statusFromState maps known states`() {
        assertEquals(RemoteVmState.RUNNING, LibvirtRemoteProvider.statusFromState("running"))
        assertEquals(RemoteVmState.PAUSED, LibvirtRemoteProvider.statusFromState("paused"))
        assertEquals(RemoteVmState.SHUTOFF, LibvirtRemoteProvider.statusFromState("shut off"))
        assertEquals(RemoteVmState.UNKNOWN, LibvirtRemoteProvider.statusFromState("weird"))
    }

    @Test
    fun `parseDomstats groups by domain`() {
        val out = """
            Domain-1 node1
              cpu.time 12.500
              balloon.maximum 2097152
              balloon.current 1048576
            Domain-2 node2
              cpu.time 3.250
              balloon.maximum 1048576
        """.trimIndent()
        val stats = LibvirtRemoteProvider.parseDomstats(out)
        assertEquals(2, stats.size)
        assertEquals(12500.0, stats["node1"]!!.cpuTimeMs, 0.001)
        assertEquals(2097152, stats["node1"]!!.maxMemKb)
        assertEquals(1048576, stats["node2"]!!.maxMemKb)
    }

    @Test
    fun `parseGuestIp extracts ipv4`() {
        val out = """
            Name       MAC address        Protocol     Address
            vnet0      52:54:00:aa:bb:cc  ipv4         192.168.122.10/24
        """.trimIndent()
        assertEquals("192.168.122.10", LibvirtRemoteProvider.parseGuestIp(out))
        assertNull(LibvirtRemoteProvider.parseGuestIp("no ipv4 here"))
    }

    @Test
    fun `mergeVm preserves stored specs and overlays discovered state`() {
        val server = RemoteServer(id = "s", name = "srv", host = "h", username = "u")
        val stored = RemoteVm(
            serverId = "s",
            uuid = "uuid-1",
            name = "node1",
            guestDistro = "alpine",
            tailscaleIp = "100.1.1.1",
        )
        val dom = VirshDomain(null, "node1", "running")
        val merged = LibvirtRemoteProvider.mergeVm(
            server = server,
            stored = stored,
            domain = dom,
            stats = DomainStats(0.0, 2097152, 1048576),
            guestIp = "192.168.122.10",
            uuid = "uuid-1",
        )
        assertEquals("alpine", merged.guestDistro)
        assertEquals("100.1.1.1", merged.tailscaleIp)
        assertEquals("192.168.122.10", merged.guestIp)
        assertEquals(RemoteVmState.RUNNING, merged.state)
        assertEquals(2048, merged.memMb)
    }

    @Test
    fun `domainUuid parses from a fake executor`() = runBlocking {
        val exec = SshExecutor { Result.success(ExecResult(0, "12345678-1234-1234-1234-123456789abc\n", "")) }
        val uuid = LibvirtRemoteProvider.domainUuid(exec, "node1").getOrNull()
        assertEquals("12345678-1234-1234-1234-123456789abc", uuid)
    }

    @Test
    fun `defaultBaseImageUrl is arch-aware`() {
        assertTrue(LibvirtRemoteProvider.defaultBaseImageUrl("amd64").contains("amd64"))
        assertTrue(LibvirtRemoteProvider.defaultBaseImageUrl("arm64").contains("arm64"))
    }
}
