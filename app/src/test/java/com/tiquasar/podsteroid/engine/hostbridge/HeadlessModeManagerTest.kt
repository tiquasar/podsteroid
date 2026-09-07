package com.tiquasar.podsteroid.engine.hostbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessModeManagerTest {
    @Test fun defaultsToInactive() {
        assertFalse(HeadlessModeManager().isActive("vm-a"))
        assertTrue(HeadlessModeManager().active.value.isEmpty())
    }

    @Test fun setActiveTogglesPerVm() {
        val m = HeadlessModeManager()
        assertFalse(m.isActive("vm-a"))

        m.setActive("vm-a", true)
        assertTrue(m.isActive("vm-a"))
        assertFalse(m.isActive("vm-b"))
        assertTrue(m.active.value["vm-a"] == true)

        m.setActive("vm-a", false)
        assertFalse(m.isActive("vm-a"))
        assertTrue(m.active.value.isEmpty())
    }

    @Test fun multipleVmsTrackIndependently() {
        val m = HeadlessModeManager()
        m.setActive("vm-a", true)
        m.setActive("vm-b", true)
        assertTrue(m.isActive("vm-a"))
        assertTrue(m.isActive("vm-b"))

        m.setActive("vm-a", false)
        assertFalse(m.isActive("vm-a"))
        assertTrue(m.isActive("vm-b"))
    }
}
