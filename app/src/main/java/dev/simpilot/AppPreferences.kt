package dev.simpilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppPreferences(context: Context) {
    private val storageContext = if (context.isDeviceProtectedStorage) {
        context
    } else {
        context.createDeviceProtectedStorageContext().also {
            it.moveSharedPreferencesFrom(context, PREFS_NAME)
        }
    }
    private val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): MonitorConfig {
        val qualityV2 = prefs.getBoolean("quality_v2", false)
        return MonitorConfig(
        enabled = prefs.getBoolean("enabled", false),
        followVoice = prefs.getBoolean("follow_voice", false),
        followSms = prefs.getBoolean("follow_sms", false),
        wifiRestoreEnabled = prefs.getBoolean("wifi_restore_enabled", false),
        wifiDataEnabled = prefs.getBoolean("wifi_data_enabled", true),
        wifiDataSubId = prefs.getInt("wifi_data_sub_id", -1),
        wifiVoiceEnabled = prefs.getBoolean("wifi_voice_enabled", false),
        wifiVoiceSubId = prefs.getInt("wifi_voice_sub_id", -1),
        wifiSmsEnabled = prefs.getBoolean("wifi_sms_enabled", false),
        wifiSmsSubId = prefs.getInt("wifi_sms_sub_id", -1),
        intervalSeconds = prefs.getInt("interval", 30),
        latencyThresholdMs = prefs.getInt("latency", 1200),
        speedThresholdKbps = if (qualityV2) prefs.getInt("speed", 5_000) else 5_000,
        weakSignalDbm = prefs.getInt("weak_signal_dbm", -114),
        consecutiveFailures = prefs.getInt("failures", 3),
        cooldownMinutes = prefs.getInt("cooldown", 5),
        )
    }

    fun save(config: MonitorConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putBoolean("follow_voice", config.followVoice)
            .putBoolean("follow_sms", config.followSms)
            .putBoolean("wifi_restore_enabled", config.wifiRestoreEnabled)
            .putBoolean("wifi_data_enabled", config.wifiDataEnabled)
            .putInt("wifi_data_sub_id", config.wifiDataSubId)
            .putBoolean("wifi_voice_enabled", config.wifiVoiceEnabled)
            .putInt("wifi_voice_sub_id", config.wifiVoiceSubId)
            .putBoolean("wifi_sms_enabled", config.wifiSmsEnabled)
            .putInt("wifi_sms_sub_id", config.wifiSmsSubId)
            .putInt("interval", config.intervalSeconds)
            .putInt("latency", config.latencyThresholdMs)
            .putInt("speed", config.speedThresholdKbps)
            .putInt("weak_signal_dbm", config.weakSignalDbm)
            .putBoolean("quality_v2", true)
            .putInt("failures", config.consecutiveFailures)
            .putInt("cooldown", config.cooldownMinutes)
            .apply()
    }

    fun loadPlans(): List<SimPlanConfig> = runCatching {
        val array = JSONArray(prefs.getString("data_plans", "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val allowancesJson = item.optJSONArray("allowances") ?: JSONArray()
                val allowances = buildList {
                    for (allowanceIndex in 0 until allowancesJson.length()) {
                        val allowance = allowancesJson.getJSONObject(allowanceIndex)
                        add(
                            DataAllowance(
                                id = allowance.getLong("id"),
                                name = allowance.optString("name", "データ容量"),
                                capacityBytes = allowance.getLong("capacityBytes"),
                                startEpochDay = allowance.getLong("startEpochDay"),
                                endEpochDay = allowance.getLong("endEpochDay"),
                            )
                        )
                    }
                }
                add(
                    SimPlanConfig(
                        subId = item.getInt("subId"),
                        enabled = item.optBoolean("enabled", false),
                        type = runCatching { DataPlanType.valueOf(item.optString("type")) }.getOrDefault(DataPlanType.MONTHLY),
                        capacityBytes = item.optLong("capacityBytes", 20L * GB),
                        billingDay = item.optInt("billingDay", 1),
                        startEpochDay = item.optLong("startEpochDay", java.time.LocalDate.now().toEpochDay()),
                        endEpochDay = item.optLong("endEpochDay", java.time.LocalDate.now().plusMonths(1).minusDays(1).toEpochDay()),
                        allowances = allowances,
                        manualRemainingBytes = if (item.has("manualRemainingBytes") && !item.isNull("manualRemainingBytes")) {
                            item.getLong("manualRemainingBytes")
                        } else null,
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun savePlans(plans: List<SimPlanConfig>) {
        val array = JSONArray()
        plans.forEach { plan ->
            array.put(JSONObject().apply {
                put("subId", plan.subId)
                put("enabled", plan.enabled)
                put("type", plan.type.name)
                put("capacityBytes", plan.capacityBytes)
                put("billingDay", plan.billingDay)
                put("startEpochDay", plan.startEpochDay)
                put("endEpochDay", plan.endEpochDay)
                put("manualRemainingBytes", plan.manualRemainingBytes ?: JSONObject.NULL)
                put("allowances", JSONArray().apply {
                    plan.allowances.forEach { allowance ->
                        put(JSONObject().apply {
                            put("id", allowance.id)
                            put("name", allowance.name)
                            put("capacityBytes", allowance.capacityBytes)
                            put("startEpochDay", allowance.startEpochDay)
                            put("endEpochDay", allowance.endEpochDay)
                        })
                    }
                })
            })
        }
        prefs.edit().putString("data_plans", array.toString()).apply()
    }

    var lastSwitchAt: Long
        get() = prefs.getLong("last_switch_at", 0L)
        set(value) = prefs.edit().putLong("last_switch_at", value).apply()

    fun observedDefault(role: SimRole): Int? {
        val key = "audit_observed_${role.name.lowercase()}"
        return if (prefs.contains(key)) prefs.getInt(key, -1) else null
    }

    fun setObservedDefault(role: SimRole, subId: Int) {
        prefs.edit().putInt("audit_observed_${role.name.lowercase()}", subId).apply()
    }

    fun pendingSwitch(role: SimRole): PendingSwitch? {
        val prefix = "audit_pending_${role.name.lowercase()}"
        if (!prefs.contains("${prefix}_target")) return null
        return PendingSwitch(
            targetSubId = prefs.getInt("${prefix}_target", -1),
            origin = prefs.getString("${prefix}_origin", "unknown") ?: "unknown",
            requestedAt = prefs.getLong("${prefix}_at", 0L),
        )
    }

    fun setPendingSwitch(role: SimRole, pending: PendingSwitch) {
        val prefix = "audit_pending_${role.name.lowercase()}"
        prefs.edit()
            .putInt("${prefix}_target", pending.targetSubId)
            .putString("${prefix}_origin", pending.origin)
            .putLong("${prefix}_at", pending.requestedAt)
            .commit()
    }

    fun clearPendingSwitch(role: SimRole) {
        val prefix = "audit_pending_${role.name.lowercase()}"
        prefs.edit()
            .remove("${prefix}_target")
            .remove("${prefix}_origin")
            .remove("${prefix}_at")
            .apply()
    }

    fun claimUnlockCheck(now: Long = System.currentTimeMillis()): Boolean = synchronized(unlockCheckLock) {
        val last = prefs.getLong("last_unlock_check", 0L)
        if (now - last in 0 until UNLOCK_DEDUP_MS) return false
        prefs.edit().putLong("last_unlock_check", now).commit()
    }

    companion object {
        private const val PREFS_NAME = "sim_pilot"
        private const val UNLOCK_DEDUP_MS = 5_000L
        private val unlockCheckLock = Any()
    }
}
