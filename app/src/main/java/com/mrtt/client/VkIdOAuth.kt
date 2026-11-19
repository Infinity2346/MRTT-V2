package com.mrtt.client

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Вход через приложение ВК по официальному протоколу VK ID (OAuth 2.1 + PKCE).
 *
 * Почему так, а не «просто открыть приложение ВК»: приложение ВК хранит свой
 * вход в собственном хранилище и чужим приложениям его не отдаёт. Единственный
 * способ получить вход — попросить его у самого ВК по протоколу:
 *
 *   https://id.vk.ru/authorize?response_type=code&…&redirect_uri=vk7793118://vk.ru/blank.html
 *      → человек входит (или уже вошёл) в приложении ВК
 *      → ВК возвращает нас по нашей схеме: vk7793118://vk.ru/blank.html?code=…&device_id=…&state=…
 *      → POST https://id.vk.ru/oauth2/auth (grant_type=authorization_code, code_verifier)
 *      → access_token + refresh_token
 *
 * Код и обмен живут здесь, отдельно от WebView: так их можно проверить тестами
 * без Android.
 */
internal object VkIdOAuth {
    /** Client ID нашего приложения из кабинета VK ID. */
    const val CLIENT_ID = MrttConstants.VkAutoHash.VK_APP_ID

    /**
     * Адрес возврата.
     *
     * Своя схема `vk{client_id}://vk.ru/blank.html` формально правильная для
     * VK ID SDK, но страница `id.vk.ru/authorize` знает только адреса, которые
     * зарегистрированы у приложения; на нашу схему она отвечает `{"error":"not found"}`
     * — окно оставалось белым, и вход не начинался вообще. Поэтому возвращаемся
     * на стандартный `https://oauth.vk.ru/blank.html`: его ВК принимает всегда,
     * а страницу с кодом мы подменяем сами (`shouldInterceptRequest`) и код
     * забираем до загрузки.
     */
    const val REDIRECT_URI = "https://oauth.vk.ru/blank.html"

    /** Схема возврата из приложения ВК (объявлена в манифесте). */
    const val REDIRECT_SCHEME = "vk${CLIENT_ID}"

    const val AUTHORIZE_URL = "https://id.vk.ru/authorize"
    const val TOKEN_URL = "https://id.vk.ru/oauth2/auth"

    /**
     * Права доступа — именами, как требует VK ID: числовую маску (`1073737727`)
     * страница `id.vk.ru` не понимает. Нужны только звонки и долгий вход.
     */
    const val SCOPE = "vkid.personal_info,calls,offline"

    /** Алфавит для verifier/state: ровно тот, что разрешает VK ID (base64url без `=`). */
    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Параметры PKCE: verifier остаётся у нас, challenge уходит в ВК. */
    internal data class Pkce(
        val verifier: String,
        val challenge: String,
        val state: String,
    )

    /**
     * Новая пара PKCE на каждый вход.
     *
     * Требования VK ID: verifier — 43–128 символов из `a-z A-Z 0-9 _ -`,
     * challenge = BASE64URL(SHA256(ASCII(verifier))), метод `S256`.
     */
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

    /** `BASE64URL(SHA256(ASCII(verifier)))` без выравнивающих `=` — как требует VK ID. */
    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomToken(random: SecureRandom, length: Int): String {
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return buildString(length) { bytes.forEach { append(B64URL[((it.toInt() and 0xFF) % B64URL.length)]) } }
    }

    /** Ссылка, которую открываем: в WebView (вход логином) или через приложение ВК. */
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

    /** Разобранный возврат из приложения ВК. */
    internal data class Callback(
        val code: String,
        val deviceId: String,
        val state: String,
        val error: String,
        /** `error_description` от ВК: там настоящая причина отказа. */
        val errorDescription: String = "",
    )

    /**
     * Разбирает ссылку возврата. Возвращает `null`, если это не наш возврат:
     * так в WebView не перехватываются случайные ссылки с похожей схемой.
     */
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

    /** `https://oauth.vk.ru/blank.html` (или vk.com) — стандартный адрес возврата. */
    fun isStandardRedirect(url: String): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("https://")) return false
        val host = lowered.removePrefix("https://").substringBefore('/').substringBefore('?')
        return (host == "oauth.vk.ru" || host == "oauth.vk.com") &&
            lowered.substringAfter(host).startsWith("/blank.html")
    }

    /**
     * Проверка `state`: без неё возврат можно подделать и подсунуть чужой код.
     * Пустой state считаем несовпадением — настоящий ВК его всегда возвращает.
     */
    fun stateMatches(expected: String, actual: String): Boolean =
        expected.isNotBlank() && actual.isNotBlank() && expected == actual

    /** Параметры обмена кода на токен: ровно как в документации VK ID. */
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
