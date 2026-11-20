package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupDiagnosticsTest {
    @Test
    fun emptyReasonKeepsTheGenericHint() {
        val text = StartupDiagnostics.credentialsDiagnostic(null)
        assertTrue(text.contains("Rust-клиент запущен"))
        assertTrue(text.contains("сеть, токен и капчу"))
        assertEquals(text, StartupDiagnostics.credentialsDiagnostic("   "))
    }

    @Test
    fun maxTransportFailureSaysTheSignalingIsDownNotTheToken() {
        val text = StartupDiagnostics.credentialsDiagnostic(
            "[MAX][WS] Попытка 2/2 не удалась: WS подключение ws-api.oneme.ru: Connection timed out (os error 110)",
        )
        assertTrue(text.contains("MAX-сигналинг недоступен"))
        assertTrue(text.contains("os error 110"))
        assertTrue(!text.contains("капчу"))
    }

    @Test
    fun bothMaxTransportsShowTheLastRealReason() {
        val text = StartupDiagnostics.credentialsDiagnostic(
            "MAX call token: не удалось ни по WS, ни по TCP (MAX TCP: подключение к api.oneme.ru)",
        )
        assertTrue(text.contains("обоими путями"))
        assertTrue(text.contains("api.oneme.ru"))
    }

    @Test
    fun captchaAndVkErrorsStaySeparate() {
        assertTrue(
            StartupDiagnostics.credentialsDiagnostic("CAPTCHA_SOLVE|требуется капча")
                .contains("капчи"),
        )
        assertTrue(
            StartupDiagnostics.credentialsDiagnostic("User authorization failed: error_code=5")
                .contains("токен аккаунта"),
        )
        assertTrue(
            StartupDiagnostics.credentialsDiagnostic("не удалось получить TURN-креды")
                .contains("TURN-креды"),
        )
    }

    @Test
    fun maxRejectedTokenIsNotReportedAsAnUnreachableSignaling() {
        val text = StartupDiagnostics.credentialsDiagnostic(
            "MAX TCP: LOGIN: MAX TCP: сервер вернул ошибку на opcode 19: " +
                "Map([(Str(\"error\"), Str(\"login.cred\")), (Str(\"message\"), Str(\"FAIL_WRONG_PASSWORD\"))])",
        )
        assertTrue(text.contains("не принял сохранённый токен"))
        assertTrue(text.contains("войдите в MAX заново"))
        assertTrue(!text.contains("сигналинг недоступен"))
    }

    @Test
    fun configTimeoutExplainsTheZeroTrafficLoop() {
        val text = StartupDiagnostics.configTimeoutDiagnostic()
        assertTrue(text.contains("GETCONF"))
        assertTrue(text.contains("проверьте адрес сервера"))
    }

    @Test
    fun idleTunnelSaysWhereToLookInsteadOfSilence() {
        val vk = StartupDiagnostics.idleDiagnostic(maxMode = false)
        assertTrue(vk.contains("Соединение есть, трафика нет"))
        assertTrue(vk.contains("TURN"))
        assertTrue(vk.contains("нужных приложений"))

        val max = StartupDiagnostics.idleDiagnostic(maxMode = true)
        assertTrue(max.contains("MAX-звонок создан"))
    }

    @Test
    fun unknownReasonIsStillShown() {
        val text = StartupDiagnostics.credentialsDiagnostic("что-то пошло не так")
        assertTrue(text.startsWith("[ДИАГНОСТИКА] Креды не пришли"))
        assertTrue(text.contains("что-то пошло не так"))
    }
}
