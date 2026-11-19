//! Резервный транспорт MAX: TCP + TLS + MessagePack (ver=10).
//!
//! У MAX два входа в один и тот же сигналинг:
//!   1. `wss://ws-api.oneme.ru/websocket` — JSON, ver=11, то, чем ходит web.max.ru;
//!   2. `api.oneme.ru:443` — сырой TLS, 10-байтный заголовок + MessagePack, ver=10,
//!      то, чем ходят официальные приложения.
//!
//! На живом телефоне web-хост не открывался вовсе (`Connection timed out`),
//! хотя адрес находился: оператор резал именно `ws-api`. Этот модуль повторяет
//! второй путь и отдаёт тот же `call_token` (opcode 158), что и WS-вариант.
//! Никаких новых зависимостей: TLS уже есть (`turn-rustls`), а MessagePack
//! кодируется и разбирается здесь же — нужен маленький срез формата.

use anyhow::{Context, Result, bail};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use turn_rustls::{ClientConfig, RootCertStore, pki_types::ServerName};
use turn_tokio_rustls::TlsConnector;
use uuid::Uuid;

pub const MAX_TCP_HOST: &str = "api.oneme.ru";
const MAX_TCP_PORT: u16 = 443;
/// Версия протокола приложений. Web-протокол — 11, приложения — 10.
const PROTOCOL_VERSION: u8 = 10;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
const STEP_TIMEOUT: Duration = Duration::from_secs(15);
/// Кадр больше этого не бывает: защита от мусора в заголовке.
const MAX_FRAME_BYTES: usize = 4 * 1024 * 1024;
const MAX_DECODED_BYTES: usize = 8 * 1024 * 1024;

trait Io: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Io for T {}

/// Готовая сессия: TLS-поток плюс счётчик `seq`.
struct Session {
    stream: Box<dyn Io>,
    seq: u16,
}

impl Session {
    /// Отправить запрос: заголовок ver=10 + MessagePack-payload.
    async fn send(&mut self, opcode: u16, payload: Vec<u8>) -> Result<()> {
        self.seq = self.seq.wrapping_add(1);
        let mut frame = Vec::with_capacity(10 + payload.len());
        frame.push(PROTOCOL_VERSION);
        frame.push(0); // cmd = запрос
        frame.extend_from_slice(&self.seq.to_be_bytes());
        frame.extend_from_slice(&opcode.to_be_bytes());
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(&payload);
        self.stream
            .write_all(&frame)
            .await
            .context("MAX TCP: запись кадра")?;
        self.stream.flush().await.context("MAX TCP: flush")?;
        Ok(())
    }

    /// Дождаться ответа `cmd=1` на `opcode`. Сервер присылает и push-сообщения
    /// (`cmd=0`), и ошибки (`cmd=3`) — их пропускаем либо отдаём как ошибку.
    async fn wait_for(&mut self, opcode: u16) -> Result<Mp> {
        let deadline = tokio::time::Instant::now() + STEP_TIMEOUT;
        let mut skipped = 0usize;
        loop {
            let frame = tokio::time::timeout_at(deadline, self.read_frame())
                .await
                .with_context(|| format!("MAX TCP: таймаут ответа на opcode {opcode}"))?;
            let (cmd, resp_opcode, payload) = match frame {
                Ok(frame) => frame,
                // Пропускаем ТОЛЬКО ошибку разбора кадра (payload не сложился).
                // Ошибку транспорта (early eof, сокет закрыт, таймаут) глотать
                // нельзя: соединения уже нет, и цикл крутился бы бесконечно,
                // заливая журнал одной и той же строкой.
                Err(error) if is_decode_error(&error) => {
                    skipped = skipped.saturating_add(1);
                    if skipped > MAX_SKIPPED_FRAMES {
                        return Err(error).with_context(|| {
                            format!("MAX TCP: слишком много нечитаемых кадров подряд ({skipped})")
                        });
                    }
                    if skipped <= 3 {
                        crate::log_error!("[MAX][TCP] пропускаем нечитаемый кадр: {error:#}");
                    }
                    continue;
                }
                Err(error) => {
                    return Err(error)
                        .with_context(|| format!("MAX TCP: соединение потеряно на opcode {opcode}"));
                }
            };
            match cmd {
                3 => {
                    // Имя ошибки вытаскиваем в начало строки: по нему клиент
                    // отличает «токен не принят» от сетевой поломки.
                    let name = payload
                        .get("error")
                        .and_then(Mp::as_str)
                        .unwrap_or("unknown");
                    if name == "login.cred" {
                        bail!(
                            "MAX TCP: token rejected (login.cred) на opcode {resp_opcode}: {}",
                            payload.describe()
                        );
                    }
                    bail!(
                        "MAX TCP: сервер вернул ошибку на opcode {resp_opcode}: {}",
                        payload.describe()
                    );
                }
                1 if resp_opcode == OPCODE_LOGOUT => {
                    // Opcode 20 в приложении-протоколе приходит пустым сразу
                    // после LOGIN. Не валим сессию (в разных сборках это либо
                    // logout, либо служебный ack), но пишем: по этой строке
                    // видно, что сервер закрыл сессию, и тогда 158 честно
                    // ответит `proto.state: Must be ONLINE session`.
                    crate::log_error!(
                        "[MAX][TCP] сервер прислал opcode 20 (пустой) · состояние сессии сброшено"
                    );
                    continue;
                }
                1 if resp_opcode == opcode => return Ok(payload),
                _ => {
                    // Чужие кадры тоже не бесконечны: сервер может завалить
                    // push-сообщениями, и ожидание нужного ответа не должно
                    // превращаться в вечный цикл.
                    skipped = skipped.saturating_add(1);
                    if skipped > MAX_SKIPPED_FRAMES * 4 {
                        bail!("MAX TCP: нужный ответ на opcode {opcode} не пришёл за {} кадров", skipped);
                    }
                    continue;
                }
            }
        }
    }

    async fn read_frame(&mut self) -> Result<(u8, u16, Mp)> {
        let mut header = [0u8; 10];
        self.stream
            .read_exact(&mut header)
            .await
            .context("MAX TCP: чтение заголовка")?;
        let cmd = header[1];
        let opcode = u16::from_be_bytes([header[4], header[5]]);
        let packed = u32::from_be_bytes([header[6], header[7], header[8], header[9]]);
        let flag = (packed >> 24) as u8;
        let compressed = flag != 0;
        let length = (packed & 0x00FF_FFFF) as usize;
        if length > MAX_FRAME_BYTES {
            bail!("MAX TCP: кадр {length} байт больше предела");
        }
        let mut payload = vec![0u8; length];
        if length > 0 {
            self.stream
                .read_exact(&mut payload)
                .await
                .context("MAX TCP: чтение payload")?;
        }
        // Полный журнал шагов MAX: видно каждый кадр, его флаг сжатия и размер.
        // Без этого «LZ4 неверное смещение» невозможно было привязать к opcode.
        crate::log_error!(
            "[MAX][TCP] ← cmd={cmd} opcode={opcode} флаг=0x{flag:02x} {}Б",
            payload.len()
        );
        // Пустой payload — норма: сервер так подтверждает часть запросов
        // (например opcode 20 после LOGIN). Раньше разбор на нуле байт падал
        // «MessagePack оборван» и убивал всю сессию уже после успешного входа.
        let message = if payload.is_empty() {
            Mp::Map(Vec::new())
        } else {
            decode_frame(&payload, flag).map_err(|error| {
                // Помечаем именно ошибку разбора: транспортные ошибки к этому
                // моменту уже позади, и путать их нельзя.
                anyhow::Error::new(DecodeError).context(format!(
                    "MAX TCP: распаковка opcode {opcode} (флаг 0x{flag:02x}, {}Б): {error:#}",
                    payload.len()
                ))
            })?
        };
        Ok((cmd, opcode, message))
    }
}

/// Токен, который выдаёт web.max.ru, — это токен ВЕБ-сессии.
///
/// Приложение MAX (`api.oneme.ru`, ver=10) проверяет токен иначе и на веб-токен
/// отвечает `login.cred` / `FAIL_WRONG_PASSWORD`, хотя сам токен живой: ровно
/// эта пара и приходила с телефона («MAX привязан», а потом отказ). Поэтому
/// веб-токен в приложение-протокол не отправляем вовсе — честно говорим, что
/// нужен другой транспорт, а не крутим отказ как сетевую ошибку.
pub(crate) fn is_web_session_token(token: &str) -> bool {
    // Токены приложения MAX заметно длиннее веб-сессионных (в них больше
    // 96 символов). Разделение только по длине: формату сервера не верим,
    // но заведомо короткий токен в приложение-протокол отправлять нельзя —
    // он гарантированно получит `login.cred`.
    token.trim().len() < 96
}

/// Получить `call_token` через приложение-протокол MAX (TCP + TLS + MessagePack).
pub async fn fetch_call_token(access_token: &str, device_id: &str) -> Result<String> {
    if access_token.trim().is_empty() {
        bail!("MAX TCP: пустой access token");
    }
    if is_web_session_token(access_token) {
        crate::log_error!(
            "[MAX][TCP] Токен от web.max.ru не подходит приложению-протоколу · остаёмся на web-пути"
        );
        bail!("MAX TCP: token rejected (login.cred): web-токен не принимается приложением-протоколом");
    }
    // deviceId — тот же, на котором токен выдан в браузере: иначе INIT/LOGIN
    // выглядят как новое устройство и сервер отвечает login.cred.
    let device_id = if device_id.trim().is_empty() {
        Uuid::new_v4().to_string()
    } else {
        device_id.trim().to_owned()
    };

    // Два прохода: если сервер обменял токен в ответе LOGIN, повторяем вход
    // уже с новым токеном. Ровно это и делает приложение: старый токен после
    // обмена недействителен (`login.cred`, «Access token is broken»), а 158
    // отдаётся только живой («онлайн») сессии.
    let mut token = access_token.trim().to_owned();
    let mut last_error: Option<anyhow::Error> = None;
    for attempt in 1..=2 {
        let mut session = connect().await?;
        match session_login(&mut session, &token, &device_id).await {
            Ok(LoginOutcome { session_token, call_token }) => {
                let exchanged = session_token
                    .as_ref()
                    .is_some_and(|fresh| fresh != &token);
                if let Some(fresh) = session_token.as_deref() {
                    publish_session_token(fresh);
                }
                // Сначала отдаём то, что просили: отдельный токен для звонков.
                if let Some(call_token) = call_token {
                    return Ok(call_token);
                }
                // Сервер обменял токен — повторяем вход с новым, как приложение.
                if exchanged && attempt == 1 {
                    crate::log_error!(
                        "[MAX][TCP] сервер обменял токен сессии · повторяем вход с новым"
                    );
                    token = session_token.unwrap_or(token);
                    last_error = Some(anyhow::anyhow!(
                        "MAX TCP: после обмена токена сессия не дала call token"
                    ));
                    continue;
                }
                // Отдельного токена нет — из приложения не выйти «наполовину»:
                // подставлять сюда токен сессии нельзя, calls-API отвечает на
                // него `AUTH_LOGIN: Access token is broken` и клиент падает.
                // Честно говорим, что приложение-протокол токен не выдал.
                last_error = Some(anyhow::anyhow!(
                    "MAX TCP: сервер не выдал токен для звонков (158)"
                ));
            }
            Err(error) => {
                crate::log_error!("[MAX][TCP] попытка {attempt}/2: {error:#}");
                last_error = Some(error);
            }
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("MAX TCP: вход не выполнен")))
}

struct LoginOutcome {
    /// Новый токен сессии, если сервер его выдал.
    session_token: Option<String>,
    /// Токен для calls-API (opcode 158), если сервер его выдал.
    call_token: Option<String>,
}

/// INIT → LOGIN → LOGIN2 → 158 в одной открытой сессии.
async fn session_login(session: &mut Session, token: &str, device_id: &str) -> Result<LoginOutcome> {
    session
        .send(OPCODE_HELLO, hello_payload(device_id))
        .await
        .context("MAX TCP: отправка INIT")?;
    session.wait_for(OPCODE_HELLO).await.context("MAX TCP: INIT")?;

    session
        .send(OPCODE_LOGIN, login_payload(token))
        .await
        .context("MAX TCP: отправка LOGIN")?;
    let login = session.wait_for(OPCODE_LOGIN).await.context("MAX TCP: LOGIN")?;
    let session_token = login_token(&login);

    // LOGIN2 (8) — штатный второй шаг приложения: без него сессия не
    // считается «онлайн», и 158 отвечает `proto.state: Must be ONLINE session`.
    session
        .send(OPCODE_LOGIN2, empty_map())
        .await
        .context("MAX TCP: отправка LOGIN2")?;
    match session.wait_for(OPCODE_LOGIN2).await {
        Ok(_) => {}
        Err(error) => crate::log_error!("[MAX][TCP] LOGIN2 не подтверждён: {error:#}"),
    }

    session
        .send(OPCODE_CALL_TOKEN, empty_map())
        .await
        .context("MAX TCP: отправка 158")?;
    let call_token = match session.wait_for(OPCODE_CALL_TOKEN).await {
        Ok(payload) => {
            let token = payload
                .get("token")
                .and_then(Mp::as_str)
                .map(str::to_owned);
            if token.is_none() {
                crate::log_error!(
                    "[MAX][TCP] в ответе 158 нет поля token · пришло: {}",
                    payload.describe()
                );
            }
            token
        }
        Err(error) => {
            crate::log_error!("[MAX][TCP] 158 не выдал токен: {error:#}");
            None
        }
    };
    Ok(LoginOutcome { session_token, call_token })
}

/// Токен из ответа LOGIN: `tokenAttrs.LOGIN.token` (так его отдаёт SMS/QR-вход)
/// либо плоское `token`.
fn login_token(message: &Mp) -> Option<String> {
    let nested = message
        .get("tokenAttrs")
        .and_then(|attrs| attrs.get("LOGIN"))
        .and_then(|login| login.get("token"))
        .and_then(Mp::as_str);
    nested
        .or_else(|| message.get("token").and_then(Mp::as_str))
        .map(str::trim)
        .filter(|token| token.len() >= 16)
        .map(str::to_owned)
}

/// Новый токен сессии, который MAX выдал в ответе LOGIN.
///
/// Сервер меняет токен при первом входе: старый после этого недействителен
/// (`login.cred`), а calls-бэкенд отвечает `AUTH_LOGIN: Access token is broken`.
/// Приложение должно сохранить этот токен и ходить дальше с ним.
pub fn take_session_token() -> Option<String> {
    SESSION_TOKEN.lock().ok().and_then(|mut slot| slot.take())
}

fn publish_session_token(token: &str) {
    if let Ok(mut slot) = SESSION_TOKEN.lock() {
        *slot = Some(token.to_owned());
    }
}

static SESSION_TOKEN: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

const OPCODE_HELLO: u16 = 6;
const OPCODE_LOGIN: u16 = 19;
/// Второй шаг входа приложения: переводит сессию в рабочее («онлайн») состояние.
const OPCODE_LOGIN2: u16 = 8;
/// Кадр не разобрался как MessagePack (не транспортная поломка).
#[derive(Debug)]
struct DecodeError;

impl std::fmt::Display for DecodeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "нечитаемый кадр")
    }
}

impl std::error::Error for DecodeError {}

fn is_decode_error(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| cause.is::<DecodeError>())
}

/// Сколько нечитаемых кадров подряд терпим, прежде чем считать соединение мёртвым.
const MAX_SKIPPED_FRAMES: usize = 8;

const OPCODE_CALL_TOKEN: u16 = 158;
/// Сервер завершил сессию: так MAX отвечает, когда тот же токен уже занят
/// другим клиентом (например, открытой web-сессией).
const OPCODE_LOGOUT: u16 = 20;

async fn connect() -> Result<Session> {
    let addresses = crate::dns::global()
        .lookup(MAX_TCP_HOST)
        .await
        .with_context(|| format!("MAX TCP: DNS {MAX_TCP_HOST}"))?;
    if addresses.is_empty() {
        bail!("MAX TCP: DNS {MAX_TCP_HOST} не дал адресов");
    }
    // IPv4 первыми: IPv6 у оператора часто «есть», но маршрута нет, и соединение
    // висит до таймаута — это и был `os error 110` на живом телефоне.
    let mut ordered = addresses;
    ordered.sort_by_key(|address| if address.is_ipv4() { 0 } else { 1 });
    ordered.dedup();

    let connector = TlsConnector::from(tls_config());
    let mut last_error: Option<anyhow::Error> = None;
    for address in ordered {
        let attempt = async {
            let socket = tokio::time::timeout(
                CONNECT_TIMEOUT,
                TcpStream::connect((address, MAX_TCP_PORT)),
            )
            .await
            .with_context(|| format!("MAX TCP: таймаут TCP к {address}"))?
            .with_context(|| format!("MAX TCP: TCP к {address}"))?;
            let _ = socket.set_nodelay(true);
            let server_name = ServerName::try_from(MAX_TCP_HOST.to_string())
                .context("MAX TCP: некорректное имя для TLS")?;
            tokio::time::timeout(
                CONNECT_TIMEOUT,
                connector.connect(server_name, socket),
            )
            .await
            .with_context(|| format!("MAX TCP: таймаут TLS к {address}"))?
            .with_context(|| format!("MAX TCP: TLS к {address}"))
        }
        .await;
        match attempt {
            Ok(stream) => {
                crate::log_error!("[MAX][TCP] {address} ({MAX_TCP_HOST}): соединение открыто");
                return Ok(Session {
                    stream: Box::new(stream),
                    seq: 0,
                });
            }
            Err(error) => {
                crate::log_error!("[MAX][TCP] {address} ({MAX_TCP_HOST}): {error:#}");
                last_error = Some(error);
            }
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("MAX TCP: нет адресов")))
        .context("MAX TCP: подключение к api.oneme.ru")
}

fn tls_config() -> std::sync::Arc<ClientConfig> {
    static CONFIG: std::sync::OnceLock<std::sync::Arc<ClientConfig>> =
        std::sync::OnceLock::new();
    CONFIG
        .get_or_init(|| {
            let mut roots = RootCertStore::empty();
            roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
            std::sync::Arc::new(
                ClientConfig::builder()
                    .with_root_certificates(roots)
                    .with_no_client_auth(),
            )
        })
        .clone()
}

/// SESSION_INIT для приложения-протокола. Порядок и набор полей не такие
/// вольные, как в web-версии: приложение присылает профиль Android, включая
/// `clientSessionId` (время в миллисекундах) и `arch`. Поля идут в том же
/// порядке, что у приложения: поддельный отпечаток должен быть правдоподобным.
/// Отпечаток устройства для INIT.
///
/// Тип устройства обязан совпадать с тем, где выдан токен: привязка идёт
/// через web.max.ru, поэтому приложение-протокол представляется WEB-клиентом
/// (тот же набор полей, что у браузера). С отпечатком ANDROID/GCM сервер
/// принимал LOGIN, сразу присылал `opcode 20` (закрытие сессии) и на 158
/// отвечал `proto.state: Must be ONLINE session`.
fn hello_payload(device_id: &str) -> Vec<u8> {
    let mut out = Vec::new();
    map(&mut out, 3);
    str_value(&mut out, "userAgent");
    map(&mut out, 11);
    for (key, value) in [
        ("deviceType", "WEB"),
        ("pushDeviceType", "WEBPUSH"),
        ("appVersion", crate::max_calls::MAX_APP_VERSION),
        ("osVersion", "Android"),
        ("locale", "ru"),
        ("deviceLocale", "ru"),
        ("deviceName", "Chrome"),
        ("headerUserAgent", WEB_USER_AGENT),
        ("screen", "1080x1920 1.0x"),
        ("timezone", "Europe/Moscow"),
        ("networkType", "wifi"),
    ] {
        str_value(&mut out, key);
        str_value(&mut out, value);
    }
    str_value(&mut out, "deviceId");
    str_value(&mut out, device_id);
    str_value(&mut out, "clientSessionId");
    int_value(&mut out, now_millis());
    out
}

/// Web-клиент MAX: тот же User-Agent, под которым работает web.max.ru.
const WEB_USER_AGENT: &str =
    "Mozilla/5.0 (Linux; Android 15; CPH2645) AppleWebKit/537.36 (KHTML, like Gecko) \
     Chrome/144.0.0.0 Mobile Safari/537.36";

/// ABI устройства: сервер сверяет его с `arch` в отпечатке.
#[cfg(target_arch = "aarch64")]
const ANDROID_ARCH: &str = "arm64-v8a";
#[cfg(target_arch = "arm")]
const ANDROID_ARCH: &str = "armeabi-v7a";
#[cfg(target_arch = "x86_64")]
const ANDROID_ARCH: &str = "x86_64";
#[cfg(not(any(target_arch = "aarch64", target_arch = "arm", target_arch = "x86_64")))]
const ANDROID_ARCH: &str = "arm64-v8a";

fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

fn login_payload(access_token: &str) -> Vec<u8> {
    let mut out = Vec::new();
    map(&mut out, 7);
    str_value(&mut out, "token");
    str_value(&mut out, access_token);
    str_value(&mut out, "interactive");
    out.push(0xc3);
    for key in [
        "chatsCount",
        "chatsSync",
        "contactsSync",
        "presenceSync",
        "draftsSync",
    ] {
        str_value(&mut out, key);
        int_value(&mut out, 0);
    }
    out
}

fn empty_map() -> Vec<u8> {
    let mut out = Vec::new();
    map(&mut out, 0);
    out
}

// ── MessagePack: минимальный кодировщик ─────────────────────────────────────

fn map(out: &mut Vec<u8>, entries: usize) {
    if entries < 16 {
        out.push(0x80 | entries as u8);
    } else {
        out.push(0xde);
        out.extend_from_slice(&(entries as u16).to_be_bytes());
    }
}

/// Кодирование массивов нужно только тестам: запросы MAX — плоские карты.
#[cfg(test)]
fn array(out: &mut Vec<u8>, entries: usize) {
    if entries < 16 {
        out.push(0x90 | entries as u8);
    } else {
        out.push(0xdc);
        out.extend_from_slice(&(entries as u16).to_be_bytes());
    }
}

fn str_value(out: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    if bytes.len() < 32 {
        out.push(0xa0 | bytes.len() as u8);
    } else if bytes.len() <= u8::MAX as usize {
        out.push(0xd9);
        out.push(bytes.len() as u8);
    } else {
        out.push(0xda);
        out.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
    }
    out.extend_from_slice(bytes);
}

fn int_value(out: &mut Vec<u8>, value: i64) {
    if value >= 0 && value < 128 {
        out.push(value as u8);
    } else if value >= -32 && value < 0 {
        out.push(value as i8 as u8);
    } else if value >= i8::MIN as i64 && value <= i8::MAX as i64 {
        out.push(0xd0);
        out.push(value as i8 as u8);
    } else if value >= i16::MIN as i64 && value <= i16::MAX as i64 {
        out.push(0xd1);
        out.extend_from_slice(&(value as i16).to_be_bytes());
    } else if value >= i32::MIN as i64 && value <= i32::MAX as i64 {
        out.push(0xd2);
        out.extend_from_slice(&(value as i32).to_be_bytes());
    } else {
        out.push(0xd3);
        out.extend_from_slice(&value.to_be_bytes());
    }
}

// ── MessagePack: разборщик ──────────────────────────────────────────────────

/// Разобранное значение MessagePack — только то, что нужно ответам MAX.
#[derive(Debug, Clone, PartialEq)]
pub enum Mp {
    Nil,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    Bin(Vec<u8>),
    Array(Vec<Mp>),
    Map(Vec<(Mp, Mp)>),
}

impl Mp {
    fn get(&self, key: &str) -> Option<&Mp> {
        match self {
            Mp::Map(entries) => entries
                .iter()
                .find(|(name, _)| matches!(name, Mp::Str(text) if text == key))
                .map(|(_, value)| value),
            _ => None,
        }
    }

    fn as_str(&self) -> Option<&str> {
        match self {
            Mp::Str(text) => Some(text),
            _ => None,
        }
    }

    /// Короткое текстовое описание для журнала (без утечки токенов целиком).
    fn describe(&self) -> String {
        let text = format!("{self:?}");
        if text.chars().count() > 200 {
            text.chars().take(200).collect::<String>() + "…"
        } else {
            text
        }
    }
}

/// Разбор кадра: сервер присылает служебный префикс перед msgpack-объектом —
/// два ведущих int (`f0 a0`, `f3 a7`, …) или другой мусор. Префикс не часть
/// структуры, и его длина плавает от версии к версии, поэтому объект ищем не
/// «по формуле», а перебором: первый offset, на котором карта разбирается и
/// съедает payload целиком, и есть начало объекта.
fn decode_message(payload: &[u8]) -> Result<Mp> {
    let preferred = leading_ints_len(payload);
    let mut candidates = vec![preferred];
    candidates.extend((0..=16usize).filter(|offset| *offset != preferred));
    let mut last_error: Option<anyhow::Error> = None;
    for offset in candidates {
        let Some(rest) = payload.get(offset..) else {
            continue;
        };
        match decode_value(rest) {
            Ok((value, consumed)) if consumed == rest.len() => {
                if matches!(value, Mp::Map(_) | Mp::Array(_)) {
                    return Ok(value);
                }
            }
            Ok(_) => {}
            Err(error) => last_error = Some(error),
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("MAX TCP: payload без карты")))
        .context("MAX TCP: разбор MessagePack")
}

fn leading_ints_len(payload: &[u8]) -> usize {
    let mut offset = 0usize;
    while offset < payload.len().min(8) {
        let byte = payload[offset];
        let step = match byte {
            0x00..=0x7f | 0xe0..=0xff => 1,
            0xcc | 0xd0 => 2,
            0xcd | 0xd1 => 3,
            0xce | 0xd2 => 5,
            _ => break,
        };
        if offset + step > payload.len() {
            break;
        }
        offset += step;
    }
    offset
}

fn decode_value(data: &[u8]) -> Result<(Mp, usize)> {
    let mut cursor = Cursor { data, pos: 0 };
    let value = cursor.value()?;
    Ok((value, cursor.pos))
}

struct Cursor<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn byte(&mut self) -> Result<u8> {
        let byte = *self
            .data
            .get(self.pos)
            .context("MAX TCP: MessagePack оборван")?;
        self.pos += 1;
        Ok(byte)
    }

    fn take(&mut self, count: usize) -> Result<&'a [u8]> {
        let end = self
            .pos
            .checked_add(count)
            .context("MAX TCP: длина MessagePack переполнена")?;
        let slice = self
            .data
            .get(self.pos..end)
            .context("MAX TCP: MessagePack оборван")?;
        self.pos = end;
        Ok(slice)
    }

    fn uint(&mut self, count: usize) -> Result<u64> {
        let bytes = self.take(count)?;
        let mut value = 0u64;
        for byte in bytes {
            value = (value << 8) | u64::from(*byte);
        }
        Ok(value)
    }

    fn text(&mut self, length: usize) -> Result<Mp> {
        if length > MAX_DECODED_BYTES {
            bail!("MAX TCP: строка MessagePack слишком длинная ({length})");
        }
        let bytes = self.take(length)?;
        Ok(Mp::Str(String::from_utf8_lossy(bytes).into_owned()))
    }

    fn value(&mut self) -> Result<Mp> {
        let marker = self.byte()?;
        match marker {
            0x00..=0x7f => Ok(Mp::Int(i64::from(marker))),
            0xe0..=0xff => Ok(Mp::Int(i64::from(marker as i8))),
            0x80..=0x8f => self.map(usize::from(marker & 0x0f)),
            0x90..=0x9f => self.array(usize::from(marker & 0x0f)),
            0xa0..=0xbf => self.text(usize::from(marker & 0x1f)),
            0xc0 => Ok(Mp::Nil),
            0xc2 => Ok(Mp::Bool(false)),
            0xc3 => Ok(Mp::Bool(true)),
            0xc4 | 0xd9 => {
                let length = usize::from(self.byte()?);
                self.text_or_bin(marker, length)
            }
            0xc5 | 0xda => {
                let length = self.uint(2)? as usize;
                self.text_or_bin(marker, length)
            }
            0xc6 | 0xdb => {
                let length = self.uint(4)? as usize;
                self.text_or_bin(marker, length)
            }
            0xc7 => {
                let length = usize::from(self.byte()?);
                self.ext(length)
            }
            0xc8 => {
                let length = self.uint(2)? as usize;
                self.ext(length)
            }
            0xc9 => {
                let length = self.uint(4)? as usize;
                self.ext(length)
            }
            0xca => {
                let bytes = self.take(4)?;
                Ok(Mp::Float(f32::from_be_bytes([
                    bytes[0], bytes[1], bytes[2], bytes[3],
                ]) as f64))
            }
            0xcb => {
                let bytes = self.take(8)?;
                Ok(Mp::Float(f64::from_be_bytes([
                    bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6],
                    bytes[7],
                ])))
            }
            0xcc => Ok(Mp::Int(self.uint(1)? as i64)),
            0xcd => Ok(Mp::Int(self.uint(2)? as i64)),
            0xce => Ok(Mp::Int(self.uint(4)? as i64)),
            0xcf => Ok(Mp::Int(self.uint(8)? as i64)),
            0xd0 => Ok(Mp::Int(i64::from(self.byte()? as i8))),
            0xd1 => Ok(Mp::Int(self.uint(2)? as u16 as i16 as i64)),
            0xd2 => Ok(Mp::Int(self.uint(4)? as u32 as i32 as i64)),
            0xd3 => Ok(Mp::Int(self.uint(8)? as i64)),
            0xd4..=0xd8 => {
                let length = 1usize << (marker - 0xd4);
                self.ext(length)
            }
            0xdc => {
                let length = self.uint(2)? as usize;
                self.array(length)
            }
            0xdd => {
                let length = self.uint(4)? as usize;
                self.array(length)
            }
            0xde => {
                let length = self.uint(2)? as usize;
                self.map(length)
            }
            0xdf => {
                let length = self.uint(4)? as usize;
                self.map(length)
            }
            other => bail!("MAX TCP: неизвестный маркер MessagePack 0x{other:02x}"),
        }
    }

    fn text_or_bin(&mut self, marker: u8, length: usize) -> Result<Mp> {
        if marker == 0xc4 || marker == 0xc5 || marker == 0xc6 {
            if length > MAX_DECODED_BYTES {
                bail!("MAX TCP: binary MessagePack слишком длинный ({length})");
            }
            Ok(Mp::Bin(self.take(length)?.to_vec()))
        } else {
            self.text(length)
        }
    }

    fn ext(&mut self, length: usize) -> Result<Mp> {
        // MAX использует ext-коды вне спецификации: содержимое нам не нужно,
        // но его надо пропустить, иначе разбор поедет.
        if length > MAX_DECODED_BYTES {
            bail!("MAX TCP: ext MessagePack слишком длинный ({length})");
        }
        let _kind = self.byte()?;
        Ok(Mp::Bin(self.take(length)?.to_vec()))
    }

    fn array(&mut self, length: usize) -> Result<Mp> {
        if length > MAX_DECODED_BYTES {
            bail!("MAX TCP: массив MessagePack слишком длинный ({length})");
        }
        let mut items = Vec::with_capacity(length.min(64));
        for _ in 0..length {
            items.push(self.value()?);
        }
        Ok(Mp::Array(items))
    }

    fn map(&mut self, length: usize) -> Result<Mp> {
        if length > MAX_DECODED_BYTES {
            bail!("MAX TCP: карта MessagePack слишком длинная ({length})");
        }
        let mut entries = Vec::with_capacity(length.min(64));
        for _ in 0..length {
            let key = self.value()?;
            let value = self.value()?;
            entries.push((key, value));
        }
        Ok(Mp::Map(entries))
    }
}

/// LZ4-блок (бит 24 в длине кадра). Сжатые ответы бывают у больших LOGIN, но
/// заголовок может прийти и с флагом — тогда разжимаем сами.
/// Разобрать payload: перебрать способы распаковки и взять тот, после
/// которого получается корректный MessagePack.
///
/// Флаг в старшем байте длины НЕ определяет кодек однозначно: живой сервер
/// прислал ответ LOGIN с `флаг=0x03`, 16 КБ, и он не читается ни как чистый
/// LZ4-блок, ни как zstd с нуля. Поэтому вместо угадывания по флагу
/// перебираем варианты (флаг-предпочтительный первым) и проверяем результат
/// разбором: это ровно то, что нужно — важно получить профиль, а не угадать
/// название кодека. Успешный вариант пишется в журнал один раз на флаг.
fn decode_frame(payload: &[u8], flag: u8) -> Result<Mp> {
    if flag == 0 {
        return decode_message(payload);
    }
    let mut errors: Vec<String> = Vec::new();
    for (name, decoded) in decompress_candidates(payload, flag) {
        match decoded {
            Ok(bytes) => match decode_message(&bytes) {
                Ok(message) => {
                    report_decoder(flag, name);
                    // LOGIN может нести новый токен: если так, по нему видно,
                    // что сессия живая и старый токен сервер уже «съел».
                    // Строка информационная: журнал не должен красить её как сбой,
                    // поэтому пишем один раз за сессию, а не на каждый кадр.
                    if message.get("token").is_some() || message.get("tokenAttrs").is_some() {
                        report_once(&SESSION_TOKEN_SEEN, || {
                            "[MAX][TCP] сервер выдал новый токен сессии"
                        });
                    }
                    return Ok(message);
                }
                Err(error) => errors.push(format!("{name}: MessagePack: {error:#}")),
            },
            Err(error) => errors.push(format!("{name}: {error:#}")),
        }
    }
    bail!("ни один способ распаковки не дал MessagePack: {}", errors.join("; "))
}

/// Варианты распаковки в порядке предпочтения: сначала тот, на который
/// указывает флаг, затем остальные.
fn decompress_candidates(payload: &[u8], flag: u8) -> Vec<(&'static str, Result<Vec<u8>>)> {
    let mut out: Vec<(&'static str, Result<Vec<u8>>)> = Vec::new();
    let preferred: &[&str] = match flag {
        0xff => &["zstd", "lz4-le", "lz4", "lz4-size", "lz4-skip2", "raw"],
        // LZ4 по спецификации хранит смещение little-endian: 6656 = 0x1A00 —
        // это прочитанное «не туда» смещение 26. На маленьком INIT (220 Б)
        // смещения меньше 256, поэтому ошибка не проявлялась.
        0x01..=0x7f => &["lz4-le", "lz4", "zstd", "lz4-size", "lz4-skip2", "raw"],
        _ => &["lz4-le", "lz4", "zstd", "lz4-size", "lz4-skip2", "raw"],
    };
    for name in preferred {
        let decoded = match *name {
            "zstd" => zstd_block(payload),
            "lz4" => lz4_block(payload),
            "lz4-le" => lz4_block_with(payload, true),
            // Некоторые сборки кладут перед LZ4-блоком несжатый размер
            // (4 байта, порядок встречается оба).
            // Часть сборок оставляет впереди ведущие ints уже до сжатия.
            "lz4-skip2" => payload
                .get(2..)
                .context("lz4-skip2: блок короче 2 байт")
                .and_then(|block| lz4_block_with(block, true)),
            "lz4-size" => payload
                .get(4..)
                .context("lz4-size: блок короче 4 байт")
                .and_then(|block| lz4_block_with(block, true)),
            _ => Ok(payload.to_vec()),
        };
        out.push((name, decoded));
    }
    out
}

/// Пишем в журнал, какой кодек реально сработал: по этой строке видно,
/// совпал ли он с флагом.
fn report_decoder(flag: u8, name: &'static str) {
    use std::sync::{Mutex, OnceLock};
    static SEEN: OnceLock<Mutex<Vec<(u8, &'static str)>>> = OnceLock::new();
    let seen = SEEN.get_or_init(|| Mutex::new(Vec::new()));
    let Ok(mut guard) = seen.lock() else { return };
    // Не засоряем журнал: одна строка на каждую пару «флаг → кодек».
    if guard.iter().any(|entry| *entry == (flag, name)) {
        return;
    }
    guard.push((flag, name));
    let expected = match flag {
        0xff => "zstd",
        0x01..=0x7f => "lz4",
        _ => name,
    };
    if name == expected {
        crate::log_error!("[MAX][TCP] распаковка: флаг=0x{flag:02x} → {name}");
    } else {
        // Одна строка на флаг, а не на каждый кадр: важно не «кто виноват»,
        // а какой кодек реально сработал.
        crate::log_error!(
            "[MAX][TCP] распаковка: флаг=0x{flag:02x} → {name} (по флагу ждали {expected})"
        );
    }
}

/// Строка «один раз за процесс»: повторяющиеся шаги (обмен токена на каждом
/// входе) не должны превращать журнал в простыню.
fn report_once(flag: &std::sync::atomic::AtomicBool, message: impl FnOnce() -> &'static str) {
    if flag.swap(true, std::sync::atomic::Ordering::AcqRel) {
        return;
    }
    crate::log_error!("{}", message());
}

static SESSION_TOKEN_SEEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Распаковка payload по флагу старшего байта длины.
///
/// MAX нарезает флаг так: `0x00` — без сжатия, `0x01..=0x7f` — LZ4,
/// `0xff` — zstd. Большие ответы (LOGIN со списком чатов, профиль) приходят
/// именно zstd; раньше мы всегда звали LZ4 и получали на них «LZ4 неверное
/// смещение», хотя токен был в порядке. Неизвестный флаг теперь пробуем
/// обоими способами: важнее распаковать, чем угадать.
#[allow(dead_code)] // используется в тестах: боевой путь идёт через decode_frame
fn decompress_payload(input: &[u8], flag: u8) -> Result<Vec<u8>> {
    match flag {
        0x00 => Ok(input.to_vec()),
        0xff => zstd_block(input),
        0x01..=0x7f => lz4_block(input),
        _ => lz4_block(input).or_else(|lz4_error| {
            zstd_block(input).map_err(|zstd_error| {
                anyhow::anyhow!("флаг 0x{flag:02x}: LZ4: {lz4_error:#}; zstd: {zstd_error:#}")
            })
        }),
    }
}

fn zstd_block(input: &[u8]) -> Result<Vec<u8>> {
    // Ёмкость с запасом: LOGIN-ответ со списком чатов заметно больше
    // сжатого блока, а лимит MAX_DECODED_BYTES всё равно проверяется ниже.
    let capacity = input.len().saturating_mul(16).clamp(64 * 1024, MAX_DECODED_BYTES);
    let data = zstd::bulk::decompress(input, capacity).context("MAX TCP: распаковка zstd")?;
    if data.len() > MAX_DECODED_BYTES {
        bail!("MAX TCP: zstd блок слишком большой");
    }
    Ok(data)
}

fn lz4_block(input: &[u8]) -> Result<Vec<u8>> {
    lz4_block_with(input, false)
}

/// LZ4-блок с явным порядком байт смещения.
///
/// По спецификации LZ4 смещение — little-endian, но живые данные MAX
/// читались и как big-endian, поэтому перебираем оба варианта и проверяем
/// результат разбором MessagePack (см. `decode_frame`). Диагностика с
/// телефона: «неверное смещение 6656» = 0x1A00, при обратном порядке это 26 —
/// валидное смещение, значит на больших блоках сервер отдаёт LE.
fn lz4_block_with(input: &[u8], little_endian: bool) -> Result<Vec<u8>> {
    let mut out: Vec<u8> = Vec::with_capacity(input.len() * 2);
    let mut index = 0usize;
    while index < input.len() {
        // Хвост нулей — выравнивание, а не данные: иначе оно читается как
        // «литералов 0, смещение 0» и блок падает на пустом месте.
        if input[index..].iter().all(|byte| *byte == 0) {
            break;
        }
        let token = *input.get(index).context("MAX TCP: LZ4 оборван")?;
        index += 1;
        let mut literals = usize::from(token >> 4);
        if literals == 15 {
            loop {
                let extra = *input.get(index).context("MAX TCP: LZ4 оборван")?;
                index += 1;
                literals += usize::from(extra);
                if extra != 0xff {
                    break;
                }
            }
        }
        let end = index
            .checked_add(literals)
            .context("MAX TCP: LZ4 длина переполнена")?;
        let chunk = input.get(index..end).context("MAX TCP: LZ4 литералы за блоком")?;
        out.extend_from_slice(chunk);
        index = end;
        if out.len() > MAX_DECODED_BYTES {
            bail!("MAX TCP: LZ4 блок слишком большой");
        }
        if index >= input.len() {
            break;
        }
        let high = *input.get(index).context("MAX TCP: LZ4 оборван")?;
        let low = *input.get(index + 1).context("MAX TCP: LZ4 оборван")?;
        index += 2;
        let offset = if little_endian {
            (usize::from(low) << 8) | usize::from(high)
        } else {
            (usize::from(high) << 8) | usize::from(low)
        };
        if offset == 0 || offset > out.len() {
            bail!("MAX TCP: LZ4 неверное смещение {offset}");
        }
        let mut match_length = usize::from(token & 0x0f);
        if match_length == 15 {
            loop {
                let extra = *input.get(index).context("MAX TCP: LZ4 оборван")?;
                index += 1;
                match_length += usize::from(extra);
                if extra != 0xff {
                    break;
                }
            }
        }
        match_length += 4;
        let start = out.len() - offset;
        for step in 0..match_length {
            let byte = out[start + step];
            out.push(byte);
        }
        if out.len() > MAX_DECODED_BYTES {
            bail!("MAX TCP: LZ4 блок слишком большой");
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_payload_is_an_empty_map_not_a_failure() {
        // Живой сервер присылает `opcode=20 флаг=0x00 0Б` как подтверждение.
        // Раньше разбор падал «MessagePack оборван» и убивал сессию после LOGIN.
        let empty = Mp::Map(Vec::new());
        assert!(empty.get("token").is_none());
    }

    #[test]
    fn login_reply_token_is_taken_from_token_attrs() {
        // Так токен отдаёт вход по SMS/QR: tokenAttrs.LOGIN.token.
        let mut attrs = Vec::new();
        map(&mut attrs, 1);
        str_value(&mut attrs, "LOGIN");
        map(&mut attrs, 1);
        str_value(&mut attrs, "token");
        str_value(&mut attrs, "An_0123456789abcdef0123456789abcdef");
        let mut root = Vec::new();
        map(&mut root, 1);
        str_value(&mut root, "tokenAttrs");
        root.extend_from_slice(&attrs);
        let message = decode_message(&root).expect("ответ разбирается");
        assert_eq!(
            login_token(&message).as_deref(),
            Some("An_0123456789abcdef0123456789abcdef"),
        );
        // Плоское `token` тоже подходит, а мусор — нет.
        let mut flat = Vec::new();
        map(&mut flat, 1);
        str_value(&mut flat, "token");
        str_value(&mut flat, "An_0123456789abcdef");
        assert_eq!(login_token(&decode_message(&flat).unwrap()).as_deref(), Some("An_0123456789abcdef"));
        let mut junk = Vec::new();
        map(&mut junk, 1);
        str_value(&mut junk, "token");
        str_value(&mut junk, "short");
        assert!(login_token(&decode_message(&junk).unwrap()).is_none());
    }

    #[test]
    fn lz4_offset_is_accepted_in_both_byte_orders() {
        // С телефона приходило «LZ4 неверное смещение 6656»: 6656 = 0x1A00, а
        // при обратном порядке байт это 26 — валидное смещение. Значит на
        // больших блоках сервер отдаёт смещение little-endian.
        let build = |high: u8, low: u8| {
            let mut out = vec![0x44]; // 4 литерала, совпадение 4+4
            out.extend_from_slice(b"abcd");
            out.push(high);
            out.push(low);
            out
        };
        // BE: смещение 4 → [0x00, 0x04]
        assert_eq!(lz4_block(&build(0x00, 0x04)).unwrap(), b"abcdabcdabcd");
        // LE: смещение 4 → [0x04, 0x00]
        assert_eq!(lz4_block_with(&build(0x04, 0x00), true).unwrap(), b"abcdabcdabcd");
        // Хвостовые нули — выравнивание, а не данные.
        let mut padded = build(0x00, 0x04);
        padded.extend_from_slice(&[0, 0, 0, 0]);
        assert_eq!(lz4_block(&padded).unwrap(), b"abcdabcdabcd");
    }

    #[tokio::test]
    async fn closed_connection_stops_the_wait_loop_at_once() {
        // Живой баг: цикл ожидания глотал «early eof» и крутился бесконечно,
        // печатая одну строку тысячи раз. Сокет закрыт — соединения нет,
        // ожидание обязано завершиться ошибкой сразу.
        use tokio::io::AsyncWriteExt;
        let (client_side, server_side) = tokio::io::duplex(64);
        drop(server_side);
        let mut session = Session { stream: Box::new(client_side), seq: 0 };
        assert!(session.send(OPCODE_CALL_TOKEN, empty_map()).await.is_ok());
        let error = session
            .wait_for(OPCODE_CALL_TOKEN)
            .await
            .expect_err("закрытое соединение не должно ждать вечно");
        assert!(format!("{error:#}").contains("соединение потеряно"), "{error:#}");
    }

    #[test]
    fn only_decode_errors_are_skippable() {
        // Транспортную ошибку путать с ошибкой разбора нельзя.
        let decode = anyhow::Error::new(DecodeError).context("MAX TCP: распаковка opcode 19");
        assert!(is_decode_error(&decode));
        let transport = anyhow::anyhow!("MAX TCP: чтение заголовка: early eof");
        assert!(!is_decode_error(&transport));
    }

    #[test]
    fn compression_flag_selects_the_right_codec() {
        // Живой телефон: INIT распаковывался, а LOGIN падал на «LZ4 неверное
        // смещение». Причина — у больших ответов MAX флаг 0xff (zstd), а не LZ4.
        let payload = b"MAX: zstd payload for LOGIN".repeat(8);
        let packed = zstd::bulk::compress(&payload, 3).unwrap();
        assert_eq!(decompress_payload(&packed, 0xff).unwrap(), payload);
        // Флаг 0x00 — без сжатия, байты не трогаем.
        assert_eq!(decompress_payload(b"raw", 0x00).unwrap(), b"raw");
        // LZ4 с флагом 0x01 по-прежнему читается.
        let lz4 = {
            // token: литералов 4, длина совпадения 4+4=8; затем 4 литерала,
            // смещение 4 — классический «abcdabcdabcd».
            let mut out = vec![0x44];
            out.extend_from_slice(b"abcd");
            out.extend_from_slice(&[0x00, 0x04]);
            out
        };
        assert_eq!(decompress_payload(&lz4, 0x01).unwrap(), b"abcdabcdabcd");
        // Неизвестный флаг пробует оба кодека, а не падает сразу.
        assert_eq!(decompress_payload(&packed, 0x7f).unwrap(), payload);
    }

    #[test]
    fn web_session_tokens_are_not_sent_to_the_app_protocol() {
        // Веб-токен ведёт себя как живой, но приложение-протокол его не
        // принимает: отправлять его туда — гарантированный login.cred.
        assert!(is_web_session_token("aBcD1234efGh5678"));
        assert!(!is_web_session_token(&"An_".to_string().repeat(40)));
    }

    #[test]
    fn header_has_protocol_version_and_big_endian_length() {
        let payload = empty_map();
        assert_eq!(payload, vec![0x80]);
        let mut frame = Vec::new();
        frame.push(PROTOCOL_VERSION);
        frame.push(0);
        frame.extend_from_slice(&7u16.to_be_bytes());
        frame.extend_from_slice(&OPCODE_CALL_TOKEN.to_be_bytes());
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(&payload);
        assert_eq!(frame[0], 10);
        assert_eq!(u16::from_be_bytes([frame[4], frame[5]]), 158);
        assert_eq!(
            u32::from_be_bytes([frame[6], frame[7], frame[8], frame[9]]),
            1
        );
    }

    #[test]
    fn hello_payload_presents_a_web_client() {
        // Токен привязан к web.max.ru: приложение представляет себя тем же
        // клиентом. С ANDROID/GCM сервер закрывал сессию (opcode 20) и 158
        // отвечал `Must be ONLINE session`.
        let decoded = decode_value(&hello_payload("d-1")).expect("разбор").0;
        let agent = decoded.get("userAgent").expect("userAgent");
        assert_eq!(agent.get("deviceType").and_then(Mp::as_str), Some("WEB"));
        assert_eq!(agent.get("pushDeviceType").and_then(Mp::as_str), Some("WEBPUSH"));
        assert!(agent.get("headerUserAgent").and_then(Mp::as_str).is_some());
        assert_eq!(decoded.get("deviceId").and_then(Mp::as_str), Some("d-1"));
    }

    #[test]
    fn hello_payload_roundtrips() {
        let payload = hello_payload("device-1");
        let decoded = decode_value(&payload).expect("разбор").0;
        assert_eq!(
            decoded.get("deviceId").and_then(Mp::as_str),
            Some("device-1")
        );
        let agent = decoded.get("userAgent").expect("userAgent");
        assert_eq!(
            agent.get("appVersion").and_then(Mp::as_str),
            Some(crate::max_calls::MAX_APP_VERSION)
        );
        assert_eq!(
            agent.get("deviceType").and_then(Mp::as_str),
            Some("ANDROID")
        );
        assert!(matches!(decoded.get("clientSessionId"), Some(Mp::Int(ms)) if *ms > 0));
    }

    #[test]
    fn login_payload_carries_token_and_flags() {
        let payload = login_payload("tok");
        let decoded = decode_value(&payload).expect("разбор").0;
        assert_eq!(decoded.get("token").and_then(Mp::as_str), Some("tok"));
        assert_eq!(decoded.get("interactive"), Some(&Mp::Bool(true)));
        assert_eq!(decoded.get("chatsCount"), Some(&Mp::Int(0)));
    }

    #[test]
    fn two_byte_prefix_is_skipped_even_when_it_starts_a_string_marker() {
        // Ответ SESSION_INIT начинается с `f0 a0`, а `0xa0` — это fixstr
        // нулевой длины: наивный «пропуск ведущих int» съел бы только `f0`
        // и разобрал пустую строку вместо карты.
        let mut payload = vec![0xf0, 0xa0];
        payload.extend_from_slice(&{
            let mut out = Vec::new();
            map(&mut out, 1);
            str_value(&mut out, "location");
            str_value(&mut out, "RU");
            out
        });
        let decoded = decode_message(&payload).expect("разбор");
        assert_eq!(decoded.get("location").and_then(Mp::as_str), Some("RU"));
    }

    #[test]
    fn leading_ints_are_stripped_before_the_object() {
        // Ответ INIT: f0 4b = (-16, 75), затем map {token: "abc"}.
        let mut payload = vec![0xf0, 0x4b];
        payload.extend_from_slice(&{
            let mut out = Vec::new();
            map(&mut out, 1);
            str_value(&mut out, "token");
            str_value(&mut out, "abc");
            out
        });
        let decoded = decode_message(&payload).expect("разбор");
        assert_eq!(decoded.get("token").and_then(Mp::as_str), Some("abc"));
    }

    #[test]
    fn int16_int32_leading_ints_and_negative_fixints() {
        assert_eq!(leading_ints_len(&[0xd1, 0x00, 0x05, 0x80]), 3);
        assert_eq!(leading_ints_len(&[0xd2, 0x00, 0x00, 0x00, 0x05, 0x80]), 5);
        assert_eq!(leading_ints_len(&[0xff, 0x80]), 2);
        assert_eq!(leading_ints_len(&[0x80]), 0);
    }

    #[test]
    fn arrays_and_nested_maps_decode() {
        let mut out = Vec::new();
        map(&mut out, 2);
        str_value(&mut out, "a");
        array(&mut out, 2);
        int_value(&mut out, 1);
        str_value(&mut out, "x");
        str_value(&mut out, "b");
        map(&mut out, 0);
        let decoded = decode_value(&out).expect("разбор").0;
        assert_eq!(
            decoded.get("a"),
            Some(&Mp::Array(vec![Mp::Int(1), Mp::Str("x".into())]))
        );
        assert_eq!(decoded.get("b"), Some(&Mp::Map(Vec::new())));
    }

    #[test]
    fn lz4_literal_only_block() {
        // 0x50: 5 литералов (h e l l o), совпадений нет.
        let block = [0x50, b'h', b'e', b'l', b'l', b'o'];
        assert_eq!(lz4_block(&block).expect("разбор"), b"hello".to_vec());
    }

    #[test]
    fn lz4_back_reference_expands() {
        // 4 литерала "abcd", затем совпадение длиной 4 со смещением 4 → "abcdabcd".
        let block = [0x40, b'a', b'b', b'c', b'd', 0x00, 0x04, 0x00];
        assert_eq!(lz4_block(&block).expect("разбор"), b"abcdabcd".to_vec());
    }

    #[test]
    fn truncated_payload_is_an_error_not_a_panic() {
        let mut out = Vec::new();
        map(&mut out, 1);
        str_value(&mut out, "token");
        assert!(decode_value(&out).is_err());
    }
}
