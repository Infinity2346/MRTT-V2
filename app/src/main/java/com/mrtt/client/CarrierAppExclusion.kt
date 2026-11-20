package com.mrtt.client

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CarrierAppExclusion {
    private const val PREFS_NAME = "mrtt_carrier_exclusion"
    private const val DONE_KEY = "applied_v1"

    private val PACKAGE_PREFIXES = listOf(
        "ru.yota",
        "com.beeline",
        "com.mts",
        "com.megafon",
        "com.tele2",
        "ru.tele2",
        "com.rostelecom",
        "ru.rostelecom",
        "ru.sbermobile",
        "com.tinkoff.mobile",
        "ru.danycom",
        "com.motiv",
    )

    private val LABEL_TOKENS = listOf(
        "yota",
        "йота",
        "beeline",
        "билайн",
        "mts",
        "мтс",
        "megafon",
        "мегафон",
        "tele2",
        "теле2",
        "rostelecom",
        "ростелеком",
        "sbermobile",
        "сбермобайл",
        "tinkoff mobile",
        "тинькофф мобайл",
        "danycom",
    )

    internal fun matches(packageName: String, label: String): Boolean {
        val pkg = packageName.lowercase()
        val name = label.lowercase()
        return PACKAGE_PREFIXES.any { prefix -> pkg == prefix || pkg.startsWith("$prefix.") } ||
            LABEL_TOKENS.any { token -> name.contains(token) }
    }

    fun detect(context: Context): Set<String> {
        val packageManager = context.packageManager
        return runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    app.packageName != context.packageName &&
                        matches(app.packageName, app.loadLabel(packageManager).toString())
                }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun apply(context: Context, settingsStore: SettingsStore) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE_KEY, false)) return@withContext

        val detected = detect(context)
        if (detected.isNotEmpty()) {
            settingsStore.addExcludedAppsForAllProfiles(detected)
            if (TunnelManager.running.value) {
                withContext(Dispatchers.Main) { TunnelManager.reloadVpn() }
            }
        }
        prefs.edit().putBoolean(DONE_KEY, true).apply()
    }
}
