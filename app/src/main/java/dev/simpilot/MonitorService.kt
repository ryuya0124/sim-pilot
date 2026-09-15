package dev.simpilot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import rikka.shizuku.Shizuku

class MonitorService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: AppPreferences
    private lateinit var switchAudit: SwitchAudit
    private lateinit var sims: SimRepository
    private lateinit var connectivity: ConnectivityManager
    private lateinit var powerManager: PowerManager
    private lateinit var subscriptionManager: SubscriptionManager
    private var badSamples = 0
    private var sampleCount = 0
    private val alternateProbeCache = mutableMapOf<Int, TimedQualitySample>()
    private val usageCache = mutableMapOf<Int, TimedUsage>()
    private val lastComparisonTrialAt = mutableMapOf<Int, Long>()
    private var directAlternateRequestSupported: Boolean? = null
    private var boundNetworkProbeSupported: Boolean? = null
    private var networkCallbackRegistered = false
    private var cellularNetworkCallbackRegistered = false
    private val cellularNetworks = ConcurrentHashMap.newKeySet<Network>()
    private var subscriptionListenerRegistered = false
    private var settingsObserverRegistered = false
    private var unlockReceiverRegistered = false
    private var screenReceiverRegistered = false
    private var shizukuListenersRegistered = false
    private var scheduledAtElapsed = Long.MAX_VALUE
    @Volatile private var backendInfo: SwitchBackendInfo? = null
    private val stopped = AtomicBoolean(false)
    private val cycleInFlight = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val forceDeepProbeRequested = AtomicBoolean(false)
    private val scheduledCycle = Runnable {
        scheduledAtElapsed = Long.MAX_VALUE
        if (!stopped.get()) enqueueCycle(forceDeepProbe = false)
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = requestRealtimeCheck("network_available", 250)
        override fun onLost(network: Network) = requestRealtimeCheck("network_lost", 250)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            requestRealtimeCheck("network_capabilities", 250)
    }
    private val cellularNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            cellularNetworks += network
            requestRealtimeCheck("cellular_network_available", 250)
        }

        override fun onLost(network: Network) {
            cellularNetworks -= network
            requestRealtimeCheck("cellular_network_lost", 250)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            cellularNetworks += network
            requestRealtimeCheck("cellular_network_capabilities", 250)
        }
    }
    private val subscriptionsChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            requestRealtimeCheck("active_subscriptions", 200)
        }
    }
    private val defaultSubscriptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            DiagnosticLog.info(
                this@MonitorService,
                "subscription_event",
                "既定SIM変更通知を受信",
                mapOf("action" to intent?.action),
            )
            requestRealtimeCheck("default_subscription", 150)
        }
    }
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            val unlocked = getSystemService(UserManager::class.java).isUserUnlocked
            val forceCheck = unlocked && prefs.claimUnlockCheck()
            DiagnosticLog.info(
                this@MonitorService,
                "unlock_event",
                "ロック解除イベントを受信",
                mapOf("action" to action, "userUnlocked" to unlocked, "forceCheck" to forceCheck),
            )
            if (forceCheck) scheduleNext(0, forceDeepProbe = true)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (powerManager.isInteractive) return
                    cancelScheduledCycle()
                    forceDeepProbeRequested.set(false)
                    rerunRequested.set(false)
                    badSamples = 0
                    sims.pause()
                    AppState.update { it.copy(status = "スリープ中 · 監視を一時停止", badSamples = 0) }
                    DiagnosticLog.info(this@MonitorService, "sleep_paused", "スリープ中は監視を一時停止")
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (!powerManager.isInteractive) return
                    sims.resume()
                    DiagnosticLog.info(this@MonitorService, "sleep_resumed", "画面復帰で監視を再開")
                    scheduleNext(0)
                }
            }
        }
    }
    private val shizukuReceivedListener = Shizuku.OnBinderReceivedListener {
        backendInfo = null
        DiagnosticLog.info(this, "shizuku_available", "Shizukuが利用可能になりました")
        scheduleNext(0, forceDeepProbe = true)
    }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        backendInfo = null
        AppState.update {
            it.copy(switchBackend = "Shizukuの起動待ち", backendChecked = false, supportedRoles = emptySet())
        }
        DiagnosticLog.warn(this, "shizuku_unavailable", "Shizukuとの接続が失われました")
        scheduleNext(0)
    }
    private val defaultSettingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            DiagnosticLog.info(
                this@MonitorService,
                "subscription_event",
                "One UIの既定SIM設定変更を検出",
                mapOf("source" to "settings_observer", "key" to uri?.lastPathSegment),
            )
            requestRealtimeCheck("default_setting", 150)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        switchAudit = SwitchAudit(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        subscriptionManager = getSystemService(SubscriptionManager::class.java)
        sims = SimRepository(this, mainExecutor) { event, subId ->
            requestRealtimeCheck(event, 300, mapOf("subId" to subId))
        }
        if (!powerManager.isInteractive) sims.pause()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { networkCallbackRegistered = true }
            .onFailure { DiagnosticLog.warn(this, "network_callback_failed", "ネットワーク監視の登録に失敗", error = it) }
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, cellularNetworkCallback)
            cellularNetworkCallbackRegistered = true
        }.onFailure {
            DiagnosticLog.warn(this, "cellular_network_callback_failed", "モバイル回線監視の登録に失敗", error = it)
        }
        runCatching {
            subscriptionManager.addOnSubscriptionsChangedListener(mainExecutor, subscriptionsChangedListener)
            subscriptionListenerRegistered = true
        }.onFailure {
            DiagnosticLog.warn(this, "subscription_listener_failed", "SIM構成監視の登録に失敗", error = it)
        }
        createNotificationChannel()
        startAsForeground("準備中")
        ContextCompat.registerReceiver(
            this,
            defaultSubscriptionReceiver,
            IntentFilter().apply {
                addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)
                addAction(SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED)
                addAction(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                addAction(ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        unlockReceiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        screenReceiverRegistered = true
        runCatching {
            DEFAULT_SETTING_KEYS.forEach { key ->
                contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, defaultSettingsObserver)
            }
            settingsObserverRegistered = true
        }.onFailure {
            DiagnosticLog.warn(this, "settings_observer_failed", "One UIの既定SIM設定監視を登録できませんでした", error = it)
        }
        ensureShizukuListeners()
        DiagnosticLog.info(
            this,
            "service_created",
            "監視サービスを作成",
            mapOf(
                "userUnlocked" to getSystemService(UserManager::class.java).isUserUnlocked,
                "interactive" to powerManager.isInteractive,
                "shizukuReady" to ShizukuBridge.isReady(),
                "shizukuGranted" to ShizukuBridge.isGranted(),
            ),
        )
        AppState.update {
            it.copy(monitorRunning = true, status = if (powerManager.isInteractive) "モニターを準備中" else "スリープ中 · 監視を一時停止")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureShizukuListeners()
        DiagnosticLog.info(
            this,
            "service_started",
            "監視サービスの開始要求を受信",
            mapOf(
                "reason" to intent?.getStringExtra(EXTRA_START_REASON),
                "action" to intent?.action,
                "startId" to startId,
            ),
        )
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.save(prefs.load().copy(enabled = false, wifiRestoreEnabled = false))
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_NOW -> scheduleNext(0, forceDeepProbe = true)
            else -> scheduleNext(0)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        if (networkCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (cellularNetworkCallbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(cellularNetworkCallback) }
        }
        cellularNetworks.clear()
        if (subscriptionListenerRegistered) {
            runCatching { subscriptionManager.removeOnSubscriptionsChangedListener(subscriptionsChangedListener) }
        }
        if (settingsObserverRegistered) runCatching { contentResolver.unregisterContentObserver(defaultSettingsObserver) }
        if (unlockReceiverRegistered) runCatching { unregisterReceiver(unlockReceiver) }
        if (screenReceiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(defaultSubscriptionReceiver) }
        if (shizukuListenersRegistered) {
            runCatching { Shizuku.removeBinderReceivedListener(shizukuReceivedListener) }
            runCatching { Shizuku.removeBinderDeadListener(shizukuDeadListener) }
        }
        sims.unregister()
        worker.shutdownNow()
        DiagnosticLog.info(this, "service_destroyed", "監視サービスを終了")
        AppState.update { it.copy(monitorRunning = false, status = "停止中") }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestRealtimeCheck(
        trigger: String,
        delayMs: Long,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        if (!powerManager.isInteractive) return
        DiagnosticLog.info(
            this,
            "realtime_trigger",
            "リアルタイム再評価を予約",
            mapOf("trigger" to trigger, "delayMs" to delayMs) + fields,
        )
        scheduleNext(delayMs)
    }

    private fun scheduleNext(delayMs: Long, forceDeepProbe: Boolean = false) {
        val safeDelay = delayMs.coerceAtLeast(0L)
        mainHandler.post {
            if (stopped.get()) return@post
            if (!powerManager.isInteractive) {
                cancelScheduledCycle()
                return@post
            }
            if (forceDeepProbe) forceDeepProbeRequested.set(true)
            val candidate = SystemClock.elapsedRealtime() + safeDelay
            if (!MonitorTiming.shouldReplace(scheduledAtElapsed, candidate)) return@post
            mainHandler.removeCallbacks(scheduledCycle)
            scheduledAtElapsed = candidate
            mainHandler.postDelayed(scheduledCycle, safeDelay)
        }
    }

    private fun cancelScheduledCycle() {
        mainHandler.removeCallbacks(scheduledCycle)
        scheduledAtElapsed = Long.MAX_VALUE
    }

    private fun enqueueCycle(forceDeepProbe: Boolean) {
        if (stopped.get() || worker.isShutdown) return
        if (forceDeepProbe) forceDeepProbeRequested.set(true)
        if (!cycleInFlight.compareAndSet(false, true)) {
            rerunRequested.set(true)
            return
        }
        worker.execute {
            try {
                val force = forceDeepProbeRequested.getAndSet(false)
                runCatching { runCycle(force) }
                    .onFailure {
                        DiagnosticLog.error(this, "cycle_failed", "監視周期で予期しないエラー", error = it)
                        val config = prefs.load()
                        finishCycle("監視エラー · 次回再試行", config)
                    }
            } finally {
                cycleInFlight.set(false)
                if ((rerunRequested.getAndSet(false) || forceDeepProbeRequested.get()) && !stopped.get()) {
                    scheduleNext(0)
                }
            }
        }
    }

    private fun ensureShizukuListeners() {
        if (shizukuListenersRegistered) return
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(shizukuReceivedListener)
            Shizuku.addBinderDeadListener(shizukuDeadListener)
            shizukuListenersRegistered = true
        }.onFailure {
            DiagnosticLog.warn(this, "shizuku_listener_failed", "Shizuku監視の登録に失敗 · 次回起動イベントで再試行", error = it)
        }
    }

    private fun runCycle(forceDeepProbe: Boolean) {
        val config = prefs.load()
        if (!config.needsService() && !forceDeepProbe) {
            stopSelf()
            return
        }
        if (!powerManager.isInteractive) return
        sims.resume()
        val lines = sims.refresh()
        val userUnlocked = getSystemService(UserManager::class.java).isUserUnlocked
        if (userUnlocked) {
            switchAudit.observe("monitor_cycle", lines)
        } else {
            DiagnosticLog.info(this, "default_audit_deferred", "ロック解除前のため既定SIM変更の照合を保留")
        }
        val active = connectivity.activeNetwork
        val activeCaps = active?.let(connectivity::getNetworkCapabilities)
        DiagnosticLog.info(
            this,
            "cycle_started",
            "通信品質の監視を開始",
            mapOf(
                "forced" to forceDeepProbe,
                "userUnlocked" to userUnlocked,
                "transport" to when {
                    activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    active == null -> "none"
                    else -> "other"
                },
                "lines" to lines.joinToString(",") { "${it.subId}:${it.title}:slot${it.slotIndex + 1}" },
                "dataSubId" to SubscriptionManager.getDefaultDataSubscriptionId(),
                "voiceSubId" to SubscriptionManager.getDefaultVoiceSubscriptionId(),
                "smsSubId" to SubscriptionManager.getDefaultSmsSubscriptionId(),
                "shizukuReady" to ShizukuBridge.isReady(),
                "shizukuGranted" to ShizukuBridge.isGranted(),
            ),
        )
        if (!sims.hasPhonePermission()) {
            finishCycle("電話の権限を許可してください", config)
            return
        }
        Log.i(
            TAG,
            "cycle force=$forceDeepProbe active=$active wifi=${activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
                "cellular=${activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} lines=${lines.map { it.subId }}",
        )
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            badSamples = 0
            if (!config.wifiRestoreEnabled) {
                finishCycle("Wi-Fi接続中 · モバイル切替停止", config)
                return
            }
            if (!ShizukuBridge.isReady()) {
                finishCycle("Wi-Fi復帰を保留 · Shizukuの起動待ち", config)
                return
            }
            if (!ShizukuBridge.isGranted()) {
                finishCycle("Wi-Fi復帰を保留 · Shizuku権限が必要です", config)
                return
            }
            val backend = resolveBackendInfo() ?: run {
                finishCycle("Wi-Fi復帰を保留 · 端末の切替方式を確認できません", config)
                return
            }
            val inCall = isInCall()
            if (inCall) {
                finishCycle("Wi-Fi接続中 · 通話終了後に既定SIMへ復帰", config)
                return
            }
            if (!powerManager.isInteractive) return
            val message = applyWifiPolicy(config, lines, backend)
            finishCycle(message, config)
            return
        }

        if (!config.enabled) {
            finishCycle("モバイル自動切替は停止中", config)
            return
        }

        if (!ShizukuBridge.isReady()) {
            finishCycle("自動切替を保留 · Shizukuの起動待ち", config)
            return
        }
        if (!ShizukuBridge.isGranted()) {
            finishCycle("自動切替を保留 · Shizuku権限が必要です", config)
            return
        }
        val backend = resolveBackendInfo() ?: run {
            finishCycle("自動切替を保留 · 端末の切替方式を確認できません", config)
            return
        }
        if (!backend.supports(SimRole.DATA)) {
            finishCycle("自動切替を保留 · この端末ではデータSIM切替未対応", config)
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

        val cellularNetwork = findCellularNetwork(dataSubId)
        val caps = cellularNetwork?.let(connectivity::getNetworkCapabilities)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val switchIsSettling = System.currentTimeMillis() - prefs.lastSwitchAt in 0 until NETWORK_SETTLE_GRACE_MS
        if (switchIsSettling && (cellularNetwork == null || !validated)) {
            badSamples = 0
            scheduleNext(1_000)
            finishCycle("${current.title} · 回線切替の反映待ち", config)
            return
        }
        val latency = cellularNetwork?.let { latencyProbe(it, allowDefaultFallback = true) }
        if (!powerManager.isInteractive) {
            badSamples = 0
            return
        }
        sampleCount++
        val weakSignal = current.dbm?.let { it <= config.weakSignalDbm }
            ?: (current.signalLevel in 0..1)
        val shouldMeasureSpeed = forceDeepProbe || weakSignal || latency == null ||
            latency > config.latencyThresholdMs * 2L / 3L || sampleCount % 10 == 0
        val speed = if (shouldMeasureSpeed) cellularNetwork?.let { speedProbe(it, allowDefaultFallback = true) } else null
        if (!powerManager.isInteractive) {
            badSamples = 0
            return
        }
        Log.i(TAG, "probe subId=$dataSubId network=$cellularNetwork validated=$validated latencyMs=$latency speedKbps=$speed")
        val sample = QualitySample(
            inService = current.inService,
            serviceStateKnown = current.serviceStateKnown,
            signalLevel = current.signalLevel,
            dbm = current.dbm,
            validated = validated,
            latencyMs = latency,
            speedKbps = speed,
        )
        val assessment = AutoSwitchDecider.assess(sample, config)
        val verdict = assessment.verdict
        val bad = verdict == QualityVerdict.BAD
        badSamples = when (verdict) {
            QualityVerdict.BAD -> badSamples + 1
            QualityVerdict.DEGRADED -> badSamples + 1
            QualityVerdict.GOOD -> (badSamples - 1).coerceAtLeast(0)
            QualityVerdict.INCONCLUSIVE -> badSamples
        }

        val candidateProbe = alternate?.let { candidate ->
            val shouldProbe = assessment.needsRapidRecheck || forceDeepProbe || sampleCount % 20 == 0
            if (shouldProbe) probeAlternate(candidate, config) else alternateProbeCache[candidate.subId]
                ?.takeIf { System.currentTimeMillis() - it.measuredAt < ALTERNATE_CACHE_MS }
                ?.sample
        }
        val candidateAssessment = candidateProbe?.let { AutoSwitchDecider.assess(it, config) }
        val usageStates = refreshDataUsage(lines)
        val quotaAdvantage = alternate != null && DataPlanPolicy.candidateHasClearAdvantage(
            usageStates[current.subId],
            usageStates[alternate.subId],
        )
        val candidatePreferred = candidateProbe != null && candidateAssessment != null &&
            AutoSwitchDecider.shouldPreferCandidate(
                assessment,
                candidateAssessment,
                sample,
                candidateProbe,
                quotaAdvantage = quotaAdvantage,
            )

        AppState.update {
            it.copy(lastLatencyMs = latency, lastSpeedKbps = speed, badSamples = badSamples)
        }
        DiagnosticLog.info(
            this,
            "quality_result",
            "通信品質を判定",
            mapOf(
                "subId" to dataSubId,
                "name" to current.title,
                "serviceStateKnown" to current.serviceStateKnown,
                "inService" to current.inService,
                "signalLevel" to current.signalLevel,
                "dbm" to current.dbm,
                "validated" to validated,
                "latencyMs" to latency,
                "latencyThresholdMs" to config.latencyThresholdMs,
                "speedKbps" to speed,
                "speedThresholdKbps" to config.speedThresholdKbps,
                "weakSignalDbm" to config.weakSignalDbm,
                "verdict" to verdict.name.lowercase(),
                "qualityScore" to assessment.score,
                "qualityReasons" to assessment.reasons.joinToString(", "),
                "badSamples" to badSamples,
                "requiredBadSamples" to config.consecutiveFailures,
                "alternateSubId" to alternate?.subId,
                "alternateName" to alternate?.title,
                "alternateDbm" to alternate?.dbm,
                "alternateSignalLevel" to alternate?.signalLevel,
                "alternateValidated" to candidateProbe?.validated,
                "alternateLatencyMs" to candidateProbe?.latencyMs,
                "alternateSpeedKbps" to candidateProbe?.speedKbps,
                "alternateScore" to candidateAssessment?.score,
                "candidatePreferred" to candidatePreferred,
                "quotaAdvantage" to quotaAdvantage,
                "currentRemainingBytes" to usageStates[current.subId]?.remainingBytes,
                "alternateRemainingBytes" to alternate?.let { usageStates[it.subId]?.remainingBytes },
            ),
        )

        val cooldownMs = config.cooldownMinutes * 60_000L
        val inCooldown = System.currentTimeMillis() - prefs.lastSwitchAt < cooldownMs
        val inCall = isInCall()
        val trialDue = alternate != null && System.currentTimeMillis() -
            (lastComparisonTrialAt[alternate.subId] ?: 0L) >= COMPARISON_TRIAL_COOLDOWN_MS
        val directCandidateUnavailable = candidateProbe != null && candidateProbe.connectivityUnavailable()
        val shouldTrialCompare = alternate != null && trialDue && directCandidateUnavailable &&
            !inCooldown && !inCall && powerManager.isInteractive &&
            (badSamples >= config.consecutiveFailures || quotaAdvantage)
        if (shouldTrialCompare) {
            lastComparisonTrialAt[alternate.subId] = System.currentTimeMillis()
            val result = compareByTemporaryDataSwitch(current, alternate, sample, assessment, config, lines, quotaAdvantage)
            when (result) {
                ComparisonResult.KEPT -> {
                    badSamples = 0
                    finishCycle("${current.title} → ${alternate.title} · 比較測定で優位", config)
                    return
                }
                ComparisonResult.REVERTED -> {
                    badSamples = (config.consecutiveFailures - 1).coerceAtLeast(0)
                    finishCycle("${current.title}を維持 · ${alternate.title}の比較結果が優位ではありません", config)
                    return
                }
                ComparisonResult.ABORTED -> {
                    finishCycle("${current.title}を維持 · ${alternate.title}の比較測定を完了できません", config)
                    return
                }
            }
        }
        val connectivityFallback = assessment.connectivityFailure && alternate != null && AutoSwitchDecider.canUseAlternate(alternate)
        if (badSamples >= config.consecutiveFailures && alternate != null &&
            (candidatePreferred || connectivityFallback) && !inCooldown && !inCall && powerManager.isInteractive) {
            val requiredRoles = buildSet {
                add(SimRole.DATA)
                if (config.followVoice) add(SimRole.VOICE)
                if (config.followSms) add(SimRole.SMS)
            }
            val unsupported = requiredRoles - backend.supportedRoles
            if (unsupported.isNotEmpty()) {
                finishCycle("自動切替を保留 · ${unsupported.joinToString { it.label }}の切替方式が未対応", config)
                return
            }
            switchDataAndFollowers(alternate.subId, config, lines)
            badSamples = 0
            val message = "${current.title} → ${alternate.title} に切替"
            finishCycle(message, config)
            return
        }

        val detail = buildString {
            append(current.title)
            append(" · ")
            append(
                when {
                    latency != null -> "${latency}ms"
                    speed != null -> "遅延測定保留"
                    validated -> "疎通確認済み · 測定保留"
                    else -> "通信未検証"
                }
            )
            speed?.let { append(" · ${it}kbps") }
            append(" · score ${assessment.score}")
            if (badSamples > 0) append(" · ${if (verdict == QualityVerdict.DEGRADED) "品質低下" else "低品質"} $badSamples/${config.consecutiveFailures}")
            candidateAssessment?.let { append(" · ${alternate?.title} score ${it.score}") }
            if (verdict == QualityVerdict.INCONCLUSIVE) append(" · 次回再測定")
            if (bad && alternate == null) append(" · 利用可能な切替先なし")
            if (badSamples >= config.consecutiveFailures && alternate != null && !candidatePreferred && !connectivityFallback) {
                append(" · 切替先が明確に良好ではないため維持")
            }
            if (inCall) append(" · 通話中は切替保留")
            else if (inCooldown && bad) append(" · クールダウン中")
        }
        finishCycle(detail, config)
    }

    private fun switchDataAndFollowers(subId: Int, config: MonitorConfig, lines: List<SimLine>) {
        setDefaultWithAudit(SimRole.DATA, subId, "auto_failover", lines).getOrThrow()
        switchFollowersAndRecord(subId, config, lines, "auto_failover_follower")
    }

    private fun switchFollowersAndRecord(subId: Int, config: MonitorConfig, lines: List<SimLine>, origin: String) {
        if (config.followVoice) setDefaultWithAudit(SimRole.VOICE, subId, origin, lines).getOrThrow()
        if (config.followSms) setDefaultWithAudit(SimRole.SMS, subId, origin, lines).getOrThrow()
        prefs.lastSwitchAt = System.currentTimeMillis()
        AppState.update { it.copy(lastSwitchAt = prefs.lastSwitchAt) }
        switchAudit.observe("auto_failover_result", lines)
    }

    private fun applyWifiPolicy(
        config: MonitorConfig,
        lines: List<SimLine>,
        backend: SwitchBackendInfo,
    ): String {
        val requested = buildList {
            if (config.wifiDataEnabled) add(SimRole.DATA to config.wifiDataSubId)
            if (config.wifiVoiceEnabled) add(SimRole.VOICE to config.wifiVoiceSubId)
            if (config.wifiSmsEnabled) add(SimRole.SMS to config.wifiSmsSubId)
        }
        if (requested.isEmpty()) return "Wi-Fi接続中 · 復帰対象なし"

        val changed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        requested.forEach { (role, subId) ->
            if (!powerManager.isInteractive) return "スリープ中 · Wi-Fi復帰を保留"
            if (!backend.supports(role)) {
                skipped += "${role.label}未対応"
                return@forEach
            }
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
            setDefaultWithAudit(role, subId, "wifi_restore", lines)
                .onSuccess { changed += "${role.label}=${line.title}" }
                .onFailure {
                    skipped += "${role.label}失敗"
                }
        }
        switchAudit.observe("wifi_restore_result", lines)
        sims.refresh()
        return when {
            changed.isNotEmpty() && skipped.isNotEmpty() -> "Wi-Fi復帰: ${changed.joinToString()} · ${skipped.joinToString()}"
            changed.isNotEmpty() -> "Wi-Fi復帰: ${changed.joinToString()}"
            skipped.isNotEmpty() -> "Wi-Fi復帰保留: ${skipped.joinToString()}"
            else -> "Wi-Fi接続中 · 既定SIM復帰済み"
        }
    }

    private fun finishCycle(message: String, config: MonitorConfig) {
        val nextCheckMs = MonitorTiming.nextDelayMs(
            configuredIntervalMs = config.intervalSeconds * 1_000L,
            enabled = config.enabled,
            badSamples = badSamples,
            requiredBadSamples = config.consecutiveFailures,
        )
        DiagnosticLog.info(
            this,
            "decision",
            message,
            mapOf(
                "monitorEnabled" to config.enabled,
                "wifiRestoreEnabled" to config.wifiRestoreEnabled,
                "wifiDataEnabled" to config.wifiDataEnabled,
                "wifiDataSubId" to config.wifiDataSubId,
                "wifiVoiceEnabled" to config.wifiVoiceEnabled,
                "wifiVoiceSubId" to config.wifiVoiceSubId,
                "wifiSmsEnabled" to config.wifiSmsEnabled,
                "wifiSmsSubId" to config.wifiSmsSubId,
                "followVoice" to config.followVoice,
                "followSms" to config.followSms,
                "intervalSeconds" to config.intervalSeconds,
                "latencyThresholdMs" to config.latencyThresholdMs,
                "speedThresholdKbps" to config.speedThresholdKbps,
                "requiredBadSamples" to config.consecutiveFailures,
                "cooldownMinutes" to config.cooldownMinutes,
                "badSamples" to badSamples,
                "nextCheckMs" to nextCheckMs,
                "dataSubId" to SubscriptionManager.getDefaultDataSubscriptionId(),
                "voiceSubId" to SubscriptionManager.getDefaultVoiceSubscriptionId(),
                "smsSubId" to SubscriptionManager.getDefaultSmsSubscriptionId(),
                "shizukuReady" to ShizukuBridge.isReady(),
                "shizukuGranted" to ShizukuBridge.isGranted(),
            ),
        )
        AppState.update {
            it.copy(
                monitorRunning = config.needsService(),
                status = message,
                shizukuReady = ShizukuBridge.isReady(),
                shizukuGranted = ShizukuBridge.isGranted(),
                badSamples = badSamples,
            )
        }
        if (config.needsService() && !stopped.get() && powerManager.isInteractive) scheduleNext(nextCheckMs)
    }

    private fun setDefaultWithAudit(
        role: SimRole,
        subId: Int,
        origin: String,
        lines: List<SimLine>,
    ): Result<Unit> {
        switchAudit.begin(role, subId, origin, lines)
        return ShizukuBridge.setDefault(role, subId).also {
            switchAudit.result(role, subId, origin, it)
        }
    }

    private fun resolveBackendInfo(): SwitchBackendInfo? {
        backendInfo?.let { return it }
        val result = ShizukuBridge.backendInfo()
        result.onSuccess { info ->
            backendInfo = info
            AppState.update {
                it.copy(
                    switchBackend = info.label,
                    backendChecked = true,
                    supportedRoles = info.supportedRoles,
                )
            }
            DiagnosticLog.info(
                this,
                "switch_backend_detected",
                "端末のSIM切替方式を検出",
                mapOf(
                    "backend" to info.id,
                    "roles" to info.supportedRoles.joinToString(",") { it.name.lowercase() },
                    "descriptor" to info.raw,
                ),
            )
        }.onFailure {
            AppState.update {
                it.copy(switchBackend = "切替方式を確認できません", backendChecked = true, supportedRoles = emptySet())
            }
            DiagnosticLog.warn(this, "switch_backend_failed", "端末のSIM切替方式を確認できませんでした", error = it)
        }
        return result.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun isInCall(): Boolean =
        runCatching { getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)

    private fun findCellularNetwork(subId: Int): Network? {
        return cellularNetworks
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) return@mapNotNull null

                val networkSubId = (caps.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId
                if (networkSubId != null && networkSubId != subId) return@mapNotNull null
                network to caps
            }
            .maxByOrNull { (_, caps) ->
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0
            }
            ?.first
    }

    private fun latencyProbe(network: Network, allowDefaultFallback: Boolean): Long? {
        val url = URL(LATENCY_URLS.first())
        if (boundNetworkProbeSupported != false) {
            val bound = latencyAttempt { network.openConnection(url) as HttpURLConnection }
            if (bound.isSuccess) {
                boundNetworkProbeSupported = true
                return bound.getOrNull()
            }
            boundNetworkProbeSupported = false
            if (allowDefaultFallback) {
                Log.i(TAG, "direct probe unavailable on $network; using the app default route")
                DiagnosticLog.warn(this, "probe_fallback", "SIM固定の遅延測定に失敗し、既定経路で再試行", error = bound.exceptionOrNull())
            }
        }
        if (!allowDefaultFallback) return null
        var lastFailure: Throwable? = null
        LATENCY_URLS.forEach { endpoint ->
            val result = latencyAttempt { URL(endpoint).openConnection() as HttpURLConnection }
            if (result.isSuccess) return result.getOrNull()
            lastFailure = result.exceptionOrNull()
        }
        Log.w(TAG, "all latency probes failed: ${lastFailure?.message}", lastFailure)
        DiagnosticLog.warn(this, "latency_unavailable", "すべての遅延測定先が応答しませんでした", error = lastFailure)
        return null
    }

    private fun latencyAttempt(open: () -> HttpURLConnection): Result<Long> = runCatching {
        val started = System.nanoTime()
        val connection = open()
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
    }

    private fun speedProbe(network: Network, allowDefaultFallback: Boolean): Long? {
        val url = URL(SPEED_URL)
        if (boundNetworkProbeSupported != false) {
            val bound = speedAttempt { network.openConnection(url) as HttpURLConnection }
            if (bound.isSuccess) {
                boundNetworkProbeSupported = true
                return bound.getOrNull()
            }
            boundNetworkProbeSupported = false
            if (allowDefaultFallback) {
                Log.i(TAG, "direct speed probe unavailable on $network; using the app default route")
                DiagnosticLog.warn(this, "probe_fallback", "SIM固定の速度測定に失敗し、既定経路で再試行", error = bound.exceptionOrNull())
            }
        }
        if (!allowDefaultFallback) return null
        return speedAttempt { url.openConnection() as HttpURLConnection }
            .onFailure { Log.w(TAG, "default-route speed probe failed: ${it.message}", it) }
            .getOrNull()
    }

    private fun probeAlternate(line: SimLine, config: MonitorConfig): QualitySample {
        alternateProbeCache[line.subId]?.takeIf {
            System.currentTimeMillis() - it.measuredAt < ALTERNATE_CACHE_MS
        }?.let { return it.sample }

        val requested = if (directAlternateRequestSupported == false) null else requestCellularNetwork(line.subId)
        val network = requested?.network
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val latency = network?.let { latencyProbe(it, allowDefaultFallback = false) }
        val speed = network?.let { speedProbe(it, allowDefaultFallback = false) }
        requested?.release()
        val sample = QualitySample(
            inService = line.inService,
            serviceStateKnown = line.serviceStateKnown,
            signalLevel = line.signalLevel,
            dbm = line.dbm,
            validated = validated,
            latencyMs = latency,
            speedKbps = speed,
        )
        alternateProbeCache[line.subId] = TimedQualitySample(sample, System.currentTimeMillis())
        DiagnosticLog.info(
            this,
            "alternate_quality_result",
            "切替先候補の通信品質を測定",
            mapOf(
                "subId" to line.subId,
                "name" to line.title,
                "dbm" to line.dbm,
                "signalLevel" to line.signalLevel,
                "networkAcquired" to (network != null),
                "validated" to validated,
                "latencyMs" to latency,
                "speedKbps" to speed,
                "score" to AutoSwitchDecider.assess(sample, config).score,
            ),
        )
        return sample
    }

    private fun requestCellularNetwork(subId: Int): RequestedNetwork? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build())
            .build()
        val latch = CountDownLatch(1)
        var network: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                network = available
                latch.countDown()
            }

            override fun onUnavailable() = latch.countDown()
        }
        return runCatching {
            connectivity.requestNetwork(request, callback, ALTERNATE_REQUEST_TIMEOUT_MS.toInt())
            latch.await(ALTERNATE_REQUEST_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS)
            network?.let {
                directAlternateRequestSupported = true
                RequestedNetwork(it) { runCatching { connectivity.unregisterNetworkCallback(callback) } }
            }
                ?: run {
                    directAlternateRequestSupported = false
                    runCatching { connectivity.unregisterNetworkCallback(callback) }
                    null
                }
        }.onFailure {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            DiagnosticLog.warn(this, "alternate_probe_unavailable", "切替先候補の専用ネットワークを取得できません", mapOf("subId" to subId), it)
        }.getOrNull()
    }

    private fun compareByTemporaryDataSwitch(
        current: SimLine,
        candidate: SimLine,
        currentSample: QualitySample,
        currentAssessment: QualityAssessment,
        config: MonitorConfig,
        lines: List<SimLine>,
        quotaAdvantage: Boolean,
    ): ComparisonResult {
        DiagnosticLog.info(
            this,
            "comparison_trial_started",
            "候補SIMへ一時切替して比較測定",
            mapOf("fromSubId" to current.subId, "toSubId" to candidate.subId, "fromScore" to currentAssessment.score),
        )
        val switched = setDefaultWithAudit(SimRole.DATA, candidate.subId, "comparison_probe", lines)
        if (switched.isFailure) return ComparisonResult.ABORTED

        val deadline = SystemClock.elapsedRealtime() + COMPARISON_SETTLE_TIMEOUT_MS
        var network: Network? = null
        while (powerManager.isInteractive && SystemClock.elapsedRealtime() < deadline) {
            if (isWifiActive()) break
            if (SubscriptionManager.getDefaultDataSubscriptionId() == candidate.subId) {
                val found = findCellularNetwork(candidate.subId)
                val caps = found?.let(connectivity::getNetworkCapabilities)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                    network = found
                    break
                }
            }
            Thread.sleep(300)
        }

        if (!powerManager.isInteractive || isWifiActive() || network == null) {
            setDefaultWithAudit(SimRole.DATA, current.subId, "comparison_revert", sims.refresh())
                .onSuccess { markSwitchSettling() }
            DiagnosticLog.warn(this, "comparison_trial_aborted", "比較測定を中断して元のSIMへ復帰")
            return ComparisonResult.ABORTED
        }

        val refreshedCandidate = sims.refresh().firstOrNull { it.subId == candidate.subId } ?: candidate
        val latency = latencyProbe(network, allowDefaultFallback = true)
        val speed = speedProbe(network, allowDefaultFallback = true)
        val candidateSample = QualitySample(
            inService = refreshedCandidate.inService,
            serviceStateKnown = refreshedCandidate.serviceStateKnown,
            signalLevel = refreshedCandidate.signalLevel,
            dbm = refreshedCandidate.dbm,
            validated = true,
            latencyMs = latency,
            speedKbps = speed,
        )
        val candidateAssessment = AutoSwitchDecider.assess(candidateSample, config)
        alternateProbeCache[candidate.subId] = TimedQualitySample(candidateSample, System.currentTimeMillis())
        val keep = AutoSwitchDecider.shouldPreferCandidate(
            currentAssessment,
            candidateAssessment,
            currentSample,
            candidateSample,
            quotaAdvantage,
        )
        DiagnosticLog.info(
            this,
            "comparison_trial_result",
            if (keep) "候補SIMを維持" else "候補SIMから元のSIMへ復帰",
            mapOf(
                "fromSubId" to current.subId,
                "toSubId" to candidate.subId,
                "currentDbm" to currentSample.dbm,
                "candidateDbm" to candidateSample.dbm,
                "currentLatencyMs" to currentSample.latencyMs,
                "candidateLatencyMs" to candidateSample.latencyMs,
                "currentSpeedKbps" to currentSample.speedKbps,
                "candidateSpeedKbps" to candidateSample.speedKbps,
                "currentScore" to currentAssessment.score,
                "candidateScore" to candidateAssessment.score,
                "quotaAdvantage" to quotaAdvantage,
                "keepCandidate" to keep,
            ),
        )
        return if (keep) {
            switchFollowersAndRecord(candidate.subId, config, sims.refresh(), "comparison_keep_follower")
            ComparisonResult.KEPT
        } else {
            setDefaultWithAudit(SimRole.DATA, current.subId, "comparison_revert", sims.refresh())
                .onSuccess { markSwitchSettling() }
            ComparisonResult.REVERTED
        }
    }

    private fun markSwitchSettling() {
        prefs.lastSwitchAt = System.currentTimeMillis()
        AppState.update { it.copy(lastSwitchAt = prefs.lastSwitchAt) }
    }

    private fun isWifiActive(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(active)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun refreshDataUsage(lines: List<SimLine>): Map<Int, DataUsageState> {
        val plans = prefs.loadPlans().associateBy { it.subId }
        val now = System.currentTimeMillis()
        val states = buildMap {
            lines.forEach { line ->
                val plan = plans[line.subId] ?: return@forEach
                val window = DataPlanPolicy.window(plan) ?: return@forEach
                val measured = if (plan.manualRemainingBytes != null) null else {
                    val cached = usageCache[line.subId]?.takeIf {
                        it.startMillis == window.startMillis && now - it.measuredAt < USAGE_CACHE_MS
                    }
                    if (cached != null) cached.bytes else {
                        ShizukuBridge.mobileUsageBytes(
                            line.subId,
                            window.startMillis,
                            minOf(now, window.endMillis),
                        ).onSuccess { bytes ->
                            usageCache[line.subId] = TimedUsage(bytes, window.startMillis, now)
                        }.onFailure { error ->
                            usageCache[line.subId] = TimedUsage(null, window.startMillis, now)
                            DiagnosticLog.warn(
                                this@MonitorService,
                                "usage_unavailable",
                                "SIM別通信量を取得できません",
                                mapOf("subId" to line.subId, "name" to line.title),
                                error,
                            )
                        }.getOrNull()
                    }
                }
                DataPlanPolicy.state(plan, measured)?.let { put(line.subId, it) }
            }
        }
        AppState.update { it.copy(dataUsage = states) }
        return states
    }

    private fun speedAttempt(open: () -> HttpURLConnection): Result<Long> = runCatching {
        val started = System.nanoTime()
        val connection = open()
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
    }

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

    companion object {
        private const val CHANNEL_ID = "sim_pilot_monitor"
        private const val TAG = "SimPilotMonitor"
        private const val NOTIFICATION_ID = 6201
        private val LATENCY_URLS = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.google.com/generate_204",
            "https://speed.cloudflare.com/__down?bytes=1",
        )
        private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=65536"
        private const val NETWORK_SETTLE_GRACE_MS = 10_000L
        private const val ALTERNATE_REQUEST_TIMEOUT_MS = 8_000L
        private const val ALTERNATE_CACHE_MS = 30_000L
        private const val USAGE_CACHE_MS = 15L * 60_000L
        private const val COMPARISON_SETTLE_TIMEOUT_MS = 10_000L
        private const val COMPARISON_TRIAL_COOLDOWN_MS = 5L * 60_000L
        private const val ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"
        private const val ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED"
        private const val EXTRA_START_REASON = "dev.simpilot.extra.START_REASON"
        private val DEFAULT_SETTING_KEYS = listOf(
            "multi_sim_data_call",
            "multi_sim_voice_call",
            "multi_sim_sms",
        )
        const val ACTION_STOP = "dev.simpilot.STOP"
        const val ACTION_TEST_NOW = "dev.simpilot.TEST_NOW"

        fun start(context: Context, testNow: Boolean = false, reason: String = "unspecified") {
            val intent = Intent(context, MonitorService::class.java)
            if (testNow) intent.action = ACTION_TEST_NOW
            intent.putExtra(EXTRA_START_REASON, reason)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}

private data class TimedQualitySample(val sample: QualitySample, val measuredAt: Long)
private data class TimedUsage(val bytes: Long?, val startMillis: Long, val measuredAt: Long)
private data class RequestedNetwork(val network: Network, val release: () -> Unit)
private enum class ComparisonResult { KEPT, REVERTED, ABORTED }

private fun QualitySample.connectivityUnavailable(): Boolean = !validated && latencyMs == null && speedKbps == null
