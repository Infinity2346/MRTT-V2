package com.mrtt.client.ui.tunnel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrtt.client.AdblockPolicy
import com.mrtt.client.AdblockRepository
import com.mrtt.client.SettingsStore
import com.mrtt.client.TunnelManager
import com.mrtt.client.ui.AppSectionCard
import com.mrtt.client.ui.components.MrttScreen
import com.mrtt.client.ui.components.MrttSettingRow
import com.mrtt.client.ui.design.MrttSizes
import com.mrtt.client.ui.design.MrttStyleShapes
import kotlinx.coroutines.launch

@Composable
fun AdblockTab(settingsStore: SettingsStore) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val enabled by settingsStore.adblockEnabled.collectAsStateWithLifecycle(initialValue = false)
    val userDomains by settingsStore.adblockDomains.collectAsStateWithLifecycle(initialValue = "")
    val subscriptionsJson by settingsStore.adblockSubscriptions.collectAsStateWithLifecycle(initialValue = "")
    val categoriesRaw by settingsStore.adblockCategories.collectAsStateWithLifecycle(initialValue = "")
    val geositeRu by settingsStore.geositeRuEnabled.collectAsStateWithLifecycle(initialValue = false)
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()

    val rules = remember(userDomains) { AdblockPolicy.parseRules(userDomains) }
    val subscriptions = remember(subscriptionsJson) { AdblockPolicy.subscriptionsFromJson(subscriptionsJson) }
    val categories = remember(categoriesRaw) {
        AdblockPolicy.categoryIds(categoriesRaw.split(','))
    }

    var domainsDraft by remember(userDomains) { mutableStateOf(userDomains) }
    var urlDraft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var geositeUpdatedAt by remember { mutableStateOf(0L) }
    var updatingGeosite by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf("") }

    // Списки из ассетов нужны и фильтру, и кнопке «Обновить списки»: раскладываем
    // их при открытии вкладки, чтобы первый запуск не ждал копирования.
    LaunchedEffect(Unit) {
        runCatching { AdblockRepository.syncAssets(context) }
        geositeUpdatedAt = AdblockRepository.geositeUpdatedAt(context)
    }

    fun saveRules(text: String) {
        scope.launch {
            settingsStore.saveAdblockDomains(text)
            AdblockRepository.saveUserRules(context, text)
            status = "Свои домены сохранены · применится при следующем подключении"
        }
    }

    fun saveSubscriptions(items: List<AdblockPolicy.Subscription>) {
        scope.launch {
            settingsStore.saveAdblockSubscriptions(AdblockPolicy.subscriptionsToJson(items))
        }
    }

    fun updateGeosite() {
        if (updatingGeosite) return
        updatingGeosite = true
        updateProgress = "Готовимся скачать наборы…"
        scope.launch {
            val result = runCatching {
                AdblockRepository.updateGeositeCategories(context) { done, total, title ->
                    updateProgress = if (title.isEmpty()) {
                        "Готово: $done из $total"
                    } else {
                        "Скачиваем $done из $total · $title"
                    }
                }
            }.getOrNull()
            updatingGeosite = false
            updateProgress = ""
            geositeUpdatedAt = AdblockRepository.geositeUpdatedAt(context)
            status = when {
                result == null -> "Обновить списки не удалось: нет сети"
                result.updated == 0 -> "Списки не обновились: проверьте соединение"
                result.failed == 0 ->
                    "Списки обновлены: ${result.updated} наборов, ${result.domains} доменов · применится при следующем подключении"
                else ->
                    "Обновлено ${result.updated} из ${result.updated + result.failed} наборов · применится при следующем подключении"
            }
        }
    }

    fun toggleCategory(id: String, on: Boolean) {
        val updated = if (on) (categories + id).distinct() else categories.filter { it != id }
        scope.launch { settingsStore.saveAdblockCategories(updated.joinToString(",")) }
    }

    MrttScreen {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Главный переключатель и состояние.
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Adblock",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = if (!enabled) {
                        "Выключен: реклама и трекеры идут как обычно"
                    } else if (tunnelRunning) {
                        "Работает · списки применились при подключении"
                    } else {
                        "Включён · заработает при следующем подключении"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MrttSettingRow(
                    title = "Фильтровать рекламу и трекеры",
                    checked = enabled,
                    description = "Домены получают пустой ответ, обход фильтра через шифрованный DNS прерывается. Снимается при следующем подключении",
                    onCheckedChange = { on -> scope.launch { settingsStore.saveAdblockEnabled(on) } },
                )
                Text(
                    text = AdblockPolicy.summary(rules, subscriptions, categories),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Готовые категории из MetaCubeX/meta-rules-dat.
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Категории правил",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Готовые списки доменов от MetaCubeX/meta-rules-dat. Включённая категория " +
                        "становится отдельным списком: её домены получают пустой ответ, как и свои. " +
                        "Можно включить несколько — списки складываются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                for ((group, items) in AdblockPolicy.categoriesByGroup()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = group.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (category in items) {
                        MrttSettingRow(
                            title = category.title,
                            checked = category.id in categories,
                            description = category.description + "\n≈ " +
                                "${category.approxDomains.coerceAtLeast(0)} доменов",
                            onCheckedChange = { on -> toggleCategory(category.id, on) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Наборы уже вложены в приложение — работают без сети. Кнопка ниже скачивает " +
                        "свежие версии из MetaCubeX: новые домены рекламы появляются там раньше, чем выходит " +
                        "обновление приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (geositeUpdatedAt > 0L) {
                        "Последнее обновление: ${relativeTime(geositeUpdatedAt)}"
                    } else {
                        "Обновлённые списки ещё не скачивались — используется версия из приложения"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (geositeUpdatedAt > 0L) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (updatingGeosite) {
                    Text(
                        text = updateProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = { updateGeosite() },
                    enabled = !updatingGeosite,
                    modifier = Modifier.fillMaxWidth().height(MrttSizes.ControlHeight),
                    shape = MrttStyleShapes.control,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (updatingGeosite) "Обновляем списки…" else "Обновить списки",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Свои домены.
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Свои домены",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "По одному в строке: домен, ссылка на страницу, hosts-строка или правило ||домен^. " +
                            "Строка с @@ в начале — исключение: такой домен всегда открывается",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = domainsDraft,
                    onValueChange = { domainsDraft = it },
                    label = { Text("Домены и исключения") },
                    placeholder = { Text("ads.example.com\n@@ok.example.com") },
                    shape = MrttStyleShapes.control,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { saveRules(domainsDraft) },
                        modifier = Modifier.weight(1f).height(MrttSizes.ControlHeight),
                        shape = MrttStyleShapes.control,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text("Сохранить", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            // Приводим текст к чистому виду: без мусора и повторов.
                            val cleaned = AdblockPolicy.rulesToText(AdblockPolicy.parseRules(domainsDraft))
                            domainsDraft = cleaned
                            saveRules(cleaned)
                        },
                        modifier = Modifier.weight(1f).height(MrttSizes.ControlHeight),
                        shape = MrttStyleShapes.control,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text("Очистить и сохранить", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Свои ссылки на списки.
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Подписки на списки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Ссылка на список трекеров: обычный текстовый список доменов, hosts или JSON категории geosite",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (subscriptions.isEmpty()) {
                    Text(
                        text = "Ссылок нет",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (item in subscriptions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MrttSettingRow(
                                title = item.title.ifBlank { item.url },
                                checked = item.enabled,
                                description = if (item.title.isBlank()) "" else item.url,
                                onCheckedChange = { on ->
                                    saveSubscriptions(
                                        subscriptions.map { if (it.url == item.url) it.copy(enabled = on) else it },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                saveSubscriptions(subscriptions.filterNot { it.url == item.url })
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Удалить подписку",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    label = { Text("Ссылка на список") },
                    placeholder = { Text("https://example.com/adblock.txt") },
                    singleLine = true,
                    shape = MrttStyleShapes.control,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val url = AdblockPolicy.normalizeSubscriptionUrl(urlDraft) ?: return@OutlinedButton
                            if (subscriptions.size >= AdblockPolicy.MAX_SUBSCRIPTIONS) return@OutlinedButton
                            if (subscriptions.none { it.url == url }) {
                                saveSubscriptions(subscriptions + AdblockPolicy.Subscription(url = url))
                            }
                            urlDraft = ""
                        },
                        modifier = Modifier.weight(1f).height(MrttSizes.ControlHeight),
                        shape = MrttStyleShapes.control,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text("Добавить", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            scope.launch {
                                val result = AdblockRepository.downloadSubscriptions(context, subscriptions)
                                busy = false
                                status = when {
                                    result.downloaded == 0 && result.failed == 0 -> "Нет включённых подписок"
                                    result.downloaded == 0 -> "Не удалось скачать списки · проверьте ссылки и сеть"
                                    else ->
                                        "Скачано списков: ${result.downloaded}, доменов: ${result.domains}" +
                                            if (result.failed > 0) " · ошибок: ${result.failed}" else ""
                                }
                            }
                        },
                        enabled = !busy && subscriptions.any { it.enabled },
                        modifier = Modifier.weight(1f).height(MrttSizes.ControlHeight),
                        shape = MrttStyleShapes.control,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    ) {
                        Text(if (busy) "Загрузка…" else "Обновить списки", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Российские сервисы мимо туннеля.
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Российские сервисы",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                MrttSettingRow(
                    title = "Открывать напрямую",
                    checked = geositeRu,
                    description = "Список geosite-category-ru (маркетплейсы, банки, госуслуги) идёт мимо туннеля: без нагрузки на каналы и с обычной скоростью",
                    onCheckedChange = { on -> scope.launch { settingsStore.saveGeositeRuEnabled(on) } },
                )
                Text(
                    text = "Работает только в режиме «через туннель идёт всё, кроме списка»: в режиме «только список» маршрутизацией управляет он. Изменение применится при следующем подключении",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }

            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Как это работает",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Домены из списков получают пустой ответ прямо в приложении: запрос не уходит дальше и реклама " +
                        "не загружается. Обход фильтра через шифрованный DNS блокируется отдельно, иначе " +
                        "браузер обходил бы фильтр; хост-критичные адреса YouTube (маршрутизация видео) не блокируются никогда.\n\n" +
                        "Встроенный список — около 76 тысяч строк. Свои домены, подписки и категории лежат " +
                        "отдельными файлами и читаются вместе с ним. Все изменения применяются после переподключения VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val diff = (System.currentTimeMillis() - timestamp) / 1000L
    return when {
        diff < 60L -> "только что"
        diff < 3600L -> "${diff / 60L} мин назад"
        diff < 86_400L -> "${diff / 3600L} ч назад"
        diff < 2_592_000L -> "${diff / 86_400L} дн назад"
        else -> {
            val days = diff / 86_400L
            "${days / 30L} мес назад"
        }
    }
}
