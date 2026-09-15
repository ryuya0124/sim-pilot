package dev.simpilot

import android.content.Context
import android.app.usage.NetworkStatsManager
import android.net.ConnectivityManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.Keep
import org.json.JSONObject
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.TimeUnit

class ShellUserService : IShellService.Stub {
    private var serviceContext: Context? = null

    constructor()

    @Keep
    constructor(context: Context) {
        serviceContext = context
    }

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

    @Suppress("DEPRECATION", "MissingPermission")
    override fun mobileUsage(subId: Int, startTime: Long, endTime: Long): String = runCatching {
        require(subId >= 0 && startTime >= 0 && endTime >= startTime)
        val context = checkNotNull(serviceContext) { "UserService context unavailable" }
        val shellContext = runCatching {
            context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrDefault(context)
        val apiBytes = runCatching {
            val telephony = shellContext.getSystemService(TelephonyManager::class.java)
                .createForSubscriptionId(subId)
            val subscriberId = checkNotNull(telephony.subscriberId)
            val stats = shellContext.getSystemService(NetworkStatsManager::class.java)
                .querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, subscriberId, startTime, endTime)
            stats.rxBytes + stats.txBytes
        }.getOrNull()
        val totalBytes = apiBytes ?: usageFromNetstatsDump(subId, startTime, endTime)
        JSONObject().apply {
            put("subId", subId)
            put("totalBytes", totalBytes)
            put("startTime", startTime)
            put("endTime", endTime)
            put("source", if (apiBytes != null) "network_stats_api" else "netstats_shell")
        }.toString()
    }.getOrElse { error ->
        JSONObject().apply {
            put("subId", subId)
            put("error", error.javaClass.simpleName + ": " + error.message.orEmpty())
        }.toString()
    }

    private fun usageFromNetstatsDump(subId: Int, startTime: Long, endTime: Long): Long {
        val output = runRawCommand("/system/bin/dumpsys", "netstats", "--full")
        val target = "subId=$subId"
        val firstBucket = startTime / 3_600_000L * 3_600L
        val endSeconds = (endTime + 999L) / 1_000L
        var matchingIdentity = false
        var total = 0L
        output.lineSequence().forEach { line ->
            if (line.startsWith("  ident=")) {
                matchingIdentity = line.contains("type=0,") && line.contains(target) &&
                    line.contains("transports={0}") && line.contains(" set=ALL ")
                return@forEach
            }
            if (!matchingIdentity) return@forEach
            val match = NETSTATS_BUCKET.find(line) ?: return@forEach
            val startSeconds = match.groupValues[1].toLongOrNull() ?: return@forEach
            if (startSeconds !in firstBucket until endSeconds) return@forEach
            total += (match.groupValues[2].toLongOrNull() ?: 0L) +
                (match.groupValues[3].toLongOrNull() ?: 0L)
        }
        check(total >= 0) { "Invalid netstats total" }
        return total
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
        return runCatching { "OK:${runRawCommand(*command)}" }
            .getOrElse { "ERROR:${it.message.orEmpty()}" }
    }

    private fun runRawCommand(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        var output = ""
        val reader = Thread {
            output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        }.apply { start() }
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Command timeout")
        }
        reader.join(2_000)
        check(process.exitValue() == 0) { "Command failed: ${process.exitValue()}" }
        return output
    }

    override fun destroy() {
        System.exit(0)
    }

    companion object {
        private const val ISUB_STUB = "com.android.internal.telephony.ISub\$Stub"
        private val NETSTATS_BUCKET = Regex("st=(\\d+) rb=(\\d+) rp=\\d+ tb=(\\d+)")
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
