package com.mrtt.client

object TunnelLogPolicy {
    fun isAllowedWhenInactiveLogging(key: String, message: String): Boolean =
        (key == "vk_js_call_progress" && message.contains("Звонок создан", ignoreCase = true)) ||
            key == "vk_call_created_progress" ||
            key == "workers_done"

    @Volatile
    var maxVerbose: Boolean = false

    fun isMaxVerboseLine(line: String): Boolean = maxVerbose && line.contains("[MAX", ignoreCase = true)

    fun isTransportAddressProbe(line: String): Boolean {
        // В MAX-режиме перебор адресов тоже нужен: именно по нему видно,
        // на каком хосте/адресе всё встало.
        if (maxVerbose) return false
        val prefix = listOf("[MAX][WS] ", "[MAX][TCP] ")
            .firstOrNull { line.startsWith(it, ignoreCase = true) }
            ?: return false
        val body = line.substring(prefix.length)
        val first = body.substringBefore(' ')
        // `ws-api.oneme.ru → …` — итог по хосту, его показываем; `1.2.3.4: …`
        // и `2a00:…`: — это перебор адресов внутри одного хоста.
        if (body.contains('→')) return false
        return first.isNotEmpty() &&
            (first.startsWith("[").not() && (first.first().isDigit() || first.first() == ':'))
    }

    fun isInternalRecovery(line: String): Boolean {
        if (isMaxVerboseLine(line)) return false
        if (line.contains("FATAL_", ignoreCase = true)) return false
        if (isTransportAddressProbe(line)) return true
        // Таймаут конфига — НЕ фон. Именно из-за него воркер молча
        // пересоздаёт сессию, и туннель показывает готовый TURN при нулевом
        // трафике; спрятав строку, мы прячем причину.
        if (isConfigTimeout(line)) return false
        return line.contains("GETCONF", ignoreCase = true) ||
            line.contains("[СЕТЬ][RETRY]", ignoreCase = true) ||
            line.contains("[TURN][RETRY]", ignoreCase = true) ||
            line.contains("[HANDSHAKE][RETRY]", ignoreCase = true) ||
            line.contains("PEER_LIVENESS_TIMEOUT", ignoreCase = true) ||
            line.contains("486", ignoreCase = true) ||
            line.contains("437", ignoreCase = true) ||
            (line.contains("turn", ignoreCase = true) && line.contains("timeout", ignoreCase = true)) ||
            (line.contains("allocation", ignoreCase = true) && line.contains("quota", ignoreCase = true)) ||
            (line.contains("allocation", ignoreCase = true) && line.contains("mismatch", ignoreCase = true)) ||
            (line.contains("[КРЕД", ignoreCase = true) && line.contains("Повторяем через", ignoreCase = true))
    }

    fun isConfigTimeout(line: String): Boolean {
        if (!line.contains("GETCONF", ignoreCase = true)) {
            return line.contains("ответа конфига: timeout", ignoreCase = true)
        }
        val lowered = line.lowercase()
        return lowered.contains("timeout") ||
            lowered.contains("таймаут") ||
            lowered.contains("не получен") ||
            lowered.contains("nocof")
    }

    fun isTurnStreamFailure(line: String): Boolean {
        if (isInternalRecovery(line)) return false
        return line.contains("[TURN][DOWN]", ignoreCase = true) ||
            (
                line.contains("[ВОРКЕР", ignoreCase = true) &&
                    line.contains("[TURN]", ignoreCase = true) &&
                    (
                        line.contains("ошибка", ignoreCase = true) ||
                            line.contains("failed", ignoreCase = true) ||
                            line.contains("exhausted", ignoreCase = true)
                        )
                )
    }
}
