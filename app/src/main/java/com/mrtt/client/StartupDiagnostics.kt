package com.mrtt.client

object StartupDiagnostics {
    fun credentialsDiagnostic(reason: String?): String {
        val text = reason?.trim().orEmpty()
        if (text.isEmpty()) {
            return "[ДИАГНОСТИКА] Rust-клиент запущен, но креды не пришли: проверьте сеть, токен и капчу"
        }
        val lowered = text.lowercase()
        return when {
            // MAX отверг сохранённый токен: это не сеть и не «сигналинг
            // недоступен» — нужен новый вход в MAX.
            lowered.contains("login.cred") || lowered.contains("fail_wrong_password") ||
                lowered.contains("token rejected") ->
                "[ДИАГНОСТИКА] MAX не принял сохранённый токен · войдите в MAX заново и привяжите группу · $text"
            lowered.contains("max tcp") || lowered.contains("max ws") ->
                "[ДИАГНОСТИКА] MAX-сигналинг недоступен обоими путями · $text"
            lowered.contains("max") && (lowered.contains("oneme") || lowered.contains("ws-api")) ->
                "[ДИАГНОСТИКА] MAX-сигналинг недоступен · $text"
            lowered.contains("oneme") || lowered.contains("ws-api") ->
                "[ДИАГНОСТИКА] MAX-сигналинг недоступен · $text"
            lowered.contains("капч") || lowered.contains("captcha") ->
                "[ДИАГНОСТИКА] VK требует решения капчи · $text"
            lowered.contains("authorization failed") || lowered.contains("error_code=5") ->
                "[ДИАГНОСТИКА] VK не принял токен аккаунта · $text"
            lowered.contains("turn") ->
                "[ДИАГНОСТИКА] Не удалось получить TURN-креды · $text"
            else -> "[ДИАГНОСТИКА] Креды не пришли · $text"
        }
    }

    fun configTimeoutDiagnostic(): String =
        "[ДИАГНОСТИКА] Сервер не отвечает на запрос конфига (GETCONF): " +
            "звонок и TURN работают, но данные туннеля сервер не принимает · " +
            "проверьте адрес сервера (peer) и что он запущен этой же версии"

    fun idleDiagnostic(maxMode: Boolean): String {
        val where = if (maxMode) {
            "MAX-звонок создан, но обмена с сервером нет"
        } else {
            "канал TURN готов, но сервер не отвечает"
        }
        return "[ДИАГНОСТИКА] Соединение есть, трафика нет: $where · " +
            "проверьте, что VPN включён для нужных приложений (список приложений в настройках)"
    }
}
