package com.mrtt.client

/**
 * Текст диагностики старта туннеля.
 *
 * Раньше на любой случай была одна строка «проверьте сеть, токен и капчу»: по
 * ней нельзя было понять, что делать — сеть есть, токен только что привязан, а
 * креды не пришли. Теперь показываем последнюю настоящую причину из журнала
 * клиента (MAX WS/TCP, VK, капча, TURN) — и она же объясняет, куда смотреть.
 */
object StartupDiagnostics {
    /**
     * @param reason последняя значимая строка журнала клиента (может быть null).
     */
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

    /**
     * Сервер не ответил на запрос конфига (GETCONF).
     *
     * Ровно это и выглядит как «туннель готов, а трафика нет»: звонок создан,
     * TURN-канал поднят, воркер получает конфиг — и не получает. Сессия
     * пересоздаётся, и так по кругу; в журнале это видно только повторами
     * «ChannelBind активен».
     */
    fun configTimeoutDiagnostic(): String =
        "[ДИАГНОСТИКА] Сервер не отвечает на запрос конфига (GETCONF): " +
            "звонок и TURN работают, но данные туннеля сервер не принимает · " +
            "проверьте адрес сервера (peer) и что он запущен этой же версии"

    /**
     * Туннель поднялся, но данных нет: канал TURN готов, воркеры в строю, а
     * активных соединений ноль. Это уже не «креды не пришли» — искать надо в
     * другом месте, и по журналу должно быть видно, в каком именно.
     *
     * @param maxMode включён ли MAX-транспорт (там нет своего VK-звонка).
     */
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
