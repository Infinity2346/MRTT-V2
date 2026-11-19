package com.mrtt.client

/**
 * Что именно меняем в маршрутизации приложений; `null` — не трогать.
 *
 * Отдельный файл не случаен: SettingsStore держит все свойства `val` выше
 * `init`-блоков (это проверяет SettingsStoreInitOrderTest), а объявление с
 * полями пришлось бы ставить после них.
 */
data class AppRoutingPatch(
    val excluded: String? = null,
    val whitelist: String? = null,
    val isWhitelist: Boolean? = null,
    val directFiltered: String? = null,
    val directRaw: String? = null,
)
