package com.tiquasar.podsteroid.data.repository

import com.tiquasar.podsteroid.engine.EngineSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class VmDefinitionTest {

    @Test
    fun `round-trips through json`() {
        val def = VmDefinition(
            id = "abc123",
            name = "Test VM",
            backend = EngineSelection.AVF,
            ramMb = 256,
            cpus = 1,
            cpuLimitPercent = 50,
            loadBalanceEnabled = true,
            storageSizeGb = 8,
            sshEnabled = true,
            storageAccessEnabled = true,
            usbPassthroughEnabled = true,
            bandwidthMbps = 100,
            verboseLogging = true,
            sshHostPort = 1234,
            vncHostPort = 5910,
            audioHostPort = 4723,
            portForwards = listOf(PortForwardRule(8080, 80), PortForwardRule(5353, 53, "udp")),
        )
        val restored = VmDefinition.fromJson(def.toJson())
        assertEquals(def, restored)
    }

    @Test
    fun `list round-trips through json`() {
        val list = listOf(
            VmDefinition(id = "a", name = "A"),
            VmDefinition(id = "b", name = "B", cpuLimitPercent = 75, ramMb = 768),
        )
        assertEquals(list, VmDefinition.listFromJson(VmDefinition.listToJson(list)))
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val o = org.json.JSONObject().put("id", "x").put("name", "X")
        val def = VmDefinition.fromJson(o)
        assertEquals(512, def.ramMb)
        assertEquals(2, def.cpus)
        assertEquals(0, def.cpuLimitPercent)
        assertEquals(9922, def.sshHostPort)
        assertEquals(EngineSelection.AUTO, def.backend)
        assertEquals(emptyList<PortForwardRule>(), def.portForwards)
    }

    @Test
    fun `unknown backend value falls back to AUTO`() {
        val o = org.json.JSONObject().put("id", "x").put("name", "X").put("backend", "BOGUS")
        assertEquals(EngineSelection.AUTO, VmDefinition.fromJson(o).backend)
    }

    @Test
    fun `garbage json yields empty list`() {
        assertEquals(emptyList<VmDefinition>(), VmDefinition.listFromJson("not json"))
    }
}
