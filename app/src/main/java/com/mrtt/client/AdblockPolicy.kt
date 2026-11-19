package com.mrtt.client

import org.json.JSONArray
import org.json.JSONObject

internal object AdblockPolicy {

    enum class CategorySource {
        BuiltIn,

        GeositeRemote,
    }

    val KNOWN_CATEGORIES: List<Category> = listOf(
        Category(
            id = "geosite-category-ads-all",
            title = "Реклама и трекеры (все)",
            description = "Самый широкий набор: рекламные сети, баннеры и трекеры со всего мира. Начните с него",
            assetFile = "adblock-geosite-ads-all.txt",
            group = CategoryGroup.Global,
            source = CategorySource.GeositeRemote,
            approxDomains = 908,
        ),
        Category(
            id = "geosite-category-ads-and-telemetry",
            title = "Реклама и телеметрия",
            description = "Реклама вместе со счётчиками аналитики: приложения меньше «стучат» наружу",
            assetFile = "adblock-geosite-ads-and-telemetry.txt",
            group = CategoryGroup.Global,
            source = CategorySource.GeositeRemote,
            approxDomains = 889,
        ),
        Category(
            id = "geosite-category-telemetry",
            title = "Только телеметрия",
            description = "Счётчики, аналитика и крэш-репорты без самой рекламы: меньше данных у сервисов",
            assetFile = "adblock-geosite-telemetry.txt",
            group = CategoryGroup.Global,
            source = CategorySource.GeositeRemote,
            approxDomains = 237,
        ),
        Category(
            id = "geosite-category-public-tracker",
            title = "Публичные трекеры",
            description = "Открытые BitTorrent-трекеры: их часто блокируют провайдеры и антипиратские сервисы",
            assetFile = "adblock-geosite-public-tracker.txt",
            group = CategoryGroup.Global,
            source = CategorySource.GeositeRemote,
            approxDomains = 433,
        ),
        Category(
            id = "geosite-category-porn",
            title = "Взрослые сайты",
            description = "Порно-сайты и их CDN: удобно, если телефоном пользуется ребёнок",
            assetFile = "adblock-geosite-porn.txt",
            group = CategoryGroup.Extra,
            source = CategorySource.GeositeRemote,
            approxDomains = 6519,
        ),
        Category(
            id = "geosite-category-doh",
            title = "Шифрованный DNS",
            description = "DoH-серверы: закрывают обход фильтра через HTTPS-резолверы. Включайте, если реклама проскакивает",
            assetFile = "adblock-geosite-doh.txt",
            group = CategoryGroup.Extra,
            source = CategorySource.GeositeRemote,
            approxDomains = 127,
        ),
        Category(
            id = "geosite-category-ru@ads",
            title = "Российские: реклама",
            description = "Рекламные и промо-домены российских сервисов: Ozon, банки, маркетплейсы",
            assetFile = "adblock-geosite-ru-ads.txt",
            group = CategoryGroup.Russia,
            source = CategorySource.GeositeRemote,
            approxDomains = 189,
        ),
        Category(
            id = "geosite-category-ru@telemetry",
            title = "Российские: телеметрия",
            description = "Счётчики и трекеры аналитики: Яндекс.Метрика, sentry, статистика приложений",
            assetFile = "adblock-geosite-ru-telemetry.txt",
            group = CategoryGroup.Russia,
            source = CategorySource.GeositeRemote,
            approxDomains = 50,
        ),
        Category(
            id = "geosite-category-ru@cn",
            title = "Российские: «китайские» трекеры",
            description = "Набор geosite-category-ru@cn: активация и статистика антивирусов, трекеры",
            assetFile = "adblock-geosite-ru-cn.txt",
            group = CategoryGroup.Russia,
            source = CategorySource.GeositeRemote,
            approxDomains = 98,
        ),
    )

    enum class CategoryGroup(val title: String, val hint: String) {
        Global(
            "Для всех",
            "Мировые рекламные сети и трекеры: эти наборы стоит включить в первую очередь",
        ),
        Russia(
            "Российские сервисы",
            "Реклама, аналитика и трекеры российских приложений и сайтов",
        ),
        Extra(
            "Дополнительно",
            "Не про рекламу, но часто нужно: взрослые сайты и обход фильтра через DoH",
        ),
    }

    fun categoriesByGroup(): List<Pair<CategoryGroup, List<Category>>> =
        CategoryGroup.entries.map { group -> group to KNOWN_CATEGORIES.filter { it.group == group } }

    val UPDATABLE_CATEGORIES: List<Category> =
        KNOWN_CATEGORIES.filter { it.source == CategorySource.GeositeRemote }

    fun categoryById(id: String): Category? = KNOWN_CATEGORIES.firstOrNull { it.id == id }

    const val MAX_USER_DOMAINS: Int = 200_000

    const val MAX_SUBSCRIPTIONS: Int = 32

    enum class RuleKind { Block, Allow }

    enum class SubscriptionKind { Plain, Geosite }

    data class Category(
        val id: String,
        val title: String,
        val description: String,
        val assetFile: String,
        val group: CategoryGroup = CategoryGroup.Global,
        val source: CategorySource = CategorySource.BuiltIn,
        val approxDomains: Int = 0,
    ) {
        val geositeName: String get() = id.removePrefix("geosite-")
    }

    data class DomainRule(
        val domain: String,
        val kind: RuleKind,
    )

    data class Subscription(
        val url: String,
        val title: String = "",
        val enabled: Boolean = true,
    )

    fun normalizeCategory(raw: String): String? {
        var name = raw.trim().lowercase()
        if (name.isEmpty()) return null
        // Быстрый путь: точный id набора.
        KNOWN_CATEGORIES.firstOrNull { it.id == name }?.let { return it.id }
        for (prefix in listOf("geosite-", "geo-")) {
            if (name.startsWith(prefix)) {
                name = name.removePrefix(prefix)
                break
            }
        }
        // Запись бывает двух видов: `категория@набор` (`ru@ads`) и просто набор.
        // Во втором случае это может быть и короткое `ads`, и полное
        // `category-ads-all`. Порядок проверок важен: `ads` не должен
        // превратиться в мировой набор вместо российского.
        val parts = name.split('@', limit = 2)
        if (parts.size == 2 && (parts[0].isEmpty() || parts[1].isEmpty())) return null
        val candidates = if (parts.size == 1) {
            listOf("geosite-category-ru@$name", "geosite-$name")
        } else {
            val head = if (parts[0] == "category-ru" || parts[0] == "ru") "category-ru" else parts[0]
            listOf("geosite-$head@${parts[1]}")
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            KNOWN_CATEGORIES.firstOrNull { it.id == candidate }?.id
        }
    }

    fun categoryIds(raw: Collection<String>): List<String> {
        val normalized = raw.mapNotNull { normalizeCategory(it) }.toSet()
        return KNOWN_CATEGORIES.map { it.id }.filter { it in normalized }
    }

    fun enabledCategories(raw: Collection<String>): List<Category> {
        val ids = categoryIds(raw).toSet()
        return KNOWN_CATEGORIES.filter { it.id in ids }
    }

    fun parseDomain(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("!")) return null
        // Строка hosts: адрес и домен через пробел.
        if (value.first().isDigit() || value.startsWith("::")) {
            value = value.split(Regex("\\s+")).getOrNull(1) ?: return null
        }
        // Формат Adblock Plus: ||example.com^
        value = value
            .removePrefix("||")
            .removePrefix("*.")
            .removePrefix(".")
            .trimEnd('^', '/')
        if (value.contains("://")) value = value.substringAfter("://")
        value = value.substringBefore('/')
        value = value.substringBefore('?')
        value = value.substringBefore('#')
        // Порт отрезаем только у «хост:порт», иначе сломаем IPv6-подобные записи.
        if (value.count { it == ':' } == 1) value = value.substringBefore(':')
        value = value.trim().trim('.').trim('*').trim('.')
        if (value.startsWith("www.")) value = value.removePrefix("www.")
        value = value.lowercase()
        if (value.length < 3 || value.length > 253 || !value.contains('.')) return null
        if (!value.all { (it.isLetterOrDigit() && it.code < 128) || it == '.' || it == '-' || it == '_' }) {
            return null
        }
        if (value.startsWith('.') || value.contains("..")) return null
        return value
    }

    fun parseRules(text: String, kind: RuleKind = RuleKind.Block): List<DomainRule> {
        val seen = HashSet<String>()
        val rules = ArrayList<DomainRule>()
        for (line in text.lineSequence()) {
            var raw = line
            var lineKind = kind
            if (raw.trim().startsWith("@@")) {
                lineKind = RuleKind.Allow
                raw = raw.trim().removePrefix("@@")
            }
            val domain = parseDomain(raw) ?: continue
            if (!seen.add("${lineKind.name}:$domain")) continue
            rules.add(DomainRule(domain, lineKind))
            if (rules.size >= MAX_USER_DOMAINS) break
        }
        return rules
    }

    fun rulesToText(rules: List<DomainRule>): String = rules.joinToString("\n") { rule ->
        when (rule.kind) {
            RuleKind.Block -> rule.domain
            RuleKind.Allow -> "@@${rule.domain}"
        }
    }

    fun blockDomains(rules: List<DomainRule>): List<String> =
        rules.filter { it.kind == RuleKind.Block }.map { it.domain }.distinct()

    fun allowDomains(rules: List<DomainRule>): List<String> =
        rules.filter { it.kind == RuleKind.Allow }.map { it.domain }.distinct()

    fun normalizeSubscriptionUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("!")) return null
        val candidate = when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("abp:") ->
                "https://" + value.removePrefix("abp:").trimStart('/')
            value.startsWith("domains://") ->
                "https://" + value.removePrefix("domains://")
            value.contains("://") -> value
            // Совсем без схемы: если это домен, пробуем https.
            parseDomain(value) != null -> "https://$value"
            else -> return null
        }
        return candidate.takeIf { it.length in 8..2048 }
    }

    fun extractDomainsFromPayload(payload: String, kind: SubscriptionKind): String = when (kind) {
        SubscriptionKind.Plain -> payload
        SubscriptionKind.Geosite -> geositeJsonToDomains(payload)
    }

    fun geositeJsonToDomains(payload: String): String {
        val text = payload.trim()
        if (!text.startsWith("{")) return payload
        val domains = LinkedHashSet<String>()
        runCatching {
            val root = JSONObject(text)
            val rules = root.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                for (key in listOf("domain", "domain_suffix")) {
                    val array = rule.optJSONArray(key) ?: continue
                    for (j in 0 until array.length()) {
                        val value = array.optString(j).trim().lowercase().trimStart('.')
                        if (value.contains('.') && domains.size < MAX_USER_DOMAINS) {
                            domains.add(value)
                        }
                    }
                }
            }
        }
        return domains.joinToString("\n")
    }

    fun rulesText(rules: List<DomainRule>, subscriptionTexts: List<String>): String {
        val builder = StringBuilder()
        for (rule in rules) {
            builder.append(
                when (rule.kind) {
                    RuleKind.Block -> rule.domain
                    RuleKind.Allow -> "@@${rule.domain}"
                }
            ).append('\n')
        }
        for (text in subscriptionTexts) {
            if (text.isBlank()) continue
            builder.append(text.trimEnd()).append('\n')
        }
        return builder.toString()
    }

    fun summary(
        rules: List<DomainRule>,
        subscriptions: List<Subscription>,
        categories: Collection<String>,
    ): String {
        val blocks = rules.count { it.kind == RuleKind.Block }
        val allows = rules.count { it.kind == RuleKind.Allow }
        val enabledSubs = subscriptions.count { it.enabled }
        val parts = ArrayList<String>()
        parts.add("свои домены: $blocks")
        if (allows > 0) parts.add("исключения: $allows")
        if (enabledSubs > 0) parts.add("подписки: $enabledSubs")
        val cats = categoryIds(categories)
        if (cats.isNotEmpty()) parts.add("категории: ${cats.size}")
        return parts.joinToString(" · ")
    }

    fun subscriptionsToJson(subscriptions: List<Subscription>): String {
        val array = JSONArray()
        for (item in subscriptions.take(MAX_SUBSCRIPTIONS)) {
            array.put(
                JSONObject()
                    .put("url", item.url)
                    .put("title", item.title)
                    .put("enabled", item.enabled)
            )
        }
        return array.toString()
    }

    fun subscriptionsFromJson(raw: String): List<Subscription> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = normalizeSubscriptionUrl(item.optString("url")) ?: return@mapNotNull null
                Subscription(
                    url = url,
                    title = item.optString("title"),
                    enabled = item.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }
}
