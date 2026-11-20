package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortHidePolicyTest {

    @Test
    fun rulesCoverLoopbackForEveryUidExceptOwn() {
        val rules = PortHidePolicy.rules(listOf(10123, 10124), ownUid = 10200)
        assertEquals(4, rules.size)
        assertTrue(rules.any { it.uid == 10123 && it.binary == PortHidePolicy.IPV4_BINARY })
        assertTrue(rules.any { it.uid == 10123 && it.binary == PortHidePolicy.IPV6_BINARY })
        // Свой UID не трогаем: клиент сам ходит на 127.0.0.1.
        val ownRules = PortHidePolicy.rules(listOf(10200), ownUid = 10200)
        assertTrue(ownRules.isEmpty())
    }

    @Test
    fun duplicatesAndSystemUidsAreDropped() {
        val rules = PortHidePolicy.rules(listOf(10123, 10123, 0, 1000), ownUid = 10500)
        assertEquals(listOf(10123, 10123), rules.map { it.uid })
    }

    @Test
    fun insertAndDeleteScriptsAreDifferentFormsOfTheSameRule() {
        val rule = PortHidePolicy.Rule(PortHidePolicy.IPV4_BINARY, 10123, "127.0.0.1/32")
        assertEquals(
            "iptables -w -I OUTPUT 1 -m owner --uid-owner 10123 -d 127.0.0.1/32 -j REJECT",
            rule.insertScript(),
        )
        assertEquals(
            "iptables -w -D OUTPUT -m owner --uid-owner 10123 -d 127.0.0.1/32 -j REJECT",
            rule.deleteScript(),
        )
    }

    @Test
    fun applyScriptInsertsRulesAndReportsSuccess() {
        val rules = PortHidePolicy.rules(listOf(10123), ownUid = 10500)
        val script = PortHidePolicy.applyScript(rules)
        assertTrue(script.contains("iptables -w -I OUTPUT 1 -m owner --uid-owner 10123"))
        assertTrue(script.contains("ip6tables -w -I OUTPUT 1 -m owner --uid-owner 10123 -d ::1/128"))
        assertTrue(script.trimEnd().endsWith("echo MRI_OK"))
        // Ошибка одного правила не должна рвать остальные.
        assertTrue(script.startsWith("set +e"))
    }

    @Test
    fun clearScriptRemovesRuleRepeatedly() {
        val rules = PortHidePolicy.rules(listOf(10123), ownUid = 10500)
        val script = PortHidePolicy.clearScript(rules)
        // По шесть попыток на каждое семейство адресов: правило в списке одно,
        // но при повторе оно всё равно удаляется, пока не закончится.
        assertEquals(6, Regex("^iptables -w -D OUTPUT -m owner --uid-owner 10123", RegexOption.MULTILINE).findAll(script).count())
        assertEquals(6, Regex("^ip6tables -w -D OUTPUT -m owner --uid-owner 10123", RegexOption.MULTILINE).findAll(script).count())
        assertTrue(script.contains("echo MRI_OK"))
    }

    @Test
    fun uidsParseAndJoinRoundTrip() {
        assertEquals(listOf(10001, 10002), PortHidePolicy.parseUids("10002, 10001,10002"))
        assertEquals("", PortHidePolicy.joinUids(emptyList()))
        assertEquals("10001,10002", PortHidePolicy.joinUids(listOf(10002, 10001)))
    }

    @Test
    fun statusTextExplainsFallbackWithoutRoot() {
        assertEquals(
            "Root не выдан · обычный режим: порт случайный, но локально доступен",
            PortHidePolicy.statusText(rootAvailable = false, hideEnabled = true, targets = 3),
        )
        assertTrue(
            PortHidePolicy.statusText(rootAvailable = true, hideEnabled = true, targets = 0)
                .contains("выберите приложения"),
        )
        assertTrue(
            PortHidePolicy.statusText(rootAvailable = true, hideEnabled = true, targets = 2)
                .contains("закрыт для приложений: 2"),
        )
        assertFalse(PortHidePolicy.statusText(rootAvailable = false, hideEnabled = false, targets = 0).isEmpty())
    }
}
