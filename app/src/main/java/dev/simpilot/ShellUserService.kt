package dev.simpilot

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import org.json.JSONObject
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.TimeUnit

class ShellUserService : IShellService.Stub {
    constructor()

    @Keep
    constructor(context: Context)

    override fun describeBackend(): String = resolution().toJson().toString()

    override fun setDefault(role: String, subId: Int): String {
        require(subId >= -1) { "Invalid subscription" }
        val normalizedRole = role.lowercase()
        val backend = resolution()
        val transactionCode = backend.transactions[normalizedRole]
            ?: return "ERROR:unsupported_role:$normalizedRole:${backend.id}"
        val switchResult = runCommand(
            "/system/bin/service",
            "call",
            "isub",
            transactionCode.toString(),
            "i32",
            subId.toString(),
        )
        return if (switchResult.startsWith("OK:") && switchResult.contains("Parcel")) {
            "OK:${backend.id}:transaction=$transactionCode"
        } else {
            "ERROR:${backend.id}:transaction=$transactionCode:$switchResult"
        }
    }

    private fun resolution(): BackendResolution {
        val runtime = SwitchBackendResolver.roles.mapNotNull { (role, method) ->
            dynamicTransaction(method)?.let { role to it }
        }.toMap()
        return SwitchBackendResolver.resolve(isPrimarySamsung(), runtime)
    }

    @Suppress("DEPRECATION")
    private fun dynamicTransaction(methodName: String): Int? = runCatching {
        SystemServiceHelper.getTransactionCode(ISUB_STUB, methodName)
    }.getOrNull()?.takeIf { it > 0 }

    private fun isPrimarySamsung(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.MODEL.equals("SM-S948Q", ignoreCase = true) &&
            Build.VERSION.SDK_INT == 36

    private fun runCommand(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "ERROR:timeout"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return if (process.exitValue() == 0) "OK:$output" else "ERROR:${process.exitValue()}:$output"
    }

    override fun destroy() {
        System.exit(0)
    }

    companion object {
        private const val ISUB_STUB = "com.android.internal.telephony.ISub\$Stub"
    }
}

private fun BackendResolution.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("manufacturer", Build.MANUFACTURER)
    put("model", Build.MODEL)
    put("sdk", Build.VERSION.SDK_INT)
    put("primaryDevice", primaryDevice)
    put("transactions", JSONObject(transactions))
    put("runtimeTransactions", JSONObject(runtimeTransactions))
}
