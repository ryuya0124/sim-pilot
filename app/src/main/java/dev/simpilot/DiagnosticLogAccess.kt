package dev.simpilot

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DiagnosticLogEntry(
    val id: String,
    val timestamp: String,
    val level: String,
    val event: String,
    val message: String,
    val summary: String,
    val raw: String,
)

data class DiagnosticLogSnapshot(
    val entries: List<DiagnosticLogEntry>,
    val fileCount: Int,
    val totalBytes: Long,
)

data class DiagnosticExportResult(val fileCount: Int, val totalBytes: Long)

object DiagnosticLogAccess {
    private const val CURRENT_FILE = "sim-pilot-current.jsonl"
    private const val MAX_RECENT_BYTES = 2 * 1024 * 1024
    private val allowedName = Regex("sim-pilot-(?:current)\\.jsonl|sim-pilot\\.[1-9][0-9]*\\.jsonl(?:\\.gz)?")

    fun load(context: Context, maxEntries: Int = 160): DiagnosticLogSnapshot {
        val files = files(context)
        val current = files.firstOrNull { it.name == CURRENT_FILE }
        val entries = current?.let { readRecent(it, maxEntries) }.orEmpty()
        return DiagnosticLogSnapshot(entries, files.size, files.sumOf(File::length))
    }

    fun exportZip(context: Context, uri: Uri): Result<DiagnosticExportResult> = runCatching {
        val files = files(context)
        var exportedBytes = 0L
        context.contentResolver.openOutputStream(uri, "w").use { rawOutput ->
            checkNotNull(rawOutput) { "保存先を開けませんでした" }
            ZipOutputStream(rawOutput.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("SIM-Pilot-diagnostics/README.txt"))
                zip.write(
                    buildString {
                        appendLine("SIM Pilot diagnostics")
                        appendLine("Exported: ${OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
                        appendLine("App: ${BuildConfig.VERSION_NAME}")
                        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Android: ${Build.VERSION.RELEASE}")
                        appendLine("Files: ${files.size}")
                        appendLine("Cell identity fields may reveal an approximate location. Handle this archive carefully.")
                    }.toByteArray(),
                )
                zip.closeEntry()
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry("SIM-Pilot-diagnostics/${file.name}"))
                    file.inputStream().buffered().use { input -> exportedBytes += input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        DiagnosticExportResult(files.size, exportedBytes)
    }

    fun files(context: Context): List<File> = DiagnosticLog.directory(context)
        .listFiles()
        .orEmpty()
        .filter { it.isFile && allowedName.matches(it.name) }
        .sortedWith(compareBy<File> { if (it.name == CURRENT_FILE) 0 else 1 }.thenBy { it.name })

    fun file(context: Context, name: String): File? {
        if (!allowedName.matches(name)) return null
        return File(DiagnosticLog.directory(context), name).takeIf(File::isFile)
    }

    private fun readRecent(file: File, maxEntries: Int): List<DiagnosticLogEntry> {
        val text = RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - MAX_RECENT_BYTES).coerceAtLeast(0)
            input.seek(start)
            if (start > 0) input.readLine()
            val remaining = (input.length() - input.filePointer).toInt().coerceAtLeast(0)
            ByteArray(remaining).also(input::readFully).toString(Charsets.UTF_8)
        }
        return text.lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull(::parseEntry)
            .toList()
            .takeLast(maxEntries)
            .asReversed()
    }

    private fun parseEntry(raw: String): DiagnosticLogEntry? = runCatching {
        val root = JSONObject(raw)
        val timestamp = root.optString("timestamp")
        val event = root.optString("event", "unknown")
        val fields = root.optJSONObject("fields") ?: JSONObject()
        DiagnosticLogEntry(
            id = "$timestamp:$event:${raw.hashCode()}",
            timestamp = timestamp.replace('T', ' ').substringBeforeLast('+'),
            level = root.optString("level", "INFO"),
            event = event,
            message = root.optString("message"),
            summary = summary(fields),
            raw = raw,
        )
    }.getOrNull()

    private fun summary(fields: JSONObject): String {
        val radio = fields.optJSONObject("radioDetails")
        val parts = buildList {
            addValue("SIM", fields.value("name"))
            addValue("subId", fields.value("subId"))
            addValue("方式", radio?.value("technology") ?: fields.value("radioTechnology"))
            addValue("dBm", radio?.value("referenceDbm") ?: fields.value("dbm") ?: fields.value("radioReferenceDbm"))
            addValue("RSRQ", radio?.value("rsrqDb") ?: fields.value("rsrqDb") ?: fields.value("radioRsrqDb"))
            addValue("SINR", radio?.value("sinrDb") ?: fields.value("sinrDb") ?: fields.value("radioSinrDb"))
            addValue("CQI", radio?.value("cqi") ?: fields.value("radioCqi"))
            addValue("帯域kHz", radio?.value("bandwidthKhz") ?: fields.value("radioBandwidthKhz"))
            addValue("セル", radio?.optJSONArray("cells")?.length())
            addValue("遅延ms", fields.value("latencyMs"))
            addValue("速度kbps", fields.value("speedKbps"))
            addValue("スコア", fields.value("score"))
            addValue("判定", fields.value("verdict") ?: fields.value("decision"))
            addValue("理由", fields.value("reason"))
        }
        return parts.joinToString(" · ")
    }

    private fun JSONObject.value(key: String): Any? = if (has(key) && !isNull(key)) get(key) else null

    private fun MutableList<String>.addValue(label: String, value: Any?) {
        if (value != null && value.toString().isNotBlank()) add("$label $value")
    }
}
