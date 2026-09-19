package dev.simpilot

data class RadioIntelligenceAssessment(
    val penalty: Int,
    val capacityScore: Int,
    val confidence: Int,
    val reasons: List<String>,
    val recentServingCellChanges: Int,
) {
    val poor: Boolean get() = penalty >= 12
}

/**
 * Converts radio-layer hints into conservative decision inputs.
 * Connectivity and active probes remain authoritative; inferred radio data never causes a switch alone.
 */
object RadioIntelligence {
    fun assess(radio: RadioMetrics?, recentServingCellChanges: Int = 0): RadioIntelligenceAssessment {
        if (radio == null) return RadioIntelligenceAssessment(0, 0, 0, emptyList(), recentServingCellChanges)

        var penalty = 0
        val reasons = mutableListOf<String>()

        if (!radio.primaryConnected || radio.servingCellCount == 0) {
            penalty += 12
            reasons += "Primaryセル未確認"
        }

        val technologyPenalty = when {
            radio.technology.startsWith("2G") -> 24
            radio.technology.startsWith("3G") -> 14
            radio.technology == "CDMA" -> 18
            else -> 0
        }
        if (technologyPenalty > 0) {
            penalty += technologyPenalty
            reasons += "通信方式 ${radio.technology}"
        }

        val bandwidthKhz = radio.bandwidthKhz
        val bandwidthPenalty = when {
            bandwidthKhz == null -> 0
            bandwidthKhz <= 5_000 -> 10
            bandwidthKhz <= 10_000 -> 5
            else -> 0
        }
        if (bandwidthPenalty > 0) {
            penalty += bandwidthPenalty
            reasons += "帯域幅 ${bandwidthKhz!! / 1_000.0}MHz"
        }

        val cqiPenalty = when {
            radio.cqi == null -> 0
            radio.cqi <= 3 -> 12
            radio.cqi <= 6 -> 7
            radio.cqi <= 9 -> 3
            else -> 0
        }
        if (cqiPenalty > 0) {
            penalty += cqiPenalty
            reasons += "CQI ${radio.cqi}"
        }

        val mobilityPenalty = when {
            recentServingCellChanges >= 5 -> 10
            recentServingCellChanges >= 3 -> 5
            else -> 0
        }
        if (mobilityPenalty > 0) {
            penalty += mobilityPenalty
            reasons += "Servingセル変化 ${recentServingCellChanges}回/2分"
        }

        val technologyCapacity = when {
            radio.technology.startsWith("5G+") -> 52
            radio.technology.startsWith("5G") -> 46
            radio.technology.startsWith("4G+") -> 36
            radio.technology.startsWith("4G") -> 26
            radio.technology.startsWith("3G") -> 10
            radio.technology.startsWith("2G") -> 3
            else -> 12
        }
        val bandwidthCapacity = ((radio.bandwidthKhz ?: 0) / 2_000).coerceIn(0, 15)
        val aggregationCapacity = when {
            radio.secondaryCellCount >= 2 -> 14
            radio.carrierAggregation -> 9
            else -> 0
        }
        val cqiCapacity = radio.cqi?.let { ((it.coerceIn(0, 15) * 12) / 15) } ?: 0
        val sinrCapacity = radio.sinrDb?.let { ((it + 5.0) / 2.0).toInt().coerceIn(0, 12) } ?: 0

        val confidence = buildList {
            if (radio.primaryConnected) add(20)
            if (radio.referenceDbm != null) add(15)
            if (radio.rsrqDb != null) add(15)
            if (radio.sinrDb != null) add(15)
            if (radio.cqi != null) add(10)
            if (radio.bandwidthKhz != null) add(10)
            if (radio.cellId != null) add(10)
            if (radio.cells.isNotEmpty()) add(5)
        }.sum().coerceAtMost(100)

        return RadioIntelligenceAssessment(
            penalty = penalty.coerceAtMost(28),
            capacityScore = (technologyCapacity + bandwidthCapacity + aggregationCapacity + cqiCapacity + sinrCapacity)
                .coerceIn(0, 100),
            confidence = confidence,
            reasons = reasons,
            recentServingCellChanges = recentServingCellChanges,
        )
    }
}
