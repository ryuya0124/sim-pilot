package dev.simpilot

data class QualitySample(
    val inService: Boolean,
    val serviceStateKnown: Boolean,
    val signalLevel: Int,
    val dbm: Int?,
    val validated: Boolean,
    val latencyMs: Long?,
    val speedKbps: Long?,
)

enum class QualityVerdict { GOOD, DEGRADED, BAD, INCONCLUSIVE }

data class QualityAssessment(
    val verdict: QualityVerdict,
    val score: Int,
    val reasons: List<String>,
    val connectivityFailure: Boolean = false,
) {
    val needsRapidRecheck: Boolean get() = verdict == QualityVerdict.BAD || verdict == QualityVerdict.DEGRADED
}

object AutoSwitchDecider {
    fun assess(sample: QualitySample, config: MonitorConfig): QualityAssessment {
        if (sample.serviceStateKnown && !sample.inService) {
            return QualityAssessment(QualityVerdict.BAD, 100, listOf("圏外"), connectivityFailure = true)
        }
        if (!sample.validated) {
            return QualityAssessment(QualityVerdict.BAD, 90, listOf("インターネット未検証"), connectivityFailure = true)
        }

        val reasons = mutableListOf<String>()
        var score = signalPenalty(sample, config).also { penalty ->
            if (penalty > 0) reasons += "電波 ${sample.dbm?.let { "$it dBm" } ?: "${sample.signalLevel}/4"}"
        }

        sample.latencyMs?.let { latency ->
            val threshold = config.latencyThresholdMs.coerceAtLeast(100)
            val penalty = when {
                latency > threshold * 2L -> 38
                latency > threshold -> 25
                latency > threshold * 2L / 3L -> 12
                latency > threshold / 2L -> 6
                else -> 0
            }
            score += penalty
            if (penalty > 0) reasons += "遅延 ${latency}ms"
        }

        sample.speedKbps?.let { speed ->
            val target = config.speedThresholdKbps.coerceAtLeast(500)
            val penalty = when {
                speed < 128 -> 55
                speed < target / 10L -> 48
                speed < target / 5L -> 38
                speed < target / 2L -> 26
                speed < target -> 14
                else -> 0
            }
            score += penalty
            if (penalty > 0) reasons += "速度 ${speed}kbps"
        }

        val verdict = when {
            score >= 45 -> QualityVerdict.BAD
            score >= 20 -> QualityVerdict.DEGRADED
            sample.latencyMs == null && sample.speedKbps == null && score == 0 -> QualityVerdict.INCONCLUSIVE
            else -> QualityVerdict.GOOD
        }
        return QualityAssessment(verdict, score.coerceAtMost(100), reasons)
    }

    fun evaluate(sample: QualitySample, config: MonitorConfig): QualityVerdict = assess(sample, config).verdict

    fun isBad(sample: QualitySample, config: MonitorConfig): Boolean =
        evaluate(sample, config) == QualityVerdict.BAD

    fun canUseAlternate(line: SimLine): Boolean =
        (!line.serviceStateKnown || line.inService) && (line.signalLevel < 0 || line.signalLevel >= 1)

    fun shouldPreferCandidate(
        current: QualityAssessment,
        candidate: QualityAssessment,
        currentSample: QualitySample,
        candidateSample: QualitySample,
        quotaAdvantage: Boolean = false,
    ): Boolean {
        if (candidate.connectivityFailure) return false
        if (current.connectivityFailure) return true
        if (candidate.score + 15 <= current.score) return true

        val signalGain = when {
            currentSample.dbm != null && candidateSample.dbm != null -> candidateSample.dbm - currentSample.dbm
            else -> (candidateSample.signalLevel - currentSample.signalLevel) * 6
        }
        val speedGain = currentSample.speedKbps?.let { currentSpeed ->
            candidateSample.speedKbps?.let { candidateSpeed -> candidateSpeed >= currentSpeed * 3 / 2 }
        } == true
        if (current.verdict != QualityVerdict.GOOD && signalGain >= 6 && speedGain) return true
        return quotaAdvantage && current.verdict == QualityVerdict.GOOD &&
            candidate.verdict == QualityVerdict.GOOD && candidate.score <= current.score + 5
    }

    private fun signalPenalty(sample: QualitySample, config: MonitorConfig): Int {
        sample.dbm?.let { dbm ->
            return when {
                dbm <= -125 -> 60
                dbm <= -120 -> 45
                dbm <= config.weakSignalDbm -> 32
                dbm <= config.weakSignalDbm + 6 -> 20
                dbm <= config.weakSignalDbm + 14 -> 8
                else -> 0
            }
        }
        return when (sample.signalLevel) {
            0 -> 55
            1 -> 32
            2 -> 14
            else -> 0
        }
    }
}
