package com.mrtt.client

import org.junit.Assert.*
import org.junit.Test

class VkCookieScopeTest {
    @Test fun onlyVkDomainsCanBeCleared() {
        assertTrue(vkCookieDeletionHeaders("https://captcha.example/", "session=123").isEmpty())
        assertTrue(vkCookieDeletionHeaders("https://vk.com.example/", "session=123").isEmpty())
        assertTrue(vkCookieDeletionHeaders("https://vk.com/", null).isEmpty())
    }

    @Test fun onlyRealSessionCookiesCountAsALogin() {
        // Служебные куки ВК остаются после любого захода на сайт. Раньше любая
        // кука длиннее 24 символов считалась сессией — окно шло за токеном
        // с пустышкой и показывало «web_token: VK не выдал access_token».
        assertFalse(VkAuthSecurity.hasVkSessionCookie("remixlang=0; tmr_lvid=abcdefabcdef; vkui_theme=light"))
        assertFalse(VkAuthSecurity.hasVkSessionCookie(""))
        assertFalse(VkAuthSecurity.hasVkSessionCookie(null))
        // Настоящие сессионные куки (в том числе от входа через приложение).
        assertTrue(VkAuthSecurity.hasVkSessionCookie("remixlang=0; remixsid=abc123"))
        assertTrue(VkAuthSecurity.hasVkSessionCookie("remixsid6=abc123"))
        assertTrue(VkAuthSecurity.hasVkSessionCookie("p=1; vk_session=xyz"))
        assertTrue(VkAuthSecurity.isVkSessionCookieName("RemixSid"))
        assertFalse(VkAuthSecurity.isVkSessionCookieName("remixlang"))
    }

    @Test fun clearsHostOnlyAndParentDomainCookiesWithoutTheirValues() {
        val headers = vkCookieDeletionHeaders("https://login.vk.com/", "remixsid=private-value; other=second-value")
        assertEquals(6, headers.size)
        assertTrue(headers.any { it.endsWith("Domain=vk.com") })
        assertTrue(headers.any { it.endsWith("Domain=login.vk.com") })
        assertTrue(headers.all { it.contains("Max-Age=0") && !it.contains("private-value") && !it.contains("second-value") })
        assertFalse(headers.any { it.endsWith("Domain=com") })
    }
}
