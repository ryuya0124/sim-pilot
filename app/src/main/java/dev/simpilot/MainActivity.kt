package dev.simpilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var repository: SimRepository
    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashStartedAt = SystemClock.elapsedRealtime()
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition {
            SystemClock.elapsedRealtime() - splashStartedAt < 650L
        }
        super.onCreate(savedInstanceState)
        DiagnosticLog.info(
            this,
            "activity_opened",
            "アプリ画面を起動",
            mapOf("userUnlocked" to getSystemService(UserManager::class.java).isUserUnlocked),
        )
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .scaleX(1.06f)
                .scaleY(1.06f)
                .setDuration(260L)
                .withEndAction(provider::remove)
                .start()
        }
        enableEdgeToEdge()
        repository = SimRepository(this, mainExecutor)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent { SimPilotTheme { SimPilotApp(repository) } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        repository.unregister()
        super.onDestroy()
    }

    private fun refresh() {
        val ready = ShizukuBridge.isReady()
        val granted = ready && ShizukuBridge.isGranted()
        AppState.update {
            it.copy(
                shizukuReady = ready,
                shizukuGranted = granted,
                switchBackend = when {
                    !ready -> "Shizukuの起動待ち"
                    !granted -> "Shizuku許可後に確認"
                    else -> it.switchBackend
                },
                backendChecked = if (granted) it.backendChecked else false,
                supportedRoles = if (granted) it.supportedRoles else emptySet(),
            )
        }
        repository.refresh()
    }
}

@Composable
private fun SimPilotTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9CCAFF), secondary = Color(0xFF6EE4C5))
        else -> lightColorScheme(primary = Color(0xFF245EA8), secondary = Color(0xFF006B59))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimPilotApp(repository: SimRepository) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(AppState.current()) }
    var config by remember { mutableStateOf(AppPreferences(context).load()) }
    var plans by remember { mutableStateOf(AppPreferences(context).loadPlans()) }
    var appPage by remember { mutableStateOf(AppPage.HOME) }
    var appBackProgress by remember { mutableStateOf(0f) }
    var appBackDirection by remember { mutableStateOf(1f) }
    var radioRefreshing by remember { mutableStateOf(false) }
    var radioRefreshToken by remember { mutableStateOf(0) }
    var busyRole by remember { mutableStateOf<SimRole?>(null) }
    val switchAudit = remember { SwitchAudit(context) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { repository.refresh() }

    DisposableEffect(Unit) {
        val listener: (AppSnapshot) -> Unit = { snapshot = it }
        AppState.addListener(listener)
        onDispose { AppState.removeListener(listener) }
    }

    LaunchedEffect(Unit) {
        if (config.needsService()) MonitorService.start(context, reason = "ui_opened")
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    LaunchedEffect(snapshot.shizukuReady, snapshot.shizukuGranted) {
        if (!snapshot.shizukuReady || !snapshot.shizukuGranted) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { ShizukuBridge.backendInfo() }
        result.onSuccess { info ->
            AppState.update {
                it.copy(
                    switchBackend = info.label,
                    backendChecked = true,
                    supportedRoles = info.supportedRoles,
                )
            }
            DiagnosticLog.info(
                context,
                "switch_backend_detected",
                "アプリ画面で端末のSIM切替方式を確認",
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
            DiagnosticLog.warn(context, "switch_backend_failed", "アプリ画面で切替方式を確認できませんでした", error = it)
        }
    }

    LaunchedEffect(appPage, radioRefreshToken, snapshot.lines.map { it.subId }) {
        if (appPage != AppPage.RADIO) return@LaunchedEffect
        while (true) {
            val activeSubIds = AppState.current().lines.mapTo(linkedSetOf()) { it.subId }
            if (activeSubIds.isNotEmpty()) {
                radioRefreshing = true
                val result = withContext(Dispatchers.IO) { repository.refreshRadioDetails(activeSubIds) }
                result.onSuccess { refresh ->
                    val linesBySubId = AppState.current().lines.associateBy { it.subId }
                    refresh.metrics.forEach { (subId, radio) ->
                        DiagnosticLog.info(
                            context,
                            "radio_page_snapshot",
                            "無線ページでNetMonster Coreの全取得値を保存",
                            mapOf(
                                "source" to "radio_page_5s",
                                "subId" to subId,
                                "name" to linesBySubId[subId]?.title,
                                "radioDetails" to radio.logValue(),
                            ),
                        )
                    }
                }
                repository.refresh()
                radioRefreshing = false
            }
            delay(5_000)
        }
    }

    fun persist(next: MonitorConfig) {
        config = next
        AppPreferences(context).save(next)
        DiagnosticLog.info(
            context,
            "config_changed",
            "監視設定を変更",
            mapOf(
                "monitorEnabled" to next.enabled,
                "wifiRestoreEnabled" to next.wifiRestoreEnabled,
                "wifiDataEnabled" to next.wifiDataEnabled,
                "wifiDataSubId" to next.wifiDataSubId,
                "wifiVoiceEnabled" to next.wifiVoiceEnabled,
                "wifiVoiceSubId" to next.wifiVoiceSubId,
                "wifiSmsEnabled" to next.wifiSmsEnabled,
                "wifiSmsSubId" to next.wifiSmsSubId,
                "followVoice" to next.followVoice,
                "followSms" to next.followSms,
                "intervalSeconds" to next.intervalSeconds,
                "latencyThresholdMs" to next.latencyThresholdMs,
                "speedThresholdKbps" to next.speedThresholdKbps,
                "weakSignalDbm" to next.weakSignalDbm,
                "consecutiveFailures" to next.consecutiveFailures,
                "cooldownMinutes" to next.cooldownMinutes,
            ),
        )
        if (next.needsService()) MonitorService.start(context, reason = "config_changed") else MonitorService.stop(context)
    }

    fun persistPlans(next: List<SimPlanConfig>) {
        plans = next
        AppPreferences(context).savePlans(next)
        DiagnosticLog.info(
            context,
            "data_plans_changed",
            "SIM別データプラン設定を変更",
            mapOf("configuredSubIds" to next.filter { it.enabled }.joinToString(",") { it.subId.toString() }),
        )
        if (config.needsService()) MonitorService.start(context, reason = "data_plans_changed")
    }

    fun switch(role: SimRole, subId: Int) {
        if (snapshot.backendChecked && role !in snapshot.supportedRoles) {
            AppState.update { it.copy(status = "この端末では${role.label}SIM切替に未対応です") }
            return
        }
        if (!snapshot.shizukuGranted) {
            if (snapshot.shizukuReady) ShizukuBridge.requestPermission(REQUEST_SHIZUKU)
            return
        }
        scope.launch {
            busyRole = role
            val lines = snapshot.lines
            val result = withContext(Dispatchers.IO) {
                switchAudit.begin(role, subId, "ui_manual", lines)
                ShizukuBridge.setDefault(role, subId).also {
                    switchAudit.result(role, subId, "ui_manual", it)
                }
            }
            if (result.isSuccess) {
                if (role == SimRole.DATA) AppPreferences(context).lastSwitchAt = System.currentTimeMillis()
                AppState.update { it.copy(status = "${role.label}を切り替えました") }
                delay(700)
                val refreshed = repository.refresh()
                switchAudit.observe("ui_manual_result", refreshed)
            } else {
                AppState.update { it.copy(status = result.exceptionOrNull()?.message ?: "切替に失敗しました") }
            }
            busyRole = null
        }
    }

    PredictiveBackHandler(enabled = appPage != AppPage.HOME) { events ->
        try {
            events.collect { event ->
                appBackProgress = event.progress
                appBackDirection = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
            }
            appPage = AppPage.HOME
        } finally {
            appBackProgress = 0f
            appBackDirection = 1f
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            MaterialTheme.colorScheme.surfaceContainer,
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * 0.12f * appBackProgress * appBackDirection
                        scaleX = 1f - 0.03f * appBackProgress
                        scaleY = 1f - 0.03f * appBackProgress
                        alpha = 1f - 0.12f * appBackProgress
                    }
            ) {
                when (appPage) {
                    AppPage.HOME -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 10.dp,
                            bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 112.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item { Hero(snapshot, config) }
                        if (!snapshot.shizukuGranted) {
                            item {
                                ShizukuCard(
                                    ready = snapshot.shizukuReady,
                                    onRequest = {
                                        if (snapshot.shizukuReady) ShizukuBridge.requestPermission(REQUEST_SHIZUKU)
                                        else runCatching {
                                            val launch = context.packageManager.getLaunchIntentForPackage("af.shizuku.plus.api")
                                                ?: context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            if (launch != null) context.startActivity(launch)
                                        }
                                    },
                                )
                            }
                        }
                        item {
                            AutoCard(
                                config = config,
                                running = snapshot.monitorRunning,
                                backend = snapshot.switchBackend,
                                onChange = ::persist,
                                onTest = { MonitorService.start(context, testNow = true, reason = "ui_quality_test") },
                            )
                        }
                        item {
                            Text("既定SIM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "データ・通話・メッセージは独立して変更されます",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            BoxWithConstraints {
                                val wide = maxWidth >= 720.dp
                                if (wide) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        RoleCard(SimRole.DATA, snapshot.dataSubId, snapshot.lines, busyRole, !snapshot.backendChecked || SimRole.DATA in snapshot.supportedRoles, Modifier.weight(1f), ::switch)
                                        RoleCard(SimRole.VOICE, snapshot.voiceSubId, snapshot.lines, busyRole, !snapshot.backendChecked || SimRole.VOICE in snapshot.supportedRoles, Modifier.weight(1f), ::switch)
                                        RoleCard(SimRole.SMS, snapshot.smsSubId, snapshot.lines, busyRole, !snapshot.backendChecked || SimRole.SMS in snapshot.supportedRoles, Modifier.weight(1f), ::switch)
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        RoleCard(SimRole.DATA, snapshot.dataSubId, snapshot.lines, busyRole, !snapshot.backendChecked || SimRole.DATA in snapshot.supportedRoles, Modifier.fillMaxWidth(), ::switch)
                                        RoleCard(SimRole.VOICE, snapshot.voiceSubId, snapshot.lines, busyRole, !snapshot.backendChecked || SimRole.VOICE in snapshot.supportedRoles, Modifier.fillMaxWidth(), ::switch)
                                        RoleCard(SimRole.SMS, snapshot.smsSubId, snapshot.lines, busyRole, !snapshot.backendChecked || SimRole.SMS in snapshot.supportedRoles, Modifier.fillMaxWidth(), ::switch)
                                    }
                                }
                            }
                        }
                        item { QualityCard(snapshot) }
                        item { SimDetails(snapshot.lines, snapshot.dataSubId, snapshot.dataUsage) }
                    }
                    AppPage.RADIO -> RadioDetailsPage(
                        lines = snapshot.lines,
                        refreshing = radioRefreshing,
                        onRefresh = { radioRefreshToken++ },
                    )
                    AppPage.SETTINGS -> SettingsPageScreen(
                        config = config,
                        lines = snapshot.lines,
                        dataSubId = snapshot.dataSubId,
                        voiceSubId = snapshot.voiceSubId,
                        smsSubId = snapshot.smsSubId,
                        plans = plans,
                        onChange = ::persist,
                        onPlansChange = ::persistPlans,
                    )
                }
            }
            AppNavigationBar(
                selected = appPage,
                onSelected = { appPage = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private enum class AppPage { HOME, RADIO, SETTINGS }

@Composable
private fun AppNavigationBar(selected: AppPage, onSelected: (AppPage) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier.fillMaxWidth(), tonalElevation = 6.dp) {
        NavigationBarItem(
            selected = selected == AppPage.HOME,
            onClick = { onSelected(AppPage.HOME) },
            icon = { Icon(Icons.Rounded.Home, null) },
            label = { Text("ホーム") },
        )
        NavigationBarItem(
            selected = selected == AppPage.RADIO,
            onClick = { onSelected(AppPage.RADIO) },
            icon = { Icon(Icons.Rounded.CellTower, null) },
            label = { Text("無線") },
        )
        NavigationBarItem(
            selected = selected == AppPage.SETTINGS,
            onClick = { onSelected(AppPage.SETTINGS) },
            icon = { Icon(Icons.Rounded.Settings, null) },
            label = { Text("設定") },
        )
    }
}

@Composable
private fun Hero(snapshot: AppSnapshot, config: MonitorConfig) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(primary, secondary)))
                .padding(22.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color.White.copy(alpha = 0.17f), shape = CircleShape) {
                        Icon(Icons.Rounded.SwapHoriz, null, tint = Color.White, modifier = Modifier.padding(10.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SIM Pilot", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Text("DSDV SMART FAILOVER", color = Color.White.copy(alpha = .76f), fontSize = 11.sp, letterSpacing = 1.7.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (config.enabled && snapshot.monitorRunning) Color(0xFF80FFDA) else Color.White.copy(.5f))
                    )
                    Spacer(Modifier.width(9.dp))
                    AnimatedContent(snapshot.status, label = "status") { status ->
                        Text(status, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuCard(ready: Boolean, onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ready) "Shizukuの許可が必要" else "Shizukuを起動してください", fontWeight = FontWeight.Bold)
                Text(
                    if (ready) "通常版／Shizuku Plusのshell権限を使用します" else "ShizukuまたはShizuku Plusを起動します",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onRequest) { Text(if (ready) "許可" else "開く") }
        }
    }
}

@Composable
private fun AutoCard(
    config: MonitorConfig,
    running: Boolean,
    backend: String,
    onChange: (MonitorConfig) -> Unit,
    onTest: () -> Unit,
) {
    val container by animateColorAsState(
        if (config.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "autoColor",
    )
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("データSIMを自動切替", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(if (config.enabled && running) "バックグラウンドで監視中" else "通信品質の低下を検知して切替", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = config.enabled, onCheckedChange = { onChange(config.copy(enabled = it)) })
            }
            AnimatedVisibility(config.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
                    FollowSwitch("通話もデータSIMに追従", config.followVoice) { onChange(config.copy(followVoice = it)) }
                    FollowSwitch("メッセージもデータSIMに追従", config.followSms) { onChange(config.copy(followSms = it)) }
                    Text("追従はそれぞれ独立。OFFなら現在のSIMを維持します。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(onClick = onTest, enabled = config.enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("今すぐ品質テスト")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(7.dp))
                Text("切替方式: $backend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FollowSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChecked)
    }
}

@Composable
private fun RoleCard(
    role: SimRole,
    selectedSubId: Int,
    lines: List<SimLine>,
    busyRole: SimRole?,
    roleSupported: Boolean,
    modifier: Modifier,
    onSelect: (SimRole, Int) -> Unit,
) {
    val icon = when (role) {
        SimRole.DATA -> Icons.Rounded.DataUsage
        SimRole.VOICE -> Icons.Rounded.Phone
        SimRole.SMS -> Icons.AutoMirrored.Rounded.Message
    }
    Card(modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(icon, null, modifier = Modifier.padding(9.dp).size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(role.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (busyRole == role) {
                    Spacer(Modifier.weight(1f))
                    Text("切替中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    FilterChip(
                        selected = selectedSubId == line.subId,
                        onClick = { onSelect(role, line.subId) },
                        enabled = busyRole == null && roleSupported,
                        label = { Text(line.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            if (lines.isEmpty()) {
                Text("有効なSIMがありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!roleSupported) {
                Text("この端末では${role.label}SIMの切替方式を検出できません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun QualityCard(snapshot: AppSnapshot) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CellTower, null)
                Spacer(Modifier.width(10.dp))
                Text("直近の品質", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("応答", snapshot.lastLatencyMs?.let { "${it} ms" } ?: "—", Modifier.weight(1f))
                Metric("実効速度", snapshot.lastSpeedKbps?.let { if (it >= 1000) "%.1f Mbps".format(it / 1000.0) else "$it kbps" } ?: "—", Modifier.weight(1f))
                Metric("低品質", "${snapshot.badSamples} 回", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

@Composable
private fun SimDetails(lines: List<SimLine>, dataSubId: Int, dataUsage: Map<Int, DataUsageState>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("回線状態", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (lines.isEmpty()) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Text(
                    "有効なSIMは検出されていません",
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        lines.forEach { line ->
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = if (line.subId == dataSubId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("${line.slotIndex + 1}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (line.subId == dataSubId) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(line.title, fontWeight = FontWeight.Bold)
                        val usage = dataUsage[line.subId]
                        Text(
                            buildString {
                                append(line.subtitle)
                                usage?.let {
                                    append(if (it.available) " · 残り ${formatGb(it.remainingBytes)} / ${formatGb(it.capacityBytes)} GB" else " · 通信量取得待ち")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(line.networkType, fontWeight = FontWeight.Bold)
                        Text(line.dbm?.let { "$it dBm" } ?: signalText(line.signalLevel), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun signalText(level: Int) = if (level < 0) "取得中" else "電波 $level/4"

@Composable
private fun RadioDetailsPage(
    lines: List<SimLine>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    var selectedSubId by remember { mutableStateOf(lines.firstOrNull()?.subId ?: -1) }
    LaunchedEffect(lines.map { it.subId }) {
        if (selectedSubId !in lines.map { it.subId }) selectedSubId = lines.firstOrNull()?.subId ?: -1
    }
    val selectedLine = lines.firstOrNull { it.subId == selectedSubId } ?: lines.firstOrNull()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp,
                bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("無線状態", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "接続セル・副セル・近隣セルを5秒ごとに観測",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        Icon(Icons.Rounded.Refresh, if (refreshing) "更新中" else "今すぐ更新")
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        "NetMonster Coreが端末から返された値を検証・統合して表示します。端末や基地局が公開しない項目は表示されません。セル識別子や基地局座標は位置を推測できる情報です。",
                        Modifier.padding(15.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (lines.isEmpty()) {
                item { Text("有効なSIMがありません", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (lines.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lines.forEach { line ->
                            FilterChip(
                                selected = line.subId == selectedSubId,
                                onClick = { selectedSubId = line.subId },
                                label = { Text("SIM ${line.slotIndex + 1} · ${line.title}") },
                            )
                        }
                    }
                }
            }
            selectedLine?.let { line ->
                item(key = line.subId) { RadioLineDetails(line) }
            }
        }
    }
}

@Composable
private fun RadioLineDetails(line: SimLine) {
    val radio = line.radio
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("${line.slotIndex + 1}", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("subId ${line.subId} · ${radio?.technology ?: line.networkType}", style = MaterialTheme.typography.bodySmall)
                }
                Text(radio?.referenceDbm?.let { "$it dBm" } ?: "取得待ち", style = MaterialTheme.typography.labelMedium)
            }
            if (radio == null) {
                Text(
                    "取得中です。電話と位置情報の権限を確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))
            RadioDetailRow("セル構成", "Primary ${if (radio.primaryConnected) 1 else 0} · Secondary ${radio.secondaryCellCount} · Neighbor ${radio.neighboringCellCount}")
            RadioDetailRow("代表バンド", listOfNotNull(radio.bandLabel, radio.channelNumber?.let { "CH $it" }).joinToString(" · ").ifBlank { "—" })
            RadioDetailRow("品質", "RSRP ${radio.rsrpDbm?.let { "%.1f".format(it) } ?: "—"} · RSRQ ${radio.rsrqDb?.let { "%.1f".format(it) } ?: "—"} · SINR ${radio.sinrDb?.let { "%.1f".format(it) } ?: "—"}")
            val intelligence = RadioIntelligence.assess(radio)
            RadioDetailRow("無線評価", "減点 ${intelligence.penalty} · 余力 ${intelligence.capacityScore} · 信頼度 ${intelligence.confidence}%")
            if (intelligence.reasons.isNotEmpty()) {
                RadioDetailRow("評価理由", intelligence.reasons.joinToString(" · "))
            }
            if (radio.aggregatedBands.isNotEmpty()) {
                RadioDetailRow("CA", radio.aggregatedBands.joinToString(" + "))
            }
            Text("観測セル ${radio.cells.size}件", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            radio.cells.forEachIndexed { index, cell ->
                RadioCellCard(index + 1, cell)
            }
        }
    }
}

@Composable
private fun RadioCellCard(index: Int, cell: RadioCellObservation) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#$index  ${cell.title}", fontWeight = FontWeight.Bold)
                    Text(
                        cell.connection + if (cell.connectionInferred) "（推定）" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = when (cell.connection) {
                            "Primary" -> MaterialTheme.colorScheme.primary
                            "Secondary" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                cell.sourceTimestamp?.let {
                    Text("TS $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RadioFieldGroup("ネットワーク", cell.network)
            RadioFieldGroup("周波数", cell.band)
            RadioFieldGroup("セル識別", cell.identity)
            RadioFieldGroup("信号", cell.signal)
        }
    }
}

@Composable
private fun RadioFieldGroup(title: String, fields: List<RadioField>) {
    if (fields.isEmpty()) return
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    fields.forEach { field -> RadioDetailRow(field.label, field.value) }
}

@Composable
private fun RadioDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(.42f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(.58f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPageScreen(
    config: MonitorConfig,
    lines: List<SimLine>,
    dataSubId: Int,
    voiceSubId: Int,
    smsSubId: Int,
    plans: List<SimPlanConfig>,
    onChange: (MonitorConfig) -> Unit,
    onPlansChange: (List<SimPlanConfig>) -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(SettingsPage.HOME) }
    var backProgress by remember { mutableStateOf(0f) }
    var backDirection by remember { mutableStateOf(1f) }
    PredictiveBackHandler(enabled = page != SettingsPage.HOME) { events ->
        try {
            events.collect { event ->
                backProgress = event.progress
                backDirection = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
            }
            page = when (page) {
                SettingsPage.APP_LICENSE,
                SettingsPage.NETMONSTER_LICENSE,
                SettingsPage.SHIZUKU_LICENSE -> SettingsPage.LICENSES
                else -> SettingsPage.HOME
            }
        } finally {
            backProgress = 0f
            backDirection = 1f
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            modifier = Modifier.graphicsLayer {
                translationX = size.width * 0.16f * backProgress * backDirection
                scaleX = 1f - 0.025f * backProgress
                scaleY = 1f - 0.025f * backProgress
                alpha = 1f - 0.14f * backProgress
            },
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 12.dp,
                bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (page) {
                SettingsPage.HOME -> {
                    item { Text("設定", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item {
                        SettingsCategory(Icons.Rounded.CellTower, "通信品質", "電波・速度・遅延・切替感度") {
                            page = SettingsPage.QUALITY
                        }
                    }
                    item {
                        SettingsCategory(Icons.Rounded.Wifi, "Wi-Fi接続時", "切替停止と既定SIMへの復帰") {
                            page = SettingsPage.WIFI
                        }
                    }
                    item {
                        SettingsCategory(Icons.Rounded.DataUsage, "データプラン", "SIM別容量・期間・povoトッピング") {
                            page = SettingsPage.DATA_PLANS
                        }
                    }
                    item {
                        SettingsCategory(Icons.Rounded.Bolt, "バッテリー最適化", "バックグラウンド監視の制限を確認") {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                    item {
                        SettingsCategory(Icons.Rounded.Shield, "ライセンス", "SIM Pilotと第三者ライブラリ") {
                            page = SettingsPage.LICENSES
                        }
                    }
                }
                SettingsPage.WIFI -> {
                    item { SettingsPageHeader("Wi-Fi接続時") { page = SettingsPage.HOME } }
                    item {
                        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Wifi, null)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("指定した既定SIMへ戻す", fontWeight = FontWeight.Bold)
                                        Text("モバイル通信の自動切替は常に停止します", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(config.wifiRestoreEnabled, { onChange(config.copy(wifiRestoreEnabled = it)) })
                                }
                                AnimatedVisibility(config.wifiRestoreEnabled) {
                                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                onChange(config.copy(wifiDataSubId = dataSubId, wifiVoiceSubId = voiceSubId, wifiSmsSubId = smsSubId))
                                            },
                                            enabled = lines.isNotEmpty(),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("現在の既定SIMを復帰先にセット") }
                                        WifiRoleSetting(SimRole.DATA, config.wifiDataEnabled, config.wifiDataSubId, lines, { onChange(config.copy(wifiDataEnabled = it)) }, { onChange(config.copy(wifiDataSubId = it)) })
                                        WifiRoleSetting(SimRole.VOICE, config.wifiVoiceEnabled, config.wifiVoiceSubId, lines, { onChange(config.copy(wifiVoiceEnabled = it)) }, { onChange(config.copy(wifiVoiceSubId = it)) })
                                        WifiRoleSetting(SimRole.SMS, config.wifiSmsEnabled, config.wifiSmsSubId, lines, { onChange(config.copy(wifiSmsEnabled = it)) }, { onChange(config.copy(wifiSmsSubId = it)) })
                                    }
                                }
                            }
                        }
                    }
                }
                SettingsPage.QUALITY -> {
                    item { SettingsPageHeader("通信品質") { page = SettingsPage.HOME } }
                    item { SettingSlider("監視間隔", "${config.intervalSeconds} 秒", config.intervalSeconds.toFloat(), 15f..120f, 7) { onChange(config.copy(intervalSeconds = it.toInt())) } }
                    item { SettingSlider("遅延しきい値", "${config.latencyThresholdMs} ms", config.latencyThresholdMs.toFloat(), 300f..3000f, 8) { onChange(config.copy(latencyThresholdMs = (it / 100).toInt() * 100)) } }
                    item { SettingSlider("目標速度", "${config.speedThresholdKbps / 1000f} Mbps", config.speedThresholdKbps.toFloat(), 500f..20000f, 38) { onChange(config.copy(speedThresholdKbps = (it / 500).toInt() * 500)) } }
                    item { SettingSlider("弱電波の基準", "${config.weakSignalDbm} dBm", config.weakSignalDbm.toFloat(), -125f..-95f, 29) { onChange(config.copy(weakSignalDbm = it.toInt())) } }
                    item { SettingSlider("連続低品質", "${config.consecutiveFailures} 回", config.consecutiveFailures.toFloat(), 2f..5f, 2) { onChange(config.copy(consecutiveFailures = it.toInt())) } }
                    item { SettingSlider("切替後クールダウン", "${config.cooldownMinutes} 分", config.cooldownMinutes.toFloat(), 1f..30f, 28) { onChange(config.copy(cooldownMinutes = it.toInt())) } }
                    item {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(
                                "電波・遅延・実効速度を合算し、弱電波時は3秒間隔で再確認します。短い疎通成功1回だけでは悪化履歴を消しません。",
                                Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SettingsPage.DATA_PLANS -> {
                    item { SettingsPageHeader("データプラン") { page = SettingsPage.HOME } }
                    item { DataPlanSettings(lines = lines, plans = plans, onPlansChange = onPlansChange) }
                }
                SettingsPage.LICENSES -> {
                    item { SettingsPageHeader("ライセンス") { page = SettingsPage.HOME } }
                    item {
                        LicenseOverview(
                            onAppLicense = { page = SettingsPage.APP_LICENSE },
                            onNetMonsterLicense = { page = SettingsPage.NETMONSTER_LICENSE },
                            onShizukuLicense = { page = SettingsPage.SHIZUKU_LICENSE },
                        )
                    }
                }
                SettingsPage.APP_LICENSE -> {
                    item { SettingsPageHeader("SIM Pilotライセンス") { page = SettingsPage.LICENSES } }
                    item { LicenseText("licenses/SIM-Pilot-LICENSE.txt") }
                }
                SettingsPage.NETMONSTER_LICENSE -> {
                    item { SettingsPageHeader("NetMonster Core") { page = SettingsPage.LICENSES } }
                    item { LicenseText("licenses/netmonster-core-LICENSE.txt") }
                }
                SettingsPage.SHIZUKU_LICENSE -> {
                    item { SettingsPageHeader("Shizuku API") { page = SettingsPage.LICENSES } }
                    item { LicenseText("licenses/shizuku-LICENSE.txt") }
                }
            }
        }
    }
}

private enum class SettingsPage {
    HOME,
    WIFI,
    QUALITY,
    DATA_PLANS,
    LICENSES,
    APP_LICENSE,
    NETMONSTER_LICENSE,
    SHIZUKU_LICENSE,
}

@Composable
private fun LicenseOverview(
    onAppLicense: () -> Unit,
    onNetMonsterLicense: () -> Unit,
    onShizukuLicense: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SIM Pilot ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Copyright © 2026 ryuya0124 · All rights reserved", style = MaterialTheme.typography.bodyMedium)
                Text("SIM Pilot本体は権利留保です。第三者コンポーネントにはそれぞれのライセンスが適用されます。", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = onAppLicense, modifier = Modifier.fillMaxWidth()) { Text("SIM Pilotの全文を表示") }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NetMonster Core 1.3.0", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Copyright 2019 Michal Mroček", style = MaterialTheme.typography.bodyMedium)
                Text("Apache License 2.0", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("RIL値の検証・統合、セル情報と無線パラメータの取得に使用しています。", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = onNetMonsterLicense, modifier = Modifier.fillMaxWidth()) { Text("Apache 2.0全文を表示") }
                TextButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mroczis/netmonster-core"))) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("公式リポジトリ")
                }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku API / Provider 13.1.5", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Copyright © 2021 RikkaW", style = MaterialTheme.typography.bodyMedium)
                Text("MIT License", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = onShizukuLicense, modifier = Modifier.fillMaxWidth()) { Text("MIT License全文を表示") }
                TextButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku-API"))) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("公式リポジトリ")
                }
            }
        }
        Text(
            "AndroidX / Jetpack ComposeはApache License 2.0です。全依存関係の帰属情報はソース配布物のTHIRD_PARTY_NOTICES.mdで管理しています。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseText(assetPath: String) {
    val context = LocalContext.current
    val text = remember(assetPath) {
        runCatching { context.assets.open(assetPath).bufferedReader().use { it.readText() } }
            .getOrElse { "ライセンス本文を読み込めませんでした。" }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "設定へ戻る") }
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsCategory(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Icon(icon, null, Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DataPlanSettings(
    lines: List<SimLine>,
    plans: List<SimPlanConfig>,
    onPlansChange: (List<SimPlanConfig>) -> Unit,
) {
    fun update(plan: SimPlanConfig) {
        onPlansChange(plans.filterNot { it.subId == plan.subId } + plan)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lines.isEmpty()) {
            Text("有効なSIMを検出すると設定できます", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lines.forEach { line ->
            val plan = plans.firstOrNull { it.subId == line.subId } ?: SimPlanConfig(subId = line.subId)
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line.title, fontWeight = FontWeight.Bold)
                            Text("残量を切替判断に使う", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(plan.enabled, { update(plan.copy(enabled = it)) })
                    }
                    AnimatedVisibility(plan.enabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DataPlanType.entries.forEach { type ->
                                    FilterChip(
                                        selected = plan.type == type,
                                        onClick = { update(plan.copy(type = type, manualRemainingBytes = null)) },
                                        label = { Text(type.label) },
                                    )
                                }
                            }
                            when (plan.type) {
                                DataPlanType.MONTHLY -> {
                                    CapacitySlider("月間容量", plan.capacityBytes, 200) {
                                        update(plan.copy(capacityBytes = it, manualRemainingBytes = null))
                                    }
                                    SettingSlider(
                                        "開始日",
                                        "毎月 ${plan.billingDay} 日開始",
                                        plan.billingDay.toFloat(),
                                        1f..31f,
                                        29,
                                    ) { update(plan.copy(billingDay = it.toInt())) }
                                }
                                DataPlanType.FIXED -> {
                                    CapacitySlider("容量", plan.capacityBytes, 300) {
                                        update(plan.copy(capacityBytes = it, manualRemainingBytes = null))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PlanDateButton("開始", plan.startEpochDay, Modifier.weight(1f)) {
                                            update(plan.copy(startEpochDay = it.coerceAtMost(plan.endEpochDay)))
                                        }
                                        PlanDateButton("終了", plan.endEpochDay, Modifier.weight(1f)) {
                                            update(plan.copy(endEpochDay = it.coerceAtLeast(plan.startEpochDay)))
                                        }
                                    }
                                }
                                DataPlanType.FLEXIBLE -> {
                                    plan.allowances.forEachIndexed { index, allowance ->
                                        FlexibleAllowanceEditor(
                                            allowance = allowance,
                                            index = index,
                                            onChange = { changed ->
                                                update(plan.copy(allowances = plan.allowances.map { if (it.id == changed.id) changed else it }, manualRemainingBytes = null))
                                            },
                                            onDelete = {
                                                update(plan.copy(allowances = plan.allowances.filterNot { it.id == allowance.id }, manualRemainingBytes = null))
                                            },
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            val today = LocalDate.now()
                                            val next = DataAllowance(
                                                id = System.currentTimeMillis(),
                                                name = "トッピング ${plan.allowances.size + 1}",
                                                capacityBytes = 3L * GB,
                                                startEpochDay = today.toEpochDay(),
                                                endEpochDay = today.plusDays(30).toEpochDay(),
                                            )
                                            update(plan.copy(allowances = plan.allowances + next))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Rounded.Add, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("データトッピングを追加")
                                    }
                                    if (plan.allowances.isEmpty()) {
                                        Text("有効期間と容量を持つトッピングを追加してください", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            val totalCapacity = when (plan.type) {
                                DataPlanType.FLEXIBLE -> plan.allowances.sumOf { it.capacityBytes }
                                else -> plan.capacityBytes
                            }
                            if (totalCapacity > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("残量を手動指定", fontWeight = FontWeight.Medium)
                                        Text("事業者アプリの表示を優先", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = plan.manualRemainingBytes != null,
                                        onCheckedChange = {
                                            update(plan.copy(manualRemainingBytes = if (it) totalCapacity else null))
                                        },
                                    )
                                }
                                AnimatedVisibility(plan.manualRemainingBytes != null) {
                                    CapacitySlider("現在の残量", plan.manualRemainingBytes ?: totalCapacity, (totalCapacity / GB).coerceAtLeast(1).toInt()) {
                                        update(plan.copy(manualRemainingBytes = it.coerceAtMost(totalCapacity)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "通信不能・圏外からの復旧を最優先します。両回線が良好な場合だけ、残量率が15ポイント以上かつ500MB以上多い回線を優先します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlexibleAllowanceEditor(
    allowance: DataAllowance,
    index: Int,
    onChange: (DataAllowance) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(allowance.name.ifBlank { "トッピング ${index + 1}" }, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "削除") }
            }
            CapacitySlider("容量", allowance.capacityBytes, 300) {
                onChange(allowance.copy(capacityBytes = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanDateButton("開始", allowance.startEpochDay, Modifier.weight(1f)) {
                    onChange(allowance.copy(startEpochDay = it.coerceAtMost(allowance.endEpochDay)))
                }
                PlanDateButton("終了", allowance.endEpochDay, Modifier.weight(1f)) {
                    onChange(allowance.copy(endEpochDay = it.coerceAtLeast(allowance.startEpochDay)))
                }
            }
        }
    }
}

@Composable
private fun CapacitySlider(label: String, bytes: Long, maxGb: Int, onValue: (Long) -> Unit) {
    val valueGb = bytes.toFloat() / GB
    SettingSlider(label, "${formatGb(bytes)} GB", valueGb, 0f..maxGb.toFloat(), maxGb * 2 - 1) {
        onValue(((it * 2).toInt() / 2f * GB).toLong())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanDateButton(label: String, epochDay: Long, modifier: Modifier = Modifier, onDate: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("$label ${LocalDate.ofEpochDay(epochDay)}")
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = epochDay * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(it / 86_400_000L) }
                    open = false
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("キャンセル") } },
        ) { DatePicker(state = state) }
    }
}

private fun formatGb(bytes: Long): String = String.format(java.util.Locale.JAPAN, "%.1f", bytes.toDouble() / GB)

@Composable
private fun WifiRoleSetting(
    role: SimRole,
    enabled: Boolean,
    selectedSubId: Int,
    lines: List<SimLine>,
    onEnabled: (Boolean) -> Unit,
    onSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${role.label}を戻す", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(enabled, onEnabled)
        }
        AnimatedVisibility(enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (lines.isEmpty()) {
                    Text("有効なSIMがありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        lines.forEach { line ->
                            FilterChip(
                                selected = selectedSubId == line.subId,
                                onClick = { onSelected(line.subId) },
                                label = { Text(line.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    if (selectedSubId !in lines.map { it.subId }) {
                        Text(
                            if (selectedSubId < 0) "復帰先を選択してください" else "設定したSIMは現在ありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValue: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(valueText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValue, valueRange = range, steps = steps)
    }
}

private const val REQUEST_SHIZUKU = 6201
