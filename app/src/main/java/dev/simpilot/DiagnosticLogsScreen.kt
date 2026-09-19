package dev.simpilot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class DiagnosticLogFilter(val label: String) {
    ALL("すべて"),
    RADIO("無線"),
    DECISION("判定"),
    SWITCH("切替"),
    WARNING("警告"),
    ;

    fun accepts(entry: DiagnosticLogEntry): Boolean = when (this) {
        ALL -> true
        RADIO -> entry.event.contains("radio") || entry.event.contains("quality")
        DECISION -> listOf("decision", "probe", "comparison", "monitor_cycle")
            .any(entry.event::contains)
        SWITCH -> listOf("switch", "default", "wifi_restore").any(entry.event::contains)
        WARNING -> entry.level == "WARN" || entry.level == "ERROR"
    }
}

@Composable
internal fun DiagnosticLogsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<DiagnosticLogSnapshot?>(null) }
    var filter by remember { mutableStateOf(DiagnosticLogFilter.ALL) }
    var loading by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            snapshot = withContext(Dispatchers.IO) { DiagnosticLogAccess.load(context) }
            loading = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                exportStatus = "保存中…"
                val result = withContext(Dispatchers.IO) { DiagnosticLogAccess.exportZip(context, uri) }
                exportStatus = result.fold(
                    onSuccess = { "${it.fileCount}ファイル・${formatLogSize(it.totalBytes)}を保存しました" },
                    onFailure = { "保存に失敗: ${it.message.orEmpty()}" },
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    val visibleEntries = snapshot?.entries.orEmpty().filter(filter::accepts)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 12.dp,
            bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 112.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { DiagnosticLogHeader(onBack) }
        item {
            DiagnosticLogControls(
                snapshot = snapshot,
                loading = loading,
                filter = filter,
                exportStatus = exportStatus,
                onFilter = { filter = it },
                onRefresh = ::refresh,
                onExport = {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    exportLauncher.launch("SIM-Pilot-logs-$timestamp.zip")
                },
            )
        }
        if (!loading && visibleEntries.isEmpty()) {
            item {
                Text(
                    "この条件のログはまだありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(visibleEntries, key = DiagnosticLogEntry::id) { entry ->
            DiagnosticLogEntryCard(entry)
        }
    }
}

@Composable
private fun DiagnosticLogHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "戻る")
        }
        Text("診断ログ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiagnosticLogControls(
    snapshot: DiagnosticLogSnapshot?,
    loading: Boolean,
    filter: DiagnosticLogFilter,
    exportStatus: String?,
    onFilter: (DiagnosticLogFilter) -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("端末内診断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    snapshot?.let { "${it.fileCount}ファイル · ${formatLogSize(it.totalBytes)} · 最近${it.entries.size}件を表示" }
                        ?: if (loading) "読み込み中…" else "ログ情報を取得できません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "セル識別情報は、おおよその場所を推測できる場合があります。共有先に注意してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.weight(1f)) {
                        Text(if (loading) "更新中…" else "更新")
                    }
                    Button(
                        onClick = onExport,
                        enabled = snapshot?.fileCount?.let { it > 0 } == true,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("ZIPへ保存")
                    }
                }
                exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagnosticLogFilter.entries.forEach { candidate ->
                FilterChip(
                    selected = filter == candidate,
                    onClick = { onFilter(candidate) },
                    label = { Text(candidate.label) },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLogEntryCard(entry: DiagnosticLogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val accent = when (entry.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.level, color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(10.dp))
                Text(
                    entry.timestamp,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                entry.event,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(entry.message, style = MaterialTheme.typography.bodyMedium)
            if (entry.summary.isNotBlank()) {
                Text(entry.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "詳細を閉じる" else "JSON詳細")
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        entry.raw,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatLogSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
