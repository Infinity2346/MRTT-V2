package com.mrtt.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object AdblockRepository {

    private const val TAG = MrttConstants.General.APP_NAME
    private const val BASE_LIST = "adblock.txt"
    private const val USER_LIST = "adblock-user.txt"
    private const val SUBSCRIPTIONS_LIST = "adblock-subscriptions.txt"
    private const val ASSET_STAMP = "adblock.version"
    private const val SUBSCRIPTION_TIMEOUT_MS = 15_000
    private const val MAX_SUBSCRIPTION_BYTES = 16L * 1024L * 1024L
    private const val GEOSITE_STAMP = "adblock-geosite.version"
    private const val GEOSITE_TIMEOUT_MS = 20_000
    private const val GEOSITE_RAW_LIMIT = 24L * 1024L * 1024L
    const val RU_SERVICES_FILE = "geosite-category-ru.txt"

    private const val GEOSITE_BASE =
        "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite"

    fun ruleFiles(context: Context): List<File> = listOf(
        File(context.filesDir, USER_LIST),
        File(context.filesDir, SUBSCRIPTIONS_LIST),
    )

    fun userListFile(context: Context): File = File(context.filesDir, USER_LIST)

    fun subscriptionsFile(context: Context): File = File(context.filesDir, SUBSCRIPTIONS_LIST)

    fun ruServicesFile(context: Context): File = File(context.filesDir, RU_SERVICES_FILE)

    fun categoryFiles(context: Context, categories: Collection<String>): List<File> =
        AdblockPolicy.enabledCategories(categories).mapNotNull { category ->
            File(context.filesDir, category.assetFile).takeIf { it.isFile && it.length() > 0L }
        }

    suspend fun updateGeositeCategories(
        context: Context,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): GeositeUpdateResult = withContext(Dispatchers.IO) {
        val categories = AdblockPolicy.UPDATABLE_CATEGORIES
        var updated = 0
        var failed = 0
        var domains = 0
        var index = 0
        for (category in categories) {
            onProgress(index, categories.size, category.title)
            index++
            val payload = runCatching { fetchGeositeJson(category) }.getOrNull()
            if (payload == null) {
                failed++
                Log.w(TAG, "[ADBLOCK] Не удалось скачать ${category.id}")
                continue
            }
            val text = AdblockPolicy.geositeJsonToDomains(payload)
            if (text.isBlank()) {
                failed++
                continue
            }
            val target = File(context.filesDir, category.assetFile)
            val written = runCatching {
                target.writeText(
                    "# Снимок MetaCubeX/meta-rules-dat (geosite), лицензия GPL-3.0.\n" +
                        "# Источник: https://github.com/MetaCubeX/meta-rules-dat (ветка sing).\n" +
                        "# Набор: ${category.id}\n" +
                        "# Обновлён приложением: ${System.currentTimeMillis()}\n" +
                        text,
                )
            }.isSuccess
            if (!written) {
                failed++
                continue
            }
            updated++
            domains += text.count { it == '\n' } + 1
        }
        onProgress(categories.size, categories.size, "")
        if (updated > 0) {
            runCatching { File(context.filesDir, GEOSITE_STAMP).writeText(System.currentTimeMillis().toString()) }
        }
        GeositeUpdateResult(updated = updated, failed = failed, domains = domains)
    }

    data class GeositeUpdateResult(val updated: Int, val failed: Int, val domains: Int)

    fun geositeUpdatedAt(context: Context): Long =
        runCatching { File(context.filesDir, GEOSITE_STAMP).readText().trim().toLongOrNull() }
            .getOrNull() ?: 0L

    fun hasUpdatedGeosite(context: Context): Boolean =
        AdblockPolicy.UPDATABLE_CATEGORIES.any { category ->
            File(context.filesDir, category.assetFile).let { it.isFile && it.length() > 0L }
        }

    private fun fetchGeositeJson(category: AdblockPolicy.Category): String? {
        val url = "$GEOSITE_BASE/${category.geositeName}.json"
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.useCaches = false
            connection.connectTimeout = GEOSITE_TIMEOUT_MS
            connection.readTimeout = GEOSITE_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MRTTAndroid/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                val builder = StringBuilder()
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > GEOSITE_RAW_LIMIT) break
                    builder.append(String(buffer, 0, read, Charsets.UTF_8))
                }
                builder.toString()
            }
            text.takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            Log.w(TAG, "[ADBLOCK] Сеть недоступна для ${category.id}", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun syncAssets(context: Context) = withContext(Dispatchers.IO) { syncAssetsBlocking(context) }

    fun syncAssetsBlocking(context: Context) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0).toString()
        val stamp = File(context.filesDir, ASSET_STAMP)
        val stored = runCatching { stamp.readText().trim() }.getOrDefault("")
        val categoryAssets = AdblockPolicy.KNOWN_CATEGORIES.map { it.assetFile }.toSet()
        val assets = buildList {
            add(BASE_LIST)
            add(RU_SERVICES_FILE)
            addAll(categoryAssets.sorted())
        }
        for (asset in assets) {
            val target = File(context.filesDir, asset)
            // Свежие ассеты переезжают при смене версии сборки; если файла нет
            // (или он пустой), копируем всегда — иначе фильтр останется без списка.
            //
            // Категории MetaCubeX не перезаписываем при обновлении приложения:
            // человек мог скачать их свежими, а снимок в APK старше его версии.
            if (asset in categoryAssets && target.isFile && target.length() > 0L) continue
            if (target.isFile && target.length() > 0L && stored == version) continue
            runCatching {
                context.assets.open(asset).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { error ->
                Log.w(TAG, "[ADBLOCK] Не удалось разложить $asset", error)
            }
        }
        runCatching { stamp.writeText(version) }
    }

    suspend fun saveUserRules(context: Context, text: String) = withContext(Dispatchers.IO) {
        runCatching { userListFile(context).writeText(text) }
    }

    fun writeUserRulesBlocking(context: Context, text: String) {
        runCatching { userListFile(context).writeText(text) }
            .onFailure { error -> Log.w(TAG, "[ADBLOCK] Не удалось записать свои домены", error) }
    }

    suspend fun saveSubscriptionRules(context: Context, text: String) = withContext(Dispatchers.IO) {
        runCatching { subscriptionsFile(context).writeText(text) }
    }

    suspend fun downloadSubscriptions(
        context: Context,
        subscriptions: List<AdblockPolicy.Subscription>,
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        val builder = StringBuilder()
        var downloaded = 0
        var failed = 0
        var domains = 0
        for (item in subscriptions.filter { it.enabled }.take(AdblockPolicy.MAX_SUBSCRIPTIONS)) {
            val payload = runCatching { fetch(item.url) }.getOrNull()
            if (payload == null) {
                failed++
                Log.w(TAG, "[ADBLOCK] Подписка недоступна: ${item.url}")
                continue
            }
            val kind = if (payload.trimStart().startsWith("{")) {
                AdblockPolicy.SubscriptionKind.Geosite
            } else {
                AdblockPolicy.SubscriptionKind.Plain
            }
            val text = AdblockPolicy.extractDomainsFromPayload(payload, kind)
            val rules = AdblockPolicy.parseRules(text)
            if (rules.isEmpty()) {
                failed++
                continue
            }
            downloaded++
            domains += rules.size
            builder.append("# ${item.title.ifBlank { item.url }}\n")
            builder.append(rules.joinToString("\n") { it.domain }).append('\n')
        }
        saveSubscriptionRules(context, builder.toString())
        SubscriptionResult(downloaded = downloaded, failed = failed, domains = domains)
    }

    data class SubscriptionResult(val downloaded: Int, val failed: Int, val domains: Int)

    private fun fetch(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.useCaches = false
            connection.connectTimeout = SUBSCRIPTION_TIMEOUT_MS
            connection.readTimeout = SUBSCRIPTION_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MRTTAndroid/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "text/plain, application/json, */*")
            val code = connection.responseCode
            if (code !in 200..299) return null
            val text = connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                val builder = StringBuilder()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SUBSCRIPTION_BYTES) break
                    builder.append(String(buffer, 0, read, Charsets.UTF_8))
                }
                builder.toString()
            }
            text.takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            Log.w(TAG, "[ADBLOCK] Ошибка загрузки $url", error)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
