package dev.simpilot

import android.content.Context
import android.telephony.SubscriptionManager

data class PendingSwitch(
    val targetSubId: Int,
    val origin: String,
    val requestedAt: Long,
)

class SwitchAudit(private val context: Context) {
    private val prefs = AppPreferences(context)

    fun observe(trigger: String, lines: List<SimLine> = emptyList()) = synchronized(lock) {
        SimRole.entries.forEach { role ->
            val current = currentDefault(role)
            val previous = prefs.observedDefault(role)
            val pending = prefs.pendingSwitch(role)
            when {
                previous == null -> {
                    DiagnosticLog.info(
                        context,
                        "default_baseline",
                        "既定SIMの初期値を記録",
                        changeFields(role, null, current, "baseline", trigger, lines),
                    )
                }
                previous != current -> {
                    val actor = classifyActor(current, pending, System.currentTimeMillis())
                    DiagnosticLog.info(
                        context,
                        "default_changed",
                        if (actor.startsWith("app/")) "SIM Pilotによる既定SIM変更を検出" else "外部からの既定SIM変更を検出",
                        changeFields(role, previous, current, actor, trigger, lines),
                    )
                }
            }
            prefs.setObservedDefault(role, current)
            if (pending != null && (current == pending.targetSubId || isExpired(pending, System.currentTimeMillis()))) {
                prefs.clearPendingSwitch(role)
            }
        }
    }

    fun begin(role: SimRole, targetSubId: Int, origin: String, lines: List<SimLine> = emptyList()) {
        val from = currentDefault(role)
        prefs.setPendingSwitch(role, PendingSwitch(targetSubId, origin, System.currentTimeMillis()))
        DiagnosticLog.info(
            context,
            "switch_requested",
            "既定SIMの変更を要求",
            changeFields(role, from, targetSubId, "app/$origin", "request", lines),
        )
    }

    fun result(role: SimRole, targetSubId: Int, origin: String, result: Result<Unit>) {
        if (result.isFailure) prefs.clearPendingSwitch(role)
        val fields = mutableMapOf<String, Any?>(
            "role" to role.name.lowercase(),
            "targetSubId" to targetSubId,
            "origin" to "app/$origin",
            "success" to result.isSuccess,
        )
        result.exceptionOrNull()?.message?.let { fields["errorMessage"] = it }
        if (result.isSuccess) {
            DiagnosticLog.info(context, "switch_result", "既定SIM変更コマンド成功", fields)
        } else {
            DiagnosticLog.error(
                context,
                "switch_result",
                "既定SIM変更コマンド失敗",
                fields,
                result.exceptionOrNull(),
            )
        }
    }

    private fun changeFields(
        role: SimRole,
        from: Int?,
        to: Int,
        actor: String,
        trigger: String,
        lines: List<SimLine>,
    ): Map<String, Any?> = mapOf(
        "role" to role.name.lowercase(),
        "fromSubId" to from,
        "fromName" to from?.let { lineName(it, lines) },
        "toSubId" to to,
        "toName" to lineName(to, lines),
        "actor" to actor,
        "trigger" to trigger,
    )

    private fun lineName(subId: Int, lines: List<SimLine>): String =
        lines.firstOrNull { it.subId == subId }?.title ?: "unknown"

    companion object {
        private const val PENDING_WINDOW_MS = 60_000L
        private val lock = Any()

        internal fun classifyActor(currentSubId: Int, pending: PendingSwitch?, now: Long): String =
            if (pending != null && pending.targetSubId == currentSubId && !isExpired(pending, now)) {
                "app/${pending.origin}"
            } else {
                "external_user_or_system"
            }

        private fun isExpired(pending: PendingSwitch, now: Long): Boolean =
            now - pending.requestedAt !in 0..PENDING_WINDOW_MS

        fun currentDefault(role: SimRole): Int = when (role) {
            SimRole.DATA -> SubscriptionManager.getDefaultDataSubscriptionId()
            SimRole.VOICE -> SubscriptionManager.getDefaultVoiceSubscriptionId()
            SimRole.SMS -> SubscriptionManager.getDefaultSmsSubscriptionId()
        }
    }
}
