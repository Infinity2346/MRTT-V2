package com.mrtt.client

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Кто владелец сетевого потока.
 *
 * В VPN видно только IP-пакеты: имени приложения в них нет. Поэтому, когда
 * клиент встречает новый поток, а в настройках включён прямой режим для
 * приложений, он спрашивает приложение-владельца через
 * `ConnectivityManager.getConnectionOwnerUid` (доступно активному VpnService)
 * и присылает ответ командой `APP_DIRECT_FLOW|…`.
 *
 * Если владельца узнать нельзя (Android 9 и ниже, приложение привязано к
 * интерфейсу), поток идёт в туннель как обычно: медленнее, но никогда не
 * рвётся и не утекает в обход.
 */
object FlowOwnerResolver {
    /** Сколько раз спрашивать один и тот же поток, прежде чем сдаться. */
    private const val MAX_ATTEMPTS = 12

    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val directPackages: Set<String>
        get() = SettingsStore.cachedAppDirectFiltered
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()

    private val rawPackages: Set<String>
        get() = SettingsStore.cachedAppDirectRaw
            .split(',')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()

    /** Включён ли прямой режим хотя бы для одного приложения. */
    fun isEnabled(): Boolean = directPackages.isNotEmpty() || rawPackages.isNotEmpty()

    /**
     * Ответ на вопрос клиента: `null`, если владельца опознать не удалось и
     * повторять вопрос уже бессмысленно.
     */
    fun resolve(
        context: Context,
        src: String,
        dst: String,
        protocol: Int,
    ): String? {
        val key = "$src>$dst>$protocol"
        val attempt = attempts.merge(key, 1, Int::plus) ?: 1
        if (attempt > MAX_ATTEMPTS) {
            attempts.remove(key)
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val owner = ownerPackage(context, src, dst, protocol) ?: return null
        attempts.remove(key)
        val direct = owner in directPackages
        val raw = owner in rawPackages
        if (!direct && !raw) {
            // Приложение не из прямого режима: запоминаем ответ, чтобы не спрашивать
            // про этот поток снова. filtered=1 не используется — поток идёт в туннель.
            return "$src|$dst|$protocol|0|0"
        }
        // В лог ничего не пишем: режим приложения — это не событие сети.
        // Раньше каждая строка вида «… → напрямую · реклама режется» засоряла
        // журнал настолько, что настоящие ошибки терялись.
        val filtered = if (direct) 1 else 0
        return "$src|$dst|$protocol|1|$filtered"
    }

    private fun ownerPackage(context: Context, src: String, dst: String, protocol: Int): String? {
        val local = parseAddress(src) ?: return null
        val remote = parseAddress(dst) ?: return null
        val ipProtocol = when (protocol) {
            6 -> android.system.OsConstants.IPPROTO_TCP
            17 -> android.system.OsConstants.IPPROTO_UDP
            else -> return null
        }
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val uid = try {
            manager.getConnectionOwnerUid(ipProtocol, local, remote)
        } catch (error: Exception) {
            // Не активный VpnService, гонка при закрытии соединения, чужая сеть.
            Log.d("FlowOwnerResolver", "owner lookup failed: ${error.message}")
            return null
        }
        if (uid <= 0) return null
        return try {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (error: Exception) {
            null
        }
    }

    private fun parseAddress(value: String): InetSocketAddress? {
        val trimmed = value.trim()
        val split = trimmed.lastIndexOf(':')
        if (split <= 0) return null
        val host = trimmed.substring(0, split).trim('[', ']')
        val port = trimmed.substring(split + 1).toIntOrNull() ?: return null
        return try {
            InetSocketAddress(InetAddress.getByName(host), port)
        } catch (error: Exception) {
            null
        }
    }
}
