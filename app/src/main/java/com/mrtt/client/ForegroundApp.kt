package com.mrtt.client

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Следит, какое приложение на экране, чтобы игровой режим включался сам.
 *
 * Android отдаёт имя активного приложения только через `UsageStatsManager` и только
 * с разрешением «Доступ к данным об использовании». Разрешение необязательное: если
 * его нет, автодетект просто молчит, а режим остаётся доступен вручную и по портам.
 */
internal object ForegroundApp {

    /** Игровой режим проверяем раз в 3 с: чаще незачем, а батарею это не ест. */
    private const val POLL_MS = 3_000L

    /** Пакет считался игрой в последние 20 с? Значит, мы всё ещё в игре (свёрнутая пауза). */
    private const val FOREGROUND_WINDOW_MS = 20_000L

    fun hasUsageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrNull()
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Пакет на экране сейчас; null, если разрешения нет или система не ответила. */
    fun currentPackage(context: Context): String? = runCatching {
        if (!hasUsageAccess(context)) return null
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - 60_000L, now) ?: return null
        var foreground: String? = null
        var resumedAt = 0L
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND,
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (event.timeStamp >= resumedAt) {
                        resumedAt = event.timeStamp
                        foreground = event.packageName
                    }
                }
                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND,
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (event.packageName == foreground) foreground = null
                }
            }
        }
        foreground
    }.getOrNull()

    /**
     * Открывает системный список «Доступ к данным об использовании».
     *
     * На части прошивок общий экран списка приложений пуст или вообще не
     * показывает сторонние приложения; тогда сразу открываем карточку MRTT
     * в настройках приложений — оттуда «Использование данных» в один тап.
     */
    fun openUsageAccessSettings(context: Context) {
        val attempts = listOf(
            android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}")),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}")),
        )
        for (intent in attempts) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /**
     * Цикл автодетекта: пока `running()` возвращает true, раз в [POLL_MS] читает
     * активное приложение и сообщает его в [onForeground].
     */
    suspend fun watch(
        context: Context,
        running: () -> Boolean,
        onForeground: suspend (String?) -> Unit,
    ) {
        while (running()) {
            val foreground = withContext(Dispatchers.IO) { currentPackage(context) }
            onForeground(foreground)
            delay(POLL_MS)
        }
    }
}
