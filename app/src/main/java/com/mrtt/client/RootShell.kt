package com.mrtt.client

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Тонкая обёртка над `su`: единственное место, где приложение просит root.
 *
 * Ничего не делает молча: каждое действие логируется, а недоступность root —
 * это нормальное состояние, а не ошибка. Все вызовы короткие (у проверки
 * доступа потолок 4 секунды), чтобы старт туннеля не ждал менеджер прав.
 */
internal object RootShell {

    private const val TAG = MrttConstants.General.APP_NAME
    private const val PROBE_TIMEOUT_MS = 4_000L
    private const val COMMAND_TIMEOUT_MS = 8_000L

    /** Один раз проверенный ответ: `su` на телефоне не появляется и не исчезает. */
    @Volatile
    private var cachedAvailable: Boolean? = null

    fun isAvailable(context: Context): Boolean {
        cachedAvailable?.let { return it }
        val available = runCatching { probe() }.getOrDefault(false)
        cachedAvailable = available
        Log.i(TAG, "[ROOT] su ${if (available) "доступен" else "недоступен"}")
        return available
    }

    /** Сбрасывает кэш: пользователь мог выдать или отозвать права между подключениями. */
    fun resetCache() {
        cachedAvailable = null
    }

    fun isCachedAvailable(): Boolean? = cachedAvailable

    private fun probe(): Boolean {
        val process = runCatching { ProcessBuilder("su", "-c", "id").start() }.getOrNull() ?: return false
        return try {
            val finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.exitValue() == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            process.destroy()
            false
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * Скрипт целиком уходит в `su -c` одной строкой: так root-шелл поднимается
     * один раз, а не на каждую команду.
     */
    fun run(script: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): String? {
        val process = runCatching {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            Log.w(TAG, "[ROOT] Команда не выполнена", error)
            null
        } finally {
            runCatching { process.destroy() }
        }
    }

    /** Есть ли в системе нужный бинарь (iptables бывает только `iptables-legacy`). */
    fun hasBinary(name: String): Boolean {
        val output = run("command -v $name || echo MISSING") ?: return false
        return !output.contains("MISSING")
    }
}
