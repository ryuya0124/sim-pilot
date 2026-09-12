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
    fun normalAndFinalSamplesUseConfiguredInterval() {
        assertEquals(30_000, MonitorTiming.nextDelayMs(30_000, true, 0, 3))
        assertEquals(30_000, MonitorTiming.nextDelayMs(30_000, true, 3, 3))
        assertEquals(30_000, MonitorTiming.nextDelayMs(30_000, false, 1, 3))
    }
}
