package dev.simpilot

internal object MonitorTiming {
    private const val DEGRADED_RECHECK_MS = 3_000L
    private const val MIN_BAD_SAMPLE_GAP_MS = 2_500L
    private const val QUOTA_COMPARISON_SETTLE_MS = 7_000L
    private const val QUALITY_RECOVERY_SETTLE_MS = 30_000L

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

    fun comparisonSettleTimeoutMs(qualityRecovery: Boolean): Long =
        if (qualityRecovery) QUALITY_RECOVERY_SETTLE_MS else QUOTA_COMPARISON_SETTLE_MS

    fun shouldAcceptBadSample(currentCount: Int, lastAcceptedAt: Long, now: Long): Boolean =
        currentCount == 0 || now - lastAcceptedAt >= MIN_BAD_SAMPLE_GAP_MS
}
