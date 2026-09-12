package dev.simpilot

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("sim_pilot", Context.MODE_PRIVATE)

    fun load(): MonitorConfig = MonitorConfig(
        enabled = prefs.getBoolean("enabled", false),
        followVoice = prefs.getBoolean("follow_voice", false),
        followSms = prefs.getBoolean("follow_sms", false),
        intervalSeconds = prefs.getInt("interval", 30),
        latencyThresholdMs = prefs.getInt("latency", 1200),
        speedThresholdKbps = prefs.getInt("speed", 512),
        consecutiveFailures = prefs.getInt("failures", 3),
        cooldownMinutes = prefs.getInt("cooldown", 5),
    )

    fun save(config: MonitorConfig) {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putBoolean("follow_voice", config.followVoice)
            .putBoolean("follow_sms", config.followSms)
            .putInt("interval", config.intervalSeconds)
            .putInt("latency", config.latencyThresholdMs)
            .putInt("speed", config.speedThresholdKbps)
            .putInt("failures", config.consecutiveFailures)
            .putInt("cooldown", config.cooldownMinutes)
            .apply()
    }

    var lastSwitchAt: Long
        get() = prefs.getLong("last_switch_at", 0L)
        set(value) = prefs.edit().putLong("last_switch_at", value).apply()
}
