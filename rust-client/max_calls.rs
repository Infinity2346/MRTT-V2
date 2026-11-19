
use crate::max_tcp;
use crate::profiles::Profile;
use crate::vk_js_calls::{
    ActiveCalls, ApiSession, Bootstrap, CallBackend, CredentialBroker, RequestSite,
    StartedCalls, check_api_error, post_form_with_origin, required_string, retry,
};
use anyhow::{Context, Result, bail};
use futures::{SinkExt, StreamExt};
use primp::Client;
use serde_json::{Value, json};
use std::{time::Duration, sync::Arc};
use tokio_tungstenite::{tungstenite::{Message, client::IntoClientRequest}};
use uuid::Uuid;

pub const MAX_API_KEY: &str = "CNHIJPLGDIHBABABA";
pub const ONEME_WS_URL: &str = "wss://ws-api.oneme.ru/websocket";
pub const MAX_APP_VERSION: &str = "26.10.3";
const WS_CONNECT_ATTEMPTS: usize = 2;
const WS_CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const WS_TOTAL_BUDGET: Duration = Duration::from_secs(10);
const WS_RETRY_DELAY: Duration = Duration::from_millis(700);
pub const MAX_CALLS_API: &str = "https://calls.okcdn.ru/fb.do";
pub const MAX_ORIGIN: &str = "https://web.max.ru";

pub(crate) async fn open_max_session(
    client: &Client,
    access_token: &str,
    device_id: &str,
) -> Result<ApiSession> {
    let call_token = fetch_call_token(access_token, device_id).await.context("MAX WS call token")?;
    let session_data = json!({
        "auth_token": call_token,
        "client_type": "SDK_JS",
        "client_version": "1.1",
        "device_id": device_id,
        "version": 3,
    })
    .to_string();
    let login = retry(3, || async {
        post_form_with_origin(
            client,
            MAX_CALLS_API,
            &[
                ("session_data", session_data.as_str()),
                ("method", "auth.anonymLogin"),
                ("format", "JSON"),
                ("application_key", MAX_API_KEY),
            ],
            RequestSite::Ok,
            MAX_ORIGIN,
        )
        .await
    })
    .await
    .context("MAX auth.anonymLogin")?;
    check_api_error(&login)?;
    // Веб-клиент MAX последовательно дёргает calls.okcdn.ru/fb.do,
    // игнорируя поле api_server из ответа — делаем так же.
    Ok(ApiSession {
        api_url: MAX_CALLS_API.to_owned(),
        session_key: required_string(&login, "session_key")?.to_owned(),
    })
}

pub async fn start(
    bootstrap: Bootstrap,
    group_ids: &[String],
    profile: &Profile,
) -> Result<StartedCalls> {
    if bootstrap.token.trim().is_empty() {
        bail!("MAX access token отсутствует");
    }
    let Some(group_id) = group_ids.first().filter(|id| !id.is_empty()) else {
        bail!("Не указан ID группы MAX (--max <group_id>)");
    };
    let client = Client::builder()
        .impersonate(profile.impersonate)
        .impersonate_os(profile.os)
        .timeout(Duration::from_secs(15))
        .connect_timeout(Duration::from_secs(8))
        .redirect(primp::redirect::Policy::limited(5))
        .dns_resolver(crate::dns::global())
        .build()
        .context("инициализация браузерного HTTP-клиента (MAX)")?;
    let access_token = bootstrap.token;
    // deviceId обязан совпадать с тем, на котором токен выдан в браузере:
    // MAX привязывает сессию к устройству, иначе LOGIN → login.cred.
    let device_id = bootstrap_device_id(&bootstrap.device_id);
    let creator_session = open_max_session(&client, &access_token, &device_id).await?;
    // MAX обменивает токен при входе: дальше нужен именно новый, иначе
    // следующий запуск получит login.cred, а calls-API — «Access token is broken».
    if let Some(fresh) = max_tcp::take_session_token() {
        crate::log_error!("[MAX] Сервер выдал обновлённый токен сессии · приложение сохранит его");
        crate::events::current().map(|bus| bus.max_session_token(&fresh));
    }

    let info = post_form_with_origin(
        &client,
        &creator_session.api_url,
        &[
            ("method", "system.getInfo"),
            ("format", "JSON"),
            ("application_key", MAX_API_KEY),
            ("session_key", creator_session.session_key.as_str()),
        ],
        RequestSite::Ok,
        MAX_ORIGIN,
    )
    .await
    .context("MAX system.getInfo")?;
    check_api_error(&info)?;
    if info.get("serverTime").is_none() {
        bail!("MAX system.getInfo: нет serverTime");
    }
    crate::log_error!("[MAX] Сессия Calls подтверждена · группа {group_id}");

    let conversation_id = Uuid::new_v4().to_string();
    let payload = json!({ "is_video": false, "chat_id": group_id }).to_string();
    let call = retry(3, || async {
        post_form_with_origin(
            &client,
            &creator_session.api_url,
            &[
                ("conversationId", conversation_id.as_str()),
                ("isVideo", "false"),
                ("protocolVersion", "5"),
                ("payload", payload.as_str()),
                ("method", "vchat.startConversation"),
                ("format", "JSON"),
                ("application_key", MAX_API_KEY),
                ("session_key", creator_session.session_key.as_str()),
            ],
            RequestSite::Ok,
            MAX_ORIGIN,
        )
        .await
    })
    .await;
    // Как референс-экстрактор: startConversation у MAX часто отвечает VCHAT_DETAILED_ERROR,
    // но TURN выдаёт getConversationParams по локально сгенерированному conversationId —
    // поэтому ошибку старта не считаем фатальной и продолжаем со своим conversationId.
    let final_conversation_id = match call {
        Ok(value) => match check_api_error(&value) {
            Ok(()) => value
                .get("id")
                .or_else(|| value.get("conversationId"))
                .and_then(Value::as_str)
                .filter(|id| id.len() == 36)
                .map(str::to_owned)
                .unwrap_or_else(|| conversation_id.clone()),
            Err(error) => {
                // Штатный ответ MAX (обычно API 1114): пишем без текста ошибки, иначе UI красит строку как сбой.
                let code = error.to_string();
                let code = code.split(':').next().unwrap_or("API").trim().to_owned();
                crate::log_info!("[MAX] Звонок без startConversation ({code}) · TURN по локальному conversationId");
                conversation_id.clone()
            }
        },
        Err(error) => {
            crate::log_error!("[MAX] vchat.startConversation не ответил: {error:#} · продолжаю с локальным conversationId");
            conversation_id.clone()
        }
    };
    crate::log_error!("[MAX] Звонок создан: {final_conversation_id}");
    let credential_broker = Arc::new(CredentialBroker::from_parts(
        client.clone(),
        access_token.clone(),
        final_conversation_id.clone(),
        CallBackend::Max,
    ));
    let active = ActiveCalls::from_parts(
        client, creator_session.api_url, access_token, creator_session.session_key,
        vec![final_conversation_id], CallBackend::Max,
    );
    Ok(StartedCalls {
        hashes: vec!["max".to_owned()],
        active,
        credential_broker,
    })
}

async fn fetch_call_token(access_token: &str, device_id: &str) -> Result<String> {
    // Токен веб-сессии приложение-протокол не принимает (`login.cred`), а
    // именно его отдаёт привязка через web.max.ru. Раньше TCP шёл первым,
    // съедал время и «выигрывал» гонку своим отказом — теперь с таким токеном
    // сразу идём по WS, где он и работает.
    if max_tcp::is_web_session_token(access_token) {
        crate::log_error!("[MAX] Токен веб-сессии: используем web-транспорт (WS)");
        return fetch_call_token_ws(access_token, device_id)
            .await
            .context("MAX call token (web)");
    }
    match max_transport_preference() {
        Some(MaxTransport::Tcp) => {
            return max_tcp::fetch_call_token(access_token, device_id)
                .await
                .context("MAX call token (MRTT_MAX_TRANSPORT=tcp)");
        }
        Some(MaxTransport::Ws) => {
            return fetch_call_token_ws(access_token, device_id)
                .await
                .context("MAX call token (MRTT_MAX_TRANSPORT=ws)");
        }
        None => {}
    }
    tokio::select! {
        biased;
        result = max_tcp::fetch_call_token(access_token, device_id) => match result {
            Ok(token) => {
                // Токен заново берётся на каждую TURN-сессию: писать об этом
                // каждый раз — значит повторять одну строку десятки раз.
                static FIRST_TOKEN: std::sync::atomic::AtomicBool =
                    std::sync::atomic::AtomicBool::new(true);
                if FIRST_TOKEN.swap(false, std::sync::atomic::Ordering::AcqRel) {
                    crate::log_error!("[MAX] call token получен по TCP-протоколу приложения");
                }
                Ok(token)
            }
            Err(tcp_error) => {
                crate::log_error!("[MAX] Транспорт приложения не сработал: {tcp_error:#}");
                fetch_call_token_ws(access_token, device_id).await.map_err(|ws_error| {
                    anyhow::anyhow!(
                        "MAX call token: не удалось ни по TCP, ни по WS ({tcp_error:#}; {ws_error:#})"
                    )
                })
            }
        },
        result = fetch_call_token_ws(access_token, device_id) => match result {
            Ok(token) => Ok(token),
            Err(ws_error) => {
                crate::log_error!("[MAX] Web-транспорт недоступен: {ws_error:#}");
                max_tcp::fetch_call_token(access_token, device_id).await.map_err(|tcp_error| {
                    anyhow::anyhow!(
                        "MAX call token: не удалось ни по WS, ни по TCP ({ws_error:#}; {tcp_error:#})"
                    )
                })
            }
        },
    }
}

fn bootstrap_device_id(from_bootstrap: &str) -> String {
    let trimmed = from_bootstrap.trim();
    if !trimmed.is_empty() && trimmed.len() <= 64 && !trimmed.chars().any(char::is_whitespace) {
        return trimmed.to_owned();
    }
    crate::log_error!(
        "[MAX] Привязка без deviceId · сервер MAX может отклонить токен (login.cred): войдите в MAX заново, чтобы привязать заново"
    );
    Uuid::new_v4().to_string()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum MaxTransport {
    Ws,
    Tcp,
}

fn max_transport_preference() -> Option<MaxTransport> {
    parse_transport(&std::env::var("MRTT_MAX_TRANSPORT").ok()?)
}

fn parse_transport(raw: &str) -> Option<MaxTransport> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "tcp" => Some(MaxTransport::Tcp),
        "ws" | "websocket" => Some(MaxTransport::Ws),
        _ => None,
    }
}

async fn fetch_call_token_ws(access_token: &str, device_id: &str) -> Result<String> {
    // WS MAX — единственное место, где нужен живой web.max.ru. При звонке, смене
    // сети или на слабом канале соединение не успевает за 10 с, и раньше это
    // уходило в [ФАТАЛ] «MAX WS connect timeout: deadline has elapsed»: клиент
    // завершался с кодом 1, приложение перезапускало его, и так по кругу.
    // Теперь это обычная временная ошибка: несколько попыток с паузой, и только
    // полный отказ (все попытки) считается настоящей поломкой.
    let connect_timeout = mrtt_env_duration("MRTT_MAX_WS_TIMEOUT_SECS")
        .map(Duration::from_secs)
        .unwrap_or(WS_CONNECT_TIMEOUT);
    let mut last_error: Option<anyhow::Error> = None;
    for attempt in 1..=WS_CONNECT_ATTEMPTS {
        match connect_ws(connect_timeout).await {
            Ok(stream) => return finish_ws_login(stream, access_token, device_id).await,
            Err(error) => {
                crate::log_error!(
                    "[MAX][WS] Попытка {attempt}/{WS_CONNECT_ATTEMPTS} не удалась: {error:#}"
                );
                last_error = Some(error);
                if attempt < WS_CONNECT_ATTEMPTS {
                    tokio::time::sleep(WS_RETRY_DELAY * attempt as u32).await;
                }
            }
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("MAX WS: нет соединения")))
        .context("MAX WS call token")
}

fn mrtt_env_duration(name: &str) -> Option<u64> {
    std::env::var(name).ok()?.trim().parse::<u64>().ok().filter(|value| *value > 0)
}

async fn connect_ws(
    timeout: Duration,
) -> Result<
    tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
> {
    // Раньше здесь был один адрес и один системный резолвер. Живой телефон
    // показал `Connection timed out (os error 110)`: адрес находился (в том
    // числе IPv6, до которого у оператора нет маршрута), и все попытки висели
    // на первом же адресе. Теперь: IPv4 вперёд, свой адресный лист, короткий
    // таймаут на каждую попытку и, если WS недоступен совсем, — резервный
    // транспорт приложения MAX (`api.oneme.ru:443`).
    let request = ws_request()?;
    let mut last_error: Option<anyhow::Error> = None;
    let budget_deadline = tokio::time::Instant::now() + WS_TOTAL_BUDGET;
    for host in WS_HOSTS {
        if tokio::time::Instant::now() >= budget_deadline {
            last_error = Some(anyhow::anyhow!("WS: исчерпан общий бюджет адресов"));
            break;
        }
        match resolve_host(host).await {
            Ok(addresses) if !addresses.is_empty() => {
                let ordered = prefer_ipv4(addresses);
                crate::log_error!("[MAX][WS] {host} → {ordered:?}");
                for address in ordered {
                    if tokio::time::Instant::now() >= budget_deadline {
                        break;
                    }
                    let step = timeout.min(budget_deadline - tokio::time::Instant::now());
                    match connect_ws_at(&request, address, step).await {
                        Ok(stream) => return Ok(stream),
                        Err(error) => {
                            // Адрес первым: приложение фильтрует такие строки
                            // как перебор, а не как ошибку.
                            crate::log_error!("[MAX][WS] {address} ({host}): {error:#}");
                            last_error = Some(error);
                        }
                    }
                }
            }
            Ok(_) => {
                last_error = Some(anyhow::anyhow!("{host}: DNS не дал адресов"));
            }
            Err(error) => {
                crate::log_error!("[MAX][WS] DNS {host}: {error:#}");
                last_error = Some(error);
            }
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("WS: нет ни адресов, ни соединения")))
        .context("WS подключение ws-api.oneme.ru")
}

fn ws_request() -> Result<tokio_tungstenite::tungstenite::handshake::client::Request> {
    let mut request = ONEME_WS_URL.into_client_request()
        .context("MAX WebSocket request")?;
    request.headers_mut().insert("Origin", "https://web.max.ru".parse()?);
    request
        .headers_mut()
        .insert("User-Agent", "Mozilla/5.0 Chrome/144.0.0.0 Safari/537.36".parse()?);
    Ok(request)
}

async fn connect_ws_at(
    request: &tokio_tungstenite::tungstenite::handshake::client::Request,
    address: std::net::IpAddr,
    timeout: Duration,
) -> Result<
    tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
> {
    let deadline = tokio::time::Instant::now() + timeout;
    let tcp = tokio::time::timeout_at(deadline, tokio::net::TcpStream::connect((address, 443)))
        .await
        .with_context(|| format!("таймаут TCP к {address}:443"))?
        .with_context(|| format!("TCP к {address}:443"))?;
    let _ = tcp.set_nodelay(true);
    let (stream, _) = tokio::time::timeout_at(
        deadline,
        tokio_tungstenite::client_async_tls_with_config(request.clone(), tcp, None, None),
    )
    .await
    .with_context(|| format!("таймаут TLS к {address}:443"))?
    .with_context(|| format!("TLS/WebSocket к {address}:443"))?;
    Ok(stream)
}

fn prefer_ipv4(addresses: Vec<std::net::IpAddr>) -> Vec<std::net::IpAddr> {
    let mut sorted = addresses;
    sorted.sort_by_key(|address| if address.is_ipv4() { 0 } else { 1 });
    sorted.dedup();
    sorted
}

const WS_HOSTS: [&str; 2] = ["ws-api.oneme.ru", "ws-api.max.ru"];

async fn resolve_host(host: &str) -> Result<Vec<std::net::IpAddr>> {
    crate::dns::global()
        .lookup(host)
        .await
        .with_context(|| format!("DNS для {host}"))
}

async fn finish_ws_login(
    stream: tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
    access_token: &str,
    device_id: &str,
) -> Result<String> {
    let (mut sink, mut reader) = stream.split();

    let device_id = if device_id.trim().is_empty() {
        Uuid::new_v4().to_string()
    } else {
        device_id.trim().to_owned()
    };
    let init = json!({
        "ver": 11, "cmd": 0, "seq": 1, "opcode": 6,
        "payload": {
            "deviceId": device_id,
            "userAgent": {
                "deviceType": "WEB",
                "pushDeviceType": "WEBPUSH",
                "locale": "ru",
                "deviceLocale": "ru",
                "osVersion": "Windows",
                "deviceName": "Chrome",
                "headerUserAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
                "appVersion": MAX_APP_VERSION,
                "screen": "1920x1080 1.0x",
                "timezone": "Europe/Moscow",
            }
        }
    });
    sink.send(Message::Text(init.to_string().into()))
        .await
        .context("WS send INIT")?;

    let mut seq = 1u64;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    loop {
        let msg = tokio::time::timeout_at(deadline, reader.next())
            .await
            .context("WS таймаут oneme")?
            .context("WS oneme закрыт")?
            .context("WS oneme ошибка потока")?;
        let text: &str = match &msg {
            Message::Text(t) => t.as_ref(),
            Message::Close(_) => bail!("WS oneme закрыт сервером до ответа 158"),
            _ => continue,
        };
        let m: Value = serde_json::from_str(text).context("WS oneme: некорректный JSON")?;
        let cmd = m.get("cmd").and_then(Value::as_i64).unwrap_or(-1);
        if cmd == 0 {
            continue;
        }
        if cmd == 3 {
            // Имя ошибки в начале строки: по нему клиент отличает «токен не
            // принят» от сетевой поломки.
            let name = m
                .pointer("/payload/error")
                .and_then(Value::as_str)
                .unwrap_or("unknown");
            if name == "login.cred" {
                bail!("MAX WS: token rejected (login.cred): {:?}", m.get("payload"));
            }
            bail!("WS oneme ошибка: {:?}", m.get("payload"));
        }
        if cmd != 1 {
            continue;
        }
        match m.get("opcode").and_then(Value::as_i64).unwrap_or(-1) {
            6 => {
                seq += 1;
                let login = json!({
                    "ver": 11, "cmd": 0, "seq": seq, "opcode": 19,
                    "payload": {
                        "token": access_token,
                        "interactive": true,
                        "chatsCount": 0, "chatsSync": 0, "contactsSync": 0,
                        "presenceSync": 0, "draftsSync": 0,
                    }
                });
                sink.send(Message::Text(login.to_string().into()))
                    .await
                    .context("WS send LOGIN")?;
            }
            19 => {
                seq += 1;
                let req = json!({ "ver": 11, "cmd": 0, "seq": seq, "opcode": 158, "payload": {} });
                sink.send(Message::Text(req.to_string().into()))
                    .await
                    .context("WS send 158")?;
            }
            158 => {
                return m
                    .pointer("/payload/token")
                    .and_then(Value::as_str)
                    .map(str::to_owned)
                    .context("WS oneme 158: нет payload.token");
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn browser_device_id_is_reused_for_the_max_session() {
        // Токен MAX привязан к устройству: чужой deviceId → login.cred.
        assert_eq!(bootstrap_device_id(" 10984d3d-ac32  "), "10984d3d-ac32");
        let generated = bootstrap_device_id("");
        assert!(Uuid::parse_str(&generated).is_ok());
    }

    #[test]
    fn max_api_key_matches_extractor() {
        assert_eq!(MAX_API_KEY, "CNHIJPLGDIHBABABA");
        assert!(MAX_CALLS_API.ends_with("/fb.do"));
        assert!(ONEME_WS_URL.starts_with("wss://ws-api.oneme.ru"));
    }

    #[test]
    fn websocket_hosts_start_with_the_web_endpoint() {
        // Первым идёт тот же хост, что у web.max.ru; вторым — зеркало для
        // случая, когда оператор режет именно `ws-api.oneme.ru`.
        assert_eq!(WS_HOSTS[0], "ws-api.oneme.ru");
        assert!(WS_HOSTS.iter().all(|host| host.ends_with(".ru")));
    }

    #[test]
    fn ipv4_addresses_are_tried_before_ipv6() {
        // Ровно этот порядок спасает от `Connection timed out (os error 110)`:
        // IPv6 у оператора есть в DNS, но маршрута до него нет.
        let ordered = prefer_ipv4(vec![
            "2a00:1450:4010:c0e::64".parse().expect("IPv6"),
            "155.212.204.70".parse().expect("IPv4"),
        ]);
        assert!(ordered[0].is_ipv4());
        assert!(ordered[1].is_ipv6());
    }

    #[test]
    fn transport_preference_is_parsed_from_env_value() {
        // `MRTT_MAX_TRANSPORT=tcp` — принудительный путь приложения; в остальных
        // случаях поведение по умолчанию: web, затем приложение.
        assert_eq!(parse_transport("TCP"), Some(MaxTransport::Tcp));
        assert_eq!(parse_transport(" ws "), Some(MaxTransport::Ws));
        assert_eq!(parse_transport("websocket"), Some(MaxTransport::Ws));
        assert_eq!(parse_transport("auto"), None);
    }
}