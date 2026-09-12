package dev.simpilot

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuBridge {
    fun isReady(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = isReady() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    fun setDefault(role: SimRole, subId: Int): Result<Unit> = runCatching {
        check(isReady()) { "Shizukuが起動していません" }
        check(isGranted()) { "Shizuku権限がありません" }
        val response = ShellUserServiceManager.requireService().setDefault(role.transactionCode, subId)
        check(response.startsWith("OK:")) { "${role.label}切替に失敗: ${response.take(160)}" }
    }
}
