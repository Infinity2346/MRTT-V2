package com.mrtt.client

import com.mrtt.client.ui.utils.parseMrttLink
import com.mrtt.client.ui.utils.peerAddress
import kotlinx.coroutines.flow.first

data class ConnectionSource(
    val peer: String,
    val password: String,
    val hashes: String,
    val hashesFromLink: Boolean,
    val vkAccessToken: String,
    val tokenFromLink: Boolean,
    val maxGroupId: String = "",
    val jitsiRoom: String = "",
    val jitsiSeats: Int = 0,
)


suspend fun resolveConnectionSource(store: SettingsStore): ConnectionSource? {
    // Без активного профиля подключаться не к чему: старая ссылка в настройках не считается.
    if (store.activeConnectionProfileId.first().isBlank()) return null
    val transportMode = store.transportMode.first()
    val maxTransportSelected = transportMode == MrttConstants.Transport.MODE_MAX
    val jitsiSelected = transportMode == MrttConstants.Transport.MODE_JITSI
    val invalidHashes = VkHashValidationCodec.decode(store.vkHashCheckResults.first())
    fun activeHashes(raw: String): String {
        val rawList = raw.split(Regex("[,\\s\\n]+")).map(String::trim).filter { it.length >= 16 }.distinct()
        val filtered = VkHashValidationCodec.active(rawList, invalidHashes)
        return (if (filtered.isNotEmpty()) filtered else rawList).joinToString(",")
    }

    // Ссылка профиля — основной путь; если её вдруг нет (или она битая), но адрес
    // сервера и пароль заданы, подключаемся по ним, а не требуем ссылку заново.
    val link = if (store.mrttLinkMode.first()) {
        parseMrttLink(store.mrttLink.first())
    } else {
        null
    }
    if (link != null) {
        // Свой привязанный VK важнее хешей из ссылки, если у профиля не выбрано обратное.
        val ownVk = store.vkSession.first().isNotBlank()
        // «Авто ВК» означает «звонки от своего аккаунта»: ссылка с аккаунтом сервера его не отменяет,
        // но явный выбор профиля «Из ссылки» сильнее.
        val preferLinkVk = store.vkPreferLinkHashes.first()
        val ownAutoJs = store.vkAuthMode.first() == MrttConstants.VkAuth.MODE_AUTO_JS && !preferLinkVk
        // Токен из ссылки — аккаунт сервера: он важнее старого входа в приложении.
        val useLinkVk = shouldUseLinkVk(
            ownVkBound = ownVk,
            preferLinkHashes = preferLinkVk,
            linkTokenPresent = link.vkAccessToken.isNotEmpty(),
            linkHashCount = link.hashes.size,
            ownAutoJsMode = ownAutoJs,
        )
        val linkHashes = if (useLinkVk) activeHashes(link.hashes.joinToString(",")) else ""
        val tokenFromLink = useLinkVk && link.vkAccessToken.isNotEmpty()
        return ConnectionSource(
            peer = link.peerAddress(),
            password = link.password,
            hashes = if (tokenFromLink) "" else linkHashes.ifEmpty { activeHashes(store.vkHashes.first()) },
            hashesFromLink = linkHashes.isNotEmpty(),
            vkAccessToken = if (tokenFromLink) link.vkAccessToken else "",
            tokenFromLink = tokenFromLink,
            maxGroupId = if (maxTransportSelected) link.maxGroupId.ifBlank { store.maxGroupId.first() } else "",
            jitsiRoom = if (jitsiSelected) store.jitsiRoomOverride.first().ifBlank { link.jitsiRoom } else "",
            jitsiSeats = if (jitsiSelected && store.jitsiRoomOverride.first().ifBlank { link.jitsiRoom } == link.jitsiRoom) {
                link.jitsiSeats
            } else {
                0
            },
        )
    }

    val basePeer = store.peer.first()
    val passwordState = store.connectionPasswordState.first()
    val password = passwordState.value
    if (basePeer.isBlank()) return null
    val serverPeerPort = if (store.manualPortsEnabled.first()) {
        store.serverPeerPort.first()
    } else {
        MrttConstants.Network.DEFAULT_SERVER_PEER_PORT
    }
    val peer = if (basePeer.contains(':')) basePeer else "$basePeer:$serverPeerPort"
    return ConnectionSource(
        peer = peer,
        password = password,
        hashes = activeHashes(store.vkHashes.first()),
        hashesFromLink = false,
        vkAccessToken = "",
        tokenFromLink = false,
        maxGroupId = if (maxTransportSelected) store.maxGroupId.first() else "",
    )
}
