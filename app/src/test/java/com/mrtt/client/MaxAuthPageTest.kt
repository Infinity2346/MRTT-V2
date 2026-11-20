package com.mrtt.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MaxAuthPageTest {
    private val token = "test-token-".repeat(5)

    private fun page(url: String, value: String = token) = MaxAuthPage.parse(
        JSONObject().put("url", url).put("token", value).toString(),
    )

    @Test
    fun `token alone does not complete binding`() {
        val snapshot = page("https://web.max.ru/")!!
        assertEquals(token, snapshot.token)
        assertNull(snapshot.credentials(""))
        assertNull(page("https://web.max.ru/-123", ""))
    }

    @Test
    fun `opened group completes binding with the current token`() {
        assertEquals(MaxAuthCredentials(token, "-123"), page("https://web.max.ru/-123")!!.credentials(""))
    }

    @Test
    fun `group from connection link must match the opened group`() {
        val page = page("https://web.max.ru/-123")!!
        assertNull(page.credentials("-456"))
        assertEquals(MaxAuthCredentials(token, "-123"), page.credentials("-123"))
    }

    @Test
    fun `recognizes MAX routes and legacy query parameters`() {
        for (path in listOf("/-123", "/-123/456", "/c/-123/456", "/chat/-123", "/chats/-123", "/?chat_id=-123", "/?chatId=%2D123")) {
            assertEquals(path, "-123", MaxAuthPage.groupIdFromUrl("https://web.max.ru$path"))
        }
        for (path in listOf("/", "/:settings", "/join/123", "/chat/not-a-group", "/?chat_id=%", "/" + "1".repeat(65))) {
            assertNull(path, MaxAuthPage.groupIdFromUrl("https://web.max.ru$path"))
        }
    }

    @Test
    fun `rejects untrusted origins and malformed tokens`() {
        for (url in listOf("http://web.max.ru/-123", "https://max.ru.evil.test/-123", "https://evilmax.ru/-123", "https://max.ru@evil.test/-123", "https://user@web.max.ru/-123", "https://web.max.ru:8443/-123")) {
            assertNull(url, page(url))
        }
        assertNull(page("https://web.max.ru/-123", "too-short"))
        assertNull(page("https://web.max.ru/-123", token + "\n"))
        assertNull(MaxAuthPage.parse("null"))
        assertNull(MaxAuthPage.parse("not json"))
    }

    @Test
    fun `device id travels with the token so MAX accepts the session`() {
        // Сервер MAX привязывает токен к устройству: без deviceId страницы
        // LOGIN отвечает login.cred / FAIL_WRONG_PASSWORD.
        val result = JSONObject()
            .put("url", "https://web.max.ru/-123")
            .put("token", token)
            .put("deviceId", "10984d3d-ac32-4992-838c-7e1866fab5e8")
            .toString()
        val snapshot = MaxAuthPage.parse(result)!!
        assertEquals(
            MaxAuthCredentials(token, "-123", "10984d3d-ac32-4992-838c-7e1866fab5e8"),
            snapshot.credentials(""),
        )
        // Мусорный deviceId не сохраняется: лучше пусто, чем чужое устройство.
        val junk = JSONObject()
            .put("url", "https://web.max.ru/-123")
            .put("token", token)
            .put("deviceId", "not a device")
            .toString()
        assertEquals("", MaxAuthPage.parse(junk)!!.deviceId)
    }

    @Test
    fun `web session token is flagged so the app protocol is not promised`() {
        // Токен из localStorage web.max.ru — веб-сессия; приложение-протокол
        // отвечает на него `login.cred`. Пользователь должен знать заранее.
        // Длиннее 32 (порог парсера), но короче 96 — типичный веб-токен.
        assertTrue(page("https://web.max.ru/-123", "web-session-token-" + "a".repeat(32))!!.isWebSessionToken)
        assertFalse(page("https://web.max.ru/-123", "An_" + "x".repeat(120))!!.isWebSessionToken)
    }

    @Test
    fun `decodes javascript JSON escapes without changing token`() {
        val escaped = token + "\\\""
        assertEquals(escaped, page("https://web.max.ru/-123", escaped)!!.token)
    }
}
