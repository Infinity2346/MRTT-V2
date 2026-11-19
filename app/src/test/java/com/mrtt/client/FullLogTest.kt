// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullLogTest {
    @Test
    fun clientLineIsKeptOnceAndItsAppEchoIsDropped() {
        FullLog.clear()
        FullLog.add("клиент", "[MAX] Звонок создан: abc")
        FullLog.add("приложение", "[MAX] Звонок создан: abc")
        val lines = FullLog.text().lines()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("[клиент]"))
    }

    @Test
    fun appLineWithoutClientEchoIsKept() {
        FullLog.clear()
        FullLog.add("приложение", "[СЕТЬ] Активных: 3 | Трафик: 0,00 МБ")
        assertTrue(FullLog.text().contains("[СЕТЬ] Активных: 3"))
    }

    @Test
    fun repeatedClientLinesAreNotCollapsed() {
        // Порядок шагов важнее краткости: одинаковые строки клиента остаются.
        FullLog.clear()
        FullLog.add("клиент", "[MAX][TCP] ← cmd=1 opcode=158 флаг=0x02 161Б")
        FullLog.add("клиент", "[MAX][TCP] ← cmd=1 opcode=158 флаг=0x02 161Б")
        assertEquals(2, FullLog.text().lines().size)
    }

    @Test
    fun secretsAreRedacted() {
        FullLog.clear()
        FullLog.add("клиент", "access_token=abcdef123&x=1")
        assertFalse(FullLog.text().contains("abcdef123"))
        assertTrue(FullLog.text().contains("access_token=***"))
    }
}
