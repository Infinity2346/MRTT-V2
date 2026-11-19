package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdblockPolicyTest {

    @Test
    fun categoryNamesAreNormalizedLikeGeosite() {
        for (raw in listOf(
            "geosite-category-ru@ads",
            "category-ru@ads",
            "ru@ads",
            "ads",
            "GEOSITE-CATEGORY-RU@ADS",
            "  ads  ",
        )) {
            assertEquals("вариант $raw", "geosite-category-ru@ads", AdblockPolicy.normalizeCategory(raw))
        }
        assertEquals("geosite-category-ru@telemetry", AdblockPolicy.normalizeCategory("telemetry"))
        assertEquals("geosite-category-ru@cn", AdblockPolicy.normalizeCategory("cn"))
        // Мировые наборы: и полный id, и запись без префикса geosite-.
        for (raw in listOf(
            "geosite-category-ads-all",
            "category-ads-all",
            "GEOSITE-CATEGORY-ADS-ALL",
        )) {
            assertEquals("вариант $raw", "geosite-category-ads-all", AdblockPolicy.normalizeCategory(raw))
        }
        assertEquals("geosite-category-porn", AdblockPolicy.normalizeCategory("category-porn"))
        // Неизвестный набор не включаем никогда.
        assertNull(AdblockPolicy.normalizeCategory("geosite-category-xx@ads"))
        assertNull(AdblockPolicy.normalizeCategory("category-xx"))
        assertNull(AdblockPolicy.normalizeCategory(""))
    }

    @Test
    fun categoriesDeduplicateAndKeepInterfaceOrder() {
        val ids = AdblockPolicy.categoryIds(listOf("cn", "ads", "geosite-category-ru@ads", ""))
        // Порядок всегда интерфейсный: ads, telemetry, cn — не порядок настроек.
        assertEquals(listOf("geosite-category-ru@ads", "geosite-category-ru@cn"), ids)
        assertEquals(
            listOf("Российские: реклама", "Российские: телеметрия"),
            AdblockPolicy.enabledCategories(listOf("telemetry", "ads")).map { it.title },
        )
    }

    @Test
    fun everyCategoryHasGroupAndUpdateSource() {
        // Все наборы — снимки MetaCubeX, значит их можно обновить из сети.
        assertEquals(
            AdblockPolicy.KNOWN_CATEGORIES.size,
            AdblockPolicy.UPDATABLE_CATEGORIES.size,
        )
        // Групп ровно три, и каждая непустая: пустой заголовок в интерфейсе — баг.
        val groups = AdblockPolicy.categoriesByGroup()
        assertEquals(3, groups.size)
        assertTrue(groups.none { (_, items) -> items.isEmpty() })
        // id и файлы уникальны, иначе настройки будут включать не тот список.
        assertEquals(
            AdblockPolicy.KNOWN_CATEGORIES.size,
            AdblockPolicy.KNOWN_CATEGORIES.map { it.id }.distinct().size,
        )
        assertEquals(
            AdblockPolicy.KNOWN_CATEGORIES.size,
            AdblockPolicy.KNOWN_CATEGORIES.map { it.assetFile }.distinct().size,
        )
        assertNotNull(AdblockPolicy.categoryById("geosite-category-ads-all"))
        assertNull(AdblockPolicy.categoryById("geosite-category-unknown"))
    }

    @Test
    fun geositeNameMatchesMetaCubexFile() {
        assertEquals(
            "category-ru@ads",
            AdblockPolicy.categoryById("geosite-category-ru@ads")?.geositeName,
        )
        assertEquals(
            "category-ads-all",
            AdblockPolicy.categoryById("geosite-category-ads-all")?.geositeName,
        )
    }

    @Test
    fun parseDomainUnderstandsEveryListFormat() {
        assertEquals("ads.example.com", AdblockPolicy.parseDomain("ads.example.com"))
        assertEquals("ads.example.com", AdblockPolicy.parseDomain("*.ads.example.com"))
        assertEquals("ads.example.com", AdblockPolicy.parseDomain("||ads.example.com^"))
        assertEquals("ads.example.com", AdblockPolicy.parseDomain("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", AdblockPolicy.parseDomain("https://ads.example.com/path?x=1"))
        assertEquals("ads.example.com", AdblockPolicy.parseDomain("ADS.Example.COM."))
        assertEquals("example.com", AdblockPolicy.parseDomain("www.example.com:8080"))
        // Мусор и голые зоны не должны попадать в список.
        assertNull(AdblockPolicy.parseDomain("# комментарий"))
        assertNull(AdblockPolicy.parseDomain("! adblock comment"))
        assertNull(AdblockPolicy.parseDomain("ru"))
        assertNull(AdblockPolicy.parseDomain("localhost"))
        assertNull(AdblockPolicy.parseDomain(""))
    }

    @Test
    fun exceptionsAreParsedWithDoubleAt() {
        val rules = AdblockPolicy.parseRules("ads.example.com\n@@ok.example.com\n\n# комментарий\n")
        assertEquals(2, rules.size)
        assertEquals(AdblockPolicy.RuleKind.Block, rules[0].kind)
        assertEquals(AdblockPolicy.RuleKind.Allow, rules[1].kind)
        assertEquals(listOf("ok.example.com"), AdblockPolicy.allowDomains(rules))
        assertEquals(listOf("ads.example.com"), AdblockPolicy.blockDomains(rules))
        // Обратная сборка сохраняет смысл правил.
        assertEquals("ads.example.com\n@@ok.example.com", AdblockPolicy.rulesToText(rules))
    }

    @Test
    fun duplicatesAreDroppedWithoutLosingKind() {
        val rules = AdblockPolicy.parseRules("ads.example.com\nads.example.com\n@@ads.example.com\n")
        assertEquals(2, rules.size)
    }

    @Test
    fun subscriptionUrlsAreNormalized() {
        assertEquals("https://example.com/a.txt", AdblockPolicy.normalizeSubscriptionUrl("https://example.com/a.txt"))
        assertEquals("https://example.com/adblock.txt", AdblockPolicy.normalizeSubscriptionUrl("abp://example.com/adblock.txt"))
        assertEquals("https://example.com/list.txt", AdblockPolicy.normalizeSubscriptionUrl("domains://example.com/list.txt"))
        assertEquals("https://doubleclick.net", AdblockPolicy.normalizeSubscriptionUrl("doubleclick.net"))
        assertNull(AdblockPolicy.normalizeSubscriptionUrl("# комментарий"))
        assertNull(AdblockPolicy.normalizeSubscriptionUrl(""))
    }

    @Test
    fun geositeJsonBecomesDomainList() {
        val json = """{"version":2,"rules":[{"domain":["a.example.com"],"domain_suffix":["b.example.com",".c.example.com","ru"]}]}"""
        val text = AdblockPolicy.geositeJsonToDomains(json)
        val domains = text.lines().filter { it.isNotBlank() }
        assertEquals(listOf("a.example.com", "b.example.com", "c.example.com"), domains)
        // Не JSON — отдаём как есть: обычный текстовый список тоже подходит.
        assertEquals("a.example.com\nb.example.com", AdblockPolicy.geositeJsonToDomains("a.example.com\nb.example.com"))
    }

    @Test
    fun rulesTextCombinesOwnDomainsAndSubscriptions() {
        val rules = AdblockPolicy.parseRules("ads.example.com\n@@ok.example.com\n")
        val text = AdblockPolicy.rulesText(rules, listOf("tracker.example.org\n", ""))
        assertEquals("ads.example.com\n@@ok.example.com\ntracker.example.org\n", text)
    }

    @Test
    fun subscriptionsRoundTripThroughJson() {
        val items = listOf(
            AdblockPolicy.Subscription(url = "https://example.com/a.txt", title = "Мой список", enabled = true),
            AdblockPolicy.Subscription(url = "https://example.org/b.txt", title = "", enabled = false),
        )
        val restored = AdblockPolicy.subscriptionsFromJson(AdblockPolicy.subscriptionsToJson(items))
        assertEquals(items, restored)
        assertTrue(AdblockPolicy.subscriptionsFromJson("[]").isEmpty())
        assertTrue(AdblockPolicy.subscriptionsFromJson("не json").isEmpty())
    }

    @Test
    fun summaryCountsRulesSubscriptionsAndCategories() {
        val rules = AdblockPolicy.parseRules("ads.example.com\n@@ok.example.com\n")
        val summary = AdblockPolicy.summary(
            rules = rules,
            subscriptions = listOf(AdblockPolicy.Subscription(url = "https://example.com/a.txt")),
            categories = listOf("ads"),
        )
        assertTrue(summary.contains("свои домены: 1"))
        assertTrue(summary.contains("исключения: 1"))
        assertTrue(summary.contains("подписки: 1"))
        assertTrue(summary.contains("категории: 1"))
        assertFalse(summary.contains("подписки: 2"))
    }
}
