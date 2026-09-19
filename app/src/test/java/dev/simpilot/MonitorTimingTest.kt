package dev.simpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTimingTest {
    @Test
    fun earlierRealtimeEventReplacesPeriodicDeadline() {
        assertTrue(MonitorTiming.shouldReplace(existingDeadline = 30_000, candidateDeadline = 250))
    }

    @Test
    fun laterEventDoesNotPostponeExistingCheck() {
        assertFalse(MonitorTiming.shouldReplace(existingDeadline = 250, candidateDeadline = 500))
    }

    @Test
    fun degradedConnectionUsesRapidConfirmation() {
        assertEquals(
            3_000,
            MonitorTiming.nextDelayMs(
                configuredIntervalMs = 30_000,
                enabled = true,
                badSamples = 1,
                requiredBadSamples = 3,
            ),
        )
    }

    @Test
    fun normalSamplesUseConfiguredIntervalAndPersistentDegradationStaysRapid() {
        assertEquals(30_000, MonitorTiming.nextDelayMs(30_000, true, 0, 3))
        assertEquals(3_000, MonitorTiming.nextDelayMs(30_000, true, 3, 3))
        assertEquals(30_000, MonitorTiming.nextDelayMs(30_000, false, 1, 3))
    }

    @Test
    fun qualityRecoveryAllowsSlowCellularRegistrationButQuotaTrialStaysShort() {
        assertEquals(30_000, MonitorTiming.comparisonSettleTimeoutMs(qualityRecovery = true))
        assertEquals(7_000, MonitorTiming.comparisonSettleTimeoutMs(qualityRecovery = false))
    }

    @Test
    fun burstCallbacksDoNotCountAsMultipleBadSamples() {
        assertTrue(MonitorTiming.shouldAcceptBadSample(currentCount = 0, lastAcceptedAt = 0, now = 10_000))
        assertFalse(MonitorTiming.shouldAcceptBadSample(currentCount = 1, lastAcceptedAt = 10_000, now = 10_250))
        assertTrue(MonitorTiming.shouldAcceptBadSample(currentCount = 1, lastAcceptedAt = 10_000, now = 12_500))
    }
}
