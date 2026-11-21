package com.mrtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierAppExclusionTest {
    @Test
    fun knownOperatorPackagesAreDetected() {
        assertTrue(CarrierAppExclusion.matches("ru.yota.android", "Yota"))
        assertTrue(CarrierAppExclusion.matches("com.beeline", "Мой Билайн"))
        assertTrue(CarrierAppExclusion.matches("com.mts", "Мой МТС"))
        assertTrue(CarrierAppExclusion.matches("com.megafon", "МегаФон"))
        assertTrue(CarrierAppExclusion.matches("com.tele2", "Мой Теле2"))
        assertTrue(CarrierAppExclusion.matches("com.rostelecom.ru", "Ростелеком"))
        assertTrue(CarrierAppExclusion.matches("com.yota.some.plugin", "Yota"))
        assertTrue(CarrierAppExclusion.matches("ru.yota.android", "Мобильный оператор"))
    }

    @Test
    fun operatorNamesInLabelsAreDetected() {
        assertTrue(CarrierAppExclusion.matches("com.example.operator", "Личный кабинет Yota"))
        assertTrue(CarrierAppExclusion.matches("com.example.cabinet", "Мой Билайн"))
    }

    @Test
    fun unrelatedAppsAreLeftInTheTunnel() {
        assertFalse(CarrierAppExclusion.matches("com.mrtt.client", "MRTT"))
        assertFalse(CarrierAppExclusion.matches("com.example.bank", "Банк"))
        assertFalse(CarrierAppExclusion.matches("com.vkontakte.android", "VK"))
    }
}
