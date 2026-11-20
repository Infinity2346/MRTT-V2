package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VkAuthNavigationTest {

    @Test
    fun inAppSchemesStayInsideWebView() {
        for (scheme in listOf("https", "http", "HTTP", "about", "blob")) {
            assertFalse("схема $scheme должна остаться в WebView", VkAuthSecurity.isExternalScheme(scheme))
        }
    }

    @Test
    fun applicationSchemesAreHandedToTheSystem() {
        for (scheme in listOf("vk", "vkauth", "intent", "market", "tg", "mailto", "file")) {
            assertTrue("схема $scheme должна уходить системе", VkAuthSecurity.isExternalScheme(scheme))
        }
        assertFalse("пустая схема — это не переход", VkAuthSecurity.isExternalScheme(""))
    }

    @Test
    fun officialAppLoginButtonIsRecognizedByItsLabel() {
        // Именно эта кнопка на скриншоте не реагировала: её нажатие открывает
        // приложение из вложенного фрейма, и переход до WebView не доходит.
        assertTrue(VkAuthSecurity.isOfficialAppLoginLabel("Войти через приложение"))
        assertTrue(VkAuthSecurity.isOfficialAppLoginLabel("  Войти   через приложение  "))
        assertTrue(VkAuthSecurity.isOfficialAppLoginLabel("ВОЙТИ ЧЕРЕЗ ПРИЛОЖЕНИЕ"))
        // Не путаем с обычным входом и с сообщением «приложение не установлено».
        assertFalse(VkAuthSecurity.isOfficialAppLoginLabel("Войти"))
        assertFalse(VkAuthSecurity.isOfficialAppLoginLabel("Войти по паролю"))
        assertFalse(VkAuthSecurity.isOfficialAppLoginLabel("Приложение не установлено: войти через приложение"))
        assertFalse(VkAuthSecurity.isOfficialAppLoginLabel(""))
    }

    @Test
    fun returnFromTheVkAppIsRecognized() {
        // Ровно то, что приложение ВК отдаёт после «Войти через приложение»:
        // WebView такую схему не грузит, и без разбора возврат терялся, а вход
        // в MRTT так и не завершался.
        assertTrue(VkAuthSecurity.isVkAppReturn("vk7915193://vk.ru/blank.html?code=vk2.a.x&state=s"))
        assertTrue(VkAuthSecurity.isVkAppReturn("vk://vk.com/blank.html#access_token=t"))
        assertTrue(VkAuthSecurity.isVkAppReturn("vkauth://vk.ru/blank.html"))
        assertTrue(VkAuthSecurity.isVkAppReturn("vkontakte://vk.com/"))
        // Обычные страницы и чужие схемы возвратом из ВК не считаем.
        assertFalse(VkAuthSecurity.isVkAppReturn("https://vk.ru/feed"))
        assertFalse(VkAuthSecurity.isVkAppReturn("vkme://open"))
        assertFalse(VkAuthSecurity.isVkAppReturn("vk123://evil.example/callback"))
        assertFalse(VkAuthSecurity.isVkAppReturn(""))
        assertFalse(VkAuthSecurity.isVkAppReturn(null))
    }

    @Test
    fun returnFromTheVkAppIsOpenedOnAThenVkHost() {
        assertEquals("vk.ru", VkAuthSecurity.vkAppReturnHost("vk7915193://vk.ru/blank.html?code=x"))
        assertEquals("vk.com", VkAuthSecurity.vkAppReturnHost("vk://vk.com/blank.html"))
        assertNull(VkAuthSecurity.vkAppReturnHost("https://vk.ru/feed"))
    }

    @Test
    fun theManifestClaimsTheVkAppReturnScheme() {
        // Без intent-filter на `vk<client_id>://` возврат из приложения ВК уходит
        // в никуда: Android не знает, кому отдать ссылку, и вход не завершается.
        // Рабочая папка теста у Gradle — модуль `app`, но на всякий случай ищем
        // манифест и выше: тест не должен «проходить» из-за неудачного пути.
        var dir: java.io.File? = java.io.File(".").absoluteFile
        var manifest: java.io.File? = null
        while (dir != null && manifest == null) {
            val candidate = java.io.File(dir, "src/main/AndroidManifest.xml")
            if (candidate.exists()) manifest = candidate else dir = dir.parentFile
        }
        org.junit.Assume.assumeTrue("AndroidManifest.xml не найден", manifest != null)
        val text = manifest!!.readText()
        val scheme = "vk${MrttConstants.VkAutoHash.VK_APP_ID}"
        assertTrue("в манифесте нет схемы возврата $scheme", text.contains("android:scheme=\"$scheme\""))
    }

    @Test
    fun onlyVkDomainsAreTreatedAsLoginHosts() {
        assertTrue(VkAuthSecurity.isVkHost("vk.com"))
        assertTrue(VkAuthSecurity.isVkHost("id.vk.ru"))
        assertTrue(VkAuthSecurity.isVkHost("login.vk.com"))
        // Похожие домены — не ВК: с них не берём ни куки, ни токен.
        assertFalse(VkAuthSecurity.isVkHost("vk.com.evil.example"))
        assertFalse(VkAuthSecurity.isVkHost("evilvk.com"))
        assertFalse(VkAuthSecurity.isVkHost("example.com"))
    }
}
