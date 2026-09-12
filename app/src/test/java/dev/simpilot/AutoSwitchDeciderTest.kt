package dev.simpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSwitchDeciderTest {
    private val config = MonitorConfig(latencyThresholdMs = 1200, speedThresholdKbps = 512)

    @Test fun healthySampleStays() {
        assertFalse(AutoSwitchDecider.isBad(QualitySample(true, 3, true, 80, 5_000), config))
    }

    @Test fun slowSampleFails() {
        assertTrue(AutoSwitchDecider.isBad(QualitySample(true, 3, true, 100, 200), config))
    }

    @Test fun unvalidatedNetworkFails() {
        assertTrue(AutoSwitchDecider.isBad(QualitySample(true, 4, false, null, null), config))
    }
}
