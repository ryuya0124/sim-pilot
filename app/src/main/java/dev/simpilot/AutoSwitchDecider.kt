package dev.simpilot

data class QualitySample(
    val inService: Boolean,
    val signalLevel: Int,
    val validated: Boolean,
    val latencyMs: Long?,
    val speedKbps: Long?,
)

object AutoSwitchDecider {
    fun isBad(sample: QualitySample, config: MonitorConfig): Boolean {
        if (!sample.inService || !sample.validated) return true
        if (sample.signalLevel in 0..1) return true
        if (sample.latencyMs == null || sample.latencyMs > config.latencyThresholdMs) return true
        return sample.speedKbps != null && sample.speedKbps < config.speedThresholdKbps
    }

    fun canUseAlternate(line: SimLine): Boolean =
        line.inService && (line.signalLevel < 0 || line.signalLevel >= 1)
}
