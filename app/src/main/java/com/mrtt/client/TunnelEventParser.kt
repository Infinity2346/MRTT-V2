package com.mrtt.client

import org.json.JSONObject

object TunnelEventParser {
    private const val PREFIX = MrttConstants.General.MRTT_EVENT_PREFIX
    const val CALL_NOT_FOUND_CODE = 951
    const val MIN_SERVER_WORKERS = 3

    sealed class Event {
        data class Started(val message: String = "") : Event()
        data class Stopped(val message: String = "") : Event()
        data class Process(val pid: Int) : Event()
        data class Ready(val worker: Int, val message: String = "") : Event()
        data class ActiveZero(val message: String = "") : Event()
        data class CallUnavailable(val hash: String, val code: Int) : Event()

        /** Токен аккаунта VK/MAX отвергнут: нужен свежий, звонок при этом жив. */
        data class AuthExpired(val code: Int) : Event()

        /** MAX не принял свой токен: это не повод трогать вход в VK. */
        data object MaxTokenRejected : Event()

        /** MAX выдал новый токен сессии: его нужно сохранить. */
        data class MaxSessionToken(val token: String) : Event()
        data object NetworkSuspect : Event()
        data object ServerRestart : Event()
        data class Config(val config: String) : Event()

        /** Реальный локальный порт клиента: 0 — не удалось узнать. */
        data class LocalPort(val port: Int) : Event()
        data class Stats(
            val active: Int,
            val bytesUp: Long,
            val bytesDown: Long
        ) : Event()

        data class Error(
            val code: String,
            val message: String,
            val fatal: Boolean
        ) : Event()

        data class CaptchaRequest(
            val mode: String,
            val redirectUri: String,
            val sessionToken: String
        ) : Event()

        data class CaptchaDone(
            val success: Boolean,
            val error: String?
        ) : Event()

        data class Progress(val kind: String) : Event()
        data class Dtls(val worker: Int, val message: String = "") : Event()
        data class Routing(val routing: String) : Event()
        data class Workers(val workers: Int) : Event()
        data object TunLost : Event()
        data class Probe(val ms: Int) : Event()
        data class RoomSeats(val seats: Int) : Event()

        /** Вопрос клиента: чей это поток. Ответ — команда APP_DIRECT_FLOW. */
        data class FlowOwner(val src: String, val dst: String, val protocol: Int) : Event()
    }

    fun parse(line: String): Event? {
        if (!line.startsWith(PREFIX)) return null
        val withoutPrefix = line.substring(PREFIX.length)
        val typeEnd = withoutPrefix.indexOf('|')
        if (typeEnd == -1) return null

        val type = withoutPrefix.substring(0, typeEnd)
        val payload = try {
            JSONObject(withoutPrefix.substring(typeEnd + 1))
        } catch (_: Exception) {
            return null
        }

        return when (type) {
            "STARTED" -> Event.Started(payload.optString("message", ""))
            "STOPPED" -> Event.Stopped(payload.optString("message", ""))
            "PROCESS" -> payload.optInt("pid", 0)
                .takeIf { it > 0 }
                ?.let { Event.Process(it) }
            "READY" -> Event.Ready(
                worker = payload.optInt("worker", 0).coerceIn(0, MrttConstants.Tunnel.MAX_WORKERS),
                message = payload.optString("message", ""),
            )
            "DTLS" -> Event.Dtls(
                worker = payload.optInt("worker", 0).coerceIn(0, MrttConstants.Tunnel.MAX_WORKERS),
                message = payload.optString("message", ""),
            )
            "ACTIVE_ZERO" -> Event.ActiveZero(payload.optString("message", ""))
            "CALL_UNAVAILABLE" -> {
                val hash = payload.optString("hash", "").trim()
                if (hash.isBlank()) null else Event.CallUnavailable(
                    hash = hash,
                    code = payload.optInt("code", CALL_NOT_FOUND_CODE).coerceAtLeast(0),
                )
            }
            "AUTH_EXPIRED" -> Event.AuthExpired(payload.optInt("code", 0).coerceAtLeast(0))
            "MAX_TOKEN_REJECTED" -> Event.MaxTokenRejected
            "MAX_SESSION_TOKEN" -> payload.optString("token").takeIf { it.length >= 32 }
                ?.let { Event.MaxSessionToken(it) }
            "FLOW_OWNER" -> {
                val src = payload.optString("src", "").trim()
                val dst = payload.optString("dst", "").trim()
                if (src.isEmpty() || dst.isEmpty()) null else Event.FlowOwner(
                    src = src,
                    dst = dst,
                    protocol = payload.optInt("proto", 0).coerceIn(0, 255),
                )
            }
            "NETWORK_SUSPECT" -> Event.NetworkSuspect
            "SERVER_RESTART" -> if (payload.optString("source", "") == "panel") {
                Event.ServerRestart
            } else {
                null
            }
            "CONFIG" -> Event.Config(payload.optString("config", ""))
            "LOCAL_PORT" -> payload.optInt("port", 0)
                .takeIf { it in 1..65535 }
                ?.let { Event.LocalPort(it) }
            "ROUTING" -> Event.Routing(payload.optString("routing", ""))
            "TUN_LOST" -> Event.TunLost
            "PROBE" -> Event.Probe(payload.optInt("ms", -1))
            "ROOM_SEATS" -> payload.optInt("seats", 0).takeIf { it > 0 }?.let { Event.RoomSeats(it) }
            "WORKERS" -> payload.optInt("workers", -1)
                .takeIf { it == 0 || it in MIN_SERVER_WORKERS..MrttConstants.Tunnel.MAX_WORKERS }
                ?.let { Event.Workers(it) }
            "STATS" -> Event.Stats(
                active = payload.optInt("active", 0).coerceAtLeast(0),
                bytesUp = payload.optLong("bytes_up", 0L).coerceAtLeast(0L),
                bytesDown = payload.optLong("bytes_down", 0L).coerceAtLeast(0L)
            )
            "ERROR" -> Event.Error(
                code = payload.optString("code", ""),
                message = payload.optString("message", ""),
                fatal = payload.optBoolean("fatal", false)
            )
            "CAPTCHA_REQUEST" -> Event.CaptchaRequest(
                mode = payload.optString("mode", "auto"),
                redirectUri = payload.optString("redirect_uri", ""),
                sessionToken = payload.optString("session_token", "")
            )
            "CAPTCHA_DONE" -> Event.CaptchaDone(
                success = payload.optBoolean("success", false),
                error = payload.optString("error", "").takeIf { it.isNotBlank() }
            )
            "PROGRESS" -> {
                val kind = payload.strictString("kind") ?: return null
                if (kind != "credentials") return null
                Event.Progress(kind)
            }
            else -> null
        }
    }

    private fun JSONObject.strictString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return get(key) as? String
    }

}
