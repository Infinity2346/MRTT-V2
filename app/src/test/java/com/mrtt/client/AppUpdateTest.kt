package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("1.9.9", "2.0.5"))
        assertTrue(isNewerVersion("2.0", "2.0.1"))
        assertFalse(isNewerVersion("2.0.5", "2.0"))
        assertFalse(isNewerVersion("2.1.0", "2.0.9"))
        assertFalse(isNewerVersion("2.0.5", "invalid"))
    }

    @Test
    fun ordersBetasBeforeTheRelease() {
        assertTrue(isNewerVersion("v1.0-beta1", "v1.0-beta2"))
        assertTrue(isNewerVersion("v1.0-beta1", "v1.0"))
        assertTrue(isNewerVersion("v1.0-beta2", "v1.1-beta1"))
        assertFalse(isNewerVersion("v1.0-beta2", "v1.0-beta1"))
        assertFalse(isNewerVersion("v1.0", "v1.0-beta2"))
        assertFalse(isNewerVersion("v1.0-beta1", "v1.0-beta1"))
    }

    @Test
    fun releaseTagsFromRepositoryNeverLookNewerThanInstalledBuild() {
        // Тег релиза и versionName сборки — это одна и та же версия в разной записи.
        // Раньше пре-релиз в теге не распознавался и приложение навсегда «видело» обновление.
        assertFalse(isNewerVersion("1.0-beta1.83", "mrtt-1.0-beta1-83"))
        assertFalse(isNewerVersion("1.0-beta1.83", "v1.0-beta1.83"))
        assertFalse(isNewerVersion("1.0-beta1.83", "mrtt-1.0-beta1-81"))
        assertFalse(isNewerVersion("1.0-beta1.83", "1.0-beta1.83"))
        // А более свежая сборка — это действительно обновление.
        assertTrue(isNewerVersion("1.0-beta1.83", "mrtt-1.0-beta1-84"))
        assertTrue(isNewerVersion("1.0-beta1.83", "mrtt-1.0-beta2-83"))
        assertTrue(isNewerVersion("1.0-beta1.83", "v1.1"))
    }

    @Test
    fun versionParserUnderstandsEveryForm() {
        assertEquals(listOf(1, 0), parseVersion("mrtt-1.0-beta1-83")?.numbers)
        assertEquals(1, parseVersion("mrtt-1.0-beta1-83")?.preNumber)
        assertEquals(83, parseVersion("mrtt-1.0-beta1-83")?.build)
        assertEquals(83, parseVersion("1.0-beta1.83")?.build)
        // У обычного релиза пре-релиза нет, иначе он считался бы старше себя же.
        assertNull(parseVersion("mrtt-2.5.1")?.preNumber)
        assertEquals(listOf(2, 5, 1), parseVersion("2.5.1")?.numbers)
        assertNull(parseVersion("2.5.1")?.preNumber)
        assertNull(parseVersion("не версия"))
    }

    @Test
    fun shownVersionHasNoRepositoryPrefix() {
        assertEquals("1.0-beta1.83", displayVersion("mrtt-1.0-beta1-83"))
        assertEquals("1.0-beta1.83", displayVersion("v1.0-beta1.83"))
        assertEquals("2.5.1", displayVersion("v2.5.1"))
        assertEquals("", displayVersion("  "))
    }

    @Test
    fun legacyReleasesAreNotUpdates() {
        assertTrue(isLegacyRelease("v2.5.0-legacy"))
        assertTrue(isLegacyRelease("v2.1.9-legacy"))
        assertFalse(isLegacyRelease("v1.0-beta1"))
    }
}
