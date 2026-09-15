package dev.simpilot

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPlanPolicyTest {
    @Test fun monthlyCycleUsesConfiguredRenewalDay() {
        val plan = SimPlanConfig(subId = 4, enabled = true, billingDay = 20, capacityBytes = 20L * GB)
        val window = DataPlanPolicy.window(plan, LocalDate.of(2026, 9, 15))
        assertNotNull(window)
        val start = java.time.Instant.ofEpochMilli(window!!.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(LocalDate.of(2026, 8, 20), start)
    }

    @Test fun flexiblePlanCombinesCurrentlyActivePacks() {
        val today = LocalDate.of(2026, 9, 15)
        val plan = SimPlanConfig(
            subId = 5,
            enabled = true,
            type = DataPlanType.FLEXIBLE,
            allowances = listOf(
                DataAllowance(1, "3GB", 3L * GB, today.minusDays(2).toEpochDay(), today.plusDays(2).toEpochDay()),
                DataAllowance(2, "24h", 10L * GB, today.toEpochDay(), today.toEpochDay()),
                DataAllowance(3, "expired", 99L * GB, today.minusDays(10).toEpochDay(), today.minusDays(1).toEpochDay()),
            ),
        )
        assertEquals(13L * GB, DataPlanPolicy.window(plan, today)?.capacityBytes)
    }

    @Test fun manualRemainingOverridesMeasuredUsage() {
        val plan = SimPlanConfig(subId = 4, enabled = true, capacityBytes = 20L * GB, manualRemainingBytes = 7L * GB)
        val state = DataPlanPolicy.state(plan, measuredBytes = 19L * GB)!!
        assertEquals(7L * GB, state.remainingBytes)
        assertTrue(state.manual)
    }

    @Test fun expiredFixedPlanIsNotUsed() {
        val today = LocalDate.of(2026, 9, 15)
        val plan = SimPlanConfig(
            subId = 4,
            enabled = true,
            type = DataPlanType.FIXED,
            startEpochDay = today.minusDays(10).toEpochDay(),
            endEpochDay = today.minusDays(1).toEpochDay(),
        )
        assertEquals(null, DataPlanPolicy.window(plan, today))
    }

    @Test fun quotaPreferenceRequiresMeaningfulDifference() {
        fun state(remainingGb: Long, ratio: Double) = DataUsageState(0, 20L * GB, remainingGb * GB, ratio, false)
        assertTrue(DataPlanPolicy.candidateHasClearAdvantage(state(2, .10), state(8, .40)))
        assertFalse(DataPlanPolicy.candidateHasClearAdvantage(state(7, .35), state(8, .40)))
    }
}
