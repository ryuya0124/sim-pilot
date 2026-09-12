package dev.simpilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )) return

        val preferences = AppPreferences(context)
        val config = preferences.load()
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        val forceCheck = unlocked &&
            action == Intent.ACTION_BOOT_COMPLETED &&
            preferences.claimUnlockCheck()
        DiagnosticLog.info(
            context,
            "system_event",
            "システム起動イベントを受信",
            mapOf(
                "action" to action,
                "userUnlocked" to unlocked,
                "monitorEnabled" to config.enabled,
                "wifiRestoreEnabled" to config.wifiRestoreEnabled,
                "shizukuReady" to ShizukuBridge.isReady(),
                "forceCheck" to forceCheck,
            ),
        )
        if (config.needsService()) {
            runCatching { MonitorService.start(context, testNow = forceCheck, reason = "system:$action") }
                .onSuccess {
                    DiagnosticLog.info(context, "service_start_requested", "システムイベントから監視開始を要求")
                }
                .onFailure {
                    DiagnosticLog.error(context, "service_start_failed", "システムイベントから監視を開始できませんでした", error = it)
                }
        }
    }
}
