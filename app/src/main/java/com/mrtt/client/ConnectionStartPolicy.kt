package com.mrtt.client

internal enum class ConnectionStartBlocker(val message: String) {
    NoProfile("не выбран профиль: выберите его во вкладке «Профили»"),
    Link("нужна корректная ссылка MRTT"),
    Server("не указан корректный адрес сервера"),
    Port("указан некорректный порт сервера"),
    Password("не указан пароль подключения"),
    StoredSecret("не удалось прочитать сохранённый пароль подключения; введите его заново"),
    Hashes("нет действующих VK хешей или VK токена"),
}

/**
 * Чей VK использовать: свой привязанный в приложении или из ссылки.
 *
 * Токен из ссылки — это аккаунт сервера: он приходит явно и должен работать
 * сразу, даже если в приложении остался старый вход в VK. Раньше свой аккаунт
 * перебивал ссылку, и подключение требовало «зайти в ВК в приложении».
 */
internal fun shouldUseLinkVk(
    ownVkBound: Boolean,
    preferLinkHashes: Boolean,
    linkTokenPresent: Boolean,
    linkHashCount: Int,
    /** Профиль подключён своим входом в VK («Авто ВК»): аккаунт сервера его не перебивает. */
    ownAutoJsMode: Boolean = false,
): Boolean = when {
    ownAutoJsMode && ownVkBound -> false
    linkTokenPresent -> true
    !ownVkBound -> true
    linkHashCount > 0 -> preferLinkHashes
    else -> preferLinkHashes
}

internal object ConnectionStartPolicy {
    fun hasReadyVkCredentials(
        linkMode: Boolean,
        linkHashCount: Int,
        linkTokenPresent: Boolean,
        autoHashMode: Boolean,
        storedTokenPresent: Boolean,
        manualHashCount: Int,
    ): Boolean = when {
        linkMode -> linkHashCount > 0 || linkTokenPresent || manualHashCount > 0 ||
            (autoHashMode && storedTokenPresent)
        autoHashMode -> storedTokenPresent
        else -> manualHashCount > 0
    }

    fun blocker(
        linkMode: Boolean,
        linkValid: Boolean,
        peerValid: Boolean,
        peerPortValid: Boolean,
        connectionPasswordSet: Boolean,
        connectionPasswordReadable: Boolean = true,
        hashesReady: Boolean,
        profileActive: Boolean = true,
    ): ConnectionStartBlocker? {
        if (!profileActive) return ConnectionStartBlocker.NoProfile
        if (!hashesReady) return ConnectionStartBlocker.Hashes
        if (linkMode) {
            // Ссылка читается — остальное уже внутри неё.
            if (linkValid) return null
            // Ссылка не читается, но адрес сервера задан: подключаемся по нему,
            // приложение не должно упираться в ссылку.
            if (!peerValid) return ConnectionStartBlocker.Link
        } else if (!peerValid) {
            return ConnectionStartBlocker.Server
        }
        if (!peerPortValid) return ConnectionStartBlocker.Port
        return null
    }
}
