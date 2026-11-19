package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class VkIdOAuthTest {

    @Test
    fun codeChallengeIsBase64UrlOfSha256() {
        // Значение посчитано независимо: SHA-256 от 64 символов 'a'.
        val verifier = "a".repeat(64)
        assertEquals(
            "_-BU_nrgy23GXDr5th1SCfQ5hR20PQulmXM33xVGaOs",
            VkIdOAuth.challengeOf(verifier),
        )
        // В base64url не должно быть «=» и «+»/«/» — VK ID их не принимает.
        assertFalse(VkIdOAuth.challengeOf(verifier).contains('='))
    }

    @Test
    fun pkceLooksLikeTheVkIdRequires() {
        val pkce = VkIdOAuth.newPkce(SecureRandom())
        assertTrue(pkce.verifier.length in 43..128)
        assertTrue(pkce.verifier.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertEquals(VkIdOAuth.challengeOf(pkce.verifier), pkce.challenge)
        assertTrue(pkce.state.isNotBlank())
        // Каждый вход — новая пара: иначе ВК отклонит запрос как повтор.
        assertTrue(VkIdOAuth.newPkce(SecureRandom()).verifier != pkce.verifier)
    }

    @Test
    fun authorizeUrlAsksForACodeOnOurScheme() {
        val pkce = VkIdOAuth.newPkce(SecureRandom())
        val url = VkIdOAuth.authorizeUrl(pkce)
        assertTrue(url.startsWith("https://id.vk.ru/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=${VkIdOAuth.CLIENT_ID}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=${pkce.state}"))
        // redirect_uri закодирован, но это именно наша схема возврата.
        // Возврат — на стандартный адрес ВК: свою схему `id.vk.ru/authorize`
        // не знает и отвечает `{"error":"not found"}`.
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Foauth.vk.ru%2Fblank.html"))
        assertEquals("https://oauth.vk.ru/blank.html", VkIdOAuth.REDIRECT_URI)
    }

    @Test
    fun returnFromTheVkAppIsParsed() {
        val url = "${VkIdOAuth.REDIRECT_URI}?code=vk2.a.abc123&device_id=vwMQxiq7&state=st-1"
        val callback = VkIdOAuth.parseCallback(url)
        assertNotNull(callback)
        assertEquals("vk2.a.abc123", callback!!.code)
        assertEquals("vwMQxiq7", callback.deviceId)
        assertEquals("st-1", callback.state)
        assertEquals("", callback.error)
        // Хвост после кода (например, fragment) разбор не ломает.
        assertNotNull(VkIdOAuth.parseCallback("$url#done"))
    }

    @Test
    fun foreignLinksAreNotTreatedAsOurReturn() {
        assertNull(VkIdOAuth.parseCallback(null))
        assertNull(VkIdOAuth.parseCallback(""))
        assertNull(VkIdOAuth.parseCallback("https://vk.ru/feed"))
        // Чужая схема с нашим путём — не наш возврат.
        assertNull(VkIdOAuth.parseCallback("vk7915193://vk.ru/blank.html?code=x"))
        // Наша схема без blank.html — не возврат VK ID.
        assertNull(VkIdOAuth.parseCallback("vk${VkIdOAuth.CLIENT_ID}://vk.ru/feed?code=x"))
        // Наша схема с blank.html, но без кода и ошибки — тоже не возврат.
        assertNull(VkIdOAuth.parseCallback("vk${VkIdOAuth.CLIENT_ID}://vk.ru/blank.html?state=x"))
        // Чужой blank.html на постороннем домене не считаем своим.
        assertNull(VkIdOAuth.parseCallback("https://evil.example/blank.html?code=x"))
        // Ошибку разбираем отдельно, чтобы объяснить её человеку.
        val refused = VkIdOAuth.parseCallback(
            "vk${VkIdOAuth.CLIENT_ID}://vk.ru/blank.html?error=access_denied&state=s",
        )
        assertEquals("access_denied", refused?.error)
    }

    @Test
    fun ourCallbackIsAlsoAcceptedOnTheAppScheme() {
        // Возврат из приложения ВК приходит и по схеме из манифеста:
        // `vk7793118://vk.ru/blank.html?code=…`.
        val callback = VkIdOAuth.parseCallback(
            "vk${VkIdOAuth.CLIENT_ID}://vk.ru/blank.html?code=c1&device_id=d1&state=s1",
        )
        assertEquals("c1", callback?.code)
        assertEquals("d1", callback?.deviceId)
        assertTrue(VkIdOAuth.isStandardRedirect("https://oauth.vk.ru/blank.html?code=x"))
        assertTrue(VkIdOAuth.isStandardRedirect("https://oauth.vk.com/blank.html"))
        assertFalse(VkIdOAuth.isStandardRedirect("https://vk.ru/blank.html"))
        assertFalse(VkIdOAuth.isStandardRedirect("https://oauth.vk.ru.evil/blank.html"))
    }

    @Test
    fun stateMustMatchExactly() {
        assertTrue(VkIdOAuth.stateMatches("abc", "abc"))
        assertFalse(VkIdOAuth.stateMatches("abc", "abd"))
        // Пустой state — это подделка или чужой возврат, а не «совпало».
        assertFalse(VkIdOAuth.stateMatches("abc", ""))
        assertFalse(VkIdOAuth.stateMatches("", ""))
    }

    @Test
    fun tokenRequestFollowsTheVkIdContract() {
        val fields = VkIdOAuth.tokenRequestFields(
            code = "c1",
            deviceId = "d1",
            verifier = "v1",
            state = "s1",
        ).toMap()
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals("c1", fields["code"])
        assertEquals("v1", fields["code_verifier"])
        assertEquals(VkIdOAuth.CLIENT_ID.toString(), fields["client_id"])
        assertEquals("d1", fields["device_id"])
        assertEquals(VkIdOAuth.REDIRECT_URI, fields["redirect_uri"])
        assertEquals("s1", fields["state"])
    }
}
