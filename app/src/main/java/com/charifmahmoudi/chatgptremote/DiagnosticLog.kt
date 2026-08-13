package com.charifmahmoudi.chatgptremote

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Process-safe diagnostic ring buffer with a bounded private on-disk snapshot.
 *
 * Only low-cardinality, pre-sanitized metadata belongs here. The sanitizer is defense in depth;
 * callers must still never pass secrets, IDs, payloads, commands, output, URLs, or raw messages.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 500
    private const val MAX_EVENT_LENGTH = 300
    private const val MAX_FILE_BYTES = 256 * 1024
    private const val FILE_NAME = "diagnostic-events.log"
    private val safeText = Regex("[^A-Za-z0-9_.= :/+-]")
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private var logFile: File? = null

    @Synchronized
    fun initialize(context: Context) {
        if (logFile != null) return
        logFile = File(context.filesDir, FILE_NAME)
        runCatching {
            logFile?.takeIf(File::isFile)?.readLines()?.takeLast(MAX_ENTRIES)?.forEach(entries::addLast)
        }.onFailure {
            entries.clear()
        }
        record("logger", "initialized persisted=${entries.size}")
    }

    @Synchronized
    fun record(component: String, event: String) {
        val safeComponent = sanitize(component).take(32)
        val safeEvent = sanitize(event).take(MAX_EVENT_LENGTH)
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        val entry = "${Instant.now()} [$safeComponent] $safeEvent"
        entries.addLast(entry)
        persist(entry)
    }

    @Synchronized
    fun export(appVersion: String, versionCode: Int): String = buildString {
        appendLine("Android ChatGPT Remote diagnostics")
        appendLine("Version: ${sanitize(appVersion)} ($versionCode)")
        appendLine("Generated: ${Instant.now()}")
        appendLine("Entries: ${entries.size}/$MAX_ENTRIES")
        appendLine("Privacy: credentials, identifiers, commands, payloads, and device output are excluded")
        appendLine("---")
        if (entries.isEmpty()) appendLine("No diagnostic events recorded")
        entries.forEach { appendLine(it) }
    }

    @Synchronized
    internal fun clear() {
        entries.clear()
        runCatching { logFile?.delete() }
    }

    private fun sanitize(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(safeText, "?")

    private fun persist(entry: String) {
        val file = logFile ?: return
        runCatching {
            file.appendText("$entry\n")
            if (file.length() <= MAX_FILE_BYTES) return
            val temporary = File(file.parentFile, "$FILE_NAME.tmp")
            temporary.writeText(entries.joinToString(separator = "\n", postfix = "\n"))
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
        }
    }
}
