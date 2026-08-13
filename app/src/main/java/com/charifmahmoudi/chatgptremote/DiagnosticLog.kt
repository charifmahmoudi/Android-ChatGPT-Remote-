package com.charifmahmoudi.chatgptremote

import java.time.Instant

/**
 * Small in-memory diagnostic ring buffer intended for user-submitted support reports.
 *
 * Callers must provide fixed, non-sensitive event text. Never pass credentials, tunnel IDs,
 * pairing data, JSON-RPC bodies, shell commands, ADB output, URLs, or exception messages.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 250
    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    @Synchronized
    fun record(component: String, event: String) {
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast("${Instant.now()} [$component] $event")
    }

    @Synchronized
    fun export(appVersion: String, versionCode: Int): String = buildString {
        appendLine("Android ChatGPT Remote diagnostics")
        appendLine("Version: $appVersion ($versionCode)")
        appendLine("Generated: ${Instant.now()}")
        appendLine("Privacy: credentials, identifiers, commands, and device output are excluded")
        appendLine("---")
        if (entries.isEmpty()) appendLine("No diagnostic events recorded")
        entries.forEach { appendLine(it) }
    }

    @Synchronized
    internal fun clear() = entries.clear()
}
