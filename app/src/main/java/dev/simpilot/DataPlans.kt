package dev.simpilot

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class DataPlanType(val label: String) {
    MONTHLY("毎月更新"),
    FIXED("期間指定"),
    FLEXIBLE("柔軟プラン"),
}

data class DataAllowance(
    val id: Long,
    val name: String,
    val capacityBytes: Long,
    val startEpochDay: Long,
    val endEpochDay: Long,
)

data class SimPlanConfig(
    val subId: Int,
    val enabled: Boolean = false,
    val type: DataPlanType = DataPlanType.MONTHLY,
    val capacityBytes: Long = 20L * GB,
    val billingDay: Int = 1,
    val startEpochDay: Long = LocalDate.now().toEpochDay(),
    val endEpochDay: Long = LocalDate.now().plusMonths(1).minusDays(1).toEpochDay(),
    val allowances: List<DataAllowance> = emptyList(),
    val manualRemainingBytes: Long? = null,
)

data class DataPlanWindow(
    val startMillis: Long,
    val endMillis: Long,
    val capacityBytes: Long,
)

data class DataUsageState(
    val usedBytes: Long,
    val capacityBytes: Long,
    val remainingBytes: Long,
    val remainingRatio: Double,
    val manual: Boolean,
    val available: Boolean = true,
)

object DataPlanPolicy {
    fun window(plan: SimPlanConfig, today: LocalDate = LocalDate.now()): DataPlanWindow? {
        if (!plan.enabled) return null
        val zone = ZoneId.systemDefault()
        val (start, endExclusive, capacity) = when (plan.type) {
            DataPlanType.MONTHLY -> {
                val currentMonth = YearMonth.from(today)
                val thisStart = currentMonth.atDay(plan.billingDay.coerceIn(1, currentMonth.lengthOfMonth()))
                val startDate = if (today < thisStart) {
                    val previous = currentMonth.minusMonths(1)
                    previous.atDay(plan.billingDay.coerceIn(1, previous.lengthOfMonth()))
                } else thisStart
                val next = YearMonth.from(startDate).plusMonths(1)
                    .atDay(plan.billingDay.coerceIn(1, YearMonth.from(startDate).plusMonths(1).lengthOfMonth()))
                Triple(startDate, next, plan.capacityBytes)
            }
            DataPlanType.FIXED -> {
                if (today.toEpochDay() !in plan.startEpochDay..plan.endEpochDay) return null
                Triple(
                    LocalDate.ofEpochDay(plan.startEpochDay),
                    LocalDate.ofEpochDay(plan.endEpochDay).plusDays(1),
                    plan.capacityBytes,
                )
            }
            DataPlanType.FLEXIBLE -> {
                val active = plan.allowances.filter {
                    today.toEpochDay() in it.startEpochDay..it.endEpochDay
                }
                if (active.isEmpty()) return null
                Triple(
                    LocalDate.ofEpochDay(active.minOf { it.startEpochDay }),
                    LocalDate.ofEpochDay(active.maxOf { it.endEpochDay }).plusDays(1),
                    active.sumOf { it.capacityBytes },
                )
            }
        }
        if (capacity <= 0 || !endExclusive.isAfter(start)) return null
        return DataPlanWindow(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            endExclusive.atStartOfDay(zone).toInstant().toEpochMilli(),
            capacity,
        )
    }

    fun state(plan: SimPlanConfig, measuredBytes: Long?): DataUsageState? {
        val window = window(plan) ?: return null
        val manual = plan.manualRemainingBytes
        val used = when {
            manual != null -> (window.capacityBytes - manual).coerceIn(0, window.capacityBytes)
            measuredBytes != null -> measuredBytes.coerceAtLeast(0)
            else -> 0
        }
        val remaining = when {
            manual != null -> manual.coerceIn(0, window.capacityBytes)
            measuredBytes != null -> (window.capacityBytes - measuredBytes).coerceAtLeast(0)
            else -> window.capacityBytes
        }
        return DataUsageState(
            usedBytes = used,
            capacityBytes = window.capacityBytes,
            remainingBytes = remaining,
            remainingRatio = remaining.toDouble() / window.capacityBytes,
            manual = manual != null,
            available = manual != null || measuredBytes != null,
        )
    }

    fun candidateHasClearAdvantage(current: DataUsageState?, candidate: DataUsageState?): Boolean {
        if (current?.available != true || candidate?.available != true) return false
        val ratioGain = candidate.remainingRatio - current.remainingRatio
        val byteGain = candidate.remainingBytes - current.remainingBytes
        return ratioGain >= 0.15 && byteGain >= 500L * MB
    }

    fun epochMillisToDay(epochMillis: Long): Long = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
}

const val MB = 1_000_000L
const val GB = 1_000_000_000L
