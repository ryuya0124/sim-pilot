package dev.simpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSwitchDeciderTest {
    private val config = MonitorConfig(latencyThresholdMs = 1200, speedThresholdKbps = 5_000, weakSignalDbm = -114)

    private fun sample(
        inService: Boolean = true,
        known: Boolean = true,
        level: Int = 4,
        dbm: Int? = -90,
        validated: Boolean = true,
        latency: Long? = 80,
        speed: Long? = 5_000,
    ) = QualitySample(inService, known, level, dbm, validated, latency, speed)

    @Test fun healthySampleStays() {
        assertFalse(AutoSwitchDecider.isBad(sample(), config))
    }

    @Test fun slowSampleFails() {
        assertTrue(AutoSwitchDecider.isBad(sample(speed = 200), config))
    }

    @Test fun unvalidatedNetworkFails() {
        assertTrue(AutoSwitchDecider.isBad(sample(validated = false, latency = null, speed = null), config))
    }

    @Test fun validatedNetworkWithTemporaryProbeFailureIsInconclusive() {
        assertEquals(
            QualityVerdict.INCONCLUSIVE,
            AutoSwitchDecider.evaluate(sample(latency = null, speed = null), config),
        )
    }

    @Test fun goodSpeedCompensatesForMissingLatencyProbe() {
        assertEquals(
            QualityVerdict.GOOD,
            AutoSwitchDecider.evaluate(sample(latency = null, speed = 5_000), config),
        )
    }

    @Test fun slowSpeedStillFailsWhenLatencyProbeIsMissing() {
        assertEquals(
            QualityVerdict.BAD,
            AutoSwitchDecider.evaluate(sample(latency = null, speed = 200), config),
        )
    }

    @Test fun unknownServiceStateDoesNotOverrideHealthyMeasurements() {
        assertEquals(
            QualityVerdict.GOOD,
            AutoSwitchDecider.evaluate(sample(inService = false, known = false, level = -1, dbm = null, latency = 40, speed = 5_000), config),
        )
    }

    @Test fun confirmedOutOfServiceStillFails() {
        assertEquals(
            QualityVerdict.BAD,
            AutoSwitchDecider.evaluate(sample(inService = false, known = true, latency = 40, speed = 5_000), config),
        )
    }

    @Test fun minus114DbmIsPenalized() {
        val result = AutoSwitchDecider.assess(sample(dbm = -114), config)
        assertEquals(QualityVerdict.DEGRADED, result.verdict)
        assertTrue(result.score >= 32)
    }

    @Test fun oneMbpsIsNotTreatedAsFullyHealthy() {
        assertEquals(QualityVerdict.DEGRADED, AutoSwitchDecider.evaluate(sample(speed = 1_000), config))
    }

    @Test fun weakAndSlowBecomesBad() {
        assertEquals(QualityVerdict.BAD, AutoSwitchDecider.evaluate(sample(dbm = -116, speed = 1_000), config))
    }

    @Test fun clearlyFasterAndStrongerCandidateWins() {
        val currentSample = sample(dbm = -118, speed = 500, latency = 700)
        val candidateSample = sample(dbm = -100, speed = 5_000, latency = 100)
        assertTrue(
            AutoSwitchDecider.shouldPreferCandidate(
                AutoSwitchDecider.assess(currentSample, config),
                AutoSwitchDecider.assess(candidateSample, config),
                currentSample,
                candidateSample,
            )
        )
    }

    @Test fun quotaOnlyBreaksTieBetweenHealthyConnections() {
        val currentSample = sample(speed = 7_000)
        val candidateSample = sample(dbm = -92, speed = 6_000)
        assertTrue(
            AutoSwitchDecider.shouldPreferCandidate(
                AutoSwitchDecider.assess(currentSample, config),
                AutoSwitchDecider.assess(candidateSample, config),
                currentSample,
                candidateSample,
                quotaAdvantage = true,
            )
        )
    }

    @Test fun unavailableCandidateNeverWinsForQuota() {
        val currentSample = sample()
        val candidateSample = sample(validated = false, latency = null, speed = null)
        assertFalse(
            AutoSwitchDecider.shouldPreferCandidate(
                AutoSwitchDecider.assess(currentSample, config),
                AutoSwitchDecider.assess(candidateSample, config),
                currentSample,
                candidateSample,
                quotaAdvantage = true,
            )
        )
    }

    @Test fun wifiRestoreKeepsBackgroundServiceAliveIndependently() {
        assertFalse(MonitorConfig().needsService())
        assertTrue(MonitorConfig(wifiRestoreEnabled = true).needsService())
        assertTrue(MonitorConfig(enabled = true).needsService())
    }

}
