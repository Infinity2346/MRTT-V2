package com.mrtt.client

internal object VkAuthSecurity {

    private val IN_APP_SCHEMES = setOf("http", "https", "about", "javascript", "data", "blob")

    fun isExternalScheme(scheme: String): Boolean {
        if (scheme.isBlank()) return false
        return scheme.lowercase() !in IN_APP_SCHEMES
    }

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

    private val VK_SESSION_COOKIE_NAMES = listOf(
        "remixsid",
        "remixsid6",
        "remixsid_s",
        "vk_session",
    )

    fun hasVkSessionCookie(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val names = cookieHeader.split(';')
            .map { it.substringBefore('=').trim().lowercase() }
            .filter { it.isNotEmpty() }
        return names.any { name -> name in VK_SESSION_COOKIE_NAMES }
    }

    fun isVkSessionCookieName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase() ?: return false
        return normalized in VK_SESSION_COOKIE_NAMES
    }

    fun isVkHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return value == "vk.com" || value.endsWith(".vk.com") ||
            value == "vk.ru" || value.endsWith(".vk.ru")
    }
}
