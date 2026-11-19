package com.mrtt.client.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три состояния приложения: в туннеле → свой интернет напрямую → свой интернет
 * с фильтром рекламы. Проверяем переключение и то, что приложение не остаётся сразу в
 * двух списках — иначе Android и клиент поняли бы его по-разному.
 */
class AppRoutingTest {
    @Test
    fun tap_cycles_tunnel_direct_filtered() {
        assertEquals(AppRoute.Direct, AppRoute.Tunnel.next())
        assertEquals(AppRoute.DirectFiltered, AppRoute.Direct.next())
        assertEquals(AppRoute.Tunnel, AppRoute.DirectFiltered.next())
    }

    @Test
    fun filtered_mode_is_direct_but_keeps_the_filter() {
        assertTrue(AppRoute.DirectFiltered.isDirect)
        assertTrue(AppRoute.Direct.isDirect)
        assertFalse(AppRoute.Tunnel.isDirect)
        assertEquals("Свой интернет + фильтр", AppRoute.DirectFiltered.shortLabel)
    }

    @Test
    fun assigning_a_route_removes_the_app_from_other_lists() {
        val routing = AppRouting()
            .assign("com.example.video", AppRoute.DirectFiltered)
            .assign("com.example.video", AppRoute.Direct)
        assertTrue("com.example.video" in routing.direct)
        assertFalse("com.example.video" in routing.filtered)
        assertFalse("com.example.video" in routing.tunnel)
    }

    @Test
    fun advance_moves_the_app_one_step() {
        val routing = AppRouting(tunnel = setOf("com.example.video")).advance("com.example.video")
        assertEquals(AppRoute.Direct, routing.routeOf("com.example.video"))
        assertFalse("com.example.video" in routing.tunnel)
    }

    @Test
    fun unknown_app_is_in_the_tunnel() {
        assertEquals(AppRoute.Tunnel, AppRouting().routeOf("com.example.other"))
    }

    @Test
    fun lists_are_parsed_from_comma_separated_settings() {
        val routing = AppRouting.parse(
            tunnel = "com.a, com.b",
            filtered = "com.c\ncom.d",
            direct = " com.e ,",
        )
        assertEquals(AppRoute.Tunnel, routing.routeOf("com.a"))
        assertEquals(AppRoute.DirectFiltered, routing.routeOf("com.d"))
        assertEquals(AppRoute.Direct, routing.routeOf("com.e"))
        assertEquals(5, routing.tunnel.size + routing.filtered.size + routing.direct.size)
    }
}
