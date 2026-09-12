package dev.simpilot

internal data class BackendResolution(
    val id: String,
    val transactions: Map<String, Int>,
    val runtimeTransactions: Map<String, Int>,
    val primaryDevice: Boolean,
)

internal object SwitchBackendResolver {
    val roles = mapOf(
        "data" to "setDefaultDataSubId",
        "voice" to "setDefaultVoiceSubId",
        "sms" to "setDefaultSmsSubId",
    )

    private val primaryFallback = mapOf(
        "data" to 31,
        "voice" to 34,
        "sms" to 37,
    )

    fun resolve(primaryDevice: Boolean, runtime: Map<String, Int>): BackendResolution {
        if (!primaryDevice) {
            return if (runtime.isEmpty()) {
                BackendResolution("unsupported", emptyMap(), emptyMap(), false)
            } else {
                BackendResolution("runtime_isub", runtime, runtime, false)
            }
        }
        return when {
            runtime.size == roles.size -> BackendResolution(
                "samsung_sm_s948q_dynamic_verified",
                runtime,
                runtime,
                true,
            )
            runtime.isNotEmpty() -> BackendResolution(
                "samsung_sm_s948q_dynamic_hybrid",
                primaryFallback + runtime,
                runtime,
                true,
            )
            else -> BackendResolution(
                "samsung_sm_s948q_oneui85_fallback",
                primaryFallback,
                emptyMap(),
                true,
            )
        }
    }
}
