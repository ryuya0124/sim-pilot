package dev.simpilot

import android.content.pm.PackageManager
import android.os.SystemClock
import org.json.JSONObject
import rikka.shizuku.Shizuku

object ShizukuBridge {
    fun isReady(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = isReady() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    fun backendInfo(): Result<SwitchBackendInfo> = runCatching {
        check(isReady()) { "Shizukuが起動していません" }
        check(isGranted()) { "Shizuku権限がありません" }
        val raw = ShellUserServiceManager.requireService().describeBackend()
        val json = JSONObject(raw)
        val id = json.getString("id")
        val manufacturer = json.optString("manufacturer", "Android")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val transactions = json.getJSONObject("transactions")
        val roles = SimRole.entries.filterTo(linkedSetOf()) { transactions.has(it.name.lowercase()) }
        SwitchBackendInfo(
            id = id,
            label = if (id == "unsupported") "$manufacturer / 未対応" else "$manufacturer / ISub",
            supportedRoles = roles,
            raw = raw,
        )
    }

    fun setDefault(role: SimRole, subId: Int): Result<Unit> = runCatching {
        check(isReady()) { "Shizukuが起動していません" }
        check(isGranted()) { "Shizuku権限がありません" }
        val response = ShellUserServiceManager.requireService().setDefault(role.name.lowercase(), subId)
        check(response.startsWith("OK:")) { "${role.label}切替に失敗: ${response.take(160)}" }
        val deadline = SystemClock.elapsedRealtime() + VERIFY_TIMEOUT_MS
        while (SwitchAudit.currentDefault(role) != subId && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(VERIFY_INTERVAL_MS)
        }
        check(SwitchAudit.currentDefault(role) == subId) {
            "${role.label}切替の反映を確認できません（target=$subId, response=${response.take(120)}）"
        }
    }

    private const val VERIFY_TIMEOUT_MS = 6_000L
    private const val VERIFY_INTERVAL_MS = 200L
}
