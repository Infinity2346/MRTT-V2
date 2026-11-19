package com.mrtt.client

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

internal object RootShell {

    private const val TAG = MrttConstants.General.APP_NAME
    private const val PROBE_TIMEOUT_MS = 4_000L
    private const val COMMAND_TIMEOUT_MS = 8_000L

    @Volatile
    private var cachedAvailable: Boolean? = null

    fun isAvailable(context: Context): Boolean {
        cachedAvailable?.let { return it }
        val available = runCatching { probe() }.getOrDefault(false)
        cachedAvailable = available
        Log.i(TAG, "[ROOT] su ${if (available) "доступен" else "недоступен"}")
        return available
    }

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

    fun hasBinary(name: String): Boolean {
        val output = run("command -v $name || echo MISSING") ?: return false
        return !output.contains("MISSING")
    }
}
