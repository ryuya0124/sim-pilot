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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DataUsage
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

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
        AppState.update {
            it.copy(
                shizukuReady = ShizukuBridge.isReady(),
                shizukuGranted = ShizukuBridge.isGranted(),
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
    var showSettings by remember { mutableStateOf(false) }
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
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
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
                "consecutiveFailures" to next.consecutiveFailures,
                "cooldownMinutes" to next.cooldownMinutes,
            ),
        )
        if (next.needsService()) MonitorService.start(context, reason = "config_changed") else MonitorService.stop(context)
    }

    fun switch(role: SimRole, subId: Int) {
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 10.dp,
                    bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Hero(snapshot, config, onSettings = { showSettings = true }) }
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
                                RoleCard(SimRole.DATA, snapshot.dataSubId, snapshot.lines, busyRole, Modifier.weight(1f), ::switch)
                                RoleCard(SimRole.VOICE, snapshot.voiceSubId, snapshot.lines, busyRole, Modifier.weight(1f), ::switch)
                                RoleCard(SimRole.SMS, snapshot.smsSubId, snapshot.lines, busyRole, Modifier.weight(1f), ::switch)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                RoleCard(SimRole.DATA, snapshot.dataSubId, snapshot.lines, busyRole, Modifier.fillMaxWidth(), ::switch)
                                RoleCard(SimRole.VOICE, snapshot.voiceSubId, snapshot.lines, busyRole, Modifier.fillMaxWidth(), ::switch)
                                RoleCard(SimRole.SMS, snapshot.smsSubId, snapshot.lines, busyRole, Modifier.fillMaxWidth(), ::switch)
                            }
                        }
                    }
                }
                item { QualityCard(snapshot) }
                item { SimDetails(snapshot.lines, snapshot.dataSubId) }
                item {
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("バッテリー最適化を確認")
                    }
                }
            }
    }

    if (showSettings) {
        SettingsSheet(
            config = config,
            lines = snapshot.lines,
            dataSubId = snapshot.dataSubId,
            voiceSubId = snapshot.voiceSubId,
            smsSubId = snapshot.smsSubId,
            onDismiss = { showSettings = false },
            onChange = ::persist,
        )
    }
}

@Composable
private fun Hero(snapshot: AppSnapshot, config: MonitorConfig, onSettings: () -> Unit) {
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
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "設定", tint = Color.White) }
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
private fun AutoCard(config: MonitorConfig, running: Boolean, onChange: (MonitorConfig) -> Unit, onTest: () -> Unit) {
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
                        enabled = busyRole == null,
                        label = { Text(line.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            if (lines.isEmpty()) {
                Text("有効なSIMがありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SimDetails(lines: List<SimLine>, dataSubId: Int) {
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
                        Text(line.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    config: MonitorConfig,
    lines: List<SimLine>,
    dataSubId: Int,
    voiceSubId: Int,
    smsSubId: Int,
    onDismiss: () -> Unit,
    onChange: (MonitorConfig) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text("Wi-Fi接続時", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
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
                            Switch(
                                checked = config.wifiRestoreEnabled,
                                onCheckedChange = { onChange(config.copy(wifiRestoreEnabled = it)) },
                            )
                        }
                        AnimatedVisibility(config.wifiRestoreEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        onChange(
                                            config.copy(
                                                wifiDataSubId = dataSubId,
                                                wifiVoiceSubId = voiceSubId,
                                                wifiSmsSubId = smsSubId,
                                            )
                                        )
                                    },
                                    enabled = lines.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("現在の既定SIMを復帰先にセット")
                                }
                                WifiRoleSetting(
                                    role = SimRole.DATA,
                                    enabled = config.wifiDataEnabled,
                                    selectedSubId = config.wifiDataSubId,
                                    lines = lines,
                                    onEnabled = { onChange(config.copy(wifiDataEnabled = it)) },
                                    onSelected = { onChange(config.copy(wifiDataSubId = it)) },
                                )
                                WifiRoleSetting(
                                    role = SimRole.VOICE,
                                    enabled = config.wifiVoiceEnabled,
                                    selectedSubId = config.wifiVoiceSubId,
                                    lines = lines,
                                    onEnabled = { onChange(config.copy(wifiVoiceEnabled = it)) },
                                    onSelected = { onChange(config.copy(wifiVoiceSubId = it)) },
                                )
                                WifiRoleSetting(
                                    role = SimRole.SMS,
                                    enabled = config.wifiSmsEnabled,
                                    selectedSubId = config.wifiSmsSubId,
                                    lines = lines,
                                    onEnabled = { onChange(config.copy(wifiSmsEnabled = it)) },
                                    onSelected = { onChange(config.copy(wifiSmsSubId = it)) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text("自動切替の判定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            item {
                SettingSlider("監視間隔", "${config.intervalSeconds} 秒", config.intervalSeconds.toFloat(), 15f..120f, 7) {
                    onChange(config.copy(intervalSeconds = it.toInt()))
                }
            }
            item {
                SettingSlider("遅延しきい値", "${config.latencyThresholdMs} ms", config.latencyThresholdMs.toFloat(), 300f..3000f, 8) {
                    onChange(config.copy(latencyThresholdMs = (it / 100).toInt() * 100))
                }
            }
            item {
                SettingSlider("最低速度", "${config.speedThresholdKbps} kbps", config.speedThresholdKbps.toFloat(), 128f..2048f, 7) {
                    onChange(config.copy(speedThresholdKbps = (it / 128).toInt() * 128))
                }
            }
            item {
                SettingSlider("連続低品質", "${config.consecutiveFailures} 回", config.consecutiveFailures.toFloat(), 2f..5f, 2) {
                    onChange(config.copy(consecutiveFailures = it.toInt()))
                }
            }
            item {
                SettingSlider("切替後クールダウン", "${config.cooldownMinutes} 分", config.cooldownMinutes.toFloat(), 1f..30f, 28) {
                    onChange(config.copy(cooldownMinutes = it.toInt()))
                }
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        "SIMの有無と表示名は端末から自動取得します。Wi-Fi復帰先が抜かれている場合、その項目だけ安全に保留します。通常は小さな疎通確認のみで、約5分ごと、または遅延悪化時に64KBの速度テストを行います。",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

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
