package com.mrtt.client

object MrttConstants {
    object General {
        const val APP_NAME = "MRTT"
        const val PACKAGE_VK = "com.vkontakte.android"
        const val PACKAGE_VK_CALLS = "com.vk.calls"
        const val MRTT_EVENT_PREFIX = "__MRTT_EVENT__|"
        const val ACTION_RESTART_WHEN_VK_REACHABLE = "com.mrtt.client.RESTART_WHEN_VK_REACHABLE"
        const val ACTION_VERIFY_VK_REACHABILITY = "com.mrtt.client.VERIFY_VK_REACHABILITY"
        const val ACTION_RECOVER_VK_CALL = "com.mrtt.client.RECOVER_VK_CALL"
        const val EXTRA_UNAVAILABLE_VK_HASH = "unavailable_vk_hash"
    }

    object Profiles {
        const val MIN_INDEX = 0
        const val MAX_INDEX = 3
    }

    object Network {
        const val LOCAL_LISTEN_HOST = "127.0.0.1"
        const val DEFAULT_LOCAL_PORT = 0
        const val DEFAULT_SERVER_PEER_PORT = 46000
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_SERVER_WEB_PORT = 46002
    }

    object Tunnel {
        const val BINARY_NAME = "libclient.so"
        const val PROCESS_ENV_EVENTS = "MRTT_EVENTS"

        const val DEFAULT_FINGERPRINT = "firefox"
        const val DEFAULT_CLIENT_IDS = "8202606,6287487"
        const val DEFAULT_OBFS_MODE = "unified"
        const val DEFAULT_TURN_TRANSPORT = "udp"
        const val TURN_TRANSPORT_TCP_TLS = "tcp_tls"

        // Сетка потоков совпадает с нативным клиентом (`rust-client/worker.rs`):
        // там группа — это 10 потоков, и только кратное десяти значение он
        // принимает без пересчёта. Девятка давала ползунок до 99 и «27» в MAX.
        const val WORKERS_PER_GROUP = 10
        const val GROUPS_PER_VK_HASH = 3
        const val MAX_VK_HASHES = 6
        const val DEFAULT_MAX_WORKERS = 100
        const val MAX_WORKERS = 100
        const val DEFAULT_WORKERS = 20
        // Jitsi: each worker is a room participant and the server holds at most 16 seats.
        const val MIN_JITSI_WORKERS = 3
        const val MAX_JITSI_WORKERS = 16
        const val DEFAULT_JITSI_WORKERS = 6
    }

    object Transport {
        const val MODE_VK = "vk"
        const val MODE_MAX = "max"
        const val MODE_JITSI = "jitsi"
        // Серверы Jitsi на выбор при деплое: пускают гостей и держат канал данных видеомоста.
        val JITSI_HOSTS = listOf("meet.egovm.ru", "meet.mamba.group", "meet.riddlerx.org", "meet.handyweb.org", "conf.expressmoney.com")
    }

    object VkAuth {
        const val MODE_CALLS = "vkcalls"
        const val MODE_CAPTCHA = "legacy"
        const val MODE_AUTO_JS = "auto_js"
    }

    object SrtpMode {
        const val WRAP = "wrap-srtp"
        const val DTLS = "dtls-srtp"
        const val AUTO = "auto"
        const val DEFAULT = AUTO
    }

    object Timeouts {
        const val CAPTCHA_AUTO_TIMEOUT_MS = 10_000L
        const val CAPTCHA_MANUAL_TIMEOUT_MS = 60_000L
        const val CAPTCHA_WV_CREATE_TIMEOUT_MS = 3_000L

        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val LOG_READER_RESET_INTERVAL_MS = 60_000L

        const val VPN_PERMISSION_COOLDOWN_MS = 300L

        const val DEPLOY_CMD_TIMEOUT_MS = 900_000L
        const val DEPLOY_STALL_TIMEOUT_MS = 30 * 60 * 1000L

        const val UPDATE_RATE_LIMIT_COOLDOWN_MS = 30L * 60L * 1000L
    }

    object Thresholds {
        const val FLOOD_CONTROL_THRESHOLD = 5
        const val IP_MISMATCH_THRESHOLD = 5
        const val CONNECTION_REFUSED_THRESHOLD = 400
        const val HASH_ERROR_THRESHOLD = 10
    }

    object Update {
        const val DEFAULT_CHECK_INTERVAL_HOURS = 12
        const val CHECK_NEVER = -1
        const val ACTION_POSTPONED = "postponed"
        const val ACTION_UPDATE = "update"

        /** Репозиторий сборок: отсюда приложение проверяет новые версии. */
        const val GITHUB_RELEASES_URL =
            "https://api.github.com/repos/Toolbox231/Project/releases?per_page=30"
    }

    object Notifications {
        const val TUNNEL_CHANNEL_ID = "mrtt_tunnel_v4"
        const val TUNNEL_NOTIFICATION_ID = 1

        const val VPN_CHANNEL_ID = "mrtt_vpn_v1"

        const val CAPTCHA_CHANNEL_ID = "captcha_channel"
        const val CAPTCHA_NOTIFICATION_ID = 9001
    }

    object Widget {
        const val ACTION_TOGGLE = "com.mrtt.client.ACTION_WIDGET_TOGGLE"
    }

    object Captcha {
        const val ERROR_SLIDER_DETECTED = "slider_detected"
        val AUTO_VIEWPORT_WIDTHS = intArrayOf(356, 358, 360, 362, 364, 366, 368)
        val AUTO_VIEWPORT_HEIGHTS = intArrayOf(376, 378, 380, 382, 384, 386, 388)
    }

    object SecureStore {
        const val KEY_ALIAS = "mrtt.settings.secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val VERSION_PREFIX = "v1:"
    }

    object VkAutoHash {
        const val MODE_MANUAL = "manual"
        const val MODE_AUTO_API = "auto_api"
        const val MODE_AUTO_JS = "auto_js"
        const val MAX_OAUTH_HOPS = 15

        const val VK_APP_ID = 7793118
        const val VK_OAUTH_SCOPE = 1073737727
        const val VK_OAUTH_REDIRECT_URI = "https://oauth.vk.ru/blank.html"
        const val VK_OAUTH_AUTH_URL =
            "https://oauth.vk.ru/authorize" +
                "?client_id=7793118" +
                "&scope=1073737727" +
                "&redirect_uri=https%3A%2F%2Foauth.vk.ru%2Fblank.html" +
                "&display=page" +
                "&response_type=token" +
                "&revoke=1" +
                "&v=5.199"
        const val VK_LOGIN_URL = "https://vk.ru/"
        /** Мобильная страница входа: сразу форма логина, без витрины и лишних нажатий. */
        const val VK_MOBILE_LOGIN_URL = "https://m.vk.ru/"
        const val API_METHOD_BASE_URL = "https://api.vk.ru/method/"
        const val API_VERSION = "5.199"

        // --- Получение токена из кук сессии (актуальный путь VK) ---
        // OAuth-редирект oauth.vk.ru/authorize больше не отдаёт токен веб-сессии;
        // рабочие клиенты обменивают куки на токен через login.vk.ru/?act=web_token.
        const val WEB_APP_ID = "6287487"
        const val WEB_API_VERSION = "5.280"
        const val WEB_ORIGIN = "https://vk.ru"
        const val WEB_TOKEN_URL = "https://login.vk.ru/?act=web_token"
        const val WEB_API_BASE = "https://api.vk.ru/method/"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/148.0.0.0 Mobile Safari/537.36"

        const val AUTH_TIMEOUT_MS = 300_000L
        const val AUTH_MONITOR_INTERVAL_MS = 200L
        const val AUTH_SILENT_RETRY_DELAY_MS = 1_500L

        const val AUTO_CALL_SMALL_DELAY_MS = 380L
        const val AUTO_CALL_LARGE_DELAY_MS = 380L
        const val AUTO_CALL_MAX_ATTEMPTS = 3
        const val FINISH_CALL_STEP_MS = 150L
        const val FINISH_CALLS_TIMEOUT_MS = 8_000L
    }

    object Vpn {
        const val DEFAULT_MTU = 1280
        const val MTU_MIN = 1280
        const val MTU_MAX = 1500
        const val EMPTY_WHITELIST_MESSAGE = "В режиме БС выберите хотя бы одно приложение"
    }

    object Theme {
        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        const val PALETTE_INDIGO = "indigo"
        const val PALETTE_ESPRESSO = "espresso"
        const val PALETTE_FOREST = "forest"
    }

    object Links {
        const val RELEASES = "https://github.com/Toolbox231/Project/releases"
        const val ISSUES = "https://github.com/Toolbox231/Project/issues/new"
        const val DEVELOPER_PROFILE = "https://github.com/amurcanov"
        const val REPOSITORY = "https://github.com/amurcanov/csqtt"
        const val TELEGRAM = "https://t.me/amurcanov_telegram"
        const val TELEGRAM_HANDLE = "t.me/amurcanov_telegram"
    }

    object Patterns {
        val VERSION_NUMBER = kotlin.text.Regex("\\d+(?:\\.\\d+)*")
    }
}
