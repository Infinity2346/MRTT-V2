package com.mrtt.client

/**
 * Правила закрытия localhost через root.
 *
 * Идея как в NekoBox: туннель слушает 127.0.0.1, и по открытому порту
 * его можно найти. Обычное приложение не может запретить чужим пакетам
 * ходить на loopback — а root может: на выходе (`OUTPUT`) для конкретного
 * UID ставится REJECT на 127.0.0.1 и ::1.
 *
 * Здесь только чистая сборка правил: без Android API и без самого `su`,
 * чтобы всё это проверялось юнит-тестами. Применяет и снимает правила
 * `PortHide`.
 */
internal object PortHidePolicy {

    const val CHAIN = "OUTPUT"
    const val IPV4_BINARY = "iptables"
    const val IPV6_BINARY = "ip6tables"

    /** Пакеты, которые нельзя закрывать: сам клиент и shell после su. */
    private val SKIPPED_UIDS = setOf(0, 1000)

    data class Rule(val binary: String, val uid: Int, val destination: String) {
        fun insertScript(): String =
            "$binary -w -I $CHAIN 1 -m owner --uid-owner $uid -d $destination -j REJECT"

        fun deleteScript(): String = removePrefix(insertScript())

        /**
         * Для снятия тот же набор флагов, но `-D` и без позиции вставки:
         * `-I CHAIN 1` и `-D CHAIN` — разные формы записи одного правила.
         */
        private fun removePrefix(insert: String): String =
            insert.replaceFirst("-w -I $CHAIN 1", "-w -D $CHAIN")
    }

    /**
     * Правила для UID приложений. Свой UID не трогаем: клиент сам ходит на
     * 127.0.0.1, иначе туннель отрежет себя же. `uids` должен быть уже
     * уникальным (склейка UID у нескольких пакетов — норма).
     */
    fun rules(uids: Collection<Int>, ownUid: Int): List<Rule> {
        val targets = uids
            .filter { it > 0 && it != ownUid && it !in SKIPPED_UIDS }
            .distinct()
            .sorted()
        val rules = ArrayList<Rule>(targets.size * 2)
        for (uid in targets) {
            rules.add(Rule(IPV4_BINARY, uid, "127.0.0.1/32"))
            rules.add(Rule(IPV6_BINARY, uid, "::1/128"))
        }
        return rules
    }

    /**
     * Скрипт применения: одна строка на правило, ошибка одного не рвёт остальные.
     *
     * Старое правило того же UID сначала снимается (вставка в начало цепочки
     * делает сравнение с дублем бессмысленным), затем правило ставится.
     */
    fun applyScript(rules: List<Rule>): String = buildString {
        append("set +e\n")
        // Без этого loopback-трафик, пришедший извне таблицы, не доходит до
        // OUTPUT: правило просто не сработало бы.
        append("sysctl -w net.ipv4.conf.all.route_localnet=1 >/dev/null 2>&1\n")
        for (rule in rules) {
            append(rule.deleteScript()).append(" >/dev/null 2>&1\n")
            append(rule.insertScript()).append(" >/dev/null 2>&1\n")
        }
        append("echo MRI_OK\n")
    }

    /** Скрипт снятия: правило удаляется, пока оно находится. */
    fun clearScript(rules: List<Rule>): String = buildString {
        append("set +e\n")
        for (rule in rules) {
            val delete = rule.deleteScript()
            repeat(CLEAR_ATTEMPTS) { append(delete).append(" >/dev/null 2>&1\n") }
        }
        append("echo MRI_OK\n")
    }

    private const val CLEAR_ATTEMPTS = 6

    /** Разбор сохранённого списка UID (`12,10123`). */
    fun parseUids(raw: String): List<Int> = raw
        .split(',', ' ', '\n', '\t')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .distinct()
        .sorted()

    fun joinUids(uids: Collection<Int>): String = uids.distinct().sorted().joinToString(",")

    /** Что показать пользователю: режим реально работает только с root. */
    fun statusText(rootAvailable: Boolean, hideEnabled: Boolean, targets: Int): String = when {
        !hideEnabled -> "Обычный режим: порт случайный, но локально доступен"
        !rootAvailable -> "Root не выдан · обычный режим: порт случайный, но локально доступен"
        targets <= 0 -> "Root есть · выберите приложения, которым закрыть localhost"
        else -> "Root есть · localhost закрыт для приложений: $targets"
    }
}
