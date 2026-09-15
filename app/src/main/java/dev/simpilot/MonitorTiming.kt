package dev.simpilot

internal object MonitorTiming {
    private const val DEGRADED_RECHECK_MS = 3_000L

    fun shouldReplace(existingDeadline: Long, candidateDeadline: Long): Boolean =
        candidateDeadline < existingDeadline

    fun nextDelayMs(
        configuredIntervalMs: Long,
        enabled: Boolean,
        badSamples: Int,
        requiredBadSamples: Int,
    ): Long {
        val degraded = enabled && badSamples > 0
        return if (degraded) minOf(configuredIntervalMs, DEGRADED_RECHECK_MS) else configuredIntervalMs
    }
}
