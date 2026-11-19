package com.mrtt.client

internal object PortHidePolicy {

    const val CHAIN = "OUTPUT"
    const val IPV4_BINARY = "iptables"
    const val IPV6_BINARY = "ip6tables"

    private val SKIPPED_UIDS = setOf(0, 1000)

    data class Rule(val binary: String, val uid: Int, val destination: String) {
        fun insertScript(): String =
            "$binary -w -I $CHAIN 1 -m owner --uid-owner $uid -d $destination -j REJECT"

        fun deleteScript(): String = removePrefix(insertScript())

        private fun removePrefix(insert: String): String =
            insert.replaceFirst("-w -I $CHAIN 1", "-w -D $CHAIN")
    }

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

    fun clearScript(rules: List<Rule>): String = buildString {
        append("set +e\n")
        for (rule in rules) {
            val delete = rule.deleteScript()
            repeat(CLEAR_ATTEMPTS) { append(delete).append(" >/dev/null 2>&1\n") }
        }
        append("echo MRI_OK\n")
    }

    private const val CLEAR_ATTEMPTS = 6

    fun parseUids(raw: String): List<Int> = raw
        .split(',', ' ', '\n', '\t')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .distinct()
        .sorted()

    fun joinUids(uids: Collection<Int>): String = uids.distinct().sorted().joinToString(",")

    fun statusText(rootAvailable: Boolean, hideEnabled: Boolean, targets: Int): String = when {
        !hideEnabled -> "Обычный режим: порт случайный, но локально доступен"
        !rootAvailable -> "Root не выдан · обычный режим: порт случайный, но локально доступен"
        targets <= 0 -> "Root есть · выберите приложения, которым закрыть localhost"
        else -> "Root есть · localhost закрыт для приложений: $targets"
    }
}
