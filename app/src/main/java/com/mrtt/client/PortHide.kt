package com.mrtt.client

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * Применение правил [PortHidePolicy] через root и их снятие.
 *
 * Правила живут недолго: они ставятся перед стартом туннеля и снимаются при
 * остановке. Если приложение убили (или телефон перезагрузили), правил в
 * iptables не остаётся необработанными: их снимает [clear] при следующем
 * подключении, а сценарий остановки вызывает [clear] всегда — даже когда сама
 * остановка идёт без root.
 */
internal object PortHide {

    private const val TAG = MrttConstants.General.APP_NAME
    private const val STATE_FILE = "port-hide-uids.txt"

    /** Последние применённые UID: по ним же снимаем правила. */
    @Volatile
    private var appliedUids: List<Int> = emptyList()

    /**
     * Есть ли у приложения root. Кэш живёт до [resetCache] — вызывается при
     * смене настройки и при подключении.
     */
    fun hasRoot(context: Context): Boolean = RootShell.isAvailable(context)

    fun resetCache() {
        RootShell.resetCache()
    }

    fun appliedCount(): Int = appliedUids.size

    /**
     * Ставит правила для выбранных пакетов. Возвращает число приложений, для
     * которых закрыт localhost (0 — root нет или список пуст).
     */
    fun apply(context: Context, packagesRaw: String, ownUid: Int): Int {
        if (!RootShell.isAvailable(context)) {
            clearApplied()
            return 0
        }
        val uids = resolveUids(context, packagesRaw)
        if (uids.isEmpty()) {
            clearApplied()
            return 0
        }
        // Свои правила для тех же UID могли остаться от прошлого запуска:
        // снимаем их до вставки, чтобы не плодить дубликаты.
        val previous = appliedUids
        if (previous.isNotEmpty()) {
            RootShell.run(PortHidePolicy.clearScript(PortHidePolicy.rules(previous, ownUid)))
        }
        val rules = PortHidePolicy.rules(uids, ownUid)
        if (rules.isEmpty()) {
            appliedUids = emptyList()
            return 0
        }
        val output = RootShell.run(PortHidePolicy.applyScript(rules))
        if (output == null || !output.contains("MRI_OK")) {
            Log.w(TAG, "[ROOT] Правила localhost не применены")
            clearApplied()
            return 0
        }
        appliedUids = uids
        context.openFileOutput(STATE_FILE, Context.MODE_PRIVATE).use { out ->
            out.write(PortHidePolicy.joinUids(uids).toByteArray())
        }
        Log.i(TAG, "[ROOT] localhost закрыт для ${uids.size} приложений")
        return uids.size
    }

    /**
     * Прячет имя процесса клиента (`libclient.so`) заменой `comm` на безликое:
     * без root это ровно та строка, по которой туннель находится в `ps` и в
     * `/proc/<pid>/cmdline`. Работает только с root; без него — тихо ничего.
     */
    fun maskProcessName(pid: Int): Boolean {
        if (pid <= 0 || RootShell.isCachedAvailable() != true) return false
        val script = buildString {
            append("set +e\n")
            // 15 символов — предел для comm; имя как у системного демона, чтобы
            // оно не выделялось в списке процессов.
            append("printf 'kworker/u2' > /proc/$pid/comm 2>/dev/null\n")
            append("printf 'kworker/u2' > /proc/$pid/task/$pid/comm 2>/dev/null\n")
            append("echo MRI_OK\n")
        }
        val output = RootShell.run(script)
        // MRI_OK печатает только сама оболочка: если она его вернула, обе записи
        // уже отработали (упавшая запись не рвёт скрипт из-за `||` и `set +e`).
        val masked = output?.contains("MRI_OK") == true
        if (masked) Log.i(TAG, "[ROOT] Имя процесса клиента скрыто")
        return masked
    }

    /** Снимает правила, поставленные этим процессом, и то, что осталось на диске. */
    fun clear(context: Context, ownUid: Int) {
        val fromState = runCatching {
            File(context.filesDir, STATE_FILE).takeIf { it.isFile }?.readText()
        }.getOrNull()
        val uids = (appliedUids + PortHidePolicy.parseUids(fromState.orEmpty())).distinct().sorted()
        if (uids.isNotEmpty() && RootShell.isCachedAvailable() == true) {
            RootShell.run(PortHidePolicy.clearScript(PortHidePolicy.rules(uids, ownUid)))
        }
        appliedUids = emptyList()
        runCatching { File(context.filesDir, STATE_FILE).delete() }
    }

    private fun clearApplied() {
        appliedUids = emptyList()
    }

    /** UID выбранных пакетов: у одного приложения их может быть несколько. */
    private fun resolveUids(context: Context, packagesRaw: String): List<Int> {
        val packages = packagesRaw
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (packages.isEmpty()) return emptyList()
        val pm = context.packageManager
        val uids = LinkedHashSet<Int>()
        for (pkg in packages) {
            val infoUid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull()
            if (infoUid == null) {
                Log.w(TAG, "[ROOT] Приложение не найдено: $pkg")
                continue
            }
            // У пакета может быть несколько UID (общий sharedUserId, клоны
            // рабочих профилей): закрываем все, иначе останется лазейка.
            runCatching { pm.getPackagesForUid(infoUid) }.getOrNull()
                ?.forEach { related ->
                    runCatching { pm.getApplicationInfo(related, 0).uid }.getOrNull()
                        ?.let(uids::add)
                }
            uids.add(infoUid)
        }
        return uids.filter { it > 0 }.distinct().sorted()
    }
}
