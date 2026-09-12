package dev.simpilot

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

class ShellUserService : IShellService.Stub {
    constructor()

    @Keep
    constructor(context: Context)

    override fun setDefault(transactionCode: Int, subId: Int): String {
        require(transactionCode in setOf(31, 34, 37)) { "Unsupported transaction" }
        require(subId >= -1) { "Invalid subscription" }
        val switchResult = runCommand(
            "/system/bin/service",
            "call",
            "isub",
            transactionCode.toString(),
            "i32",
            subId.toString(),
        )
        if (!switchResult.startsWith("OK:") || !switchResult.contains("Parcel")) return switchResult

        Thread.sleep(300)
        val key = when (transactionCode) {
            31 -> "multi_sim_data_call"
            34 -> "multi_sim_voice_call"
            else -> "multi_sim_sms"
        }
        val verify = runCommand("/system/bin/settings", "get", "global", key)
        return if (verify == "OK:$subId") switchResult else "ERROR:verification:$key:$verify"
    }

    private fun runCommand(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "ERROR:timeout"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return if (process.exitValue() == 0) {
            "OK:$output"
        } else {
            "ERROR:${process.exitValue()}:$output"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
