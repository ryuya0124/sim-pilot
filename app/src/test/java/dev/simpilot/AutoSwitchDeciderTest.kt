package dev.simpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test fun wifiRestoreKeepsBackgroundServiceAliveIndependently() {
        assertFalse(MonitorConfig().needsService())
        assertTrue(MonitorConfig(wifiRestoreEnabled = true).needsService())
        assertTrue(MonitorConfig(enabled = true).needsService())
    }

    @Test fun eachWifiRoleChangesPolicySignature() {
        val initial = MonitorConfig().wifiPolicySignature()
        assertNotEquals(initial, MonitorConfig(wifiDataSubId = 4).wifiPolicySignature())
        assertNotEquals(initial, MonitorConfig(wifiVoiceEnabled = true).wifiPolicySignature())
        assertNotEquals(initial, MonitorConfig(wifiSmsEnabled = true).wifiPolicySignature())
    }
}
