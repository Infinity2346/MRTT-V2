package com.mrtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {
    private fun shouldAutoConnect(
        enabled: Boolean,
        configurationValid: Boolean,
        running: Boolean,
        starting: Boolean,
        pausedForWifi: Boolean,
    ): Boolean = enabled && configurationValid &&
        !tunnelPowerIsOn(running, starting, pausedForWifi)

    @Test
    fun connects_when_enabled_and_idle() {
        assertTrue(
            shouldAutoConnect(
                enabled = true,
                configurationValid = true,
                running = false,
                starting = false,
                pausedForWifi = false,
            ),
        )
    }

    @Test
    fun stays_off_when_the_setting_is_disabled() {
        assertFalse(
            shouldAutoConnect(
                enabled = false,
                configurationValid = true,
                running = false,
                starting = false,
                pausedForWifi = false,
            ),
        )
    }

    @Test
    fun does_not_touch_a_running_or_starting_tunnel() {
        assertFalse(shouldAutoConnect(true, true, running = true, starting = false, pausedForWifi = false))
        assertFalse(shouldAutoConnect(true, true, running = false, starting = true, pausedForWifi = false))
        assertFalse(shouldAutoConnect(true, true, running = false, starting = false, pausedForWifi = true))
    }

    @Test
    fun broken_configuration_never_auto_connects() {
        assertFalse(
            shouldAutoConnect(
                enabled = true,
                configurationValid = false,
                running = false,
                starting = false,
                pausedForWifi = false,
            ),
        )
    }
}
