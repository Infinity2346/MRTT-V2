package com.mrtt.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object VkTokenScraper {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    suspend fun tokenFromSession(context: android.content.Context, cookies: String): String {
        if (cookies.isBlank()) return ""
        return runCatching { fetchAccessToken(cookies) }.getOrNull().orEmpty()
    }

    suspend fun fetchAccessToken(
        cookies: String,
        userAgent: String = MrttConstants.VkAutoHash.WEB_USER_AGENT,
    ): String = withContext(Dispatchers.IO) {
        require(cookies.isNotBlank()) { "куки сессии пусты" }
        val response = postForm(
            endpoint = MrttConstants.VkAutoHash.WEB_TOKEN_URL,
            fields = listOf(
                "version" to "1",
                "app_id" to MrttConstants.VkAutoHash.WEB_APP_ID,
            ),
            cookies = cookies,
            userAgent = userAgent,
        )
        val token = response.optJSONObject("data")?.optString("access_token").orEmpty()
        if (token.isBlank()) {
            throw IllegalStateException(
                describeFailure(response, "web_token: VK не выдал access_token"),
            )
        }
        token
    }

    internal data class VkIdTokens(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
    )

    suspend fun exchangeVkIdCode(
        code: String,
        deviceId: String,
        verifier: String,
        state: String,
    ): VkIdTokens = withContext(Dispatchers.IO) {
        require(code.isNotBlank()) { "пустой код подтверждения VK ID" }
        require(verifier.isNotBlank()) { "потерян code_verifier: начните вход заново" }
        val response = postFormForToken(
            endpoint = VkIdOAuth.TOKEN_URL,
            fields = VkIdOAuth.tokenRequestFields(code, deviceId, verifier, state),
        )
        tokensOf(response, "VK ID: код не обменялся на токен")
    }

    suspend fun refreshVkIdToken(refreshToken: String, deviceId: String = ""): VkIdTokens =
        withContext(Dispatchers.IO) {
            require(refreshToken.isNotBlank()) { "нет refresh_token" }
            val response = postFormForToken(
                endpoint = VkIdOAuth.TOKEN_URL,
                fields = listOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to VkIdOAuth.CLIENT_ID.toString(),
                    "device_id" to deviceId,
                    "state" to "mrtt-refresh",
                ),
            )
            tokensOf(response, "VK ID: токен не обновился")
        }

    private fun tokensOf(response: JSONObject, fallback: String): VkIdTokens {
        val token = response.optString("access_token").ifBlank {
            // Часть ответов VK заворачивает токены в data.
            response.optJSONObject("data")?.optString("access_token").orEmpty()
        }
        if (token.isBlank()) throw IllegalStateException(describeFailure(response, fallback))
        val refresh = response.optString("refresh_token").ifBlank {
            response.optJSONObject("data")?.optString("refresh_token").orEmpty()
        }
        val userId = response.optString("user_id").ifBlank {
            response.optJSONObject("data")?.optString("user_id").orEmpty()
        }
        return VkIdTokens(accessToken = token, refreshToken = refresh, userId = userId.trim())
    }

    private fun postFormForToken(
        endpoint: String,
        fields: List<Pair<String, String>>,
    ): JSONObject {
        val body = fields.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", MrttConstants.VkAutoHash.WEB_USER_AGENT)
        }
        return try {
            connection.outputStream.use { output: OutputStream ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IllegalStateException("VK ID: пустой ответ (HTTP $code)")
            runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("VK ID: ответ не JSON (HTTP $code)")
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    internal fun trimCookieHeader(cookies: String): String = cookies
        .split(';')
        .map(String::trim)
        .filter { it.isNotEmpty() && it.contains('=') }
        .distinctBy { it.substringBefore('=').lowercase() }
        .joinToString("; ")

    suspend fun fetchUserId(
        token: String,
        userAgent: String = MrttConstants.VkAutoHash.WEB_USER_AGENT,
    ): String = withContext(Dispatchers.IO) {
        val response = postForm(
            endpoint = MrttConstants.VkAutoHash.WEB_API_BASE + "users.get",
            fields = listOf("v" to MrttConstants.VkAutoHash.WEB_API_VERSION),
            bearer = token,
            userAgent = userAgent,
        )
        apiErrorOf(response)?.let { throw IllegalStateException(it) }
        val user = response.optJSONArray("response")?.optJSONObject(0)
            ?: throw IllegalStateException("users.get: пустой ответ")
        val id = user.optLong("id", 0L)
        if (id == 0L) throw IllegalStateException("users.get: пустой id")
        id.toString()
    }

    private fun postForm(
        endpoint: String,
        fields: List<Pair<String, String>>,
        cookies: String? = null,
        bearer: String? = null,
        userAgent: String,
    ): JSONObject {
        val body = fields.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Origin", MrttConstants.VkAutoHash.WEB_ORIGIN)
            setRequestProperty("Referer", MrttConstants.VkAutoHash.WEB_ORIGIN + "/")
            if (!cookies.isNullOrBlank()) setRequestProperty("Cookie", cookies)
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        return try {
            connection.outputStream.use { output: OutputStream ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IllegalStateException("пустой ответ VK (HTTP $code)")
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("VK вернул не JSON (HTTP $code)")
            }
            apiErrorOf(json)?.let { throw IllegalStateException(it) }
            json
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun apiErrorOf(json: JSONObject): String? {
        val error = json.optJSONObject("error") ?: return null
        if (error.length() == 0) return null
        // error может быть числом/строкой кода в старом формате.
        if (error.optInt("error_code", 0) == 0 && error.length() > 0 && error.optString("error_msg").isBlank()) {
            return null
        }
        val code = error.optInt("error_code", 0)
        if (code == 0 && error.optString("error_msg").isBlank()) return null
        return "VK API $code: ${error.optString("error_msg", "ошибка")}"
    }

    private fun describeFailure(response: JSONObject, fallback: String): String {
        val code = response.optInt("error_code", 0)
        val message = response.optString("error_msg").ifBlank { response.optString("message") }
        return when {
            message.isNotBlank() -> "$fallback · VK API $code: $message"
            response.has("error") -> "$fallback · ${response.opt("error")}"
            // Раньше здесь оставалось сухое «VK не выдал access_token», и понять
            // причину было нельзя. Показываем, что именно ответил ВК: так видно,
            // что куки в порядке, но нет сессии, а не «ВК сломался».
            response.optJSONObject("data") != null ->
                "$fallback · в ответе нет access_token: ${response.optJSONObject("data")}"
            else -> "$fallback · ответ VK без access_token"
        }
    }
}
