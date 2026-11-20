#![allow(linker_messages)]

pub struct MrttPorts;

impl MrttPorts {
    pub const SERVER_PEER_PORT: u16 = 46_000;
    pub const SERVER_WEB_PORT: u16 = 46_002;
    pub const SSH_PORT: u16 = 22;
}

mod adblock;
mod auth;
mod captcha;
mod captcha_slider;
mod cpu_task;
pub mod direct_relay;
mod dispatcher;
#[cfg(target_os = "linux")]
mod linux_exit;
mod tunnel_probe;
mod dns;
#[path = "../shared/dtls.rs"]
pub mod dtls;
mod events;
mod game_mode;
// Seat bookkeeping helpers of the shared module serve the server only.
#[allow(dead_code)]
#[path = "../shared/jitsi_room.rs"]
mod jitsi_room;
#[path = "../shared/stun_probe.rs"]
mod stun_probe;
mod logging;
mod max_calls;
mod max_tcp;
mod namegen;
mod obfs;
mod packet;
mod profiles;
mod protocol;
mod repair;
mod session;
mod sni;
#[path = "../shared/spsc_ring.rs"]
mod spsc_ring;
mod srtp_mode;
mod stats;
mod stun_codec;
mod tun;
mod turn;
mod turn_core;
mod turn_endpoint;
mod turn_ip_pool;
mod turn_stream;
mod vk_js_calls;
#[path = "../shared/wire_protocol.rs"]
mod wire_protocol;
mod app_direct;
mod worker;
mod wrap;
mod youtube_ads;

use anyhow::{Context, Result, bail};
use auth::{VkAuth, VkHashCheck};
use base64::{Engine, engine::general_purpose::STANDARD};
use captcha::CaptchaSolver;
use clap::Parser;
use dispatcher::{Dispatcher, Termination};
use events::Events;
use obfs::ObfsMode;
use packet::{PacketPool, packet_pool_size};
use repair::RepairState;
use session::ShutdownCoordinator;
use srtp_mode::SrtpMode;
use stats::Stats;
use std::{
    collections::HashSet,
    io::BufRead,
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    time::Duration,
};
use tokio_util::sync::CancellationToken;
use turn_endpoint::TurnTransportMode;
use turn_ip_pool::{TurnIpPool, parse_custom_turn_ips};
use worker::{
    GROUPS_PER_CREDENTIAL, GroupContext, PauseGate, RuntimeParams, WORKER_START_INTERVAL,
    WORKERS_PER_GROUP, WorkerStartPacer, parse_hashes, run_groups,
};
use wrap::derive_wrap_key;

const EXIT_NORMAL: i32 = 0;
const EXIT_FATAL: i32 = 1;
const EXIT_TUN_LOST: i32 = 3;
const EXIT_AUTH_EXPIRED: i32 = 4;
const EXIT_NETWORK_RETRY: i32 = 5;
const MAX_VK_HASHES: usize = 6;
pub const MAX_WORKERS: usize = 100;
const STREAMS_PER_RUNTIME_WORKER: usize = 8;
const STDIN_QUEUE_CAPACITY: usize = 64;
const MAX_STDIN_LINE_BYTES: usize = 64 * 1024;
type StdinLine = std::io::Result<String>;

#[derive(Parser)]
#[command(disable_help_flag = true)]
struct Arguments {
    #[arg(long, default_value = "")]
    turn: String,
    #[arg(long = "custom-turn-ips", default_value = "")]
    custom_turn_ips: String,
    #[arg(long, default_value = "")]
    port: String,
    #[arg(long, default_value = "127.0.0.1:9000")]
    listen: String,
    #[arg(long, default_value = "", allow_hyphen_values = true)]
    vk: String,
    // MAX group IDs may be negative; clap must accept -123 as a value.
    #[arg(long, default_value = "", allow_hyphen_values = true)]
    max: String,
    #[arg(long, default_value = "")]
    jitsi: String,
    #[arg(long, default_value = "manual")]
    vk_hash_mode: String,
    #[arg(long, default_value = "")]
    peer: String,
    #[arg(short = 'n', long, default_value_t = 18)]
    workers: usize,
    #[arg(long, default_value_t = 0)]
    runtime_threads: usize,
    #[arg(long, default_value = "vkcalls")]
    vk_auth_mode: String,
    #[arg(long, default_value = "auto")]
    captcha_mode: String,
    #[arg(long, default_value = "chrome")]
    fingerprint: String,
    #[arg(long, default_value = "")]
    client_ids: String,
    #[arg(long, default_value = "unified")]
    obfs: String,
    #[arg(long, default_value_t = false)]
    stealth: bool,
    #[arg(long, default_value = "udp")]
    turn_transport: String,
    #[arg(long, default_value = "auto")]
    srtp_mode: String,
    #[arg(long = "gen", default_value_t = 0)]
    generation: u64,
    #[arg(long, default_value = "")]
    salt: String,
    #[arg(long, default_value = "")]
    tun_uds: String,
    #[arg(long, default_value_t = false)]
    validate_vk_hashes: bool,
    #[arg(long = "domain-routing", default_value_t = false)]
    domain_routing: bool,
    #[arg(long = "exclude-ru-zones", default_value_t = false)]
    exclude_ru_zones: bool,
    #[arg(long = "template-domains", default_value_t = false)]
    template_domains: bool,
    #[arg(long = "domain-whitelist", default_value_t = false)]
    domain_whitelist: bool,
    #[arg(long = "direct-domains", default_value = "")]
    direct_domains: String,
    #[arg(long = "domains-file")]
    domains_file: Vec<String>,
    #[arg(long = "ip-routing", default_value_t = false)]
    ip_routing: bool,
    #[arg(long = "ip-whitelist", default_value_t = false)]
    ip_whitelist: bool,
    #[arg(long = "direct-ips", default_value = "")]
    direct_ips: String,
    #[arg(long = "exact-workers", default_value_t = false)]
    exact_workers: bool,
    #[arg(long = "adblock", default_value_t = false)]
    adblock: bool,
    #[arg(long = "game-mode", default_value_t = false)]
    game_mode: bool,
    #[arg(long = "game-ports", default_value = "")]
    game_ports: String,
    #[arg(long = "protected-ips", default_value = "")]
    protected_ips: String,
    #[arg(long = "adblock-file", default_value = "")]
    adblock_file: String,
    #[arg(long = "adblock-rules")]
    adblock_rules: Vec<String>,
    #[arg(long = "adblock-category", default_value = "")]
    adblock_category: String,
    #[arg(long = "ru-domains-file")]
    ru_domains_file: Vec<String>,
    #[arg(long = "exit-tun", default_value = "")]
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    exit_tun: String,
    #[arg(long = "exit-hook", default_value = "")]
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    exit_hook: String,
}

fn probe_peer(address: &str) -> Option<u64> {
    let peer: std::net::SocketAddr = address.parse().ok()?;
    let socket = std::net::UdpSocket::bind(if peer.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }).ok()?;
    socket.connect(peer).ok()?;
    socket.set_read_timeout(Some(Duration::from_secs(1))).ok()?;
    let mut handshake = crate::dtls::DtlsClientHandshake::new().ok()?;
    let hello = handshake.start().ok()?;
    let mut buffer = [0u8; 2048];
    for _ in 0..3 {
        let started = std::time::Instant::now();
        socket.send(&hello).ok()?;
        if let Ok(length) = socket.recv(&mut buffer)
            && crate::dtls::is_dtls_packet(&buffer[..length])
        {
            return Some(started.elapsed().as_millis() as u64);
        }
    }
    None
}

fn main() {
    // tokio-tungstenite (MAX signaling) uses plain rustls, which panics without a process-level provider.
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    // A one-shot run beside the tunnel: it touches neither the TUN nor the running client.
    let mut args = std::env::args().skip(1);
    if args.next().as_deref() == Some("--probe-peer") {
        match args.next().as_deref().and_then(probe_peer) {
            Some(ms) => println!("PROBE_MS={ms}"),
            None => std::process::exit(1),
        }
        return;
    }
    jitsi_room::set_fallback_resolver(|host| {
        Box::pin(async move { crate::dns::global().lookup(&host).await.unwrap_or_default() })
    });
    std::panic::set_hook(Box::new(|info| {
        let msg = format!("[PANIC] Rust client task failed: {info}\n");
        #[cfg(unix)]
        unsafe {
            let _ = libc::write(libc::STDERR_FILENO, msg.as_ptr().cast(), msg.len());
        }
        #[cfg(not(unix))]
        {
            use std::io::Write;
            let mut stderr = std::io::stderr().lock();
            let _ = stderr.write_all(msg.as_bytes());
            let _ = stderr.flush();
        }
    }));
    let arguments = Arguments::parse_from(normalized_arguments());
    let runtime = match build_runtime(runtime_worker_threads(
        arguments.workers,
        arguments.runtime_threads,
    )) {
        Ok(runtime) => runtime,
        Err(error) => {
            eprintln!("[ФАТАЛ] {error:#}");
            std::process::exit(EXIT_FATAL);
        }
    };
    let exit_code = runtime.block_on(async {
        match tokio::spawn(run(arguments)).await {
            Ok(Ok(code)) => code,
            Ok(Err(error)) => {
                // MAX отверг сохранённый токен: повторять его бессмысленно,
                // приложение должно взять новый (или попросить войти заново).
                if error.chain().any(|cause| cause.is::<MaxTokenRejected>()) {
                    crate::log_error!("[MAX] Токен отвергнут сервером: {error:#}");
                    EXIT_AUTH_EXPIRED
                } else if error.chain().any(|cause| cause.is::<AuthExpired>()) {
                    // Не «аварийное завершение»: приложение обновит токен и
                    // переподключится само.
                    crate::log_error!("[AUTH] {error:#}");
                    EXIT_AUTH_EXPIRED
                } else if is_network_transient(&error) {
                    // Сеть до транспорта недоступна: пробуем ещё, без «ФАТАЛ» —
                    // приложение перезапустит клиент и не покажет ошибку.
                    crate::log_error!("[СЕТЬ] Транспорт не дотянулся до сети, повторим: {error:#}");
                    EXIT_NETWORK_RETRY
                } else {
                    crate::log_error!("[ФАТАЛ] {error:#}");
                    EXIT_FATAL
                }
            }
            Err(error) => {
                crate::log_error!("[ФАТАЛ] паника верхнего уровня изолирована: {error}");
                EXIT_FATAL
            }
        }
    });
    runtime.shutdown_timeout(Duration::from_secs(2));
    let _ = logging::shutdown(Duration::from_secs(1));
    std::process::exit(exit_code);
}

fn build_runtime(default_worker_threads: usize) -> Result<tokio::runtime::Runtime> {
    let mut builder = tokio::runtime::Builder::new_multi_thread();
    builder.worker_threads(default_worker_threads);
    builder
        .enable_all()
        .build()
        .context("создание Tokio runtime")
}

fn runtime_worker_threads(requested_workers: usize, configured_threads: usize) -> usize {
    let available = std::thread::available_parallelism()
        .map(|parallelism| parallelism.get())
        .unwrap_or(1);
    runtime_worker_threads_with_limit(requested_workers, configured_threads, available)
}

fn runtime_worker_threads_with_limit(
    requested_workers: usize,
    configured_threads: usize,
    available_threads: usize,
) -> usize {
    let available = available_threads.max(1);
    if configured_threads != 0 {
        return configured_threads.clamp(1, available);
    }
    normalize_worker_count(requested_workers)
        .div_ceil(STREAMS_PER_RUNTIME_WORKER)
        .clamp(1, available)
}

fn auth_error_or_fatal(error: anyhow::Error) -> anyhow::Error {
    // MAX отвечает `login.cred` / `FAIL_WRONG_PASSWORD`, когда сохранённый токен
    // больше не действует. Это не «авария» и не «сеть»: токен надо обновить.
    let mut text = String::new();
    for cause in error.chain() {
        text.push_str(&format!("{cause:#} "));
    }
    let lowered = text.to_lowercase();
    if lowered.contains("login.cred")
        || lowered.contains("fail_wrong_password")
        || lowered.contains("max: token rejected")
    {
        // Никакого `auth_expired` здесь: приложение приняло бы это за
        // истёкший токен VK, пошло бы его обновлять и сбросило привязку.
        if let Some(events) = events::current() {
            events.max_token_rejected();
        }
        return MaxTokenRejected.into();
    }
    let auth_code = error
        .chain()
        .find_map(|cause| {
            cause
                .downcast_ref::<auth::AccountTokenExpired>()
                .map(|expired| expired.code)
        })
        .or_else(|| {
            error.chain().find_map(|cause| {
                cause
                    .downcast_ref::<auth::CallUnavailable>()
                    .map(|call| call.code)
                    .filter(|code| auth::is_auth_token_error(*code))
            })
        });
    match auth_code {
        Some(code) => {
            crate::log_error!(
                "[AUTH] Токен аккаунта отвергнут (error_code={code}) · берём свежий из сессии"
            );
            if let Some(events) = events::current() {
                events.auth_expired(code);
            }
            AuthExpired { code }.into()
        }
        None => error,
    }
}

fn is_network_transient(error: &anyhow::Error) -> bool {
    let mut text = String::new();
    for cause in error.chain() {
        text.push_str(&format!("{cause:#} "));
    }
    let lowered = text.to_lowercase();
    [
        "connect timeout",
        "deadline has elapsed",
        "timed out",
        "timeout",
        "connection refused",
        "connection reset",
        "network is unreachable",
        "no route to host",
        "dns error",
        "failed to lookup address",
        "ws подключение",
        "ws таймаут",
        "ws oneme",
        "tls handshake",
        // Только сетевые формулировки транспорта MAX. Ни `max tcp`, ни
        // `max ws` целиком тут стоять не должны: под них попадала любая
        // ошибка, включая отказ пароля/токена (`login.cred`,
        // `FAIL_WRONG_PASSWORD`) и ошибки протокола, — и вместо честного
        // сообщения клиент уходил в бесконечные перезапуски.
        "max tcp: подключение",
        "max tcp: таймаут",
        "max tcp: dns",
        "max tcp: tls",
        // Обрыв уже открытого соединения — обычная сеть: телефон ушёл в
        // другой Wi-Fi, оператор сбросил сессию. Это лечится переподключением.
        "max tcp: соединение потеряно",
        "max tcp: чтение заголовка",
        "early eof",
        // Сессию закрыл сам сервер (`opcode 20`, отказ на 158/8): это состояние
        // аккаунта, а не сеть — перезапуски не помогут.
        "max tcp: сервер не выдал токен",
        "must be online session",
        "max ws: нет",
        "ws подключение",
        "ws таймаут",
    ]
    .iter()
    .any(|marker| lowered.contains(marker))
}

#[derive(Debug)]
struct MaxTokenRejected;

impl std::fmt::Display for MaxTokenRejected {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "MAX не принял токен аккаунта: войдите в MAX заново и привяжите группу"
        )
    }
}

impl std::error::Error for MaxTokenRejected {}

#[derive(Debug)]
struct AuthExpired {
    code: i64,
}

impl std::fmt::Display for AuthExpired {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "токен аккаунта отвергнут (error_code={}), нужен свежий токен из сессии",
            self.code
        )
    }
}

impl std::error::Error for AuthExpired {}

async fn run(arguments: Arguments) -> Result<i32> {
    if arguments.validate_vk_hashes && !arguments.max.is_empty() {
        bail!("VK hash validator cannot be used with MAX transport");
    }
    if arguments.validate_vk_hashes {
        run_vk_hash_validation(&arguments).await?;
        return Ok(EXIT_NORMAL);
    }
    let max_mode = !arguments.max.trim().is_empty();
    if max_mode && (!arguments.vk.trim().is_empty() || arguments.vk_hash_mode == "auto_js") {
        bail!("[MAX] Режимы MAX и VK взаимоисключающие в одном экземпляре клиента");
    }
    let jitsi_room = if arguments.jitsi.trim().is_empty() {
        None
    } else {
        Some(Arc::new(
            jitsi_room::RoomTarget::parse(&arguments.jitsi).context("[JITSI] Комната должна быть в виде host/room")?,
        ))
    };
    let jitsi_mode = jitsi_room.is_some();
    if jitsi_mode
        && (max_mode || !arguments.vk.trim().is_empty() || arguments.vk_hash_mode == "auto_js")
    {
        bail!("[JITSI] Режим Jitsi нельзя совмещать с VK или MAX в одном экземпляре клиента");
    }
    let js_hash_mode = !jitsi_mode && (arguments.vk_hash_mode == "auto_js" || max_mode);
    let mut vk_auth_mode = arguments.vk_auth_mode.clone();
    let js_auth_mode = if max_mode {
        vk_auth_mode = "auto_js".to_owned();
        true
    } else if vk_auth_mode == "auto_js" && !js_hash_mode {
        crate::log_error!(
            "[КЛИЕНТ] Предупреждение: режим Auto JS неприменим без хешей Auto JS, переключаем на vkcalls"
        );
        vk_auth_mode = "vkcalls".to_owned();
        false
    } else {
        vk_auth_mode == "auto_js"
    };
    if arguments.peer.is_empty() || (!js_hash_mode && !jitsi_mode && arguments.vk.is_empty()) {
        bail!("[КЛИЕНТ] Нужны -peer и хеши VK");
    }
    let password = std::env::var("MRTT_PASSWORD").unwrap_or_default();
    if password.is_empty() {
        bail!("[КЛИЕНТ] Нужен пароль в переменной MRTT_PASSWORD");
    }
    let mut stdin = spawn_stdin_reader();
    let peer = resolve_peer(&arguments.peer).await?;
    let mode = ObfsMode::parse(&arguments.obfs)?;
    let stealth = arguments.stealth;
    let turn_transport = TurnTransportMode::parse(&arguments.turn_transport)?;
    let srtp_mode = SrtpMode::parse(&arguments.srtp_mode)?;
    let wrap_key = derive_wrap_key(&password)?;
    let client_wrap_key = wrap_key;
    let session_profile = profiles::random_profile(&arguments.fingerprint);
    let mut js_calls = None;
    let mut js_credential_broker = None;
    let hash_source = if jitsi_mode {
        // One pseudo-hash keeps the group machinery: room workers need no credentials.
        "jitsiroom".to_owned()
    } else if max_mode {
        let bootstrap = read_max_bootstrap(&mut stdin).await?;
        let group_ids: Vec<String> = arguments
            .max
            .split(',')
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
            .collect();
        if group_ids.len() != 1
            || group_ids[0].len() > 64
            || !group_ids[0]
                .trim_start_matches('-')
                .chars()
                .all(|c| c.is_ascii_digit())
            || group_ids[0].trim_start_matches('-').is_empty()
            || group_ids[0].matches('-').count() > 1
        {
            bail!("[MAX] Нужен один числовой ID группы (допускается знак минус)");
        }
        let started = max_calls::start(bootstrap, &group_ids, &session_profile)
            .await
            .map_err(auth_error_or_fatal)?;
        js_calls = Some(started.active);
        js_credential_broker = Some(started.credential_broker);
        started.hashes.join(",")
    } else if js_hash_mode {
        let bootstrap = read_vk_js_bootstrap(&mut stdin).await?;
        let started = vk_js_calls::start(bootstrap, js_auth_mode, &session_profile)
            .await
            .map_err(auth_error_or_fatal)?;
        let hashes = started.hashes.join(",");
        js_calls = Some(started.active);
        js_credential_broker = Some(started.credential_broker);
        hashes
    } else {
        arguments.vk.clone()
    };
    let hashes: Vec<_> = parse_hashes(&hash_source)
        .into_iter()
        .take(MAX_VK_HASHES)
        .collect();
    if hashes.is_empty() {
        bail!("[КЛИЕНТ] Нет хешей VK");
    }
    let workers = if arguments.exact_workers {
        arguments.workers.clamp(3, MAX_WORKERS)
    } else {
        normalize_worker_count(arguments.workers)
    };
    let groups = workers.div_ceil(WORKERS_PER_GROUP);
    let cancel = CancellationToken::new();
    let captcha = CaptchaSolver::new(&arguments.captcha_mode, cancel.clone());
    let events = Events::from_env();
    events.install();
    let client_ids: Vec<_> = arguments
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    let mut auth = VkAuth::new(
        &vk_auth_mode,
        session_profile,
        &client_ids,
        captcha.clone(),
        js_credential_broker,
    );
    if jitsi_mode {
        auth = auth.with_jitsi_room();
    }
    let auth = Arc::new(auth);
    let stats = Arc::new(Stats::default());
    let paused = Arc::new(PauseGate::new());
    let finish_js_calls = Arc::new(AtomicBool::new(false));
    let parent_task = start_parent_monitor(cancel.clone());
    events.process(std::process::id());
    let pool = PacketPool::new(packet_pool_size(MAX_WORKERS));
    let tun_uds = (!arguments.tun_uds.is_empty()).then_some(arguments.tun_uds.clone());
    let mut direct_domains = crate::sni::parse_direct_domains(&arguments.direct_domains);
    for file_path in &arguments.domains_file {
        if let Ok(content) = std::fs::read_to_string(file_path) {
            // Комментарии в файле списка не должны попадать в правила.
            let file_domains = crate::sni::parse_direct_domains(&strip_comments(&content));
            for domain in file_domains {
                if !direct_domains.contains(&domain) {
                    direct_domains.push(domain);
                }
            }
        }
    }
    // geosite-category-ru (MetaCubeX/meta-rules-dat): российские сервисы открываем
    // напрямую, мимо туннеля. Список приходит файлом из ассетов приложения, путь
    // передаёт Android-клиент; отдельный набор, потому что в нём тысячи доменов и
    // линейный поиск по пользовательскому списку на каждый SNI был бы заметен.
    let mut ru_services = std::collections::HashSet::new();
    for file_path in &arguments.ru_domains_file {
        match std::fs::read_to_string(file_path) {
            Ok(content) => {
                for domain in crate::sni::parse_direct_domains(&strip_comments(&content)) {
                    ru_services.insert(domain);
                }
            }
            Err(error) => {
                crate::log_error!("[DNS-РОУТИНГ] geosite-category-ru недоступен: {error}")
            }
        }
    }
    if !ru_services.is_empty() {
        let installed = crate::sni::install_direct_domain_set(ru_services);
        crate::log_error!(
            "[DNS-РОУТИНГ] geosite-category-ru: {} доменов российских сервисов идут напрямую",
            installed
        );
    }
    direct_domains.truncate(4096);
    // Категории geosite: пользователь мог включить их в приложении, файл каждой
    // категории кладёт рядом с базовым списком сам клиент.
    let categories: Vec<String> = arguments
        .adblock_category
        .split(|c: char| c == ',' || c == ';' || c.is_whitespace())
        .filter_map(crate::adblock::normalize_category)
        .collect();
    crate::adblock::mark_categories(categories.iter().map(String::as_str));
    if arguments.adblock {
        let base = if arguments.adblock_file.trim().is_empty() {
            "adblock.txt".to_owned()
        } else {
            arguments.adblock_file.clone()
        };
        let mut files = vec![base.clone()];
        for extra in &arguments.adblock_rules {
            if !extra.trim().is_empty() && !files.contains(extra) {
                files.push(extra.clone());
            }
        }
        let count = crate::adblock::init_from_files(&files);
        if count == 0 {
            crate::log_error!("[ADBLOCK] Списки не загружены или пусты: {base}");
        }
    }
    let protected_ips = parse_protected_ips(&arguments.protected_ips);
    let ip_rules = crate::sni::parse_ip_rules(&arguments.direct_ips);
    let domain_routing = dispatcher::DomainRoutingConfig {
        enabled: arguments.domain_routing,
        exclude_ru_zones: arguments.exclude_ru_zones,
        template_domains: arguments.template_domains,
        direct_domains: Arc::new(direct_domains),
        is_whitelist: arguments.domain_whitelist,
        ip_routing_enabled: arguments.ip_routing,
        ip_is_whitelist: arguments.ip_whitelist,
        ip_rules: Arc::new(ip_rules),
    };
    let dispatcher_result = Dispatcher::start_with_domain_routing(
        &arguments.listen,
        tun_uds,
        pool.clone(),
        stats.clone(),
        cancel.clone(),
        domain_routing,
        workers,
        protected_ips,
    )
    .await;
    let (dispatcher, local_port) = match dispatcher_result {
        Ok(value) => value,
        Err(error) => {
            if let Some(active) = js_calls.take() {
                active.leave_creator().await;
            }
            return Err(error);
        }
    };
    if arguments.game_mode || !arguments.game_ports.trim().is_empty() {
        let payload = format!(
            "{}|{}",
            if arguments.game_mode { "1" } else { "0" },
            arguments.game_ports
        );
        let (enabled, ports) = dispatcher.update_game_mode(&payload);
        crate::log_error!(
            "[ИГРОВОЙ РЕЖИМ] Включён: {}, портов игр: {}",
            enabled,
            ports
        );
    }
    let control_task = start_control_input(
        cancel.clone(),
        paused.clone(),
        captcha,
        events.clone(),
        finish_js_calls.clone(),
        dispatcher.clone(),
        stdin,
    );
    // Порт виден только приложению (в лог не пишем): по нему оно понимает,
    // что туннель поднялся, даже если сам порт в режиме скрытия не показывается.
    events.local_port(&local_port);
    let local_port: Arc<str> = Arc::from(local_port);
    let raw_turn_ips = if !arguments.custom_turn_ips.is_empty() {
        &arguments.custom_turn_ips
    } else {
        &arguments.turn
    };
    let turn_ip_pool = Arc::new(TurnIpPool::new(
        parse_custom_turn_ips(raw_turn_ips),
        MAX_WORKERS,
    ));
    let initial_workers = workers;
    let scale_notify = Arc::new(tokio::sync::Notify::new());
    let params_workers = Arc::new(AtomicUsize::new(initial_workers));
    let params = Arc::new(RuntimeParams {
        peer,
        turn_host: (!arguments.turn.is_empty()).then(|| Arc::from(arguments.turn.as_str())),
        turn_port: (!arguments.port.is_empty()).then(|| Arc::from(arguments.port.as_str())),
        turn_ip_pool,
        turn_transport,
        srtp_mode,
        hashes: hashes.into(),
        wrap_key,
        client_wrap_key,
        mode,
        stealth,
        generation: arguments.generation,
        salt: Arc::from(arguments.salt.as_str()),
        local_port: local_port.clone(),
        password: Arc::from(password.as_str()),
        workers: params_workers,
        configured_workers: workers,
        jitsi_room,
    });
    print_configuration(
        &arguments,
        auth.client_ids(),
        workers,
        groups,
        &params,
        &local_port,
    );
    let repair = RepairState::new(MAX_WORKERS);
    let stats_task = tokio::spawn(stats.clone().run(events.clone(), cancel.clone()));
    let (config_tx, mut config_rx) = tokio::sync::mpsc::channel::<String>(32);
    let config_events = events.clone();
    #[cfg(target_os = "linux")]
    let mut exit_tun = (!arguments.exit_tun.is_empty()).then(|| linux_exit::ExitTun {
        name: arguments.exit_tun.clone(),
        socket: arguments.tun_uds.clone(),
        hook: arguments.exit_hook.clone(),
    });
    let config_task = tokio::spawn(async move {
        let mut last_config = None;
        while let Some(config) = config_rx.recv().await {
            if last_config.as_deref() == Some(config.as_str()) {
                continue;
            }
            if let Some(value) = config.strip_prefix("TUNCONF:") {
                dns::mark_tunnel_active();
                let mut fields = value.splitn(3, ':');
                let ip = fields.next().unwrap_or_default();
                let dns = fields.next().unwrap_or_default();
                crate::log_error!("[КЛИЕНТ] Tunnel IP: {ip}/32 | DNS: {dns}");
                #[cfg(target_os = "linux")]
                if let Some(exit) = exit_tun.take() {
                    let ip = ip.to_owned();
                    let attached = tokio::task::spawn_blocking(move || linux_exit::attach(&exit, &ip)).await;
                    if !matches!(attached, Ok(Ok(()))) {
                        crate::log_error!("[КАСКАД] Не удалось поднять TUN: {attached:?}");
                        // systemd restarts the process: without the TUN this client is of no use to anyone.
                        std::process::exit(1);
                    }
                }
            }
            config_events.config(&config);
            last_config = Some(config);
        }
    });
    let (ready_credential_tx, ready_credential_rx) =
        if !max_mode && should_leave_js_creator(js_hash_mode, js_auth_mode) {
            let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
            (Some(tx), Some(rx))
        } else {
            (None, None)
        };
    let context = Arc::new(GroupContext {
        params,
        auth,
        dispatcher: dispatcher.clone(),
        pool,
        stats,
        events: events.clone(),
        paused,
        config_tx,
        start_pacer: Arc::new(WorkerStartPacer::new(WORKER_START_INTERVAL)),
        credential_pacer: Arc::new(tokio::sync::Mutex::new(())),
        ready_credential_tx,
        config_sent: Arc::new(AtomicBool::new(false)),
        config_in_flight: Arc::new(AtomicBool::new(false)),
        server_stream_repair: Arc::new(AtomicBool::new(false)),
        repair,
        shutdown: Arc::new(ShutdownCoordinator::new()),
        cancel: cancel.clone(),
        scale_notify,
    });
    let required_ready_bots = required_js_ready_bots(groups);
    if js_auth_mode {
        crate::log_error!(
            "[{}] Создатель удерживает звонок",
            if max_mode { "MAX" } else { "VK JS" }
        );
    }
    let creator_leave_task = match (js_calls.as_ref(), ready_credential_rx) {
        (Some(active), Some(receiver)) => Some(tokio::spawn(leave_js_creator_after_ready_workers(
            active.clone(),
            receiver,
            required_ready_bots,
            cancel.clone(),
        ))),
        _ => None,
    };
    let shutdown_events = events.clone();
    let tun_lost_events = events.clone();
    let tun_io_lost = dispatcher.tun_io_lost.clone();
    tokio::spawn(async move {
        loop {
            tun_io_lost.notified().await;
            tun_lost_events.tun_lost();
        }
    });
    let groups_future = run_groups(context);
    tokio::pin!(groups_future);
    let groups_completed = tokio::select! {
        _ = &mut groups_future => true,
        _ = tokio::signal::ctrl_c() => {
            crate::log_error!("[КЛИЕНТ] Получен сигнал завершения");
            cancel.cancel();
            false
        }
        _ = cancel.cancelled() => false,
    };
    cancel.cancel();
    if !groups_completed {
        groups_future.await;
    }
    dispatcher.shutdown().await;
    stats_task.abort();
    config_task.abort();
    control_task.abort();
    parent_task.abort();
    let _ = stats_task.await;
    let _ = config_task.await;
    let _ = control_task.await;
    let _ = parent_task.await;
    if let Some(mut task) = creator_leave_task
        && tokio::time::timeout(Duration::from_secs(9), &mut task)
            .await
            .is_err()
    {
        task.abort();
        let _ = task.await;
    }
    if let Some(active) = js_calls.take() {
        if finish_js_calls.load(Ordering::Acquire) {
            active.finish().await;
        } else {
            active.leave_creator().await;
        }
    }
    let termination = dispatcher.termination();
    shutdown_events.stopped();
    crate::log_error!("[КЛИЕНТ] Все воркеры завершены");
    match termination {
        None => Ok(EXIT_NORMAL),
        Some(Termination::TunLost) => Ok(EXIT_TUN_LOST),
        Some(Termination::Fatal) => bail!("аварийное завершение TUN или UDP задачи"),
    }
}

async fn leave_js_creator_after_ready_workers(
    active: vk_js_calls::ActiveCalls,
    receiver: tokio::sync::mpsc::UnboundedReceiver<usize>,
    required_ready_bots: usize,
    cancel: CancellationToken,
) {
    let all_ready = wait_for_js_credential_readiness(receiver, required_ready_bots, cancel).await;
    if all_ready {
        crate::log_error!("[VK JS] TURN-боты готовы, создатель выходит из звонка");
    }
    let _ = tokio::time::timeout(Duration::from_secs(8), active.leave_creator()).await;
}

async fn wait_for_js_credential_readiness(
    mut receiver: tokio::sync::mpsc::UnboundedReceiver<usize>,
    expected_credentials: usize,
    cancel: CancellationToken,
) -> bool {
    let mut ready = HashSet::with_capacity(expected_credentials);
    while ready.len() < expected_credentials {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => break,
            credential = receiver.recv() => match credential {
                Some(credential) => {
                    ready.insert(credential);
                }
                None => break,
            },
        }
    }
    ready.len() == expected_credentials
}

fn required_js_ready_bots(groups: usize) -> usize {
    groups.div_ceil(GROUPS_PER_CREDENTIAL).clamp(1, 2)
}

fn normalize_worker_count(requested: usize) -> usize {
    if requested <= 5 {
        return 5;
    }
    requested.clamp(WORKERS_PER_GROUP, MAX_WORKERS) / WORKERS_PER_GROUP * WORKERS_PER_GROUP
}

fn should_leave_js_creator(js_hash_mode: bool, js_auth_mode: bool) -> bool {
    js_hash_mode && js_auth_mode
}

fn normalized_arguments() -> Vec<String> {
    std::env::args().map(normalize_cli_argument).collect()
}

const ANDROID_LONG_FLAGS: &[&str] = &[
    "turn",
    "custom-turn-ips",
    "port",
    "listen",
    "vk",
    "max",
    "jitsi",
    "vk-hash-mode",
    "peer",
    "client-ids",
    "vk-auth-mode",
    "captcha-mode",
    "fingerprint",
    "obfs",
    "turn-transport",
    "srtp-mode",
    // Без этой строки `-stealth` уходит в clap как набор коротких ключей
    // (`-s -t -e …`) и клиент падает с «unexpected argument '-s' found».
    "stealth",
    "gen",
    "salt",
    "tun-uds",
    "validate-vk-hashes",
    "domain-routing",
    "exclude-ru-zones",
    "template-domains",
    "domain-whitelist",
    "direct-domains",
    "domains-file",
    "adblock",
    "adblock-file",
    "adblock-rules",
    "adblock-category",
    "ru-domains-file",
    "protected-ips",
    "game-mode",
    "game-ports",
    "ip-routing",
    "ip-whitelist",
    "direct-ips",
    "runtime-threads",
    "workers",
    "exact-workers",
];

fn normalize_cli_argument(argument: String) -> String {
    let Some(rest) = argument.strip_prefix('-') else {
        return argument;
    };
    let name = rest.split_once('=').map_or(rest, |(name, _)| name);
    if !rest.starts_with('-') && ANDROID_LONG_FLAGS.contains(&name) {
        format!("-{argument}")
    } else {
        argument
    }
}

fn normalize_stdin_line(mut bytes: Vec<u8>) -> String {
    while bytes.last() == Some(&b'\n') || bytes.last() == Some(&b'\r') {
        bytes.pop();
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn spawn_stdin_reader() -> tokio::sync::mpsc::Receiver<StdinLine> {
    let (sender, receiver) = tokio::sync::mpsc::channel(STDIN_QUEUE_CAPACITY);
    std::thread::spawn(move || {
        let stdin = std::io::stdin();
        let mut reader = stdin.lock();
        let mut line = Vec::new();
        loop {
            let available = match reader.fill_buf() {
                Ok(available) => available,
                Err(error) => {
                    let _ = sender.blocking_send(Err(error));
                    break;
                }
            };
            if available.is_empty() {
                if !line.is_empty() {
                    let _ = sender.blocking_send(Ok(normalize_stdin_line(line)));
                }
                break;
            }
            let mut chunk_end = available.len();
            let mut complete = false;
            if let Some(position) = available.iter().position(|&b| b == b'\n') {
                chunk_end = position;
                complete = true;
            }
            line.extend_from_slice(&available[..chunk_end]);
            let consumed = if complete {
                chunk_end + 1
            } else {
                available.len()
            };
            reader.consume(consumed);

            if line.len() > MAX_STDIN_LINE_BYTES {
                let _ = sender.blocking_send(Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "stdin line too large",
                )));
                break;
            }
            if complete
                && sender
                    .blocking_send(Ok(normalize_stdin_line(std::mem::take(&mut line))))
                    .is_err()
            {
                break;
            }
        }
    });
    receiver
}

async fn read_max_bootstrap(
    stdin: &mut tokio::sync::mpsc::Receiver<StdinLine>,
) -> Result<vk_js_calls::Bootstrap> {
    let line = tokio::time::timeout(Duration::from_secs(15), stdin.recv())
        .await
        .context("MAX bootstrap timeout")?
        .context("MAX bootstrap stdin closed")?
        .context("MAX bootstrap stdin read error")?;
    let encoded = line
        .trim()
        .strip_prefix("MAX_BOOTSTRAP:")
        .context("Ожидается MAX_BOOTSTRAP:<base64-json>")?;
    if encoded.len() > 32 * 1024 {
        bail!("MAX bootstrap too large");
    }
    let decoded = STANDARD
        .decode(encoded)
        .context("MAX bootstrap base64 invalid")?;
    let bootstrap: vk_js_calls::Bootstrap =
        serde_json::from_slice(&decoded).context("MAX bootstrap JSON invalid")?;
    if bootstrap.token.trim().is_empty() {
        bail!("MAX bootstrap token empty");
    }
    Ok(bootstrap)
}

async fn read_vk_js_bootstrap(
    stdin: &mut tokio::sync::mpsc::Receiver<StdinLine>,
) -> Result<vk_js_calls::Bootstrap> {
    let line = tokio::time::timeout(Duration::from_secs(15), stdin.recv())
        .await
        .context("тайм-аут передачи данных Auto JS")?
        .context("канал stdin закрыт до передачи данных Auto JS")?
        .context("чтение данных Auto JS")?;
    let encoded = line
        .trim()
        .strip_prefix("VK_JS_BOOTSTRAP:")
        .context("неверный формат данных Auto JS")?;
    if encoded.len() > 32 * 1024 {
        bail!("слишком большие данные Auto JS");
    }
    let decoded = STANDARD
        .decode(encoded)
        .context("повреждены данные Auto JS")?;
    let bootstrap: vk_js_calls::Bootstrap =
        serde_json::from_slice(&decoded).context("некорректные данные Auto JS")?;
    Ok(bootstrap)
}

async fn run_vk_hash_validation(arguments: &Arguments) -> Result<()> {
    let hashes: Vec<_> = parse_hashes(&arguments.vk)
        .into_iter()
        .take(MAX_VK_HASHES)
        .collect();
    if hashes.is_empty() {
        bail!("[КЛИЕНТ] Нет хешей VK для проверки");
    }
    let client_ids: Vec<_> = arguments
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .collect();
    for (hash, result) in auth::check_vk_hashes(&arguments.fingerprint, &client_ids, &hashes).await
    {
        let payload = match result {
            VkHashCheck::Valid => serde_json::json!({
                "hash": hash,
                "status": "valid"
            }),
            VkHashCheck::Invalid { code, message } => serde_json::json!({
                "hash": hash,
                "status": "invalid",
                "code": code,
                "message": message
            }),
            VkHashCheck::Unavailable { message } => serde_json::json!({
                "hash": hash,
                "status": "unavailable",
                "message": message
            }),
        };
        println!("HASH_CHECK:{payload}");
    }
    Ok(())
}

fn strip_comments(content: &str) -> String {
    content
        .lines()
        .filter(|line| {
            let trimmed = line.trim_start();
            !trimmed.starts_with('#') && !trimmed.starts_with('!')
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn parse_protected_ips(raw: &str) -> std::collections::HashSet<std::net::IpAddr> {
    raw.split(|c: char| c == ',' || c == ';' || c.is_whitespace())
        .filter_map(|token| {
            let cleaned = token.trim().trim_matches(|c| c == '[' || c == ']');
            cleaned.parse().ok()
        })
        .collect()
}

async fn resolve_peer(peer: &str) -> Result<SocketAddr> {
    let mut last_error = None;
    for _ in 0..15 {
        match dns::resolve_socket(peer).await {
            Ok(address) => return Ok(address),
            Err(error) => last_error = Some(error),
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("пустой DNS-ответ для пира")))
        .context("ошибка разбора пира")
}

fn start_control_input(
    cancel: CancellationToken,
    paused: Arc<PauseGate>,
    captcha: Arc<CaptchaSolver>,
    events: Events,
    finish_js_calls: Arc<AtomicBool>,
    dispatcher: Arc<Dispatcher>,
    mut stdin: tokio::sync::mpsc::Receiver<StdinLine>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let control_required = events.enabled();
        loop {
            let next = tokio::select! {
                _ = cancel.cancelled() => return,
                next = stdin.recv() => next,
            };
            let line = match next {
                Some(Ok(line)) => line,
                Some(Err(error)) => {
                    crate::log_error!("[КЛИЕНТ] Ошибка канала управления: {error}");
                    if control_required {
                        cancel.cancel();
                    }
                    return;
                }
                None => {
                    if control_required {
                        crate::log_error!("[КЛИЕНТ] Канал управления закрыт");
                        cancel.cancel();
                    }
                    return;
                }
            };
            let line = line.trim();
            if !line.contains("error:tunnel stopped") && line != "FINISH_VK_CALLS" {
                crate::log_error!("[STDIN] {}", loggable_stdin_line(line));
            }
            match line {
                "PAUSE" => paused.set_paused(true),
                "RESUME" => paused.set_paused(false),
                "FINISH_VK_CALLS" => finish_js_calls.store(true, Ordering::Release),
                "STOP" => {
                    crate::log_error!("[КЛИЕНТ] Получена команда STOP");
                    cancel.cancel();
                    return;
                }
                _ => {
                    if let Some(result) = line.strip_prefix("CAPTCHA_RESULT|") {
                        if captcha.submit_result(result.to_owned()) {
                            crate::log_error!("[КАПЧА] Результат от Kotlin записан в канал");
                        } else {
                            crate::log_error!(
                                "[КАПЧА] Канал результата уже заполнен, устаревший ответ отклонён"
                            );
                        }
                    } else if let Some(payload) = line.strip_prefix("UPDATE_DOMAINS|") {
                        dispatcher.update_domain_routing(payload);
                    } else if let Some(payload) = line.strip_prefix("GAME_MODE|") {
                        let (enabled, ports) = dispatcher.update_game_mode(payload);
                        crate::log_error!(
                            "[ИГРОВОЙ РЕЖИМ] {} · портов игр: {ports}",
                            if enabled { "включён" } else { "выключен" }
                        );
                    } else if let Some(payload) = line.strip_prefix("APP_DIRECT|") {
                        let enabled = payload.trim() != "0";
                        dispatcher.update_app_direct(enabled);
                    } else if let Some(payload) = line.strip_prefix("APP_DIRECT_FLOW|") {
                        dispatcher.update_app_direct_flow(payload);
                    } else if let Some(spec) = line.strip_prefix("PROBE|") {
                        let nonce = std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map_or(1, |elapsed| elapsed.subsec_nanos());
                        let target = tunnel_probe::parse_command(spec, nonce);
                        let dispatcher = dispatcher.clone();
                        let events = events.clone();
                        tokio::spawn(async move {
                            let ms = match target {
                                Some(target) => dispatcher.probe_tunnel(target).await,
                                None => None,
                            };
                            events.probe(ms);
                        });
                    }
                }
            }
        }
    })
}

fn loggable_stdin_line(line: &str) -> &str {
    if line.starts_with("CAPTCHA_RESULT|") {
        "CAPTCHA_RESULT|<скрыто>"
    } else if line.starts_with("VK_JS_BOOTSTRAP:") {
        "VK_JS_BOOTSTRAP:<скрыто>"
    } else {
        line
    }
}

fn start_parent_monitor(cancel: CancellationToken) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        #[cfg(unix)]
        {
            let parent = unsafe { libc::getppid() };
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_secs(2)) => {}
                }
                if unsafe { libc::getppid() } != parent {
                    cancel.cancel();
                    return;
                }
            }
        }
        #[cfg(not(unix))]
        cancel.cancelled().await;
    })
}

fn print_configuration(
    arguments: &Arguments,
    client_ids: String,
    workers: usize,
    groups: usize,
    params: &RuntimeParams,
    local_port: &str,
) {
    let hashes = params.hashes.len();
    let turn_transport = params.turn_transport;
    let srtp_mode = params.srtp_mode;
    let captcha = match arguments.captcha_mode.as_str() {
        "wv" => "WBV selected in Android",
        "rjs" => "RJS Rust v2 with WBV Auto fallback",
        _ => "AUTO: Rust v2 x2 -> WBV Auto x2 -> Rust v2 x1 -> Manual WBV",
    };
    crate::log_error!("[КЛИЕНТ] ═══════════════════════════════════════");
    crate::log_error!("[КЛИЕНТ] VK Creds: Client IDs: {client_ids}");
    crate::log_error!("[КЛИЕНТ] VK Auth: {}", arguments.vk_auth_mode);
    crate::log_error!("[КЛИЕНТ] TLS: {} fingerprint", arguments.fingerprint);
    crate::log_error!("[КЛИЕНТ] Воркеров: {workers} (групп: {groups}, по {WORKERS_PER_GROUP})");
    crate::log_error!("[КЛИЕНТ] Хешей: {hashes}");
    crate::log_error!(
        "[КЛИЕНТ] Слушаю: {} (порт {}) | Пир: {}",
        arguments.listen,
        if arguments.stealth { "скрыт" } else { local_port },
        arguments.peer
    );
    crate::log_error!(
        "[КЛИЕНТ] TURN: {} | SRTP: {} | DTLS: 1.2 (ECDSA P-256) | WRAP: ON | obfs={}",
        turn_transport.as_str(),
        srtp_mode.as_str(),
        arguments.obfs,
    );
    if arguments.stealth {
        // Режим скрытия меняет картину пакетов, об этом стоит сказать в логе,
        // но без подробностей, по которым можно опознать туннель.
        crate::log_error!("[КЛИЕНТ] Скрытие: порты закрыты, трафик под медиа-поток");
    }
    crate::log_error!("[WRAP] WRAP Ключ вычислен ✓");
    crate::log_error!("[КЛИЕНТ] Captcha: {captcha}");
    if arguments.domain_routing {
        crate::log_error!(
            "[КЛИЕНТ] Доменная маршрутизация: ВКЛ (RU/SU/РФ: {}, кастомных доменов: {})",
            if arguments.exclude_ru_zones {
                "ДА"
            } else {
                "НЕТ"
            },
            arguments
                .direct_domains
                .split(',')
                .filter(|s| !s.trim().is_empty())
                .count()
        );
    }
    crate::log_error!("[КЛИЕНТ] ═══════════════════════════════════════");
}

#[cfg(test)]
mod network_classification_tests {
    use super::*;

    fn classify(message: &str) -> bool {
        is_network_transient(&anyhow::anyhow!("{message}"))
    }

    #[test]
    fn rejected_max_token_is_not_a_network_problem() {
        // Именно эта строка приходила с телефона: сервер MAX отказал в пароле.
        // Повторять её как «сеть» — значит крутить перезапуски вечно.
        let message = "MAX call token: не удалось ни по TCP, ни по WS \
             (MAX TCP: token rejected (login.cred) на opcode 19: \
             Map([(Str(\"error\"), Str(\"login.cred\"))]))";
        assert!(!classify(message));
    }

    #[test]
    fn missing_call_token_is_not_a_network_retry() {
        // Сервер закрыл сессию и не выдал токен звонков: это состояние
        // аккаунта. Крутить перезапуски бессмысленно — нужен повторный вход.
        assert!(!classify("MAX TCP: сервер не выдал токен для звонков (158)"));
        assert!(!classify(
            "MAX TCP: сервер вернул ошибку на opcode 8: proto.state Must be ONLINE session"
        ));
    }

    #[test]
    fn dropped_max_connection_is_retried_not_fatal() {
        // Раньше цикл ожидания глотал «early eof» и крутился вечно, заливая
        // журнал; после правки это обычная сетевая ошибка → переподключение.
        assert!(classify("MAX TCP: соединение потеряно на opcode 158: MAX TCP: чтение заголовка: early eof"));
        assert!(classify("MAX TCP: чтение заголовка: early eof"));
        // А отказ токена по-прежнему не сеть.
        assert!(!classify("MAX TCP: token rejected (login.cred) на opcode 19"));
    }

    #[test]
    fn web_session_token_is_reported_as_rejected_not_retried() {
        // Новый текст из max_tcp.rs: токен веб-сессии приложению-протоколу не
        // подходит. Это не сеть — повторять с ним бессмысленно.
        assert!(!classify(
            "MAX TCP: token rejected (login.cred): web-токен не принимается приложением-протоколом"
        ));
    }

    #[test]
    fn real_max_transport_failures_stay_transient() {
        assert!(classify("MAX TCP: таймаут TCP к 155.212.204.90"));
        assert!(classify("MAX TCP: подключение к api.oneme.ru: TLS"));
        assert!(classify("WS подключение ws-api.oneme.ru: Connection timed out"));
    }
}

#[cfg(test)]
mod worker_count_tests {
    use super::*;

    #[test]
    fn every_supported_total_maps_to_complete_allocation_groups() {
        for groups in 1..=MAX_WORKERS / WORKERS_PER_GROUP {
            let workers = groups * WORKERS_PER_GROUP;
            assert_eq!(normalize_worker_count(workers), workers);
            assert_eq!(workers / WORKERS_PER_GROUP, groups);
        }
        assert_eq!(normalize_worker_count(MAX_WORKERS), 100);
    }

    #[test]
    fn invalid_totals_never_create_partial_or_excess_group() {
        for requested in 0..=1_000 {
            let workers = normalize_worker_count(requested);
            if requested <= 5 {
                assert_eq!(workers, 5);
            } else {
                assert!((WORKERS_PER_GROUP..=MAX_WORKERS).contains(&workers));
                assert_eq!(workers % WORKERS_PER_GROUP, 0);
            }
        }
    }

    #[test]
    fn runtime_thread_budget_follows_stream_budget_and_cpu_limit() {
        assert_eq!(runtime_worker_threads_with_limit(5, 0, 16), 1);
        assert_eq!(runtime_worker_threads_with_limit(10, 0, 16), 2);
        assert_eq!(runtime_worker_threads_with_limit(15, 0, 16), 2);
        assert_eq!(runtime_worker_threads_with_limit(20, 0, 16), 3);
        assert_eq!(runtime_worker_threads_with_limit(30, 0, 16), 4);
        assert_eq!(runtime_worker_threads_with_limit(50, 0, 16), 7);
        assert_eq!(runtime_worker_threads_with_limit(100, 0, 16), 13);
        assert_eq!(runtime_worker_threads_with_limit(100, 3, 16), 3);
        assert_eq!(runtime_worker_threads_with_limit(100, 32, 16), 16);
    }

    #[test]
    fn native_worker_normalization_is_bounded_and_group_aligned() {
        assert_eq!(normalize_worker_count(usize::MAX), MAX_WORKERS);
        assert_eq!(normalize_worker_count(usize::MAX - 1), MAX_WORKERS);
        assert_eq!(normalize_worker_count(48), 40);
        assert_eq!(normalize_worker_count(5), 5);
        assert_eq!(normalize_worker_count(4), 5);
        assert!(
            MAX_WORKERS.div_ceil(worker::WORKERS_PER_CREDENTIAL)
                <= vk_js_calls::MAX_ACCOUNT_CREDENTIALS
        );
    }

    #[test]
    fn auto_js_leaves_creator_only_when_both_modes_are_active() {
        assert!(!should_leave_js_creator(true, false));
        assert!(should_leave_js_creator(true, true));
        assert!(!should_leave_js_creator(false, false));
        assert!(!should_leave_js_creator(false, true));
    }

    #[test]
    fn auto_js_waits_for_at_most_two_independent_turn_bots() {
        assert_eq!(required_js_ready_bots(1), 1);
        assert_eq!(required_js_ready_bots(2), 2);
        assert_eq!(required_js_ready_bots(3), 2);
        assert_eq!(required_js_ready_bots(4), 2);
        assert_eq!(required_js_ready_bots(13), 2);
    }

    #[test]
    fn vk_hash_may_start_with_a_hyphen() {
        let arguments = Arguments::try_parse_from([
            "mrtt-client",
            "--vk",
            "-Wabc",
            "--peer",
            "127.0.0.1:9000",
        ])
        .unwrap();
        assert_eq!(arguments.vk, "-Wabc");
    }

    #[test]
    fn android_single_dash_flags_do_not_rewrite_hyphenated_hash_values() {
        assert_eq!(normalize_cli_argument("-vk".to_owned()), "--vk");
        assert_eq!(
            normalize_cli_argument("-client-ids".to_owned()),
            "--client-ids"
        );
        assert_eq!(normalize_cli_argument("-Wabc".to_owned()), "-Wabc");
    }

    #[test]
    fn local_port_event_is_parsed_without_leaking_into_logs() {
        // Приложение узнаёт занятый порт событием, а не строкой лога: в режиме
        // скрытия порт не должен попадать в журнал и уведомление.
        let line = crate::events::encode_event("LOCAL_PORT", &serde_json::json!({"port": 41234}))
            .expect("событие кодируется");
        assert!(line.starts_with("__MRTT_EVENT__|"));
        assert!(line.contains("LOCAL_PORT"));
        assert!(line.contains("41234"));
        assert!(!line.contains("Слушаю"));
    }

    #[test]
    fn every_single_dash_flag_is_known_to_the_normalizer() {
        // Страховка на будущее: новый ключ, который Android шлёт одним дефисом,
        // обязан попасть в ANDROID_LONG_FLAGS. Иначе clap разберёт его как
        // набор коротких ключей и клиент умрёт на старте.
        for flag in [
            "-turn", "-custom-turn-ips", "-port", "-listen", "-vk", "-max", "-jitsi",
            "-vk-hash-mode", "-peer", "-client-ids", "-vk-auth-mode", "-captcha-mode",
            "-fingerprint", "-obfs", "-stealth", "-turn-transport", "-srtp-mode",
            "-gen", "-salt", "-tun-uds", "-protected-ips",
        ] {
            assert_eq!(
                normalize_cli_argument(flag.to_owned()),
                format!("-{flag}"),
                "ключ {flag} должен превращаться в двойной дефис"
            );
        }
        // Значения-хеши остаются как есть.
        assert_eq!(normalize_cli_argument("-Wabc".to_owned()), "-Wabc");
    }

    #[test]
    fn every_flag_the_android_app_sends_is_accepted() {
        // Ровно та командная строка, что собирает TunnelManager со всеми
        // включёнными настройками: скрытие портов/отпечатка, adblock, маршруты.
        // Раньше `-stealth` здесь превращался в `-s -t -e …` и клиент падал с
        // «unexpected argument '-s' found».
        let raw_args = [
            "mrtt-client",
            "-peer", "1.2.3.4:46000",
            "-n", "8",
            "--exact-workers",
            "-listen", "127.0.0.1:0",
            "-tun-uds", "mrtt_tun_uds",
            "-vk", "-Wabc,-Wdef",
            "-vk-hash-mode", "manual",
            "-fingerprint", "firefox",
            "-client-ids", "1,2",
            "-obfs", "unified",
            "-stealth",
            "-turn-transport", "udp",
            "-srtp-mode", "auto",
            "-custom-turn-ips", "",
            "-vk-auth-mode", "vkcalls",
            "-gen", "42",
            "-salt", "abc",
            "-captcha-mode", "auto",
            "--domain-routing",
            "--exclude-ru-zones",
            "--template-domains",
            "--domains-file", "domains.txt",
            "--direct-domains", "example.com",
            "--protected-ips", "5.6.7.8",
            "--ip-routing",
            "--direct-ips", "",
            "--adblock",
            "--adblock-file", "adblock.txt",
            "--adblock-rules", "adblock-user.txt",
            "--adblock-rules", "adblock-subscriptions.txt",
            "--adblock-category", "geosite-category-ru@ads",
            "--ru-domains-file", "geosite-category-ru.txt",
        ];
        let normalized = raw_args
            .iter()
            .map(|s| normalize_cli_argument(s.to_string()));
        let arguments = Arguments::try_parse_from(normalized).expect("командная строка Android");
        assert!(arguments.stealth, "скрытие трафика должно включиться");
        assert!(arguments.adblock);
        assert!(arguments.domain_routing);
        assert_eq!(arguments.listen, "127.0.0.1:0");
        assert_eq!(arguments.workers, 8);
    }

    #[test]
    fn removed_identity_flags_are_not_normalized_or_accepted() {
        assert_eq!(
            normalize_cli_argument("-client-id".to_owned()),
            "-client-id"
        );
        assert_eq!(
            normalize_cli_argument("-device-id".to_owned()),
            "-device-id"
        );
        assert_eq!(normalize_cli_argument("-password".to_owned()), "-password");
        assert!(
            Arguments::try_parse_from([
                "mrtt-client",
                "--peer",
                "127.0.0.1:9000",
                "--password",
                "secret",
            ])
            .is_err()
        );
    }

    #[test]
    fn hyphenated_hash_values_parse_without_redistribution_flag() {
        let arguments = Arguments::try_parse_from([
            "mrtt-client",
            "--vk",
            "-Wabc,-Wdef",
            "--peer",
            "127.0.0.1:9000",
        ])
        .unwrap();
        assert_eq!(arguments.vk, "-Wabc,-Wdef");
    }

    #[tokio::test]
    async fn js_creator_waits_for_every_distinct_ready_credential() {
        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        sender.send(1).unwrap();
        sender.send(1).unwrap();
        sender.send(2).unwrap();
        assert!(wait_for_js_credential_readiness(receiver, 2, CancellationToken::new()).await);
    }

    #[tokio::test]
    async fn js_creator_wait_is_cancel_safe() {
        let (_sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(!wait_for_js_credential_readiness(receiver, 1, cancel).await);
    }

    #[tokio::test]
    async fn bootstrap_keeps_control_lines_that_follow_it() {
        let (sender, mut receiver) = tokio::sync::mpsc::channel(4);
        let encoded = STANDARD.encode(r#"{"token":"t"}"#);
        sender
            .send(Ok(format!("VK_JS_BOOTSTRAP:{encoded}")))
            .await
            .unwrap();
        sender.send(Ok("PAUSE".to_owned())).await.unwrap();
        let bootstrap = read_vk_js_bootstrap(&mut receiver).await.unwrap();
        assert_eq!(bootstrap.token, "t");
        assert_eq!(receiver.recv().await.unwrap().unwrap(), "PAUSE");
    }

    #[tokio::test]
    async fn max_bootstrap_carries_the_browser_device_id() {
        // Без deviceId сервер MAX отвечает на LOGIN `login.cred`.
        let (sender, mut receiver) = tokio::sync::mpsc::channel(4);
        let encoded = STANDARD.encode(r#"{"token":"abc","deviceId":"10984d3d-ac32-4992-838c-7e1866fab5e8"}"#);
        sender
            .send(Ok(format!("MAX_BOOTSTRAP:{encoded}")))
            .await
            .unwrap();
        let bootstrap = read_max_bootstrap(&mut receiver).await.unwrap();
        assert_eq!(bootstrap.device_id, "10984d3d-ac32-4992-838c-7e1866fab5e8");
    }

    #[test]
    fn captcha_and_bootstrap_payloads_are_redacted_in_stdin_logs() {
        assert_eq!(
            loggable_stdin_line("CAPTCHA_RESULT|secret-token"),
            "CAPTCHA_RESULT|<скрыто>"
        );
        assert_eq!(
            loggable_stdin_line("VK_JS_BOOTSTRAP:abc"),
            "VK_JS_BOOTSTRAP:<скрыто>"
        );
        assert_eq!(loggable_stdin_line("PAUSE"), "PAUSE");
    }

    #[test]
    fn single_dash_domain_flags_and_files_parse_correctly() {
        assert_eq!(
            normalize_cli_argument("-template-domains".to_owned()),
            "--template-domains"
        );
        assert_eq!(
            normalize_cli_argument("-domains-file".to_owned()),
            "--domains-file"
        );
        let raw_args = [
            "mrtt-client",
            "--vk",
            "abc",
            "--peer",
            "127.0.0.1:9000",
            "-template-domains",
            "-domains-file",
            "domains.txt",
        ];
        let normalized = raw_args
            .iter()
            .map(|s| normalize_cli_argument(s.to_string()));
        let arguments = Arguments::try_parse_from(normalized).unwrap();
        assert!(arguments.template_domains);
        assert_eq!(arguments.domains_file, vec!["domains.txt"]);
    }

    #[test]
    fn adblock_arguments_accept_extra_rules_categories_and_ru_domains() {
        let raw_args = vec![
            "mrtt",
            "-listen",
            "127.0.0.1:9000",
            "-adblock",
            "-adblock-file",
            "adblock.txt",
            "-adblock-rules",
            "adblock-user.txt",
            "-adblock-rules",
            "adblock-subscriptions.txt",
            "-adblock-category",
            "geosite-category-ru@ads,geosite-category-ru@telemetry",
            "-ru-domains-file",
            "geosite-category-ru.txt",
        ];
        let normalized = raw_args
            .iter()
            .map(|s| normalize_cli_argument(s.to_string()));
        let arguments = Arguments::try_parse_from(normalized).unwrap();
        assert!(arguments.adblock);
        assert_eq!(arguments.adblock_file, "adblock.txt");
        assert_eq!(
            arguments.adblock_rules,
            vec!["adblock-user.txt", "adblock-subscriptions.txt"]
        );
        assert_eq!(arguments.ru_domains_file, vec!["geosite-category-ru.txt"]);
        // Категории разбираются тем же нормализатором, что и в фильтре.
        let categories: Vec<String> = arguments
            .adblock_category
            .split(',')
            .filter_map(crate::adblock::normalize_category)
            .collect();
        assert_eq!(
            categories,
            vec![
                "geosite-category-ru@ads".to_string(),
                "geosite-category-ru@telemetry".to_string()
            ]
        );
    }
}
