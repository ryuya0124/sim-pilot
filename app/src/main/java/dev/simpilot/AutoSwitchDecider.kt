package dev.simpilot

data class QualitySample(
    val inService: Boolean,
    val serviceStateKnown: Boolean,
    val signalLevel: Int,
    val validated: Boolean,
    val latencyMs: Long?,
    val speedKbps: Long?,
)

enum class QualityVerdict { GOOD, BAD, INCONCLUSIVE }

object AutoSwitchDecider {
    fun evaluate(sample: QualitySample, config: MonitorConfig): QualityVerdict {
        if (sample.serviceStateKnown && !sample.inService) return QualityVerdict.BAD
        if (!sample.validated) return QualityVerdict.BAD
        if (sample.latencyMs != null && sample.latencyMs > config.latencyThresholdMs) return QualityVerdict.BAD
        if (sample.speedKbps != null && sample.speedKbps < config.speedThresholdKbps) return QualityVerdict.BAD
        if (sample.latencyMs == null && sample.speedKbps == null) return QualityVerdict.INCONCLUSIVE
        return QualityVerdict.GOOD
    }

    fun isBad(sample: QualitySample, config: MonitorConfig): Boolean =
        evaluate(sample, config) == QualityVerdict.BAD

    fun canUseAlternate(line: SimLine): Boolean =
        (!line.serviceStateKnown || line.inService) && (line.signalLevel < 0 || line.signalLevel >= 1)
}
