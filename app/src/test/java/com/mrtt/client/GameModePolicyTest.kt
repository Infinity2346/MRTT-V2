package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModePolicyTest {

    private val games = setOf("com.supercell.clashofclans", "com.valvesoftware.android.steam.community")

    @Test
    fun manualSwitchAlwaysWins() {
        assertTrue(GameModePolicy.isActive(manual = true, auto = false, games = emptySet(), foreground = emptyList()))
        assertTrue(GameModePolicy.isActive(manual = true, auto = true, games = emptySet(), foreground = listOf("com.android.chrome")))
    }

    @Test
    fun autoModeTurnsOnOnlyForGameInForeground() {
        assertTrue(
            GameModePolicy.isActive(
                manual = false,
                auto = true,
                games = games,
                foreground = listOf("com.supercell.clashofclans"),
            ),
        )
        assertFalse(
            GameModePolicy.isActive(
                manual = false,
                auto = true,
                games = games,
                foreground = listOf("com.android.chrome"),
            ),
        )
        assertFalse(GameModePolicy.isActive(manual = false, auto = true, games = games, foreground = emptyList()))
    }

    @Test
    fun disabledAutoNeedsTheManualSwitch() {
        assertFalse(
            GameModePolicy.isActive(
                manual = false,
                auto = false,
                games = games,
                foreground = listOf("com.supercell.clashofclans"),
            ),
        )
    }

    @Test
    fun emptyGameListNeverTriggersAuto() {
        assertFalse(GameModePolicy.isActive(manual = false, auto = true, games = emptySet(), foreground = listOf("com.supercell.clashofclans")))
    }

    @Test
    fun gamePortsIncludeDefaultsAndCustom() {
        val ports = GameModePolicy.effectivePorts("1234, 65536, abc, 27015")
        assertTrue(ports.contains(27015))
        assertTrue(ports.contains(1234))
        assertFalse(ports.contains(65536))
        assertEquals(ports.distinct(), ports)
        assertEquals(ports.sorted(), ports)
    }

    @Test
    fun commandCarriesFlagAndPorts() {
        val command = GameModePolicy.command(active = true, extraPorts = "1234")
        assertTrue(command.startsWith("GAME_MODE|1|"))
        assertTrue(command.contains("27015"))
        assertTrue(command.contains("1234"))

        val off = GameModePolicy.command(active = false, extraPorts = "")
        assertTrue(off.startsWith("GAME_MODE|0|"))
    }

    @Test
    fun packagesRoundTrip() {
        val parsed = GameModePolicy.parsePackages(" com.a.game , com.b.game ,, ")
        assertEquals(setOf("com.a.game", "com.b.game"), parsed)
        assertEquals("com.a.game,com.b.game", GameModePolicy.packagesToString(parsed))
    }
}
