package com.mrtt.client

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object FullLog {
    private const val MAX_LINES = 4000

    private const val DEDUPE_WINDOW_MS = 1_000L
    private const val DEDUPE_MEMORY = 128

    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val recentClientLines = ArrayDeque<Pair<String, Long>>()

    private val secrets = listOf(
        Regex("""(?i)\b(password|pass|pwd|token|access_token|secret|key|hashes|hash|cookie|remixsid)=([^&\s"']+)""") to "$1=***",
        Regex("""(?i)"(password|token|access_token|secret|cookie)"\s*:\s*"[^"]*"""") to "\"$1\":\"***\"",
        Regex("""vk1\.a\.[A-Za-z0-9_\-]+""") to "vk1.a.***",
        Regex("""(?i)\b(mrtt|mrttsub)://[A-Za-z0-9_\-+/=%.]+""") to "$1://***",
    )

    fun redact(text: String): String = secrets.fold(text) { acc, (pattern, replacement) -> acc.replace(pattern, replacement) }

    fun add(source: String, line: String) {
        if (line.isBlank() || !TunnelManager.isLoggingEnabled) return
        val body = redact(line.trim()).take(1000)
        val now = System.currentTimeMillis()
        synchronized(lines) {
            val fromClient = source == SOURCE_CLIENT
            if (!fromClient && recentlyLoggedFromClient(body, now)) return
            if (fromClient) rememberClientLine(body, now)
            val entry = "${synchronized(time) { time.format(Date()) }} [$source] $body"
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(entry)
        }
    }

    private fun recentlyLoggedFromClient(body: String, now: Long): Boolean {
        while (recentClientLines.isNotEmpty() && now - recentClientLines.first().second > DEDUPE_WINDOW_MS) {
            recentClientLines.removeFirst()
        }
        return recentClientLines.any { (text, _) -> text == body }
    }

    private fun rememberClientLine(body: String, now: Long) {
        recentClientLines.addLast(body to now)
        while (recentClientLines.size > DEDUPE_MEMORY) recentClientLines.removeFirst()
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) {
        lines.clear()
        recentClientLines.clear()
    }

    private const val SOURCE_CLIENT = "клиент"
}
