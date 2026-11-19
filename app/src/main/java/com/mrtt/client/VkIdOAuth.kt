package com.mrtt.client

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object VkIdOAuth {
    const val CLIENT_ID = MrttConstants.VkAutoHash.VK_APP_ID

    const val REDIRECT_URI = "https://oauth.vk.ru/blank.html"

    const val REDIRECT_SCHEME = "vk${CLIENT_ID}"

    const val AUTHORIZE_URL = "https://id.vk.ru/authorize"
    const val TOKEN_URL = "https://id.vk.ru/oauth2/auth"

    const val SCOPE = "vkid.personal_info,calls,offline"

    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    internal data class Pkce(
        val verifier: String,
        val challenge: String,
        val state: String,
    )

    fun newPkce(random: SecureRandom = SecureRandom(), verifierLength: Int = 64): Pkce {
        require(verifierLength in 43..128) { "verifier должен быть 43–128 символов" }
        val verifier = ByteArray(verifierLength).let { bytes ->
            random.nextBytes(bytes)
            buildString(verifierLength) {
                bytes.forEach { byte ->
                    append(B64URL[((byte.toInt() and 0xFF) % B64URL.length)])
                }
            }
        }
        return Pkce(
            verifier = verifier,
            challenge = challengeOf(verifier),
            state = randomToken(random, 32),
        )
    }

    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomToken(random: SecureRandom, length: Int): String {
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return buildString(length) { bytes.forEach { append(B64URL[((it.toInt() and 0xFF) % B64URL.length)]) } }
    }

    fun authorizeUrl(pkce: Pkce): String = buildString {
        append(AUTHORIZE_URL)
        append("?response_type=code")
        append("&client_id=").append(CLIENT_ID)
        append("&redirect_uri=").append(URLEncoder.encode(REDIRECT_URI, "UTF-8"))
        append("&scope=").append(URLEncoder.encode(SCOPE, "UTF-8"))
        append("&state=").append(pkce.state)
        append("&code_challenge=").append(pkce.challenge)
        append("&code_challenge_method=S256")
        append("&v=").append(MrttConstants.VkAutoHash.API_VERSION)
    }

    internal data class Callback(
        val code: String,
        val deviceId: String,
        val state: String,
        val error: String,
        val errorDescription: String = "",
    )

    fun parseCallback(url: String?): Callback? {
        if (url.isNullOrBlank()) return null
        val normalized = url.trim()
        if (!normalized.contains("blank.html", ignoreCase = true)) return null
        val ourScheme = normalized.startsWith("$REDIRECT_SCHEME://", ignoreCase = true)
        // Стандартный адрес возврата (`https://oauth.vk.ru/blank.html`) и наша
        // схема из манифеста — оба наших; остальные ссылки с blank.html чужие.
        if (!ourScheme && !isStandardRedirect(normalized)) return null
        val query = normalized.substringAfter('?', "")
        val params = parseQuery(query)
        val code = params["code"].orEmpty()
        val error = params["error"].orEmpty()
        if (code.isBlank() && error.isBlank()) return null
        return Callback(
            code = code,
            deviceId = params["device_id"].orEmpty(),
            state = params["state"].orEmpty(),
            error = error,
            errorDescription = params["error_description"].orEmpty(),
        )
    }

    fun isStandardRedirect(url: String): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("https://")) return false
        val host = lowered.removePrefix("https://").substringBefore('/').substringBefore('?')
        return (host == "oauth.vk.ru" || host == "oauth.vk.com") &&
            lowered.substringAfter(host).startsWith("/blank.html")
    }

    fun stateMatches(expected: String, actual: String): Boolean =
        expected.isNotBlank() && actual.isNotBlank() && expected == actual

    fun tokenRequestFields(
        code: String,
        deviceId: String,
        verifier: String,
        state: String,
    ): List<Pair<String, String>> = listOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "code_verifier" to verifier,
        "client_id" to CLIENT_ID.toString(),
        "device_id" to deviceId,
        "redirect_uri" to REDIRECT_URI,
        "state" to state,
    )

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = HashMap<String, String>()
        query.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val key = part.substring(0, eq)
            val value = runCatching {
                java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            }.getOrDefault(part.substring(eq + 1))
            result[key] = value
        }
        return result
    }
}
