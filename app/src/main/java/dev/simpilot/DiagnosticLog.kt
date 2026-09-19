package dev.simpilot

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

object DiagnosticLog {
    private const val TAG = "SimPilotDiag"
    private const val DIRECTORY = "diagnostics"
    private const val CURRENT_FILE = "sim-pilot-current.jsonl"
    private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    private const val MAX_ARCHIVES = 16
    private val lock = Any()
    @Volatile private var legacyMigrationDone = false

    fun info(
        context: Context,
        event: String,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
    ) = write(context, "INFO", event, message, fields, null)

    fun warn(
        context: Context,
        event: String,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = write(context, "WARN", event, message, fields, error)

    fun error(
        context: Context,
        event: String,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = write(context, "ERROR", event, message, fields, error)

    fun directory(context: Context): File = File(deviceContext(context).filesDir, DIRECTORY)

    private fun write(
        context: Context,
        level: String,
        event: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        val line = JSONObject().apply {
            put("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("level", level)
            put("event", event)
            put("message", message)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("fields", JSONObject().apply {
                fields.forEach { (key, value) -> put(key, JSONObject.wrap(value)) }
            })
            error?.let { put("error", it.stackTraceToString()) }
        }.toString() + "\n"

        when (level) {
            "ERROR" -> Log.e(TAG, "$event: $message", error)
            "WARN" -> Log.w(TAG, "$event: $message", error)
            else -> Log.i(TAG, "$event: $message")
        }

        runCatching {
            synchronized(lock) {
                val directory = directory(context).apply { mkdirs() }
                if (!legacyMigrationDone) {
                    migrateLegacyArchives(directory)
                    legacyMigrationDone = true
                }
                val current = File(directory, CURRENT_FILE)
                val bytes = line.toByteArray(Charsets.UTF_8)
                if (current.length() + bytes.size > MAX_FILE_BYTES) rotate(directory, current)
                FileOutputStream(current, true).use { it.write(bytes) }
            }
        }.onFailure { Log.e(TAG, "diagnostic log write failed", it) }
    }

    private fun rotate(directory: File, current: File) {
        File(directory, "sim-pilot.$MAX_ARCHIVES.jsonl.gz").delete()
        for (index in MAX_ARCHIVES - 1 downTo 1) {
            val source = File(directory, "sim-pilot.$index.jsonl.gz")
            if (source.exists()) source.renameTo(File(directory, "sim-pilot.${index + 1}.jsonl.gz"))
        }
        if (current.exists()) {
            gzip(current, File(directory, "sim-pilot.1.jsonl.gz"))
            current.delete()
        }
    }

    private fun migrateLegacyArchives(directory: File) {
        for (index in 1..MAX_ARCHIVES) {
            val legacy = File(directory, "sim-pilot.$index.jsonl")
            val compressed = File(directory, "sim-pilot.$index.jsonl.gz")
            if (legacy.exists() && !compressed.exists()) {
                gzip(legacy, compressed)
                legacy.delete()
            }
        }
    }

    private fun gzip(source: File, target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.delete()
        source.inputStream().buffered().use { input ->
            GZIPOutputStream(FileOutputStream(temporary)).buffered().use { output -> input.copyTo(output) }
        }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Failed to install compressed diagnostic archive ${target.name}" }
    }

    private fun deviceContext(context: Context): Context =
        if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
}
