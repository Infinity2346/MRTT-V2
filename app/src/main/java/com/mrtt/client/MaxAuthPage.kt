package com.mrtt.client

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

internal data class MaxAuthCredentials(
    val token: String,
    val groupId: String,
    val deviceId: String = "",
    val deviceSource: String = "",
)

internal data class MaxAuthPage(
    val token: String,
    val groupId: String?,
    val deviceId: String = "",
    val deviceSource: String = "",
) {
    fun credentials(requiredGroupId: String): MaxAuthCredentials? {
        val group = groupId ?: return null
        if (requiredGroupId.isNotEmpty() && group != requiredGroupId) return null
        return MaxAuthCredentials(token, group, deviceId, deviceSource)
    }

    val isWebSessionToken: Boolean
        get() = token.trim().length < 96

    companion object {
        const val DEVICE_ID_MIN = 4
        const val DEVICE_ID_MAX = 128

        fun isMaxUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                (host == "max.ru" || host.endsWith(".max.ru"))
        }

        fun parse(result: String): MaxAuthPage? = runCatching {
            val json = JSONObject(result)
            val url = json.optString("url")
            if (!isMaxUrl(url)) return null
            val token = json.opt("token") as? String ?: return null
            if (token.length < 32 || token.any(Char::isWhitespace)) return null
            // Диапазон нарочно широкий: реальный формат deviceId у MAX не
            // документирован, а жёсткие рамки отбрасывали живое значение.
            val deviceId = (json.opt("deviceId") as? String).orEmpty()
                .trim()
                .takeIf { it.length in DEVICE_ID_MIN..DEVICE_ID_MAX && it.none(Char::isWhitespace) }
                .orEmpty()
            val deviceSource = (json.opt("deviceSource") as? String).orEmpty()
                .trim()
                .take(64)
                .filter { !it.isISOControl() }
            MaxAuthPage(token, groupIdFromUrl(url), deviceId, deviceSource)
        }.getOrNull()

        fun groupIdFromUrl(url: String): String? {
            if (!isMaxUrl(url)) return null
            val uri = URI(url)
            val segments = uri.path.orEmpty().trim('/').split('/')
            val candidate = when (segments.firstOrNull()) {
                "chat", "chats", "c" -> segments.getOrNull(1)
                else -> segments.firstOrNull()
            }
            if (candidate != null && validGroupId(candidate)) return candidate
            return uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
                val pair = parameter.split('=', limit = 2)
                if (pair.size != 2 || pair[0] !in GROUP_PARAMS) return@firstNotNullOfOrNull null
                runCatching { URLDecoder.decode(pair[1], "UTF-8") }.getOrNull()
                    ?.takeIf(::validGroupId)
            }
        }

        private val GROUP_PARAMS = setOf("chat_id", "chatId", "chat", "chat_id_str")
        private val GROUP_ID = Regex("-?[0-9]{1,64}")
        private fun validGroupId(value: String): Boolean = value.length <= 64 && GROUP_ID.matches(value)
    }
}
