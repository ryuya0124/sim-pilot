package dev.simpilot

enum class SimRole(val label: String) {
    DATA("データ"),
    VOICE("通話"),
    SMS("メッセージ"),
}

data class SwitchBackendInfo(
    val id: String,
    val label: String,
    val supportedRoles: Set<SimRole>,
    val raw: String,
) {
    fun supports(role: SimRole): Boolean = role in supportedRoles
}

data class SimLine(
    val subId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String,
    val signalLevel: Int = -1,
    val dbm: Int? = null,
    val inService: Boolean = false,
    val serviceStateKnown: Boolean = false,
    val networkType: String = "—",
    val radio: RadioMetrics? = null,
) {
    val title: String get() = displayName.ifBlank { carrierName.ifBlank { "SIM ${slotIndex + 1}" } }
    val subtitle: String get() = "SIM ${slotIndex + 1} · ${carrierName.ifBlank { "回線" }}"
}

data class RadioMetrics(
    val technology: String,
    val primaryConnected: Boolean,
    val servingCellCount: Int,
    val neighboringCellCount: Int,
    val secondaryCellCount: Int,
    val bandNumber: Int? = null,
    val bandName: String? = null,
    val channelNumber: Int? = null,
    val aggregatedBands: List<String> = emptyList(),
    val bandwidthKhz: Int? = null,
    val referenceDbm: Int? = null,
    val rssiDbm: Int? = null,
    val rsrpDbm: Double? = null,
    val rsrqDb: Double? = null,
    val sinrDb: Double? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,
    val pci: Int? = null,
    val areaCode: Long? = null,
    val cellId: Long? = null,
    val cells: List<RadioCellObservation> = emptyList(),
    val observedAtElapsed: Long,
) {
    val carrierAggregation: Boolean
        get() = secondaryCellCount > 0 || aggregatedBands.isNotEmpty()

    val bandLabel: String?
        get() = bandNumber?.let { number ->
            val prefix = if (technology.startsWith("5G")) "n" else "B"
            "$prefix$number"
        } ?: bandName
}

data class RadioCellObservation(
    val technology: String,
    val connection: String,
    val connectionInferred: Boolean = false,
    val network: List<RadioField> = emptyList(),
    val band: List<RadioField> = emptyList(),
    val identity: List<RadioField> = emptyList(),
    val signal: List<RadioField> = emptyList(),
    val sourceTimestamp: Long? = null,
) {
    val title: String
        get() = buildString {
            append(technology)
            band.firstOrNull { it.key == "band" }?.value?.let { append(" · ").append(it) }
        }

    fun logValue(): Map<String, Any?> = mapOf(
        "technology" to technology,
        "connection" to connection,
        "connectionInferred" to connectionInferred,
        "network" to network.associate { it.key to it.value },
        "band" to band.associate { it.key to it.value },
        "identity" to identity.associate { it.key to it.value },
        "signal" to signal.associate { it.key to it.value },
        "sourceTimestamp" to sourceTimestamp,
    )
}

data class RadioField(
    val key: String,
    val label: String,
    val value: String,
)

data class MonitorConfig(
    val enabled: Boolean = false,
    val followVoice: Boolean = false,
    val followSms: Boolean = false,
    val wifiRestoreEnabled: Boolean = false,
    val wifiDataEnabled: Boolean = true,
    val wifiDataSubId: Int = -1,
    val wifiVoiceEnabled: Boolean = false,
    val wifiVoiceSubId: Int = -1,
    val wifiSmsEnabled: Boolean = false,
    val wifiSmsSubId: Int = -1,
    val intervalSeconds: Int = 30,
    val latencyThresholdMs: Int = 1200,
    val speedThresholdKbps: Int = 5_000,
    val weakSignalDbm: Int = -114,
    val consecutiveFailures: Int = 3,
    val cooldownMinutes: Int = 5,
) {
    fun needsService(): Boolean = enabled || wifiRestoreEnabled
}

data class AppSnapshot(
    val lines: List<SimLine> = emptyList(),
    val dataSubId: Int = -1,
    val voiceSubId: Int = -1,
    val smsSubId: Int = -1,
    val shizukuReady: Boolean = false,
    val shizukuGranted: Boolean = false,
    val switchBackend: String = "切替方式を確認中",
    val backendChecked: Boolean = false,
    val supportedRoles: Set<SimRole> = emptySet(),
    val monitorRunning: Boolean = false,
    val status: String = "初期化中",
    val lastLatencyMs: Long? = null,
    val lastSpeedKbps: Long? = null,
    val badSamples: Int = 0,
    val lastSwitchAt: Long? = null,
    val dataUsage: Map<Int, DataUsageState> = emptyMap(),
)

object AppState {
    private val lock = Any()
    private var value = AppSnapshot()
    private val listeners = LinkedHashSet<(AppSnapshot) -> Unit>()

    fun current(): AppSnapshot = synchronized(lock) { value }

    fun update(block: (AppSnapshot) -> AppSnapshot) {
        val next: AppSnapshot
        val targets: List<(AppSnapshot) -> Unit>
        synchronized(lock) {
            next = block(value)
            value = next
            targets = listeners.toList()
        }
        targets.forEach { it(next) }
    }

    fun addListener(listener: (AppSnapshot) -> Unit) {
        val snapshot: AppSnapshot
        synchronized(lock) {
            listeners += listener
            snapshot = value
        }
        listener(snapshot)
    }

    fun removeListener(listener: (AppSnapshot) -> Unit) {
        synchronized(lock) { listeners -= listener }
    }
}
