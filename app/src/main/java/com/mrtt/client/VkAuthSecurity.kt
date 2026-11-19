package com.mrtt.client

/**
 * Правила для ссылок на странице входа VK: что открывать в WebView, а что
 * отдавать системе.
 *
 * Вынесено отдельно от WebView, чтобы это можно было проверить тестами:
 * именно здесь ломался вход «через приложение» — WebView пробовал сам
 * загрузить `vk://…` и `intent://…`, падал с ошибкой, и кнопка на странице
 * ВК выглядела нерабочей.
 */
internal object VkAuthSecurity {

    /** Схемы, которые WebView умеет показывать сам. Всё остальное — чужое приложение. */
    private val IN_APP_SCHEMES = setOf("http", "https", "about", "javascript", "data", "blob")

    /** Схема требует внешнего приложения (VK, браузер, магазин). */
    fun isExternalScheme(scheme: String): Boolean {
        if (scheme.isBlank()) return false
        return scheme.lowercase() !in IN_APP_SCHEMES
    }

    /**
     * Текст на кнопке ВК «Войти через приложение»: по нему JS-скрипт в окне входа
     * ловит нажатие официальной кнопки, даже когда она открывает приложение из
     * вложенного фрейма и переход по `vk://` до WebView не доходит.
     */
    private val OFFICIAL_APP_LOGIN_LABEL = Regex(
        "войти через приложение",
        RegexOption.IGNORE_CASE,
    )

    fun isOfficialAppLoginLabel(label: String): Boolean {
        val text = label.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.length > 64) return false
        if (text.contains("не установлено", ignoreCase = true)) return false
        return OFFICIAL_APP_LOGIN_LABEL.containsMatchIn(text)
    }

    /**
     * Ссылка, по которой ВК возвращает человека из своего приложения.
     *
     * Вход «через приложение» заканчивается не в WebView: приложение ВК отдаёт
     * результат обратно браузеру (или нам) ссылкой вида
     * `vk7915193://vk.ru/blank.html?...`. WebView такую схему не грузит, и без
     * этой проверки переход молча терялся — человек возвращался в MRTT, а вход
     * так и не завершался. Такие ссылки разбираем сами: из них берём код/токен
     * либо просто считаем сигналом «сессия появилась, забираем куки».
     */
    private val VK_APP_RETURN_SCHEMES = Regex("^vk\\d*$", RegexOption.IGNORE_CASE)

    fun isVkAppReturn(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val scheme = url.substringBefore("://", "").substringBefore(':')
        if (scheme.isBlank()) return false
        if (VK_APP_RETURN_SCHEMES.matches(scheme)) {
            // Только путь, который ВК действительно использует для возврата.
            return url.contains("vk.ru/", ignoreCase = true) ||
                url.contains("vk.com/", ignoreCase = true) ||
                url.contains("blank.html", ignoreCase = true)
        }
        // Отдельные версии приложения ВК возвращают через свою схему.
        val lowered = scheme.lowercase()
        return lowered == "vkauth" || lowered == "vkontakte" || lowered == "vkconnect"
    }

    /** Хост, с которого пришёл возврат из приложения ВК: нужен, чтобы пустить WebView дальше. */
    fun vkAppReturnHost(url: String?): String? {
        if (!isVkAppReturn(url)) return null
        val afterScheme = url!!.substringAfter("://", "")
        val host = afterScheme.substringBefore('/').substringBefore('?')
        return if (isVkHost(host) || host.contains("blank.html", ignoreCase = true)) {
            host.ifBlank { "vk.ru" }
        } else {
            "vk.ru"
        }
    }

    /**
     * Куки, которые действительно означают «человек вошёл».
     *
     * Раньше сессией считались любые куки длиннее 24 символов, и этого хватало
     * для поломки: у любого захода на vk.ru в WebView остаются служебные куки
     * (`remixlang`, `tmr_*`, `vkui_*`), а сессия приложения ВК живёт в отдельном
     * хранилище и в наш WebView вообще не попадает. Из-за этого сразу после
     * возврата из приложения окно шло за токеном с пустышкой и показывало
     * «web_token: VK не выдал access_token» при каждой новой попытке.
     */
    private val VK_SESSION_COOKIE_NAMES = listOf(
        "remixsid",
        "remixsid6",
        "remixsid_s",
        "vk_session",
    )

    /**
     * Есть ли в заголовке кук настоящая сессия ВК.
     *
     * Проверяем только по именам сессионных кук: служебные куки и `id.vk.ru`
     * без сессии входом не считаются, иначе окно уходит за токеном впустую.
     */
    fun hasVkSessionCookie(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val names = cookieHeader.split(';')
            .map { it.substringBefore('=').trim().lowercase() }
            .filter { it.isNotEmpty() }
        return names.any { name -> name in VK_SESSION_COOKIE_NAMES }
    }

    /** Имена сессионных кук: по ним чистим протухшую сессию перед новой попыткой. */
    fun isVkSessionCookieName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase() ?: return false
        return normalized in VK_SESSION_COOKIE_NAMES
    }

    /** Домен принадлежит ВК: там живут куки сессии и только оттуда берём токен. */
    fun isVkHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return value == "vk.com" || value.endsWith(".vk.com") ||
            value == "vk.ru" || value.endsWith(".vk.ru")
    }
}
