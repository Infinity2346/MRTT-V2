package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCredentialPlanTest {
    @Test
    fun `auto js from the own account is not replaced by the server account`() {
        val plan = planVkCredentials(
            requestedAuthMode = MrttConstants.VkAuth.MODE_AUTO_JS,
            hashesFromLink = false,
            linkTokenPresent = true,
            ownVkSessionBound = true,
        )
        assertEquals(MrttConstants.VkAuth.MODE_AUTO_JS, plan.authMode)
        assertFalse(plan.tokenFromLink)
        assertFalse(plan.useLinkHashes)
        assertEquals(
            MrttConstants.VkAutoHash.MODE_AUTO_JS,
            plan.hashModeForResolution(),
        )
    }

    @Test
    fun `server account in the link is used without replacing the chosen mode`() {
        val plan = planVkCredentials(
            requestedAuthMode = MrttConstants.VkAuth.MODE_CALLS,
            hashesFromLink = false,
            linkTokenPresent = true,
            ownVkSessionBound = false,
        )
        // Режим, выбранный пользователем, не подменяется.
        assertEquals(MrttConstants.VkAuth.MODE_CALLS, plan.authMode)
        assertTrue(plan.tokenFromLink)
        assertEquals(MrttConstants.VkAutoHash.MODE_AUTO_API, plan.hashModeForResolution())
    }

    @Test
    fun `link hashes are used as is and the chosen mode is kept`() {
        val plan = planVkCredentials(
            requestedAuthMode = MrttConstants.VkAuth.MODE_AUTO_JS,
            hashesFromLink = true,
            linkTokenPresent = false,
            ownVkSessionBound = false,
        )
        assertTrue(plan.useLinkHashes)
        assertFalse(plan.tokenFromLink)
        assertNull(plan.hashModeForResolution())
    }

    @Test
    fun `link hashes are ignored when the own auto js account is chosen`() {
        val plan = planVkCredentials(
            requestedAuthMode = MrttConstants.VkAuth.MODE_AUTO_JS,
            hashesFromLink = true,
            linkTokenPresent = true,
            ownVkSessionBound = true,
        )
        assertEquals(MrttConstants.VkAuth.MODE_AUTO_JS, plan.authMode)
        assertFalse(plan.tokenFromLink)
        assertFalse(plan.useLinkHashes)
    }

    @Test
    fun `captcha mode ignores the server VK account from the link`() {
        val plan = planVkCredentials(
            requestedAuthMode = MrttConstants.VkAuth.MODE_CAPTCHA,
            hashesFromLink = false,
            linkTokenPresent = true,
            ownVkSessionBound = false,
        )
        assertEquals(MrttConstants.VkAuth.MODE_CAPTCHA, plan.authMode)
        assertFalse(plan.tokenFromLink)
    }

    @Test
    fun `explicit preference for the link keeps the server account in charge`() {
        val plan = planVkCredentials(
            requestedAuthMode = MrttConstants.VkAuth.MODE_AUTO_JS,
            hashesFromLink = false,
            linkTokenPresent = true,
            ownVkSessionBound = true,
            preferLinkVk = true,
        )
        assertTrue(plan.tokenFromLink)
        assertEquals(MrttConstants.VkAutoHash.MODE_AUTO_JS, plan.hashModeForResolution())
    }

    @Test
    fun `without link credentials the chosen mode is resolved from the stored session`() {
        val plan = planVkCredentials(
            requestedAuthMode = MrttConstants.VkAuth.MODE_CALLS,
            hashesFromLink = false,
            linkTokenPresent = false,
            ownVkSessionBound = true,
        )
        assertEquals(MrttConstants.VkAuth.MODE_CALLS, plan.authMode)
        assertFalse(plan.tokenFromLink)
        assertFalse(plan.useLinkHashes)
        assertNull(plan.hashModeForResolution())
    }
}
