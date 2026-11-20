package com.mrtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStartPolicyTest {
    @Test
    fun tokenFromParticipantLinkIsReadyWithoutStoredOwnerToken() {
        assertEquals(
            true,
            ConnectionStartPolicy.hasReadyVkCredentials(
                linkMode = true,
                linkHashCount = 0,
                linkTokenPresent = true,
                autoHashMode = true,
                storedTokenPresent = false,
                manualHashCount = 0,
            ),
        )
    }

    @Test
    fun participantLinkUsesExplicitlyEnteredHashesWhenLinkHasNoVkCredentials() {
        assertEquals(
            true,
            ConnectionStartPolicy.hasReadyVkCredentials(
                linkMode = true,
                linkHashCount = 0,
                linkTokenPresent = false,
                autoHashMode = false,
                storedTokenPresent = false,
                manualHashCount = 1,
            ),
        )
    }

    @Test
    fun participantLinkWithoutCredentialsIsBlockedUntilUserEntersThem() {
        assertEquals(
            false,
            ConnectionStartPolicy.hasReadyVkCredentials(
                linkMode = true,
                linkHashCount = 0,
                linkTokenPresent = false,
                autoHashMode = false,
                storedTokenPresent = false,
                manualHashCount = 0,
            ),
        )
    }

    @Test
    fun `participant may connect through a valid link with manually saved hashes`() {
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = true,
                peerValid = false,
                peerPortValid = false,
                connectionPasswordSet = false,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun `missing manual participant hashes is reported before attempting native startup`() {
        assertEquals(
            ConnectionStartBlocker.Hashes,
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = true,
                peerValid = false,
                peerPortValid = false,
                connectionPasswordSet = false,
                hashesReady = false,
            ),
        )
    }

    @Test
    fun `owner validation identifies the first missing setting`() {
        assertEquals(
            ConnectionStartBlocker.Server,
            ConnectionStartPolicy.blocker(
                linkMode = false,
                linkValid = false,
                peerValid = false,
                peerPortValid = true,
                connectionPasswordSet = true,
                hashesReady = true,
            ),
        )
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = false,
                linkValid = false,
                peerValid = true,
                peerPortValid = true,
                connectionPasswordSet = false,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun `unreadable stored secret does not block passwordless connection`() {
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = false,
                linkValid = false,
                peerValid = true,
                peerPortValid = true,
                connectionPasswordSet = false,
                connectionPasswordReadable = false,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun `link mode does not depend on the local encrypted password`() {
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = true,
                peerValid = false,
                peerPortValid = false,
                connectionPasswordSet = false,
                connectionPasswordReadable = false,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun `link mode falls back to the server address when the stored link is unreadable`() {
        // Ссылка в профиле не читается, но адрес сервера и пароль есть: подключение возможно.
        assertNull(
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = false,
                peerValid = true,
                peerPortValid = true,
                connectionPasswordSet = true,
                hashesReady = true,
            ),
        )
        // Нет ни ссылки, ни адреса сервера — вот тогда просим корректную ссылку.
        assertEquals(
            ConnectionStartBlocker.Link,
            ConnectionStartPolicy.blocker(
                linkMode = true,
                linkValid = false,
                peerValid = false,
                peerPortValid = true,
                connectionPasswordSet = true,
                hashesReady = true,
            ),
        )
    }

    @Test
    fun token_from_server_link_wins_over_a_bound_account() {
        // Аккаунт сервера приходит явно: старый вход в VK на клиенте его не перебивает.
        assertTrue(
            shouldUseLinkVk(
                ownVkBound = true,
                preferLinkHashes = false,
                linkTokenPresent = true,
                linkHashCount = 0,
            ),
        )
    }

    @Test
    fun link_hashes_follow_the_user_preference() {
        assertTrue(
            shouldUseLinkVk(
                ownVkBound = true,
                preferLinkHashes = true,
                linkTokenPresent = false,
                linkHashCount = 2,
            ),
        )
        assertFalse(
            shouldUseLinkVk(
                ownVkBound = true,
                preferLinkHashes = false,
                linkTokenPresent = false,
                linkHashCount = 2,
            ),
        )
    }

    @Test
    fun without_own_account_the_link_is_always_used() {
        assertTrue(
            shouldUseLinkVk(
                ownVkBound = false,
                preferLinkHashes = false,
                linkTokenPresent = false,
                linkHashCount = 3,
            ),
        )
    }

    @Test
    fun own_auto_vk_account_is_not_replaced_by_the_server_account_from_the_link() {
        // «Авто ВК» — звонки от своего аккаунта: аккаунт сервера в ссылке его не отменяет.
        assertFalse(
            shouldUseLinkVk(
                ownVkBound = true,
                preferLinkHashes = false,
                linkTokenPresent = true,
                linkHashCount = 0,
                ownAutoJsMode = true,
            ),
        )
        // Явный выбор «Из ссылки» остаётся сильнее.
        assertTrue(
            shouldUseLinkVk(
                ownVkBound = true,
                preferLinkHashes = true,
                linkTokenPresent = true,
                linkHashCount = 0,
                ownAutoJsMode = false,
            ),
        )
    }

    @Test
    fun server_token_is_read_from_the_link() {
        assertEquals("", linkVkTokenOf(""))
        assertEquals("", linkVkTokenOf("не ссылка"))
    }
}
