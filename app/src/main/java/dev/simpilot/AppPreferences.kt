package dev.simpilot

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("sim_pilot", Context.MODE_PRIVATE)

    fun load(): MonitorConfig = MonitorConfig(
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
        speedThresholdKbps = prefs.getInt("speed", 512),
        consecutiveFailures = prefs.getInt("failures", 3),
        cooldownMinutes = prefs.getInt("cooldown", 5),
    )

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
            .putInt("failures", config.consecutiveFailures)
            .putInt("cooldown", config.cooldownMinutes)
            .apply()
    }

    var lastSwitchAt: Long
        get() = prefs.getLong("last_switch_at", 0L)
        set(value) = prefs.edit().putLong("last_switch_at", value).apply()
}
