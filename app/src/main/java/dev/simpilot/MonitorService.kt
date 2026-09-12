package dev.simpilot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

class MonitorService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: AppPreferences
    private lateinit var sims: SimRepository
    private lateinit var connectivity: ConnectivityManager
    private var badSamples = 0
    private var sampleCount = 0
    private var lastWifiPolicySignature: String? = null
    private val stopped = AtomicBoolean(false)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNext(250)
        override fun onLost(network: Network) = scheduleNext(250)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = scheduleNext(250)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        sims = SimRepository(this, mainExecutor)
        connectivity.registerDefaultNetworkCallback(networkCallback)
        createNotificationChannel()
        startAsForeground("準備中")
        AppState.update { it.copy(monitorRunning = true, status = "モニターを準備中") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.save(prefs.load().copy(enabled = false, wifiRestoreEnabled = false))
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_NOW -> worker.execute { runCycle(forceDeepProbe = true) }
            else -> scheduleNext(0)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        sims.unregister()
        worker.shutdownNow()
        AppState.update { it.copy(monitorRunning = false, status = "停止中") }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleNext(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!stopped.get()) worker.execute { runCycle(false) }
        }, delayMs)
    }

    private fun runCycle(forceDeepProbe: Boolean) {
        val config = prefs.load()
        if (!config.needsService() && !forceDeepProbe) {
            stopSelf()
            return
        }
        val lines = sims.refresh()
        if (!sims.hasPhonePermission()) {
            finishCycle("電話の権限を許可してください", config)
            return
        }
        val active = connectivity.activeNetwork
        val activeCaps = active?.let(connectivity::getNetworkCapabilities)
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            badSamples = 0
            if (!config.wifiRestoreEnabled) {
                lastWifiPolicySignature = config.wifiPolicySignature()
                finishCycle("Wi-Fi接続中 · モバイル切替停止", config)
                return
            }
            if (!ShizukuBridge.isGranted()) {
                finishCycle("Wi-Fi復帰にはShizuku権限が必要です", config)
                return
            }
            val inCall = isInCall()
            if (inCall) {
                finishCycle("Wi-Fi接続中 · 通話終了後に既定SIMへ復帰", config)
                return
            }
            val signature = config.wifiPolicySignature() + ":" + lines.joinToString(",") { it.subId.toString() }
            val message = if (lastWifiPolicySignature != signature) {
                applyWifiPolicy(config, lines).also { lastWifiPolicySignature = signature }
            } else {
                "Wi-Fi接続中 · 既定SIM復帰済み"
            }
            finishCycle(message, config)
            return
        }
        lastWifiPolicySignature = null

        if (!config.enabled) {
            finishCycle("モバイル自動切替は停止中", config)
            return
        }

        if (!ShizukuBridge.isGranted()) {
            finishCycle("Shizuku権限を許可してください", config)
            return
        }
        if (lines.size < 2) {
            finishCycle("有効なSIMは${lines.size}回線 · 自動切替を待機", config)
            return
        }

        val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val current = lines.firstOrNull { it.subId == dataSubId }
        val alternate = lines.firstOrNull { it.subId != dataSubId && AutoSwitchDecider.canUseAlternate(it) }
        if (current == null) {
            finishCycle("データSIMを確認できません", config)
            return
        }

        val cellularNetwork = findCellularNetwork()
        val caps = cellularNetwork?.let(connectivity::getNetworkCapabilities)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val latency = cellularNetwork?.let(::latencyProbe)
        sampleCount++
        val shouldMeasureSpeed = forceDeepProbe || latency == null || latency > config.latencyThresholdMs || sampleCount % 10 == 0
        val speed = if (shouldMeasureSpeed) cellularNetwork?.let(::speedProbe) else null
        val sample = QualitySample(current.inService, current.signalLevel, validated, latency, speed)
        val bad = AutoSwitchDecider.isBad(sample, config)
        badSamples = if (bad) badSamples + 1 else 0

        AppState.update {
            it.copy(lastLatencyMs = latency, lastSpeedKbps = speed, badSamples = badSamples)
        }

        val cooldownMs = config.cooldownMinutes * 60_000L
        val inCooldown = System.currentTimeMillis() - prefs.lastSwitchAt < cooldownMs
        val inCall = isInCall()
        if (badSamples >= config.consecutiveFailures && alternate != null && !inCooldown && !inCall) {
            switchDataAndFollowers(alternate.subId, config)
            badSamples = 0
            val message = "${current.title} → ${alternate.title} に切替"
            finishCycle(message, config)
            return
        }

        val detail = buildString {
            append(current.title)
            append(" · ")
            append(latency?.let { "${it}ms" } ?: "応答なし")
            speed?.let { append(" · ${it}kbps") }
            if (badSamples > 0) append(" · 低品質 $badSamples/${config.consecutiveFailures}")
            if (inCall) append(" · 通話中は切替保留")
            else if (inCooldown && bad) append(" · クールダウン中")
        }
        finishCycle(detail, config)
    }

    private fun switchDataAndFollowers(subId: Int, config: MonitorConfig) {
        ShizukuBridge.setDefault(SimRole.DATA, subId).getOrThrow()
        if (config.followVoice) ShizukuBridge.setDefault(SimRole.VOICE, subId).getOrThrow()
        if (config.followSms) ShizukuBridge.setDefault(SimRole.SMS, subId).getOrThrow()
        prefs.lastSwitchAt = System.currentTimeMillis()
        AppState.update { it.copy(lastSwitchAt = prefs.lastSwitchAt) }
    }

    private fun applyWifiPolicy(config: MonitorConfig, lines: List<SimLine>): String {
        val requested = buildList {
            if (config.wifiDataEnabled) add(SimRole.DATA to config.wifiDataSubId)
            if (config.wifiVoiceEnabled) add(SimRole.VOICE to config.wifiVoiceSubId)
            if (config.wifiSmsEnabled) add(SimRole.SMS to config.wifiSmsSubId)
        }
        if (requested.isEmpty()) return "Wi-Fi接続中 · 復帰対象なし"

        val changed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        requested.forEach { (role, subId) ->
            val line = lines.firstOrNull { it.subId == subId }
            if (line == null) {
                skipped += if (subId < 0) "${role.label}SIM未設定" else "${role.label}SIM未検出"
                return@forEach
            }
            val current = when (role) {
                SimRole.DATA -> SubscriptionManager.getDefaultDataSubscriptionId()
                SimRole.VOICE -> SubscriptionManager.getDefaultVoiceSubscriptionId()
                SimRole.SMS -> SubscriptionManager.getDefaultSmsSubscriptionId()
            }
            if (current == subId) return@forEach
            ShizukuBridge.setDefault(role, subId)
                .onSuccess { changed += "${role.label}=${line.title}" }
                .onFailure { skipped += "${role.label}失敗" }
        }
        sims.refresh()
        return when {
            changed.isNotEmpty() && skipped.isNotEmpty() -> "Wi-Fi復帰: ${changed.joinToString()} · ${skipped.joinToString()}"
            changed.isNotEmpty() -> "Wi-Fi復帰: ${changed.joinToString()}"
            skipped.isNotEmpty() -> "Wi-Fi復帰保留: ${skipped.joinToString()}"
            else -> "Wi-Fi接続中 · 既定SIM復帰済み"
        }
    }

    private fun finishCycle(message: String, config: MonitorConfig) {
        AppState.update {
            it.copy(
                monitorRunning = config.needsService(),
                status = message,
                shizukuReady = ShizukuBridge.isReady(),
                shizukuGranted = ShizukuBridge.isGranted(),
                badSamples = badSamples,
            )
        }
        updateNotification(message)
        if (config.needsService() && !stopped.get()) scheduleNext(config.intervalSeconds * 1_000L)
    }

    @SuppressLint("MissingPermission")
    private fun isInCall(): Boolean =
        runCatching { getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)

    private fun findCellularNetwork(): Network? {
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
    }

    private fun latencyProbe(network: Network): Long? = runCatching {
        val started = System.nanoTime()
        val connection = network.openConnection(URL(LATENCY_URL)) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.connect()
            connection.responseCode
            ((System.nanoTime() - started) / 1_000_000.0).roundToLong()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun speedProbe(network: Network): Long? = runCatching {
        val started = System.nanoTime()
        val connection = network.openConnection(URL(SPEED_URL)) as HttpURLConnection
        try {
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.useCaches = false
            var bytes = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                }
            }
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            ((bytes * 8.0 / 1_000.0) / seconds).roundToLong()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.monitor_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SIM Pilot")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setRequestPromotedOngoing(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun startAsForeground(message: String) {
        val n = notification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    companion object {
        private const val CHANNEL_ID = "sim_pilot_monitor"
        private const val NOTIFICATION_ID = 6201
        private const val LATENCY_URL = "https://connectivitycheck.gstatic.com/generate_204"
        private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=65536"
        const val ACTION_STOP = "dev.simpilot.STOP"
        const val ACTION_TEST_NOW = "dev.simpilot.TEST_NOW"

        fun start(context: Context, testNow: Boolean = false) {
            val intent = Intent(context, MonitorService::class.java)
            if (testNow) intent.action = ACTION_TEST_NOW
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
