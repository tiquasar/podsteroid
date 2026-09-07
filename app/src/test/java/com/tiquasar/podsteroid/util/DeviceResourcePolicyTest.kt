package com.tiquasar.podsteroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceResourcePolicyTest {

    @Test
    fun balancedRamMb_scalesWithDevice() {
        assertEquals(512, DeviceResourcePolicy.balancedRamMb(2_048))
        assertEquals(1024, DeviceResourcePolicy.balancedRamMb(4_096))
        assertEquals(2048, DeviceResourcePolicy.balancedRamMb(8_192))
        assertEquals(4096, DeviceResourcePolicy.balancedRamMb(16_384))
    }

    @Test
    fun balancedCpus_usesHalfCappedAtFour() {
        assertEquals(1, DeviceResourcePolicy.balancedCpus(1))
        assertEquals(2, DeviceResourcePolicy.balancedCpus(4))
        assertEquals(4, DeviceResourcePolicy.balancedCpus(8))
        assertEquals(4, DeviceResourcePolicy.balancedCpus(12))
    }

    @Test
    fun balancedStorageGb_respectsAvailableSpace() {
        assertEquals(2, DeviceResourcePolicy.balancedStorageGb(4))
        assertEquals(8, DeviceResourcePolicy.balancedStorageGb(40))
        assertEquals(64, DeviceResourcePolicy.balancedStorageGb(512))
    }

    @Test
    fun nearestAtMost_picksLargestOptionNotExceedingTarget() {
        assertEquals(4, DeviceResourcePolicy.nearestAtMost(listOf(2, 4, 8), 5))
        assertEquals(2, DeviceResourcePolicy.nearestAtMost(listOf(2, 4, 8), 1))
    }

    @Test
    fun extendedLists_extendBelowAndAboveDefaults() {
        assertEquals(256, DeviceResourcePolicy.MIN_RAM_MB)
        assertEquals(256, DeviceResourcePolicy.RAM_EXTENDED_OPTIONS_MB.first())
        assertEquals(16384, DeviceResourcePolicy.RAM_EXTENDED_OPTIONS_MB.last())
        assertEquals(16, DeviceResourcePolicy.CPU_EXTENDED_OPTIONS.last())
        assertEquals(256, DeviceResourcePolicy.STORAGE_EXTENDED_OPTIONS_GB.last())
        assertEquals(listOf(0, 25, 50, 75), DeviceResourcePolicy.CPU_LIMIT_PERCENT_OPTIONS)
    }
}
