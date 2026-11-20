package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLogPolicyTest {
    @Test
    fun removesStatusStickersFromVisibleLogs() {
        assertEquals(
            "VPN TUN: разрешение Android VPN было отозвано",
            withoutLogStickers("❌ VPN TUN: разрешение Android VPN было отозвано"),
        )
        assertEquals("Поток готов ✓", withoutLogStickers("Поток готов ✓"))
        assertEquals("✗ Ошибка VPN", withoutLogStickers("✗ Ошибка VPN"))
        assertEquals("Сервис запущен", withoutLogStickers("[✅] Сервис запущен"))
        assertEquals("[СЕТЬ] Переподключение", withoutLogStickers("[СЕТЬ] ⚠ Переподключение"))
    }

    @Test
    fun maxModeShowsEveryMaxLineIncludingAddressProbes() {
        // В режиме MAX просили полный журнал: по нему видно, где именно обрыв.
        val probe = "[MAX][TCP] 155.212.204.90 (api.oneme.ru): MAX TCP: таймаут TCP"
        TunnelLogPolicy.maxVerbose = true
        try {
            assertTrue(TunnelLogPolicy.isInternalRecovery(probe).not())
            assertTrue(
                TunnelLogPolicy.isInternalRecovery(
                    "[MAX][WS] Попытка 1/2 не удалась: WS таймаут oneme",
                ).not(),
            )
            assertTrue(TunnelLogPolicy.isMaxVerboseLine(probe))
            // VK-строки по-прежнему фильтруются как раньше.
            assertFalse(TunnelLogPolicy.isMaxVerboseLine("[СЕТЬ][RETRY] TURN timeout"))
            assertTrue(TunnelLogPolicy.isInternalRecovery("[TURN][RETRY] allocation timeout"))
        } finally {
            TunnelLogPolicy.maxVerbose = false
        }
        // После выхода из режима MAX фильтры возвращаются.
        assertTrue(TunnelLogPolicy.isInternalRecovery(probe))
    }

    @Test
    fun perAddressMaxProbesAreNotShownAsErrors() {
        // Перебор адресов MAX — не поломка: в журнал идёт только итог.
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[MAX][WS] 155.212.204.70 (ws-api.oneme.ru): TCP к 155.212.204.70:443: timed out",
            ),
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[MAX][TCP] 2a00:1450::1 (api.oneme.ru): MAX TCP: таймаут TCP",
            ),
        )
        // Итоговые строки — наоборот, нужны.
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "[MAX][WS] ws-api.oneme.ru → [155.212.204.70, 155.212.204.133]",
            ),
        )
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "[MAX][WS] Попытка 2/2 не удалась: не удалось ни по WS, ни по TCP",
            ),
        )
        assertFalse(TunnelLogPolicy.isInternalRecovery("[ФАТАЛ] MAX TCP: что-то"))
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "[MAX][TCP] сервер вернул ошибку на opcode 19: login.cred",
            ),
        )
    }

    @Test
    fun inactiveLoggingKeepsOnlyCallCreatedAndCompletion() {
        assertTrue(
            TunnelLogPolicy.isAllowedWhenInactiveLogging(
                "vk_js_call_progress",
                "[MAX] Звонок создан ✓",
            ),
        )
        assertTrue(
            TunnelLogPolicy.isAllowedWhenInactiveLogging(
                "vk_call_created_progress",
                "[OK] Звонок VK создан ✓",
            ),
        )
        assertTrue(TunnelLogPolicy.isAllowedWhenInactiveLogging("workers_done", "[OK] Всё готово ✓"))
        assertFalse(TunnelLogPolicy.isAllowedWhenInactiveLogging("ready", "[OK] Готово"))
        assertFalse(TunnelLogPolicy.isAllowedWhenInactiveLogging("stats", "[СЕТЬ] Активных: 1"))
        assertFalse(TunnelLogPolicy.isAllowedWhenInactiveLogging("turn_error", "Ошибка"))
        assertFalse(TunnelLogPolicy.isAllowedWhenInactiveLogging("wrap_status", "Свёртка"))
        assertFalse(
            TunnelLogPolicy.isAllowedWhenInactiveLogging(
                "vk_js_call_progress",
                "[VK JS] Владелец вышел из звонка ✓",
            ),
        )
    }

    @Test
    fun hidesEveryGetconfDiagnostic() {
        assertTrue(TunnelLogPolicy.isInternalRecovery("[GETCONF] Sending 72 bytes"))
        // Таймаут конфига — причина, по которой сессия пересоздаётся без
        // данных: его нельзя прятать.
        assertFalse(TunnelLogPolicy.isInternalRecovery("[ВОРКЕР #8] GETCONF timeout 750ms"))
        assertFalse(TunnelLogPolicy.isInternalRecovery("GETCONF чтение ответа конфига: timeout"))
        assertFalse(
            TunnelLogPolicy.isInternalRecovery("[ВОРКЕР #3] GETCONF timeout после 4 попыток"),
        )
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "GETCONF: FATAL_AUTH: неверный пароль подключения"
            )
        )
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "GETCONF: FATAL_PROTOCOL: клиент и сервер используют разные версии протокола"
            )
        )
    }

    @Test
    fun hidesTurnRetriesAndInternalRecovery() {
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Ошибка обновления permission: timeout"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Ошибка транзакции ChannelBind: timeout"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Квота Allocate у креда #1; STUN error 486"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Allocation mismatch у креда #1; STUN error 437"
            )
        )
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][DOWN] Permission истекает, переподнимаем поток"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #17] PEER_LIVENESS_TIMEOUT: 8s без валидных пакетов"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #2] [TURN][RETRY] Попытка 46: TURN liveness probe failed"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #2] [СЕТЬ][RETRY] Временная ошибка сокета"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #1] Ошибка (попытка 1): turn allocate: transaction timeout"
            )
        )
    }

    @Test
    fun surfacesOnlyTurnFailuresThatEndAStream() {
        assertTrue(
            TunnelLogPolicy.isTurnStreamFailure(
                "[TURN][DOWN] Allocation истекает, переподнимаем поток"
            )
        )
        assertTrue(
            TunnelLogPolicy.isTurnStreamFailure(
                "[ВОРКЕР #4] [TURN] Ошибка allocation/кредов: socket closed"
            )
        )
        assertFalse(
            TunnelLogPolicy.isTurnStreamFailure(
                "[ГРУППА #1] [TURN] Креды обновлены, TURN urls=3"
            )
        )
    }
}
