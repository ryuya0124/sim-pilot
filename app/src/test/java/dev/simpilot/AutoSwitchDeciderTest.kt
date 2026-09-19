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

    private fun line(dbm: Int? = -100, level: Int = 3, radio: RadioMetrics? = null) = SimLine(
        subId = 5,
        slotIndex = 0,
        displayName = "candidate",
        carrierName = "candidate",
        signalLevel = level,
        dbm = dbm,
        inService = true,
        serviceStateKnown = true,
        radio = radio,
    )

    private fun radio(rsrq: Double, sinr: Double) = RadioMetrics(
        technology = "4G",
        primaryConnected = true,
        servingCellCount = 1,
        neighboringCellCount = 0,
        secondaryCellCount = 0,
        referenceDbm = -100,
        rsrpDbm = -100.0,
        rsrqDb = rsrq,
        sinrDb = sinr,
        observedAtElapsed = 1,
    )

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

    @Test fun poorRsrqAndSinrDegradeAnOtherwiseFastConnection() {
        val result = AutoSwitchDecider.assess(sample().copy(rsrqDb = -19.0, sinrDb = 0.0), config)
        assertEquals(QualityVerdict.DEGRADED, result.verdict)
        assertEquals(24, result.score)
        assertTrue(result.reasons.any { it.contains("RSRQ") })
        assertTrue(result.reasons.any { it.contains("SINR") })
    }

    @Test fun narrowBandwidthAndLowCqiAffectOverallVerdict() {
        val result = AutoSwitchDecider.assess(
            sample().copy(
                radioPenalty = 22,
                radioCapacityScore = 24,
                radioConfidence = 90,
                radioReasons = listOf("帯域幅 5.0MHz", "CQI 3"),
            ),
            config,
        )
        assertEquals(QualityVerdict.DEGRADED, result.verdict)
        assertEquals(22, result.score)
        assertTrue(result.reasons.any { it.contains("CQI") })
    }

    @Test fun theoreticalCapacityAloneDoesNotMoveHealthyConnection() {
        val current = sample().copy(radioCapacityScore = 30, radioConfidence = 90)
        val candidate = sample().copy(radioCapacityScore = 75, radioConfidence = 90)
        assertFalse(
            AutoSwitchDecider.shouldPreferCandidate(
                AutoSwitchDecider.assess(current, config),
                AutoSwitchDecider.assess(candidate, config),
                current,
                candidate,
            )
        )
    }

    @Test fun strongRadioHeadroomCanBreakADegradedTie() {
        val current = sample().copy(
            radioPenalty = 20,
            radioCapacityScore = 28,
            radioConfidence = 90,
        )
        val candidate = sample().copy(
            radioPenalty = 10,
            radioCapacityScore = 62,
            radioConfidence = 90,
        )
        assertTrue(
            AutoSwitchDecider.shouldPreferCandidate(
                AutoSwitchDecider.assess(current, config),
                AutoSwitchDecider.assess(candidate, config),
                current,
                candidate,
            )
        )
    }

    @Test fun quotaDoesNotProbeCandidateWithBadRadioQuality() {
        val current = AutoSwitchDecider.assess(sample(), config)
        assertFalse(
            AutoSwitchDecider.shouldTryQuotaBalance(
                current,
                line(dbm = -100, radio = radio(rsrq = -19.0, sinr = 0.0)),
                config,
                quotaAdvantage = true,
                millisSinceSwitch = 30L * 60_000L,
            )
        )
    }

    @Test fun clearlyBetterRsrqAndSinrCanJustifyRecoveryProbe() {
        val currentSample = sample(dbm = -100, speed = 2_000).copy(rsrqDb = -19.0, sinrDb = 0.0)
        assertTrue(
            AutoSwitchDecider.shouldTryQualityRecovery(
                current = AutoSwitchDecider.assess(currentSample, config),
                currentLine = line(dbm = -100, radio = radio(rsrq = -19.0, sinr = 0.0)),
                candidate = line(dbm = -107, radio = radio(rsrq = -10.0, sinr = 18.0)),
                config = config,
                consecutiveThresholdReached = true,
            )
        )
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

    @Test fun quotaDoesNotTriggerWhileCurrentConnectionIsDegraded() {
        val current = AutoSwitchDecider.assess(sample(speed = 2_483), config)
        assertFalse(
            AutoSwitchDecider.shouldTryQuotaBalance(current, line(), config, true, 30L * 60_000L)
        )
    }

    @Test fun quotaDoesNotProbeWeakCandidate() {
        val current = AutoSwitchDecider.assess(sample(), config)
        assertFalse(
            AutoSwitchDecider.shouldTryQuotaBalance(current, line(dbm = -119), config, true, 30L * 60_000L)
        )
    }

    @Test fun quotaWaitsForConnectionStability() {
        val current = AutoSwitchDecider.assess(sample(), config)
        assertFalse(
            AutoSwitchDecider.shouldTryQuotaBalance(current, line(), config, true, 5L * 60_000L)
        )
        assertTrue(
            AutoSwitchDecider.shouldTryQuotaBalance(current, line(), config, true, 15L * 60_000L)
        )
    }

    @Test fun degradedConnectionDoesNotProbeClearlyWeakerRadio() {
        val currentSample = sample(dbm = -105, speed = 2_483)
        assertFalse(
            AutoSwitchDecider.shouldTryQualityRecovery(
                current = AutoSwitchDecider.assess(currentSample, config),
                currentLine = line(dbm = -105),
                candidate = line(dbm = -119),
                config = config,
                consecutiveThresholdReached = true,
            )
        )
    }

    @Test fun degradedConnectionCanProbeClearlyStrongerRadio() {
        val currentSample = sample(dbm = -121, speed = 1_000)
        assertTrue(
            AutoSwitchDecider.shouldTryQualityRecovery(
                current = AutoSwitchDecider.assess(currentSample, config),
                currentLine = line(dbm = -121),
                candidate = line(dbm = -100),
                config = config,
                consecutiveThresholdReached = true,
            )
        )
    }

    @Test fun connectivityFailureStillAllowsWeakButAvailableCandidateProbe() {
        val currentSample = sample(dbm = -100, validated = false, latency = null, speed = null)
        assertTrue(
            AutoSwitchDecider.shouldTryQualityRecovery(
                current = AutoSwitchDecider.assess(currentSample, config),
                currentLine = line(dbm = -100),
                candidate = line(dbm = -119, level = 1),
                config = config,
                consecutiveThresholdReached = true,
            )
        )
    }

    @Test fun yesterdayRecoveryAndTodayWeakQuotaReturnDoNotConflict() {
        val incidentConfig = config.copy(latencyThresholdMs = 600, consecutiveFailures = 2)
        val yesterdayLinemo = sample(level = 1, dbm = -121, latency = 1_467, speed = 952)
        assertTrue(
            AutoSwitchDecider.shouldTryQualityRecovery(
                current = AutoSwitchDecider.assess(yesterdayLinemo, incidentConfig),
                currentLine = line(dbm = -121, level = 1),
                candidate = line(dbm = -107, level = 3),
                config = incidentConfig,
                consecutiveThresholdReached = true,
            )
        )

        val todayPovo = sample(level = 3, dbm = -105, latency = 108, speed = 2_483)
        val todayLinemo = line(dbm = -119, level = 1)
        val todayAssessment = AutoSwitchDecider.assess(todayPovo, incidentConfig)
        assertFalse(
            AutoSwitchDecider.shouldTryQualityRecovery(
                current = todayAssessment,
                currentLine = line(dbm = -105, level = 3),
                candidate = todayLinemo,
                config = incidentConfig,
                consecutiveThresholdReached = true,
            )
        )
        assertFalse(
            AutoSwitchDecider.shouldTryQuotaBalance(
                current = todayAssessment,
                candidate = todayLinemo,
                config = incidentConfig,
                quotaAdvantage = true,
                millisSinceSwitch = 30L * 60_000L,
            )
        )
    }

    @Test fun wifiRestoreKeepsBackgroundServiceAliveIndependently() {
        assertFalse(MonitorConfig().needsService())
        assertTrue(MonitorConfig(wifiRestoreEnabled = true).needsService())
        assertTrue(MonitorConfig(enabled = true).needsService())
    }

}
