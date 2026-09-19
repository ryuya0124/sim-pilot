package dev.simpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioIntelligenceTest {
    private fun radio(
        technology: String = "4G",
        bandwidthKhz: Int? = 20_000,
        cqi: Int? = 12,
        secondaryCells: Int = 0,
        primary: Boolean = true,
    ) = RadioMetrics(
        technology = technology,
        primaryConnected = primary,
        servingCellCount = if (primary) 1 + secondaryCells else 0,
        neighboringCellCount = 2,
        secondaryCellCount = secondaryCells,
        bandwidthKhz = bandwidthKhz,
        referenceDbm = -98,
        rsrpDbm = -98.0,
        rsrqDb = -10.0,
        sinrDb = 16.0,
        cqi = cqi,
        cellId = 1234,
        observedAtElapsed = 1,
    )

    @Test fun healthyWideLteHasHeadroomWithoutPenalty() {
        val result = RadioIntelligence.assess(radio(secondaryCells = 1))
        assertEquals(0, result.penalty)
        assertTrue(result.capacityScore >= 60)
        assertTrue(result.confidence >= 80)
    }

    @Test fun narrowLowCqiCellIsPenalized() {
        val result = RadioIntelligence.assess(radio(bandwidthKhz = 5_000, cqi = 3))
        assertEquals(22, result.penalty)
        assertTrue(result.reasons.any { it.contains("帯域幅") })
        assertTrue(result.reasons.any { it.contains("CQI") })
    }

    @Test fun legacyTechnologyIsOnlyAConservativePenalty() {
        val result = RadioIntelligence.assess(radio(technology = "3G", bandwidthKhz = null, cqi = null))
        assertEquals(14, result.penalty)
        assertTrue(result.capacityScore < RadioIntelligence.assess(radio()).capacityScore)
    }

    @Test fun frequentServingCellChangesAddInstabilityPenalty() {
        val result = RadioIntelligence.assess(radio(), recentServingCellChanges = 5)
        assertEquals(10, result.penalty)
        assertTrue(result.reasons.any { it.contains("5回/2分") })
    }
}
