use crate::{
    obfs::{ObfsConfig, ObfsState, SessionCipher},
    packet::{PacketBuf, PacketPool},
    stats::Stats,
    tun,
    turn::TurnAllocation,
};
use anyhow::Result;
use arc_swap::{ArcSwap, ArcSwapOption};
use crossbeam_queue::ArrayQueue;
use socket2::SockRef;
use std::{
    collections::{HashMap, HashSet},
    fs::File,
    future::Future,
    net::{IpAddr, SocketAddr},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    net::UdpSocket,
    sync::{Notify, mpsc},
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;

// Загрузка файла идёт одним большим TCP-потоком: скорость здесь задаёт не
// число воркеров, а то, сколько пакетов диспетчер успевает прочитать из TUN за
// один проход. С 32 пакетами и 14 КБ на итерацию чтение возвращалось к
// готовности сокета десятки раз на мегабайт, и большой файл качался заметно
// медленнее, чем позволяли потоки. Порция 128 пакетов и 64 КБ снимала это, но
// на гигабитном канале 64 КБ — это 0.5 мс работы: планировщик всё равно
// просыпается тысячи раз на мегабайт. Теперь в один проход читается до 1 МБ
// (≈ 700 пакетов по MTU), и переключений на мегабайт на порядок меньше.
#[cfg(unix)]
const TUN_RX_BURST: usize = 768;
#[cfg(unix)]
const TUN_RX_BURST_BYTES: usize = 1024 * 1024;
#[cfg(unix)]
const TUN_TX_BURST: usize = 768;
const UDP_RX_BURST: usize = 256;
// Сколько пакетов отдаём в локальный UDP-сокет за один проход. Приёмная
// очередь выросла до 2048 пакетов, и порция в 256 не успевала её разгружать.
const UDP_TX_BURST: usize = 512;

pub const TB_RATE_BYTES_PER_SEC: f64 = 247_000.0;
pub const TB_CAPACITY_BYTES: f64 = 10_500.0;
pub const QUANTUM_BYTES: usize = 10_500;
pub const MAX_DEFICIT: f64 = -4_500.0;

const ADAPTIVE_MIN_RATE_BYTES_PER_SEC: f64 = 150_000.0;
const ADAPTIVE_MAX_RATE_BYTES_PER_SEC: f64 = TB_RATE_BYTES_PER_SEC;
const ADAPTIVE_DECREASE_FACTOR: f64 = 0.85;
const ADAPTIVE_INCREASE_BYTES_PER_SEC: f64 = 5_000.0;
const ADAPTIVE_ADJUST_INTERVAL: Duration = Duration::from_millis(100);

// Один поток (загрузка файла, QUIC-видео) нельзя приклеить к одной аллокации:
// VK отдаёт одной аллокации около 2 Мбит/с, и именно поэтому большой файл
// качался медленнее мессенджера с десятками соединений. Здесь важен порядок
// внутри берста (перестановка пакетов режет TCP, поэтому берст не делаем
// совсем крошечным), но кредит канала — около 10 КБ — заканчивается раньше, и
// поток переезжает на следующий канал, у которого кредит ещё есть. Так
// скорость одной загрузки складывается из всех каналов, а не упирается в
// скорость одного.
const FLOW_BURST_BYTES: usize = 48 * 1024;
const FLOW_TABLE_MAX: usize = 1024;
const FLOW_EVICT_EVERY: u32 = 1024;
const FLOW_IDLE_TTL: Duration = Duration::from_secs(30);
const SMALL_UDP_MAX_LEN: usize = 300;
const SMALL_UDP_STICKY: Duration = Duration::from_secs(1);
// SanityProtocol 2.5.1: the small-UDP pin holds a flow on ONE stream, and VK caps each allocation at about one
// stream's rate: a pinned bulk flow (a QUIC upload, a nested tunnel) lost about half its speed. A flow keeps its pin
// only while it fits in one stream (3/4 of the stream rate); above that it is bulk for BULK_HOLD and moves in bursts.
const PIN_WINDOW: Duration = Duration::from_millis(250);
const PIN_WINDOW_MAX_BYTES: usize = (TB_RATE_BYTES_PER_SEC as usize) * 3 / 4 / 4;
const BULK_HOLD: Duration = Duration::from_secs(2);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
enum FlowKey {
    V4 {
        src: [u8; 4],
        dst: [u8; 4],
        proto: u8,
        sport: u16,
        dport: u16,
    },
    V6 {
        src: [u8; 16],
        dst: [u8; 16],
        proto: u8,
        sport: u16,
        dport: u16,
    },
}

#[derive(Clone, Copy, Debug)]
struct FlowState {
    idx: usize,
    last_seen: std::time::Instant,
    burst_left: usize,
    sticky_until: Option<std::time::Instant>,
    window_start: std::time::Instant,
    window_bytes: usize,
    bulk_until: Option<std::time::Instant>,
}

impl FlowState {
    fn new(idx: usize, now: std::time::Instant, len: usize, small_udp: bool) -> Self {
        Self {
            idx,
            last_seen: now,
            burst_left: FLOW_BURST_BYTES.saturating_sub(len),
            sticky_until: small_udp.then(|| now + SMALL_UDP_STICKY),
            window_start: now,
            window_bytes: len,
            bulk_until: None,
        }
    }

    fn keeps_pin(&mut self, now: std::time::Instant, len: usize, small_udp: bool) -> bool {
        if now.saturating_duration_since(self.window_start) >= PIN_WINDOW {
            self.window_start = now;
            self.window_bytes = 0;
        }
        self.window_bytes += len;
        if self.window_bytes > PIN_WINDOW_MAX_BYTES {
            self.bulk_until = Some(now + BULK_HOLD);
            self.sticky_until = None;
        }
        let bulk = self.bulk_until.is_some_and(|until| now < until);
        if small_udp && !bulk {
            self.sticky_until = Some(now + SMALL_UDP_STICKY);
        }
        self.sticky_until.is_some_and(|until| now < until)
    }
}

fn transport_ports(packet: &[u8], offset: usize, proto: u8) -> (u16, u16) {
    if (proto == 6 || proto == 17) && packet.len() >= offset + 4 {
        (
            u16::from_be_bytes([packet[offset], packet[offset + 1]]),
            u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]),
        )
    } else {
        (0, 0)
    }
}

fn tcp_reset_targets(packet: &[u8]) -> Option<(SocketAddr, SocketAddr, u32, u32)> {
    let version = packet.first()? >> 4;
    let (tcp_start, total_len) = match version {
        4 => {
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || packet[9] != 6 {
                return None;
            }
            let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            if total_len < ihl + 20 || total_len > packet.len() {
                return None;
            }
            (ihl, total_len)
        }
        6 => {
            if packet.len() < 60 || packet[6] != 6 {
                return None;
            }
            let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            let total_len = 40 + payload_len;
            if total_len < 60 || total_len > packet.len() {
                return None;
            }
            (40, total_len)
        }
        _ => return None,
    };
    let tcp = &packet[tcp_start..total_len];
    let seq = u32::from_be_bytes([tcp[4], tcp[5], tcp[6], tcp[7]]);
    let ack = u32::from_be_bytes([tcp[8], tcp[9], tcp[10], tcp[11]]);
    let data_offset = ((tcp[12] >> 4) as usize) * 4;
    if data_offset < 20 || tcp.len() < data_offset {
        return None;
    }
    // Сегмент без полезной нагрузки (чистый ACK) тоже годится: RST всё равно сбросит поток.
    let payload_len = tcp.len() - data_offset;
    Some((
        crate::sni::extract_src_addr(packet)?,
        crate::sni::extract_dst_addr(packet)?,
        seq,
        ack.wrapping_add(payload_len as u32),
    ))
}

fn flow_key(packet: &[u8]) -> Option<FlowKey> {
    let version = packet.first()? >> 4;
    match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || packet.len() < ihl {
                return None;
            }
            let proto = packet[9];
            let src = [packet[12], packet[13], packet[14], packet[15]];
            let dst = [packet[16], packet[17], packet[18], packet[19]];
            let fragment_offset = u16::from_be_bytes([packet[6], packet[7]]) & 0x1fff;
            let (sport, dport) = if fragment_offset == 0 {
                transport_ports(packet, ihl, proto)
            } else {
                (0, 0)
            };
            Some(FlowKey::V4 {
                src,
                dst,
                proto,
                sport,
                dport,
            })
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let proto = packet[6];
            let src: [u8; 16] = packet[8..24].try_into().ok()?;
            let dst: [u8; 16] = packet[24..40].try_into().ok()?;
            let (sport, dport) = transport_ports(packet, 40, proto);
            Some(FlowKey::V6 {
                src,
                dst,
                proto,
                sport,
                dport,
            })
        }
        _ => None,
    }
}

fn is_small_udp(key: &FlowKey, packet_len: usize) -> bool {
    packet_len <= SMALL_UDP_MAX_LEN
        && matches!(
            key,
            FlowKey::V4 { proto: 17, .. } | FlowKey::V6 { proto: 17, .. }
        )
}

#[cfg(unix)]
const TUN_REPLACEMENT_TIMEOUT: Duration = Duration::from_secs(20);
#[cfg(unix)]
const TUN_RETRY_TOLERANCE: u32 = 40;
#[cfg(unix)]
const TUN_EOF_TOLERANCE: u32 = 4;
#[cfg(unix)]
const TUN_RETRY_BACKOFF_STEP_MS: u64 = 25;
#[cfg(unix)]
const TUN_RETRY_BACKOFF_MAX_MS: u64 = 400;
#[cfg(unix)]
const TUN_RETRY_BACKOFF_STEPS: u32 = 16;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Termination {
    TunLost,
    Fatal,
}

#[derive(Clone, Copy, Debug)]
struct RateController {
    rate: f64,
    congested: bool,
    last_adjust: Option<std::time::Instant>,
}

impl RateController {
    fn new() -> Self {
        Self {
            rate: TB_RATE_BYTES_PER_SEC,
            congested: false,
            last_adjust: None,
        }
    }

    fn mark_congested(&mut self) {
        self.congested = true;
    }

    fn tick(&mut self, now: std::time::Instant) {
        let Some(last) = self.last_adjust else {
            self.last_adjust = Some(now);
            return;
        };
        if now.saturating_duration_since(last) < ADAPTIVE_ADJUST_INTERVAL {
            return;
        }
        if self.congested {
            self.rate = (self.rate * ADAPTIVE_DECREASE_FACTOR).max(ADAPTIVE_MIN_RATE_BYTES_PER_SEC);
            self.congested = false;
        } else {
            self.rate =
                (self.rate + ADAPTIVE_INCREASE_BYTES_PER_SEC).min(ADAPTIVE_MAX_RATE_BYTES_PER_SEC);
        }
        self.last_adjust = Some(now);
    }
}

#[derive(Default)]
pub struct UplinkState {
    pub last_update: Option<std::time::Instant>,
    pub tokens: Vec<f64>,
    rates: Vec<RateController>,
    pub cursor: usize,
    flows: HashMap<FlowKey, FlowState>,
    flow_evict_counter: u32,
}

impl UplinkState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn update_tokens(&mut self, count: usize, now: std::time::Instant) {
        if count <= 1 {
            return;
        }

        if self.tokens.len() != count {
            self.tokens.resize(count, TB_CAPACITY_BYTES);
            self.rates.resize_with(count, RateController::new);
            self.last_update = Some(now);
            // Индексы воркеров сдвинулись — старые flowlet-привязки больше не
            // действительны.
            self.flows.clear();
            self.flow_evict_counter = 0;
        }

        for controller in self.rates.iter_mut() {
            controller.tick(now);
        }

        let start = match self.last_update {
            Some(s) => s,
            None => {
                self.last_update = Some(now);
                now
            }
        };

        let elapsed = now.saturating_duration_since(start).as_secs_f64();
        if elapsed >= 0.0001 {
            self.last_update = Some(now);
            for (t, controller) in self.tokens.iter_mut().zip(self.rates.iter()) {
                *t = (*t + elapsed * controller.rate).min(TB_CAPACITY_BYTES);
            }
        }
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn try_follow_stream(
        &mut self,
        stream_idx: usize,
        count: usize,
        packet_len: usize,
        now: std::time::Instant,
    ) -> bool {
        if count <= 1 {
            return true;
        }
        if stream_idx >= count {
            return false;
        }
        self.update_tokens(count, now);

        let len_f = packet_len as f64;
        self.tokens[stream_idx] = (self.tokens[stream_idx] - len_f).max(MAX_DEFICIT);
        true
    }

    fn evict_flows(&mut self, now: std::time::Instant) {
        self.flow_evict_counter = self.flow_evict_counter.wrapping_add(1);
        if !self.flow_evict_counter.is_multiple_of(FLOW_EVICT_EVERY) && self.flows.len() < FLOW_TABLE_MAX {
            return;
        }
        self.flows
            .retain(|_, state| now.saturating_duration_since(state.last_seen) < FLOW_IDLE_TTL);
        if self.flows.len() >= FLOW_TABLE_MAX {
            self.flows.clear();
        }
    }

    fn choose_stream(&mut self, workers: &[WorkerEntry]) -> Option<usize> {
        let count = workers.len();
        if count == 0 {
            return None;
        }
        let start = self.cursor.min(count - 1);
        let mut best_idx = None;
        let mut best_tokens = f64::NEG_INFINITY;
        for offset in 0..count {
            let idx = (start + offset) % count;
            let allocation = &workers[idx].channels.allocation;
            if !allocation.is_sendable() {
                if allocation.is_alive()
                    && let Some(controller) = self.rates.get_mut(idx)
                {
                    controller.mark_congested();
                }
                continue;
            }
            if self.tokens[idx] > best_tokens {
                best_tokens = self.tokens[idx];
                best_idx = Some(idx);
            }
        }
        if let Some(idx) = best_idx {
            self.cursor = (idx + 1) % count;
        }
        best_idx
    }

    fn select_deficit_waterfall(
        &mut self,
        workers: &[WorkerEntry],
        total_len: usize,
        packet_len: usize,
        flow: Option<FlowKey>,
        now: std::time::Instant,
    ) -> usize {
        let count = workers.len();
        if count <= 1 {
            return 0;
        }
        self.update_tokens(count, now);
        let len_f = total_len as f64;

        let Some(key) = flow else {
            let Some(idx) = self.choose_stream(workers) else {
                return 0;
            };
            self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
            return idx;
        };

        self.evict_flows(now);
        let small_udp = is_small_udp(&key, packet_len);

        if let Some(mut state) = self.flows.get(&key).copied() {
            state.last_seen = now;
            let sticky = state.keeps_pin(now, total_len, small_udp);
            let assigned_ok =
                state.idx < count && workers[state.idx].channels.allocation.is_sendable();
            // Свой канал держим, только пока в нём есть кредит: иначе поток
            // сидит на одной аллокации весь берст, а она даёт около 2 Мбит/с,
            // и загрузка файла упирается в неё, сколько бы каналов ни было.
            // Берст всё равно сохраняет порядок внутри себя.
            let credit_ok = assigned_ok && self.tokens[state.idx] >= len_f;
            if assigned_ok && (sticky || (credit_ok && state.burst_left >= total_len)) {
                let idx = state.idx;
                state.burst_left = state.burst_left.saturating_sub(total_len);
                self.flows.insert(key, state);
                self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
                return idx;
            }

            let Some(idx) = self.choose_stream(workers) else {
                return 0;
            };
            state.idx = idx;
            state.burst_left = FLOW_BURST_BYTES.saturating_sub(total_len);
            self.flows.insert(key, state);
            self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
            return idx;
        }

        let Some(idx) = self.choose_stream(workers) else {
            return 0;
        };
        self.flows
            .insert(key, FlowState::new(idx, now, total_len, small_udp));
        self.tokens[idx] = (self.tokens[idx] - len_f).max(MAX_DEFICIT);
        idx
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn select(&mut self, count: usize, packet_len: usize, now: std::time::Instant) -> usize {
        if count <= 1 {
            return 0;
        }

        self.update_tokens(count, now);

        let len_f = packet_len as f64;
        for idx in 0..count {
            if self.tokens[idx] >= len_f {
                self.tokens[idx] -= len_f;
                self.cursor = idx;
                return idx;
            }
        }
        let mut best_idx = 0;
        let mut best_tokens = self.tokens[0];
        for idx in 1..count {
            if self.tokens[idx] > best_tokens {
                best_tokens = self.tokens[idx];
                best_idx = idx;
            }
        }
        self.tokens[best_idx] = (self.tokens[best_idx] - len_f).max(MAX_DEFICIT);
        self.cursor = best_idx;
        best_idx
    }
}

#[cfg(unix)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum TunWriteState {
    Complete,
    Wait,
    Backoff,
    Yield,
    Dropped,
}

#[derive(Clone)]
pub struct WorkerChannels {
    pub id: usize,
    pub incarnation_id: u64,
    pub allocation: Arc<TurnAllocation>,
    pub cipher: Arc<SessionCipher>,
    pub config: ObfsConfig,
    pub write_state: Arc<ObfsState>,
}

#[derive(Clone, Debug, Default)]
pub struct DomainRoutingConfig {
    pub enabled: bool,
    pub exclude_ru_zones: bool,
    pub template_domains: bool,
    pub direct_domains: Arc<Vec<String>>,
    pub is_whitelist: bool,
    pub ip_routing_enabled: bool,
    pub ip_is_whitelist: bool,
    pub ip_rules: Arc<Vec<crate::sni::IpRule>>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct QueueProfile {
    pub inbound_capacity: usize,
    pub outbound_capacity: usize,
}

// Буфер приёма из TUN. Раньше это было 256 пакетов (≈ 350 КБ): на быстром
// канале очередь переполнялась в момент, когда TUN-райтер отдавал пачку в
// ядро, и лишние пакеты просто выбрасывались — TCP видел это как потери и
// резал окно. Теперь минимум 512 пакетов и до 2048 (≈ 3 МБ) при большом
// числе потоков.
pub const INGRESS_CAPACITY_PKTS: usize = 512;
pub const INGRESS_CAPACITY_MAX_PKTS: usize = 2048;
pub const INGRESS_PKTS_PER_WORKER: usize = 24;

static INBOUND_DOWNLINK_EVICTED: AtomicU64 = AtomicU64::new(0);
static ADBLOCK_BLOCKED: AtomicU64 = AtomicU64::new(0);

impl QueueProfile {
    pub fn for_worker_count(workers: usize) -> Self {
        Self {
            inbound_capacity: workers
                .saturating_mul(INGRESS_PKTS_PER_WORKER)
                .clamp(INGRESS_CAPACITY_PKTS, INGRESS_CAPACITY_MAX_PKTS),
            outbound_capacity: 128,
        }
    }
}

#[derive(Clone)]
pub struct WorkerEntry {
    pub channels: WorkerChannels,
}

const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

pub struct Dispatcher {
    workers: ArcSwap<Vec<WorkerEntry>>,
    inbound: ArrayQueue<PacketBuf>,
    #[allow(dead_code)]
    outbound_capacity: usize,
    tun_wake: Arc<Notify>,
    pub(crate) tun_io_lost: Arc<Notify>,
    cancel: CancellationToken,
    tasks: tokio::sync::Mutex<Vec<JoinHandle<()>>>,
    domain_routing: Arc<ArcSwap<DomainRoutingConfig>>,
    direct_ips: Arc<ArcSwap<HashSet<IpAddr>>>,
    dns_domain_map: Arc<crate::sni::DnsDomainMap>,
    direct_relay: Option<Arc<crate::direct_relay::DirectRelay>>,
    uplink_state: Mutex<UplinkState>,
    pool: Arc<PacketPool>,
    reassembler: crate::wire_protocol::ShardedReassembler,
    next_frag_id: std::sync::atomic::AtomicU16,
    probe: crate::tunnel_probe::Slot,
    termination: Arc<OnceLock<Termination>>,
    game: Arc<crate::game_mode::GameMode>,
    game_pins: Arc<std::sync::Mutex<crate::game_mode::GamePins>>,
}

impl Dispatcher {
    #[cfg(all(test, unix))]
    pub async fn start_test_tun(
        file: File,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        _wrap_key: [u8; 32],
        _mode: crate::obfs::ObfsMode,
        cancel: CancellationToken,
    ) -> Result<Arc<Self>> {
        let profile = QueueProfile::for_worker_count(27);
        let dispatcher = Arc::new(Self {
            workers: ArcSwap::from_pointee(Vec::new()),
            inbound: ArrayQueue::new(profile.inbound_capacity),
            outbound_capacity: profile.outbound_capacity,
            tun_wake: Arc::new(Notify::new()),
            tun_io_lost: Arc::new(Notify::new()),
            cancel: cancel.clone(),
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
            direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
            dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()),
            direct_relay: None,
            uplink_state: Mutex::new(UplinkState::new()),
            pool: pool.clone(),
            reassembler: crate::wire_protocol::ShardedReassembler::new(),
            next_frag_id: std::sync::atomic::AtomicU16::new(1),
            probe: Default::default(),
            termination: Arc::new(OnceLock::new()),
            game: crate::game_mode::GameMode::new(),
            game_pins: Arc::new(std::sync::Mutex::new(crate::game_mode::GamePins::new())),
        });
        let io_dispatcher = dispatcher.clone();
        let task = spawn_critical(
            "test TUN dispatcher",
            cancel,
            dispatcher.termination.clone(),
            async move {
                let (fd_tx, mut fd_rx) = mpsc::channel(1);
                io_dispatcher.run_tun(file, pool, stats, &mut fd_rx).await;
                drop(fd_tx);
            },
        );
        dispatcher.tasks.lock().await.push(task);
        Ok(dispatcher)
    }

    #[cfg(test)]
    pub async fn start(
        listen: &str,
        tun_uds: Option<String>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        _wrap_key: [u8; 32],
        _mode: crate::obfs::ObfsMode,
        cancel: CancellationToken,
    ) -> Result<(Arc<Self>, String)> {
        Self::start_with_domain_routing(
            listen,
            tun_uds,
            pool,
            stats,
            cancel,
            DomainRoutingConfig::default(),
            27,
            std::collections::HashSet::new(),
        )
        .await
    }

    pub async fn start_with_domain_routing(
        listen: &str,
        tun_uds: Option<String>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        cancel: CancellationToken,
        domain_routing: DomainRoutingConfig,
        worker_count: usize,
        protected_ips: std::collections::HashSet<std::net::IpAddr>,
    ) -> Result<(Arc<Self>, String)> {
        let profile = QueueProfile::for_worker_count(worker_count);
        let domain_routing = Arc::new(ArcSwap::from_pointee(domain_routing));
        let direct_ips = Arc::new(ArcSwap::from_pointee(HashSet::new()));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let (direct_inbound_tx, mut direct_inbound_rx) =
            mpsc::channel(crate::direct_relay::DIRECT_RELAY_INBOUND_CAPACITY);
        let (app_direct_tx, mut app_direct_rx) = mpsc::channel(256);
        let relay = Arc::new(crate::direct_relay::DirectRelay::new(
            domain_routing.clone(),
            direct_ips.clone(),
            dns_domain_map.clone(),
            pool.clone(),
            direct_inbound_tx,
            protected_ips,
        )?);
        // Придержанные первые пакеты возвращаются сюда, когда решение по потоку
        // принято: их надо разложить заново, уже с известным владельцем.
        relay.app_direct().set_replay(app_direct_tx);
        let direct_relay = Some(relay);
        let dispatcher = Arc::new(Self {
            workers: ArcSwap::from_pointee(Vec::new()),
            inbound: ArrayQueue::new(profile.inbound_capacity),
            outbound_capacity: profile.outbound_capacity,
            tun_wake: Arc::new(Notify::new()),
            tun_io_lost: Arc::new(Notify::new()),
            cancel: cancel.clone(),
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing,
            direct_ips,
            dns_domain_map,
            direct_relay,
            uplink_state: Mutex::new(UplinkState::new()),
            pool: pool.clone(),
            reassembler: crate::wire_protocol::ShardedReassembler::new(),
            next_frag_id: std::sync::atomic::AtomicU16::new(1),
            probe: Default::default(),
            termination: Arc::new(OnceLock::new()),
            game: crate::game_mode::GameMode::new(),
            game_pins: Arc::new(std::sync::Mutex::new(crate::game_mode::GamePins::new())),
        });
        crate::log_error!(
            "[КЛИЕНТ] Профиль очередей для {worker_count} потоков: буфер I/O: {} pkts, квант: {} байт",
            profile.inbound_capacity,
            QUANTUM_BYTES
        );

        let pump_dispatcher = dispatcher.clone();
        let pump_cancel = dispatcher.cancel.clone();
        let pump_task = spawn_critical(
            "direct relay inbound pump",
            pump_cancel.clone(),
            dispatcher.termination.clone(),
            async move {
                loop {
                    tokio::select! {
                        biased;
                        _ = pump_cancel.cancelled() => return,
                        pkt = direct_inbound_rx.recv() => {
                            match pkt {
                                Some(pkt) => {
                                    pump_dispatcher.push_inbound(pkt);
                                }
                                None => return,
                            }
                        }
                    }
                }
            },
        );
        dispatcher.tasks.lock().await.push(pump_task);

        let replay_dispatcher = dispatcher.clone();
        let replay_cancel = dispatcher.cancel.clone();
        let replay_task = spawn_critical(
            "app direct replay pump",
            replay_cancel.clone(),
            dispatcher.termination.clone(),
            async move {
                loop {
                    // Придержанные пакеты, на которые так и не пришёл ответ,
                    // отпускаем обычным путём: соединение не должно ждать вечно.
                    for raw in replay_dispatcher
                        .direct_relay
                        .as_ref()
                        .map(|relay| relay.app_direct().drain_stale())
                        .unwrap_or_default()
                    {
                        replay_dispatcher.replay_packet(raw).await;
                    }
                    tokio::select! {
                        biased;
                        _ = replay_cancel.cancelled() => return,
                        raw = app_direct_rx.recv() => {
                            let Some(raw) = raw else { return };
                            let Some(mut packet) = replay_dispatcher.pool.try_acquire() else {
                                continue;
                            };
                            let area = packet.read_area();
                            if raw.len() > area.len() {
                                continue;
                            }
                            area[..raw.len()].copy_from_slice(&raw);
                            if packet.set_read_len(raw.len()).is_err() {
                                continue;
                            }
                            replay_dispatcher.dispatch(packet).await;
                        }
                        _ = tokio::time::sleep(std::time::Duration::from_millis(250)) => {}
                    }
                }
            },
        );
        dispatcher.tasks.lock().await.push(replay_task);

        if let Some(name) = tun_uds {
            crate::log_error!("[КЛИЕНТ] Запуск UDS-слушателя: {name} для получения TUN FD...");
            let bind_name = name.clone();
            let receiver = match tokio::task::spawn_blocking(move || {
                tun::FdReceiver::bind(&bind_name)
            })
            .await
            {
                Ok(result) => result?,
                Err(error) => {
                    return Err(anyhow::anyhow!("TUN FD bind task failed: {error}"));
                }
            };
            let (fd_tx, mut fd_rx) = mpsc::channel(16);
            let receive_cancel = dispatcher.cancel.clone();
            let receive_task = spawn_critical(
                "TUN FD receiver",
                receive_cancel.clone(),
                dispatcher.termination.clone(),
                async move {
                    loop {
                        let file = match receiver.receive(&receive_cancel).await {
                            Ok(file) => file,
                            Err(_) if receive_cancel.is_cancelled() => return,
                            Err(error) => {
                                crate::log_error!(
                                    "[ОШИБКА] Не удалось получить TUN FD из UDS: {error}"
                                );
                                tokio::time::sleep(Duration::from_millis(100)).await;
                                continue;
                            }
                        };
                        crate::log_error!("[UDS] Новый TUN FD успешно получен из сокета");
                        if let Err(error) = fd_tx.send(file).await {
                            if receive_cancel.is_cancelled() {
                                return;
                            }
                            crate::log_error!(
                                "[UDS] Канал передачи FD временно недоступен: {error}"
                            );
                            tokio::time::sleep(Duration::from_millis(100)).await;
                        }
                    }
                },
            );
            let io_dispatcher = dispatcher.clone();
            let task_cancel = dispatcher.cancel.clone();
            let pool_for_io = pool.clone();
            let stats_for_io = stats.clone();
            let io_task = spawn_critical(
                "TUN dispatcher",
                task_cancel.clone(),
                dispatcher.termination.clone(),
                async move {
                    while !task_cancel.is_cancelled() {
                        let file = tokio::select! {
                            _ = task_cancel.cancelled() => break,
                            file = fd_rx.recv() => match file {
                                Some(f) => f,
                                None => break,
                            },
                        };
                        crate::log_error!("[КЛИЕНТ] TUN FD успешно получен!");
                        io_dispatcher
                            .run_tun(file, pool_for_io.clone(), stats_for_io.clone(), &mut fd_rx)
                            .await;
                    }
                },
            );
            dispatcher.tasks.lock().await.push(io_task);
            dispatcher.tasks.lock().await.push(receive_task);
            Ok((dispatcher, "0".to_owned()))
        } else {
            let socket = bind_udp(listen).await?;
            let local_port = socket.local_addr()?.port().to_string();
            let socket = Arc::new(socket);
            let client = Arc::new(ArcSwapOption::<SocketAddr>::from_pointee(None));
            let read_dispatcher = dispatcher.clone();
            let read_socket = socket.clone();
            let read_client = client.clone();
            let read_pool = pool.clone();
            let read_stats = stats.clone();
            let read_task = spawn_critical(
                "UDP reader",
                dispatcher.cancel.clone(),
                dispatcher.termination.clone(),
                async move {
                    read_dispatcher
                        .read_udp(read_socket, read_client, read_pool, read_stats)
                        .await;
                },
            );
            let write_dispatcher = dispatcher.clone();
            let write_task = spawn_critical(
                "UDP writer",
                dispatcher.cancel.clone(),
                dispatcher.termination.clone(),
                async move {
                    write_dispatcher.write_udp(socket, client, stats).await;
                },
            );
            dispatcher
                .tasks
                .lock()
                .await
                .extend([read_task, write_task]);
            Ok((dispatcher, local_port))
        }
    }

    pub fn register(&self, channels: WorkerChannels) {
        self.reset_game_pins();
        let id = channels.id;
        let entry = WorkerEntry {
            channels: channels.clone(),
        };
        self.workers.rcu(|workers| {
            let mut updated = (**workers).clone();
            updated.retain(|worker| worker.channels.id != id);
            updated.push(entry.clone());
            updated.sort_by_key(|worker| worker.channels.id);
            Arc::new(updated)
        });
    }

    pub fn unregister(&self, id: usize, incarnation_id: u64) {
        self.reset_game_pins();
        self.workers.rcu(|workers| {
            let mut updated = (**workers).clone();
            updated.retain(|worker| {
                worker.channels.id != id || worker.channels.incarnation_id != incarnation_id
            });
            Arc::new(updated)
        });
    }

    #[cfg(test)]
    pub fn active_count(&self) -> usize {
        self.workers.load().len()
    }

    #[inline(always)]
    pub async fn probe_tunnel(&self, target: crate::tunnel_probe::Target) -> Option<u64> {
        let answer = self.probe.arm(target);
        let started = std::time::Instant::now();
        if !self.send_probe_packet(&crate::tunnel_probe::syn_packet(&target)).await {
            self.probe.disarm();
            return None;
        }
        let answered = matches!(tokio::time::timeout(PROBE_TIMEOUT, answer).await, Ok(Ok(())));
        let elapsed = started.elapsed().as_millis() as u64;
        self.probe.disarm();
        if !answered {
            return None;
        }
        self.send_probe_packet(&crate::tunnel_probe::rst_packet(&target)).await;
        Some(elapsed)
    }

    async fn send_probe_packet(&self, bytes: &[u8]) -> bool {
        let workers = self.workers.load();
        if workers.is_empty() {
            return false;
        }
        let Some(mut packet) = self.pool.try_acquire() else { return false };
        packet.read_area()[..bytes.len()].copy_from_slice(bytes);
        if packet.set_read_len(bytes.len()).is_err() {
            return false;
        }
        self.send_to_workers(packet, &workers).await;
        true
    }

    async fn replay_packet(&self, raw: Vec<u8>) {
        let Some(mut packet) = self.pool.try_acquire() else {
            return;
        };
        let area = packet.read_area();
        if raw.len() > area.len() {
            return;
        }
        area[..raw.len()].copy_from_slice(&raw);
        if packet.set_read_len(raw.len()).is_ok() {
            self.dispatch(packet).await;
        }
    }

    pub fn pop_inbound(&self) -> Option<PacketBuf> {
        self.inbound.pop()
    }

    pub fn push_inbound(&self, packet: PacketBuf) {
        let mut packet = packet;
        if let Err(returned) = self.inbound.push(packet) {
            packet = returned;
            let evicted = self.inbound.pop();
            drop(evicted);
            let _ = self.inbound.push(packet);
            let total = INBOUND_DOWNLINK_EVICTED.fetch_add(1, Ordering::Relaxed) + 1;
            if total <= 8 || total.is_multiple_of(1000) {
                crate::log_error!(
                    "[ОШИБКА] Даунлинк: пакет #{total} вытеснен из приёмной очереди (лимит {})",
                    self.inbound.capacity()
                );
            }
        }
        // After the push, every time: deciding from the length BEFORE it lost a wakeup — the writers could drain the
        // queue and park between that check and the push, and the packet then waited for the next downlink packet.
        // Notify coalesces into one stored permit, so this costs nothing when nobody waits.
        self.tun_wake.notify_one();
    }

    pub fn return_packet(&self, mut packet: PacketBuf) {
        strip_legacy_downlink_prefix(&mut packet);
        self.inspect_domain_routing(&packet);
        self.push_inbound(packet);
    }

    pub fn handle_incoming_fragment(&self, slice: &[u8]) {
        let now = std::time::Instant::now();
        let pool = &self.pool;
        self.reassembler
            .process_fragment_from(0, slice, now, |assembled| {
                if let Some(mut pkt) = pool.try_acquire() {
                    let area = pkt.read_area();
                    if assembled.len() <= area.len() {
                        area[..assembled.len()].copy_from_slice(assembled);
                        if pkt.set_read_len(assembled.len()).is_ok() {
                            self.return_packet(pkt);
                        }
                    }
                }
            });
    }

    pub fn termination(&self) -> Option<Termination> {
        self.termination.get().copied()
    }

    fn terminate(&self, reason: Termination) {
        let _ = self.termination.set(reason);
        self.cancel.cancel();
    }

    #[cfg(unix)]
    fn fail_on_panic(&self, name: &str, result: Result<(), tokio::task::JoinError>) {
        if let Err(error) = result
            && error.is_panic()
        {
            crate::log_error!("[СУПЕРВИЗОР] {name} завершился аварийно: {error}");
            self.terminate(Termination::Fatal);
        }
    }

    pub async fn shutdown(&self) {
        self.cancel.cancel();
        for task in self.tasks.lock().await.drain(..) {
            let _ = task.await;
        }
    }

    #[cfg(unix)]
    async fn run_tun(
        self: &Arc<Self>,
        initial_file: File,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        replacements: &mut mpsc::Receiver<File>,
    ) {
        use tokio::io::unix::AsyncFd;

        let mut file = initial_file;
        loop {
            let device = match AsyncFd::new(file) {
                Ok(device) => Arc::new(device),
                Err(error) => {
                    crate::log_error!("[ОШИБКА] Не удалось зарегистрировать TUN FD: {error}");
                    self.tun_io_lost.notify_one();
                    self.terminate(Termination::TunLost);
                    return;
                }
            };
            let generation_cancel = self.cancel.child_token();
            let read_dispatcher = self.clone();
            let read_cancel = generation_cancel.clone();
            let read_device = device.clone();
            let read_pool = pool.clone();
            let read_stats = stats.clone();
            let mut read_task = tokio::spawn(async move {
                read_dispatcher
                    .read_tun(read_device, read_pool, read_stats, read_cancel)
                    .await;
            });

            let write_dispatcher = self.clone();
            let write_cancel = generation_cancel.clone();
            let write_stats = stats.clone();
            let mut write_task = tokio::spawn(async move {
                write_dispatcher
                    .write_tun(device, write_stats, write_cancel)
                    .await;
            });

            let received = tokio::select! {
                _ = self.cancel.cancelled() => None,
                replacement = replacements.recv() => replacement,
                result = &mut read_task => {
                    self.fail_on_panic("TUN reader", result);
                    None
                }
                result = &mut write_task => {
                    self.fail_on_panic("TUN writer", result);
                    None
                }
            };
            generation_cancel.cancel();
            if !read_task.is_finished() {
                let _ = read_task.await;
            }
            if !write_task.is_finished() {
                let _ = write_task.await;
            }
            if self.cancel.is_cancelled() {
                return;
            }
            let next = match received {
                Some(file) => file,
                None => {
                    crate::log_error!("[TUN] I/O сессия завершилась, ожидаем новый TUN FD...");
                    self.tun_io_lost.notify_one();
                    let replacement = tokio::select! {
                        _ = self.cancel.cancelled() => return,
                        replacement = tokio::time::timeout(TUN_REPLACEMENT_TIMEOUT, replacements.recv()) => replacement,
                    };
                    match replacement {
                        Ok(Some(file)) => file,
                        Ok(None) => {
                            crate::log_error!("[TUN] Канал замены TUN FD закрыт, TUN потерян");
                            self.terminate(Termination::TunLost);
                            return;
                        }
                        Err(_) => {
                            crate::log_error!(
                                "[TUN] Новый TUN FD не получен за {} с, TUN потерян",
                                TUN_REPLACEMENT_TIMEOUT.as_secs()
                            );
                            self.terminate(Termination::TunLost);
                            return;
                        }
                    }
                }
            };
            crate::log_error!("[КЛИЕНТ] TUN FD заменён без перезапуска потоков");
            file = next;
        }
    }

    #[cfg(not(unix))]
    async fn run_tun(
        self: &Arc<Self>,
        _file: File,
        _pool: Arc<PacketPool>,
        _stats: Arc<Stats>,
        _replacements: &mut mpsc::Receiver<File>,
    ) {
        crate::log_error!("[ОШИБКА] TUN FD поддерживается только на Android и Unix");
        self.terminate(Termination::TunLost);
    }

    #[inline(always)]
    fn is_forwardable_packet(slice: &[u8]) -> bool {
        if slice.len() < 20 {
            return false;
        }
        let version = slice[0] >> 4;
        match version {
            4 => {
                let ihl = (slice[0] & 0x0f) as usize * 4;
                if ihl < 20 || slice.len() < ihl {
                    return false;
                }
                let total_len = u16::from_be_bytes([slice[2], slice[3]]) as usize;
                if total_len < ihl || total_len > slice.len() {
                    return false;
                }
                let dst_ip = [slice[16], slice[17], slice[18], slice[19]];
                if dst_ip[0] == 0 || dst_ip[0] == 127 || dst_ip[0] >= 224 {
                    return false;
                }
                if dst_ip[0] == 169 && dst_ip[1] == 254 {
                    return false;
                }
                true
            }
            6 => {
                if slice.len() < 40 {
                    return false;
                }
                let payload_len = u16::from_be_bytes([slice[4], slice[5]]) as usize;
                if payload_len + 40 > slice.len() {
                    return false;
                }
                let dst = &slice[24..40];
                if dst.iter().all(|&b| b == 0) {
                    return false;
                }
                if dst[..15].iter().all(|&b| b == 0) && dst[15] == 1 {
                    return false;
                }
                if dst[0] == 0xff {
                    return false;
                }
                if dst[0] == 0xfe && (dst[1] & 0xc0) == 0x80 {
                    return false;
                }
                true
            }
            _ => false,
        }
    }

    #[cfg(unix)]
    async fn read_tun(
        self: Arc<Self>,
        device: Arc<tokio::io::unix::AsyncFd<File>>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
        cancel: CancellationToken,
    ) {
        let mut eof_streak = 0u32;
        let mut error_streak = 0u32;
        loop {
            let readiness = tokio::select! {
                biased;
                _ = cancel.cancelled() => return,
                result = device.readable() => result,
            };
            let mut guard = match readiness {
                Ok(guard) => guard,
                Err(error) => {
                    if is_dead_tun_error(&error) {
                        crate::log_error!("[TUN] Ожидание чтения TUN: дескриптор мёртв ({error})");
                        return;
                    }
                    crate::log_error!("[ОШИБКА] Ожидание чтения TUN: {error}, повтор...");
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    continue;
                }
            };

            let mut burst = 0usize;
            let mut burst_bytes = 0usize;
            let mut retry_delay = None;
            while burst < TUN_RX_BURST && burst_bytes < TUN_RX_BURST_BYTES {
                let Some(mut packet) = pool.try_acquire() else {
                    if burst == 0 {
                        tokio::select! {
                            biased;
                            _ = cancel.cancelled() => return,
                            _ = pool.wait_available() => {}
                        }
                    }
                    break;
                };
                let result = {
                    let area = packet.read_area();
                    guard.try_io(|inner| {
                        use std::io::Read;

                        let mut source = inner.get_ref();
                        source.read(area)
                    })
                };
                match result {
                    Ok(Ok(0)) => {
                        eof_streak += 1;
                        if eof_streak >= TUN_EOF_TOLERANCE {
                            crate::log_error!(
                                "[TUN] Чтение TUN: интерфейс закрыт (EOF ×{eof_streak})"
                            );
                            return;
                        }
                        retry_delay = Some(tun_retry_backoff(eof_streak));
                        break;
                    }
                    Ok(Ok(length)) => {
                        burst += 1;
                        burst_bytes += length;
                        eof_streak = 0;
                        error_streak = 0;
                        if packet.set_read_len(length).is_err() {
                            continue;
                        }
                        if !Self::is_forwardable_packet(packet.as_slice()) {
                            continue;
                        }
                        if self.workers.load().is_empty() {
                            continue;
                        }
                        stats
                            .total_bytes_up
                            .fetch_add(length as i64, Ordering::Relaxed);
                        self.dispatch(packet).await;
                    }
                    Ok(Err(error)) if is_transient_tun_error(&error) => {
                        error_streak += 1;
                        if error_streak >= TUN_RETRY_TOLERANCE {
                            crate::log_error!(
                                "[TUN] Чтение TUN: интерфейс не отвечает ({error}) после {error_streak} попыток, переподнимаем"
                            );
                            return;
                        }
                        retry_delay = Some(tun_retry_backoff(error_streak));
                        break;
                    }
                    Ok(Err(error)) if is_dead_tun_error(&error) => {
                        crate::log_error!("[TUN] Чтение TUN: дескриптор мёртв: {error}");
                        return;
                    }
                    Ok(Err(error)) => {
                        crate::log_error!("[ОШИБКА] Чтение TUN: {error}");
                        retry_delay = Some(Duration::from_millis(20));
                        break;
                    }
                    Err(_) => break,
                }
            }
            if let Some(delay) = retry_delay {
                tokio::select! {
                    biased;
                    _ = cancel.cancelled() => return,
                    _ = tokio::time::sleep(delay) => {}
                }
            }
        }
    }

    async fn read_udp(
        self: Arc<Self>,
        socket: Arc<UdpSocket>,
        client: Arc<ArcSwapOption<SocketAddr>>,
        pool: Arc<PacketPool>,
        stats: Arc<Stats>,
    ) {
        loop {
            let readiness = tokio::select! {
                biased;
                _ = self.cancel.cancelled() => return,
                result = socket.readable() => result,
            };
            if readiness.is_err() {
                tokio::time::sleep(Duration::from_millis(50)).await;
                continue;
            }

            let mut burst = 0usize;
            while burst < UDP_RX_BURST {
                let Some(mut packet) = pool.try_acquire() else {
                    if burst == 0 {
                        tokio::select! {
                            biased;
                            _ = self.cancel.cancelled() => return,
                            _ = pool.wait_available() => {}
                        }
                    }
                    break;
                };
                match socket.try_recv_from(packet.read_area()) {
                    Ok((length, address)) => {
                        burst += 1;
                        if packet.set_read_len(length).is_err() {
                            continue;
                        }
                        if !Self::is_forwardable_packet(packet.as_slice()) {
                            continue;
                        }
                        let previous = client.load();
                        if previous.as_deref() != Some(&address) {
                            client.store(Some(Arc::new(address)));
                        }
                        stats
                            .total_bytes_up
                            .fetch_add(packet.len() as i64, Ordering::Relaxed);
                        self.dispatch(packet).await;
                    }
                    Err(ref error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        break;
                    }
                    Err(_) => break,
                }
            }
        }
    }

    pub fn update_game_mode(&self, payload: &str) -> (bool, usize) {
        let (enabled, ports) = self.game.apply_command(payload);
        self.reset_game_pins();
        (enabled, ports)
    }

    pub fn game_mode_enabled(&self) -> bool {
        self.game.is_enabled()
    }

    pub fn update_app_direct(&self, enabled: bool) -> bool {
        if let Some(relay) = &self.direct_relay {
            relay.set_app_direct_enabled(enabled);
        }
        enabled
    }

    pub fn update_app_direct_flow(&self, payload: &str) -> bool {
        let mut parts = payload.split('|');
        let (Some(src), Some(dst), Some(protocol)) = (parts.next(), parts.next(), parts.next()) else {
            return false;
        };
        let Ok(protocol) = protocol.trim().parse::<u8>() else {
            return false;
        };
        let direct = parts.next().map_or(true, |value| value.trim() != "0");
        let filtered = parts.next().map_or(true, |value| value.trim() != "0");
        let Some(relay) = &self.direct_relay else {
            return false;
        };
        relay
            .app_direct()
            .set_flow(src, dst, protocol, direct, filtered);
        // Поток с прямым режимом в лог не пишем: это не ошибка сети, а
        // обычная работа. Строки «… → … напрямую» заполняли журнал настолько,
        // что в нём терялись настоящие проблемы.
        true
    }

    fn reset_game_pins(&self) {
        match self.game_pins.lock() {
            Ok(mut pins) => pins.reset(),
            Err(poisoned) => poisoned.into_inner().reset(),
        }
    }

    pub fn update_domain_routing(&self, payload: &str) {
        let mut enabled = false;
        let mut ru = false;
        let mut template = false;
        let mut is_whitelist = false;
        let mut ip_routing_enabled = false;
        let mut ip_is_whitelist = false;
        let mut domains = Vec::new();
        let mut ip_rules = Vec::new();
        for part in payload.split(';') {
            if let Some((k, v)) = part.split_once('=') {
                match k.trim() {
                    "enabled" => enabled = v.trim() == "1" || v.trim().eq_ignore_ascii_case("true"),
                    "ru" => ru = v.trim() == "1" || v.trim().eq_ignore_ascii_case("true"),
                    "template" => {
                        template = v.trim() == "1" || v.trim().eq_ignore_ascii_case("true")
                    }
                    "whitelist" | "is_whitelist" => {
                        is_whitelist = v.trim() == "1" || v.trim().eq_ignore_ascii_case("true")
                    }
                    "ip_enabled" => {
                        ip_routing_enabled =
                            v.trim() == "1" || v.trim().eq_ignore_ascii_case("true")
                    }
                    "ip_whitelist" | "ip_is_whitelist" => {
                        ip_is_whitelist = v.trim() == "1" || v.trim().eq_ignore_ascii_case("true")
                    }
                    "domains" => {
                        domains = crate::sni::parse_direct_domains(v.trim());
                    }
                    "ips" => {
                        ip_rules = crate::sni::parse_ip_rules(v.trim());
                    }
                    _ => {}
                }
            }
        }
        domains.truncate(4096);
        let new_config = DomainRoutingConfig {
            enabled,
            exclude_ru_zones: ru,
            template_domains: template,
            direct_domains: Arc::new(domains.clone()),
            is_whitelist,
            ip_routing_enabled,
            ip_is_whitelist,
            ip_rules: Arc::new(ip_rules),
        };
        self.domain_routing.store(Arc::new(new_config.clone()));
        crate::log_error!(
            "[КЛИЕНТ] Горячее обновление доменов: вкл={}, RU={}, шаблоны={}, кастомных={}, ЧС/БС={}, IP вкл={}, IP ЧС/БС={}, IP правил={}",
            enabled,
            ru,
            template,
            domains.len(),
            is_whitelist,
            ip_routing_enabled,
            ip_is_whitelist,
            new_config.ip_rules.len()
        );
        if let Some(relay) = &self.direct_relay {
            relay.sync_domain_routing(&new_config);
        }
    }

    fn try_answer_blocked_dns(&self, packet: &PacketBuf) -> bool {
        if !crate::adblock::is_active() {
            return false;
        }
        let slice = packet.as_slice();
        let Some((payload, _)) = crate::adblock::dns_udp_payload(slice) else {
            return false;
        };
        let Some(question) = crate::adblock::parse_question(payload) else {
            return false;
        };
        if !crate::adblock::is_blocked(&question.name) {
            return false;
        }
        let Some(mut response) = self.pool.try_acquire() else {
            return false;
        };
        let answered = {
            let area = response.read_area();
            match crate::adblock::build_empty_answer(payload, &question, area, true) {
                Some(len) => response.set_read_len(len).is_ok(),
                None => false,
            }
        };
        if !answered {
            return false;
        }
        let Some(query_src) = crate::sni::extract_src_addr(slice) else {
            return false;
        };
        let Some(query_dst) = crate::sni::extract_dst_addr(slice) else {
            return false;
        };
        let Some(mut answer) = self.pool.try_acquire() else {
            return false;
        };
        let payload_len = response.len();
        let header_len = if query_src.is_ipv6() { 40 } else { 20 };
        let total = header_len + 8 + payload_len;
        let built = match answer.extend_tail(total) {
            Ok(dest) => {
                let start = header_len + 8;
                dest[start..start + payload_len].copy_from_slice(response.as_slice());
                crate::direct_relay::craft_udp_packet_into(
                    query_dst,
                    query_src,
                    response.as_slice(),
                    dest,
                )
            }
            Err(_) => false,
        };
        if !built {
            return false;
        }
        if self.inbound.push(answer).is_err() {
            crate::log_error!("[ADBLOCK] Не удалось поставить ответ в очередь");
            return false;
        }
        self.tun_wake.notify_one();
        let blocked = ADBLOCK_BLOCKED.fetch_add(1, Ordering::Relaxed) + 1;
        if blocked <= 10 || blocked.is_multiple_of(200) {
            crate::log_error!("[ADBLOCK] Заблокирован {} ({} байт)", question.name, total);
        }
        true
    }

    fn try_reset_encrypted_dns(&self, packet: &PacketBuf) -> bool {
        if !crate::adblock::is_active() {
            return false;
        }
        let slice = packet.as_slice();
        let Some(dst) = crate::sni::extract_dst_addr(slice) else {
            return false;
        };
        // DoH режем по имени из SNI — оно известно точно, ложных срабатываний нет.
        let blocked_by_name = crate::sni::parse_ip_packet_sni(slice)
            .is_some_and(|(sni, _)| crate::adblock::is_encrypted_dns_host(sni));
        // DoT режем только по точному порту 853 и только когда пользователь сам
        // включил набор «Шифрованный DNS». Раньше порт 853 рвался всегда, пока
        // фильтр включён: при системном «Приватном DNS» это ломало разрешение имён
        // у всех приложений сразу — страницы и картинки не открывались, а после
        // выключения Adblock всё работало. Порт 853 занят только DoT, поэтому
        // сама по себе проверка порта ложных срабатываний не даёт.
        let blocked_by_port = dst.port() == crate::adblock::ENCRYPTED_DNS_PORT
            && crate::adblock::encrypted_dns_blocking();
        if !blocked_by_port && !blocked_by_name {
            return false;
        }
        let Some((src, dst, seq, ack)) = tcp_reset_targets(slice) else {
            return false;
        };
        let Some(rst) = crate::direct_relay::build_tcp_packet(dst, src, ack, seq, 0x14, &[]) else {
            return false;
        };
        let Some(mut answer) = self.pool.try_acquire() else {
            return false;
        };
        if rst.len() > answer.read_area().len() {
            return false;
        }
        answer.read_area()[..rst.len()].copy_from_slice(&rst);
        if answer.set_read_len(rst.len()).is_err() {
            return false;
        }
        if self.inbound.push(answer).is_err() {
            return false;
        }
        self.tun_wake.notify_one();
        let blocked = ADBLOCK_BLOCKED.fetch_add(1, Ordering::Relaxed) + 1;
        if blocked <= 10 || blocked.is_multiple_of(200) {
            crate::log_error!("[ADBLOCK] Шифрованный DNS обойдён: обычный DNS фильтруется");
        }
        true
    }

    fn try_block_youtube_ad(&self, packet: &PacketBuf) -> bool {
        if !crate::adblock::is_active() {
            return false;
        }
        let slice = packet.as_slice();
        let Some((sni, port)) = crate::sni::parse_ip_packet_sni(slice) else {
            return false;
        };
        if port != 443 || !crate::youtube_ads::is_ad_sni(sni) {
            return false;
        }
        let Some((src, dst, seq, ack)) = tcp_reset_targets(slice) else {
            return false;
        };
        let Some(rst) = crate::direct_relay::build_tcp_packet(dst, src, ack, seq, 0x14, &[]) else {
            return false;
        };
        let Some(mut answer) = self.pool.try_acquire() else {
            return false;
        };
        if rst.len() > answer.read_area().len() {
            return false;
        }
        answer.read_area()[..rst.len()].copy_from_slice(&rst);
        if answer.set_read_len(rst.len()).is_err() {
            return false;
        }
        if self.inbound.push(answer).is_err() {
            return false;
        }
        self.tun_wake.notify_one();
        crate::youtube_ads::note_blocked(sni);
        true
    }

    #[inline(always)]
    fn inspect_domain_routing(&self, packet: &PacketBuf) {
        let config = self.domain_routing.load();
        if !config.enabled && !config.ip_routing_enabled {
            return;
        }
        let slice = packet.as_slice();
        if let Some((domain, _is_resp, ips)) = crate::sni::parse_ip_packet_dns(slice) {
            if !ips.is_empty() {
                self.dns_domain_map.insert_multiple(&ips, &domain);
            }
            if config.enabled
                && !ips.is_empty()
                && crate::sni::is_direct_domain_mode(
                    &domain,
                    config.is_whitelist,
                    config.exclude_ru_zones,
                    config.template_domains,
                    &config.direct_domains,
                )
            {
                self.direct_ips.rcu(|current| {
                    let mut updated = (**current).clone();
                    for &ip in &ips {
                        if updated.insert(ip) {
                            crate::log_error!("[DNS-РОУТИНГ] Прямой домен: {domain} -> {ip}");
                        }
                    }
                    Arc::new(updated)
                });
            }
        }
        if let Some((sni, _port)) = crate::sni::parse_ip_packet_sni(slice)
            && let Some(dst) = crate::sni::extract_dst_addr(slice)
        {
            let ip = dst.ip();
            self.dns_domain_map.insert(ip, sni.to_string());
            if config.enabled
                && crate::sni::is_direct_domain_mode(
                    sni,
                    config.is_whitelist,
                    config.exclude_ru_zones,
                    config.template_domains,
                    &config.direct_domains,
                )
                && !self.direct_ips.load().contains(&ip)
            {
                self.direct_ips.rcu(|current| {
                    let mut updated = (**current).clone();
                    if updated.insert(ip) {
                        crate::log_error!("[SNI-РОУТИНГ] Прямой домен: {sni} -> {ip}");
                    }
                    Arc::new(updated)
                });
            }
        }
    }

    async fn send_to_workers(&self, mut packet: PacketBuf, workers: &[WorkerEntry]) {
        let count = workers.len();
        if count == 0 {
            return;
        }

        let packet_len = packet.len();
        let flow = flow_key(packet.as_slice());
        let effective_len = packet_len.saturating_add(66);
        let game_idx = self.select_game_worker(packet.as_slice(), workers);
        let selected_idx = match game_idx {
            Some(idx) => idx,
            None => {
                let mut state = match self.uplink_state.lock() {
                    Ok(guard) => guard,
                    Err(poisoned) => poisoned.into_inner(),
                };
                state.select_deficit_waterfall(
                    workers,
                    effective_len,
                    packet_len,
                    flow,
                    std::time::Instant::now(),
                )
            }
        };

        let target = &workers[selected_idx];
        let wrapped = target.channels.cipher.wrap(
            &mut packet,
            &target.channels.config,
            &target.channels.write_state,
        );
        if wrapped.is_ok() {
            let failed = match target.channels.allocation.try_send_fast(packet) {
                None => false,
                Some(packet) => target.channels.allocation.send(packet).await.is_err(),
            };
            if failed {
                self.mark_uplink_congested(selected_idx);
            }
        }
    }

    fn select_game_worker(&self, packet: &[u8], workers: &[WorkerEntry]) -> Option<usize> {
        let dst = crate::sni::extract_dst_addr(packet);
        if !self.game.is_game_flow(dst.as_ref()) {
            return None;
        }
        let count = workers.len();
        let mut pins = match self.game_pins.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        pins.select(
            dst.as_ref(),
            packet.len(),
            |idx| workers[idx].channels.allocation.is_sendable(),
            count,
        )
    }

    fn mark_uplink_congested(&self, idx: usize) {
        let mut state = match self.uplink_state.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        if let Some(controller) = state.rates.get_mut(idx) {
            controller.mark_congested();
        }
    }

    async fn send_fragments_to_workers(
        &self,
        fragments: Vec<PacketBuf>,
        workers: &[WorkerEntry],
        flow: Option<FlowKey>,
        packet_len: usize,
    ) {
        let count = workers.len();
        if count == 0 || fragments.is_empty() {
            return;
        }

        let total_eff: usize = fragments.iter().map(|f| f.len().saturating_add(66)).sum();
        let selected_idx = {
            let mut state = match self.uplink_state.lock() {
                Ok(guard) => guard,
                Err(poisoned) => poisoned.into_inner(),
            };
            state.select_deficit_waterfall(
                workers,
                total_eff,
                packet_len,
                flow,
                std::time::Instant::now(),
            )
        };

        let target = &workers[selected_idx];
        let mut failed = false;
        for mut frag in fragments {
            let wrapped = target.channels.cipher.wrap(
                &mut frag,
                &target.channels.config,
                &target.channels.write_state,
            );
            if wrapped.is_ok() {
                failed |= match target.channels.allocation.try_send_fast(frag) {
                    None => false,
                    Some(frag) => target.channels.allocation.send(frag).await.is_err(),
                };
            }
        }
        if failed {
            self.mark_uplink_congested(selected_idx);
        }
    }

    pub async fn dispatch(&self, packet: PacketBuf) {
        if self.try_answer_blocked_dns(&packet) {
            return;
        }
        if self.try_reset_encrypted_dns(&packet) {
            return;
        }
        if self.try_block_youtube_ad(&packet) {
            return;
        }
        self.inspect_domain_routing(&packet);
        if let Some(relay) = &self.direct_relay
            && relay.try_handle(&packet).await
        {
            return;
        }
        let workers = self.workers.load();
        if workers.is_empty() {
            return;
        }

        if packet.len() <= 1280 {
            self.send_to_workers(packet, &workers).await;
        } else {
            let packet_len = packet.len();
            let flow = flow_key(packet.as_slice());
            let expected_frags = packet
                .len()
                .div_ceil(crate::wire_protocol::MAX_FRAG_PAYLOAD);
            let packet_id = self.next_frag_id.fetch_add(1, Ordering::Relaxed);
            let slice = packet.as_slice();
            let pool = self.pool.clone();
            let mut frags = Vec::with_capacity(expected_frags);
            crate::wire_protocol::fragment_packet(packet_id, slice, |frag| {
                if let Some(mut p) = pool.try_acquire() {
                    let area = p.read_area();
                    if frag.len() <= area.len() {
                        area[..frag.len()].copy_from_slice(frag);
                        if p.set_read_len(frag.len()).is_ok() {
                            frags.push(p);
                        }
                    }
                }
            });
            if frags.len() == expected_frags {
                self.send_fragments_to_workers(frags, &workers, flow, packet_len)
                    .await;
            } else {
                crate::log_error!(
                    "[ОШИБКА] Dispatcher: фрагмент целиком потерян: {} из {} (буферы)",
                    frags.len(),
                    expected_frags
                );
            }
        }
    }

    #[cfg(unix)]
    async fn write_tun(
        &self,
        device: Arc<tokio::io::unix::AsyncFd<File>>,
        stats: Arc<Stats>,
        cancel: CancellationToken,
    ) {
        let mut pending: Option<(PacketBuf, usize)> = None;
        let mut dropped = 0u64;
        let mut error_streak = 0u32;
        loop {
            if pending.is_none() {
                if let Some(mut packet) = self.pop_inbound() {
                    strip_legacy_downlink_prefix(&mut packet);
                    if self.probe.intercept(packet.as_slice()) {
                        continue;
                    }
                    if packet.len() >= 20 && Self::is_forwardable_packet(packet.as_slice()) {
                        pending = Some((packet, 0));
                    }
                } else {
                    tokio::select! {
                        biased;
                        _ = cancel.cancelled() => return,
                        _ = self.tun_wake.notified() => {},
                    }
                    continue;
                }
            }

            let mut burst = 0usize;
            let mut state = TunWriteState::Complete;
            let mut retry_delay = None;
            while burst < TUN_TX_BURST {
                let Some((packet, written)) = pending.as_mut() else {
                    state = TunWriteState::Complete;
                    break;
                };
                let write_res = device.try_io(tokio::io::Interest::WRITABLE, |inner| {
                    use std::io::Write;

                    let remaining = &packet.as_slice()[*written..];
                    let mut sink = inner;
                    sink.write(remaining)
                });

                match write_res {
                    Ok(0) => {
                        log_dropped_packet(&mut dropped, "запись вернула 0 байт");
                        pending = None;
                        state = TunWriteState::Dropped;
                        break;
                    }
                    Ok(length) => {
                        dropped = 0;
                        *written += length;
                        if *written == packet.len() {
                            error_streak = 0;
                            stats
                                .total_bytes_down
                                .fetch_add(packet.len() as i64, Ordering::Relaxed);
                            burst += 1;
                            if let Some(mut next_packet) = self.pop_inbound() {
                                strip_legacy_downlink_prefix(&mut next_packet);
                                if next_packet.len() >= 20
                                    && Self::is_forwardable_packet(next_packet.as_slice())
                                {
                                    pending = Some((next_packet, 0));
                                } else {
                                    pending = None;
                                    state = TunWriteState::Complete;
                                    break;
                                }
                            } else {
                                pending = None;
                                state = TunWriteState::Complete;
                                break;
                            }
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        state = TunWriteState::Wait;
                        break;
                    }
                    Err(error) if is_transient_tun_error(&error) => {
                        error_streak += 1;
                        if error_streak >= TUN_RETRY_TOLERANCE {
                            crate::log_error!(
                                "[TUN] Запись TUN: интерфейс не отвечает ({error}) после {error_streak} попыток, переподнимаем"
                            );
                            return;
                        }
                        state = if matches!(
                            error.raw_os_error(),
                            Some(libc::ENOBUFS) | Some(libc::ENOMEM)
                        ) {
                            TunWriteState::Backoff
                        } else {
                            TunWriteState::Yield
                        };
                        retry_delay = Some(tun_retry_backoff(error_streak));
                        break;
                    }
                    Err(error) if is_dead_tun_error(&error) => {
                        crate::log_error!("[TUN] Запись TUN: дескриптор мёртв: {error}");
                        return;
                    }
                    Err(error) => {
                        log_dropped_packet(&mut dropped, &error.to_string());
                        pending = None;
                        state = TunWriteState::Dropped;
                        break;
                    }
                }
            }

            match state {
                TunWriteState::Wait => {
                    let readiness = tokio::select! {
                        biased;
                        _ = cancel.cancelled() => return,
                        result = device.writable() => result,
                    };
                    if let Err(error) = readiness {
                        if is_dead_tun_error(&error) {
                            crate::log_error!("[TUN] Запись TUN: дескриптор мёртв ({error})");
                            return;
                        }
                        tokio::time::sleep(Duration::from_millis(20)).await;
                        continue;
                    }
                }
                TunWriteState::Backoff | TunWriteState::Yield => {
                    tokio::task::yield_now().await;
                }
                TunWriteState::Complete | TunWriteState::Dropped => {
                    if burst >= TUN_TX_BURST {
                        tokio::task::yield_now().await;
                    }
                }
            }
            if let Some(delay) = retry_delay {
                tokio::select! {
                    biased;
                    _ = cancel.cancelled() => return,
                    _ = tokio::time::sleep(delay) => {}
                }
            }
        }
    }

    async fn write_udp(
        &self,
        socket: Arc<UdpSocket>,
        client: Arc<ArcSwapOption<SocketAddr>>,
        stats: Arc<Stats>,
    ) {
        loop {
            let Some(address) = client.load().as_deref().copied() else {
                tokio::select! {
                    biased;
                    _ = self.cancel.cancelled() => return,
                    _ = self.tun_wake.notified() => {},
                }
                continue;
            };

            let mut packet = match self.pop_inbound() {
                Some(packet) => packet,
                None => {
                    tokio::select! {
                        biased;
                        _ = self.cancel.cancelled() => return,
                        _ = self.tun_wake.notified() => {},
                    }
                    continue;
                }
            };
            strip_legacy_downlink_prefix(&mut packet);

            match socket.try_send_to(packet.as_slice(), address) {
                Ok(length) if length == packet.len() => {
                    stats
                        .total_bytes_down
                        .fetch_add(length as i64, Ordering::Relaxed);
                }
                Ok(_) => {}
                Err(ref error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    self.write_udp_packet(&socket, Some(address), &stats, &packet)
                        .await;
                }
                Err(_) => {}
            }

            let mut burst = 0usize;
            while burst < UDP_TX_BURST {
                let Some(mut next_packet) = self.pop_inbound() else {
                    break;
                };
                strip_legacy_downlink_prefix(&mut next_packet);
                burst += 1;
                match socket.try_send_to(next_packet.as_slice(), address) {
                    Ok(length) if length == next_packet.len() => {
                        stats
                            .total_bytes_down
                            .fetch_add(length as i64, Ordering::Relaxed);
                    }
                    Ok(_) => {}
                    Err(ref error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        self.write_udp_packet(&socket, Some(address), &stats, &next_packet)
                            .await;
                        break;
                    }
                    Err(_) => break,
                }
            }
        }
    }

    async fn write_udp_packet(
        &self,
        socket: &UdpSocket,
        address: Option<SocketAddr>,
        stats: &Stats,
        packet: &PacketBuf,
    ) {
        if let Some(address) = address {
            loop {
                let readiness = tokio::select! {
                    biased;
                    _ = self.cancel.cancelled() => return,
                    result = socket.writable() => result,
                };
                if readiness.is_err() {
                    return;
                }

                match socket.try_send_to(packet.as_slice(), address) {
                    Ok(length) if length == packet.len() => {
                        stats
                            .total_bytes_down
                            .fetch_add(length as i64, Ordering::Relaxed);
                        return;
                    }
                    Ok(_) => return,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
                    Err(_) => return,
                }
            }
        }
    }
}

#[inline(always)]
fn strip_legacy_downlink_prefix(packet: &mut PacketBuf) {
    const LEGACY_PREFIX_LEN: usize = 24;
    let bytes = packet.as_slice();
    if !is_ip_packet(bytes)
        && bytes.len() > LEGACY_PREFIX_LEN
        && is_ip_packet(&bytes[LEGACY_PREFIX_LEN..])
    {
        let _ = packet.trim_front(LEGACY_PREFIX_LEN);
    }
}

#[inline(always)]
fn is_ip_packet(packet: &[u8]) -> bool {
    matches!(packet.first().copied().map(|byte| byte >> 4), Some(4 | 6))
}

fn spawn_critical<F>(
    name: &'static str,
    cancel: CancellationToken,
    termination: Arc<OnceLock<Termination>>,
    future: F,
) -> JoinHandle<()>
where
    F: Future<Output = ()> + Send + 'static,
{
    tokio::spawn(async move {
        if let Err(error) = tokio::spawn(future).await
            && error.is_panic()
        {
            crate::log_error!("[СУПЕРВИЗОР] {name} завершился аварийно: {error}");
            let _ = termination.set(Termination::Fatal);
            cancel.cancel();
        }
    })
}

#[cfg(unix)]
fn log_dropped_packet(counter: &mut u64, reason: &str) {
    *counter += 1;
    if *counter <= 8 || counter.is_multiple_of(1000) {
        crate::log_error!("[ОШИБКА] Запись TUN: пакет #{counter} отброшен ({reason})");
    }
}

#[cfg(unix)]
fn is_dead_tun_error(error: &std::io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code) if code == libc::EBADF || code == libc::ENODEV
    )
}

#[cfg(unix)]
fn is_transient_tun_error(error: &std::io::Error) -> bool {
    matches!(error.raw_os_error(), Some(libc::EIO)) || is_retryable_tun_error(error)
}

#[cfg(unix)]
fn tun_retry_backoff(streak: u32) -> Duration {
    let factor = u64::from(streak.clamp(1, TUN_RETRY_BACKOFF_STEPS));
    Duration::from_millis((TUN_RETRY_BACKOFF_STEP_MS * factor).min(TUN_RETRY_BACKOFF_MAX_MS))
}

#[cfg(unix)]
fn is_retryable_tun_error(error: &std::io::Error) -> bool {
    error.kind() == std::io::ErrorKind::Interrupted
        || error.kind() == std::io::ErrorKind::WouldBlock
        || matches!(
            error.raw_os_error(),
            Some(code) if code == libc::ENOBUFS || code == libc::ENOMEM
        )
}

async fn bind_udp(address: &str) -> Result<UdpSocket> {
    for attempt in 1..=5 {
        match UdpSocket::bind(address).await {
            Ok(socket) => {
                SockRef::from(&socket).set_recv_buffer_size(1024 * 1024)?;
                SockRef::from(&socket).set_send_buffer_size(256 * 1024)?;
                return Ok(socket);
            }
            Err(error) if attempt < 5 => {
                crate::log_error!("[ОЖИДАНИЕ] Порт {address} занят. Жду... ({attempt}/5): {error}");
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
            Err(error) => crate::log_error!("[АВТО-ПОРТ] Порт {address} всё ещё занят: {error}"),
        }
    }
    UdpSocket::bind("127.0.0.1:0").await.map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ipv4_packet(proto: u8, sport: u16, dport: u16, len: usize) -> Vec<u8> {
        let mut packet = vec![0u8; len];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(len as u16).to_be_bytes());
        packet[9] = proto;
        packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
        packet[16..20].copy_from_slice(&[10, 66, 67, 2]);
        packet[20..22].copy_from_slice(&sport.to_be_bytes());
        packet[22..24].copy_from_slice(&dport.to_be_bytes());
        packet
    }

    #[test]
    fn flow_key_parses_ipv4_and_separates_flows() {
        let a = ipv4_packet(6, 49152, 80, 60);
        let b = ipv4_packet(6, 49153, 80, 60);
        let key_a = flow_key(&a).expect("tcp flow");
        let key_b = flow_key(&b).expect("tcp flow");
        assert_ne!(key_a, key_b);
        assert!(matches!(
            key_a,
            FlowKey::V4 {
                proto: 6,
                sport: 49152,
                dport: 80,
                ..
            }
        ));
    }

    #[test]
    fn flow_key_parses_ipv6_transport_ports() {
        let mut packet = vec![0u8; 60];
        packet[0] = 0x60;
        packet[6] = 17;
        packet[8..24]
            .copy_from_slice(&[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]);
        packet[24..40]
            .copy_from_slice(&[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2]);
        packet[40..42].copy_from_slice(&27015u16.to_be_bytes());
        packet[42..44].copy_from_slice(&27015u16.to_be_bytes());
        assert!(matches!(
            flow_key(&packet),
            Some(FlowKey::V6 {
                proto: 17,
                sport: 27015,
                dport: 27015,
                ..
            })
        ));
    }

    #[test]
    fn bulk_udp_with_small_packets_loses_the_pin() {
        // A QUIC-like upload: 1300-byte packets at ~20 Mbit/s and a small one every 200 ms.
        let start = std::time::Instant::now();
        let mut state = FlowState::new(0, start, 100, true);
        let mut pinned_late = 0;
        for i in 1..4000u64 {
            let now = start + Duration::from_micros(i * 520);
            let small = i % 385 == 0;
            if state.keeps_pin(now, if small { 100 } else { 1300 }, small) && i > 200 {
                pinned_late += 1;
            }
        }
        assert_eq!(pinned_late, 0, "a bulk flow must not stay pinned");
    }

    #[test]
    fn game_flow_keeps_the_pin() {
        // 64 packets/s, small and 1200-byte alternating: fits in one stream, stays pinned throughout.
        let start = std::time::Instant::now();
        let mut state = FlowState::new(0, start, 120, true);
        for i in 1..320u64 {
            let now = start + Duration::from_micros(i * 15_625);
            let small = i % 2 == 0;
            assert!(state.keeps_pin(now, if small { 120 } else { 1200 }, small), "packet {i}");
        }
    }

    #[test]
    fn small_udp_detection_keeps_games_sticky() {
        let game = ipv4_packet(17, 27015, 27015, 180);
        let bulk = ipv4_packet(17, 27015, 27015, 400);
        let tcp = ipv4_packet(6, 27015, 27015, 180);
        assert!(is_small_udp(&flow_key(&game).unwrap(), game.len()));
        assert!(!is_small_udp(&flow_key(&bulk).unwrap(), bulk.len()));
        assert!(!is_small_udp(&flow_key(&tcp).unwrap(), tcp.len()));
    }

    #[test]
    fn inbound_delivers_fifo() {
        let pool = PacketPool::new(16);
        let inbound = ArrayQueue::new(16);

        let mut p1 = pool.acquire();
        p1.set_read_len(20).unwrap();
        p1.as_mut_slice()[0] = 0x45;
        p1.as_mut_slice()[19] = 1;

        let mut p2 = pool.acquire();
        p2.set_read_len(20).unwrap();
        p2.as_mut_slice()[0] = 0x45;
        p2.as_mut_slice()[19] = 2;

        let _ = inbound.push(p1);
        let _ = inbound.push(p2);

        assert_eq!(inbound.len(), 2);
        assert_eq!(inbound.pop().unwrap().as_slice()[19], 1);
        assert_eq!(inbound.pop().unwrap().as_slice()[19], 2);
    }

    #[test]
    fn outbound_delivers_fifo() {
        let pool = PacketPool::new(16);
        let outbound = ArrayQueue::new(16);

        let mut p1 = pool.acquire();
        p1.set_read_len(20).unwrap();
        p1.as_mut_slice()[0] = 0x45;
        p1.as_mut_slice()[19] = 1;

        let mut p2 = pool.acquire();
        p2.set_read_len(20).unwrap();
        p2.as_mut_slice()[0] = 0x45;
        p2.as_mut_slice()[19] = 2;

        let _ = outbound.push(p1);
        let _ = outbound.push(p2);

        assert_eq!(outbound.len(), 2);
        assert_eq!(outbound.pop().unwrap().as_slice()[19], 1);
        assert_eq!(outbound.pop().unwrap().as_slice()[19], 2);
    }

    #[test]
    fn return_packet_delivers_fifo() {
        let pool = PacketPool::new(16);
        let cancel = CancellationToken::new();
        let dispatcher = Dispatcher {
            workers: ArcSwap::from_pointee(Vec::new()),
            inbound: ArrayQueue::new(16),
            outbound_capacity: 32,
            tun_wake: Arc::new(Notify::new()),
            tun_io_lost: Arc::new(Notify::new()),
            cancel,
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
            direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
            dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()),
            direct_relay: None,
            uplink_state: Mutex::new(UplinkState::new()),
            pool: pool.clone(),
            reassembler: crate::wire_protocol::ShardedReassembler::new(),
            next_frag_id: std::sync::atomic::AtomicU16::new(1),
            probe: Default::default(),
            termination: Arc::new(OnceLock::new()),
        };

        let mut p1 = pool.acquire();
        let raw1 = [
            0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x01, 0x01,
            0x01, 0x01, 0x02, 0x02, 0x02, 0x01,
        ];
        p1.set_read_len(raw1.len()).unwrap();
        p1.as_mut_slice()[..raw1.len()].copy_from_slice(&raw1);

        let mut p2 = pool.acquire();
        let raw2 = [
            0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x01, 0x01,
            0x01, 0x01, 0x02, 0x02, 0x02, 0x02,
        ];
        p2.set_read_len(raw2.len()).unwrap();
        p2.as_mut_slice()[..raw2.len()].copy_from_slice(&raw2);

        dispatcher.return_packet(p1);
        dispatcher.return_packet(p2);

        let out1 = dispatcher.pop_inbound().expect("packet 1");
        assert_eq!(out1.as_slice(), &raw1);
        let out2 = dispatcher.pop_inbound().expect("packet 2");
        assert_eq!(out2.as_slice(), &raw2);
    }

    #[test]
    fn uplink_cascade_waterfall_fills_worker_zero_then_overflows() {
        let mut state = UplinkState::new();
        let now = std::time::Instant::now();

        // 3 workers, each has initial TB_CAPACITY_BYTES (10500.0)
        // 8 packets of 1200 bytes fit entirely into worker 0 (8 * 1200 = 9600 <= 10500)
        for _ in 0..8 {
            assert_eq!(state.select(3, 1200, now), 0);
        }
        // Worker 0 now has 900 tokens left (< 1200). 9th packet cascades to worker 1!
        assert_eq!(state.select(3, 1200, now), 1);
        // Worker 1 now has 9300 tokens left. 10th packet also goes to worker 1:
        assert_eq!(state.select(3, 1200, now), 1);
        // Worker 1 takes 6 more packets (total 8: packets 9..16). 17th packet cascades to worker 2:
        for _ in 0..6 {
            assert_eq!(state.select(3, 1200, now), 1);
        }
        assert_eq!(state.select(3, 1200, now), 2);
    }

    #[test]
    fn uplink_cascade_pacing_pins_spaced_ticks_to_worker_zero() {
        let mut state = UplinkState::new();
        let mut now = std::time::Instant::now();

        // Game ticks at 64 Hz (15.625 ms), 200 bytes each.
        // Worker 0 replenishes tokens faster than consumption, keeping 100% of packets on worker 0!
        let mut seen = std::collections::HashSet::new();
        for _ in 0..30 {
            let idx = state.select(3, 200, now);
            seen.insert(idx);
            now += std::time::Duration::from_micros(15625);
        }
        assert_eq!(seen.len(), 1);
        assert!(seen.contains(&0));
    }

    #[test]
    fn adaptive_rate_backs_off_on_congestion_and_recovers() {
        let mut controller = RateController::new();
        let mut now = std::time::Instant::now();
        assert_eq!(controller.rate, TB_RATE_BYTES_PER_SEC);
        controller.tick(now);

        controller.mark_congested();
        now += ADAPTIVE_ADJUST_INTERVAL;
        controller.tick(now);
        assert!(controller.rate < TB_RATE_BYTES_PER_SEC);
        let after_first = controller.rate;

        for _ in 0..10 {
            controller.mark_congested();
            now += ADAPTIVE_ADJUST_INTERVAL;
            controller.tick(now);
        }
        assert_eq!(controller.rate, ADAPTIVE_MIN_RATE_BYTES_PER_SEC);
        assert!(controller.rate <= after_first);

        for _ in 0..40 {
            now += ADAPTIVE_ADJUST_INTERVAL;
            controller.tick(now);
        }
        assert_eq!(controller.rate, TB_RATE_BYTES_PER_SEC);
    }

    #[test]
    fn adaptive_rate_adjusts_at_most_once_per_interval() {
        let mut controller = RateController::new();
        let mut now = std::time::Instant::now();
        controller.tick(now);
        controller.mark_congested();
        now += Duration::from_millis(50);
        controller.tick(now);
        assert_eq!(controller.rate, TB_RATE_BYTES_PER_SEC);
        now += Duration::from_millis(50);
        controller.tick(now);
        assert!(controller.rate < TB_RATE_BYTES_PER_SEC);
        assert!(!controller.congested);
    }

    #[test]
    fn adaptive_token_refill_uses_per_stream_rate() {
        let mut state = UplinkState::new();
        let mut now = std::time::Instant::now();
        state.update_tokens(2, now);
        assert_eq!(state.rates.len(), 2);

        state.tokens[0] = 0.0;
        state.tokens[1] = 0.0;
        state.rates[0].rate = ADAPTIVE_MIN_RATE_BYTES_PER_SEC;
        state.rates[1].rate = ADAPTIVE_MAX_RATE_BYTES_PER_SEC;
        state.rates[0].last_adjust = Some(now);
        state.rates[1].last_adjust = Some(now);
        state.last_update = Some(now);

        now += Duration::from_millis(10);
        state.update_tokens(2, now);
        let low = state.tokens[0];
        let high = state.tokens[1];
        assert!(high > low);
        let expected = (ADAPTIVE_MAX_RATE_BYTES_PER_SEC - ADAPTIVE_MIN_RATE_BYTES_PER_SEC) * 0.010;
        assert!((high - low - expected).abs() < 1.0);
    }

    #[test]
    fn queue_profile_ingress_capacity_scales_and_is_bounded() {
        // Буфер приёма из TUN: 24 пакета на поток, не меньше 512 и не больше
        // 2048 (≈ 3 МБ) — прежние 256…1024 переполнялись на быстром канале.
        for (workers, expected) in [
            (3, 512),
            (5, 512),
            (10, 512),
            (20, 512),
            (30, 720),
            (40, 960),
            (50, 1_200),
            (60, 1_440),
            (70, 1_680),
            (80, 1_920),
            (90, 2_048),
            (100, 2_048),
        ] {
            let profile = QueueProfile::for_worker_count(workers);
            assert_eq!(profile.inbound_capacity, expected);
            assert_eq!(profile.outbound_capacity, 128);
        }
        // Границы держим явно: рост числа потоков не должен тянуть память
        // без предела.
        assert!(QueueProfile::for_worker_count(1).inbound_capacity >= INGRESS_CAPACITY_PKTS);
        assert!(QueueProfile::for_worker_count(usize::MAX).inbound_capacity <= INGRESS_CAPACITY_MAX_PKTS);
    }

    #[test]
    fn test_dispatcher_inbound_capacity_and_overflow_replacement() {
        let profile = QueueProfile::for_worker_count(9);
        let inbound_capacity = profile.inbound_capacity;
        let pool = PacketPool::new(inbound_capacity + 16);
        let cancel = CancellationToken::new();
        let dispatcher = Dispatcher {
            workers: ArcSwap::from_pointee(Vec::new()),
            inbound: ArrayQueue::new(inbound_capacity),
            outbound_capacity: profile.outbound_capacity,
            tun_wake: Arc::new(Notify::new()),
            tun_io_lost: Arc::new(Notify::new()),
            cancel,
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
            direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
            dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()),
            direct_relay: None,
            uplink_state: Mutex::new(UplinkState::new()),
            pool: pool.clone(),
            reassembler: crate::wire_protocol::ShardedReassembler::new(),
            next_frag_id: std::sync::atomic::AtomicU16::new(1),
            probe: Default::default(),
            termination: Arc::new(OnceLock::new()),
        };

        for i in 0..inbound_capacity {
            let mut p = pool.acquire();
            let b = (i & 0xff) as u8;
            p.set_read_len(1500).unwrap();
            p.as_mut_slice()[0] = 0x45;
            p.as_mut_slice()[19] = b;
            let res = dispatcher.inbound.push(p);
            assert!(res.is_ok());
        }

        assert_eq!(dispatcher.inbound.len(), inbound_capacity);

        let mut bulk_pkt = pool.acquire();
        bulk_pkt.set_read_len(1500).unwrap();
        bulk_pkt.as_mut_slice()[0] = 0x45;
        bulk_pkt.as_mut_slice()[19] = 0xfe;
        dispatcher.push_inbound(bulk_pkt);
        assert_eq!(dispatcher.inbound.len(), inbound_capacity);

        let mut small_pkt = pool.acquire();
        small_pkt.set_read_len(150).unwrap();
        small_pkt.as_mut_slice()[0] = 0x45;
        small_pkt.as_mut_slice()[19] = 0xfd;
        dispatcher.push_inbound(small_pkt);
        assert_eq!(dispatcher.inbound.len(), inbound_capacity);

        let first = dispatcher.pop_inbound().unwrap();
        assert_eq!(first.as_slice()[19], 2);

        let second = dispatcher.pop_inbound().unwrap();
        assert_eq!(second.as_slice()[19], 3);
    }

    #[tokio::test]
    async fn test_push_inbound_wakes_the_writer_whatever_the_queue_length() {
        let profile = QueueProfile::for_worker_count(1);
        let pool = PacketPool::new(profile.inbound_capacity + 8);
        let dispatcher = Dispatcher {
            workers: ArcSwap::from_pointee(Vec::new()),
            inbound: ArrayQueue::new(profile.inbound_capacity),
            outbound_capacity: profile.outbound_capacity,
            tun_wake: Arc::new(Notify::new()),
            tun_io_lost: Arc::new(Notify::new()),
            cancel: CancellationToken::new(),
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
            direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
            dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()),
            direct_relay: None,
            uplink_state: Mutex::new(UplinkState::new()),
            pool: pool.clone(),
            reassembler: crate::wire_protocol::ShardedReassembler::new(),
            next_frag_id: std::sync::atomic::AtomicU16::new(1),
            probe: Default::default(),
            termination: Arc::new(OnceLock::new()),
        };
        let packet = || {
            let mut p = pool.acquire();
            p.set_read_len(60).unwrap();
            p.as_mut_slice()[0] = 0x45;
            p
        };
        // three already queued (more than the old "<= 2" threshold), the writer's permit consumed: it is parked
        for _ in 0..3 {
            dispatcher.push_inbound(packet());
        }
        let wait = std::time::Duration::from_millis(50);
        assert!(tokio::time::timeout(wait, dispatcher.tun_wake.notified()).await.is_ok());
        assert!(tokio::time::timeout(wait, dispatcher.tun_wake.notified()).await.is_err());
        // the next packet must wake it — before the fix it waited for the next downlink packet
        dispatcher.push_inbound(packet());
        assert!(tokio::time::timeout(wait, dispatcher.tun_wake.notified()).await.is_ok());
    }

    #[tokio::test]
    async fn test_dispatch_malformed_packets_safety() {
        let pool = PacketPool::new(16);
        let cancel = CancellationToken::new();
        let dispatcher = Dispatcher {
            workers: ArcSwap::from_pointee(Vec::new()),
            inbound: ArrayQueue::new(16),
            outbound_capacity: 64,
            tun_wake: Arc::new(Notify::new()),
            tun_io_lost: Arc::new(Notify::new()),
            cancel,
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
            direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
            dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()),
            direct_relay: None,
            uplink_state: Mutex::new(UplinkState::new()),
            pool: pool.clone(),
            reassembler: crate::wire_protocol::ShardedReassembler::new(),
            next_frag_id: std::sync::atomic::AtomicU16::new(1),
            probe: Default::default(),
            termination: Arc::new(OnceLock::new()),
        };

        let p_empty = pool.acquire();
        dispatcher.dispatch(p_empty).await;

        for len in 1..20 {
            let mut p = pool.acquire();
            p.set_read_len(len).unwrap();
            p.as_mut_slice()[0] = 0x45;
            dispatcher.dispatch(p).await;
        }

        let mut p_bad_ver = pool.acquire();
        p_bad_ver.set_read_len(20).unwrap();
        p_bad_ver.as_mut_slice()[0] = 0x15;
        dispatcher.dispatch(p_bad_ver).await;

        let mut p_bad_ihl = pool.acquire();
        p_bad_ihl.set_read_len(20).unwrap();
        p_bad_ihl.as_mut_slice()[0] = 0x40;
        dispatcher.dispatch(p_bad_ihl).await;
    }

    #[tokio::test]
    async fn test_dispatch_large_packet_direct_relay_bypass() {
        let pool = PacketPool::new(32);
        let cancel = CancellationToken::new();
        let (inbound_tx, _inbound_rx) = tokio::sync::mpsc::channel(64);
        let domain_routing = Arc::new(ArcSwap::from_pointee(
            crate::dispatcher::DomainRoutingConfig {
                enabled: true,
                is_whitelist: false,
                exclude_ru_zones: true,
                template_domains: false,
                direct_domains: vec!["example.com".to_string()].into(),
                ip_routing_enabled: false,
                ip_is_whitelist: false,
                ip_rules: vec![].into(),
            },
        ));
        let direct_ips = Arc::new(ArcSwap::from_pointee(HashSet::new()));
        let dns_domain_map = Arc::new(crate::sni::DnsDomainMap::default());
        let relay = crate::direct_relay::DirectRelay::new(
            domain_routing.clone(),
            direct_ips.clone(),
            dns_domain_map.clone(),
            pool.clone(),
            inbound_tx,
            std::collections::HashSet::new(),
        )
        .expect("smoltcp test stack must initialize");

        let dst_ip: std::net::IpAddr = "93.184.216.34".parse().unwrap();
        direct_ips.store(Arc::new(std::iter::once(dst_ip).collect()));

        let dispatcher = Dispatcher {
            workers: ArcSwap::from_pointee(Vec::new()),
            inbound: ArrayQueue::new(16),
            outbound_capacity: 64,
            tun_wake: Arc::new(Notify::new()),
            tun_io_lost: Arc::new(Notify::new()),
            cancel,
            tasks: tokio::sync::Mutex::new(Vec::new()),
            domain_routing,
            direct_ips,
            dns_domain_map,
            direct_relay: Some(Arc::new(relay)),
            uplink_state: Mutex::new(UplinkState::new()),
            pool: pool.clone(),
            reassembler: crate::wire_protocol::ShardedReassembler::new(),
            next_frag_id: std::sync::atomic::AtomicU16::new(1),
            probe: Default::default(),
            termination: Arc::new(OnceLock::new()),
        };

        let src: SocketAddr = "10.66.67.2:54321".parse().unwrap();
        let dst: SocketAddr = "93.184.216.34:80".parse().unwrap();
        let syn_raw = crate::direct_relay::build_tcp_packet(src, dst, 1000, 0, 0x02, &[]).unwrap();
        let mut pkt = pool.try_acquire().unwrap();
        pkt.extend_tail(syn_raw.len())
            .unwrap()
            .copy_from_slice(&syn_raw);

        let initial_frag_id = dispatcher
            .next_frag_id
            .load(std::sync::atomic::Ordering::Relaxed);
        dispatcher.dispatch(pkt).await;
        let next_frag_id = dispatcher
            .next_frag_id
            .load(std::sync::atomic::Ordering::Relaxed);
        assert_eq!(next_frag_id, initial_frag_id);
    }

    #[test]
    fn test_try_follow_stream_respects_deficit_window() {
        let mut state = UplinkState::new();
        let now = std::time::Instant::now();

        // 3 workers, initial tokens TB_CAPACITY_BYTES each
        let chosen = state.select(3, 1154, now);
        assert_eq!(chosen, 0);
        assert!((state.tokens[0] - (TB_CAPACITY_BYTES - 1154.0)).abs() < 1e-6);

        // Follow with tail fragment (494 bytes)
        assert!(state.try_follow_stream(0, 3, 494, now));
        assert!((state.tokens[0] - (TB_CAPACITY_BYTES - 1154.0 - 494.0)).abs() < 1e-6);

        // Manually lower tokens to near MAX_DEFICIT (-4500.0)
        state.tokens[0] = -4450.0;
        // Packet of 50 bytes: -4450 - 50 = -4500 >= -4500
        assert!(state.try_follow_stream(0, 3, 50, now));
        assert!((state.tokens[0] - MAX_DEFICIT).abs() < 1e-6);

        // Packet of 100 bytes: clamped to MAX_DEFICIT (-4500.0) without separating fragment
        assert!(state.try_follow_stream(0, 3, 100, now));
        assert!((state.tokens[0] - MAX_DEFICIT).abs() < 1e-6);

        // Out of bounds stream index
        assert!(!state.try_follow_stream(10, 3, 100, now));
    }

    #[test]
    fn test_select_cascade_waterfall_cursor() {
        let mut state = UplinkState::new();
        let now = std::time::Instant::now();

        // 3 workers, quantum TB_CAPACITY_BYTES (10500)
        // 8 packets of 1200 bytes fit into worker 0 (9600 <= 10500):
        for _ in 0..8 {
            let idx = state.select(3, 1200, now);
            assert_eq!(idx, 0);
            assert_eq!(state.cursor, 0);
        }

        // 9th packet (10800 > 10500) overflows to worker 1:
        let idx2 = state.select(3, 1200, now);
        assert_eq!(idx2, 1);
        assert_eq!(state.cursor, 1);
    }

    #[test]
    fn test_select_cursor_bounds_after_scale_down() {
        let mut state = UplinkState::new();
        let now = std::time::Instant::now();

        // Cursor was at 35 with 40 workers
        state.cursor = 35;
        // Workers scaled down to 20: select MUST NOT panic and return index < 20
        let idx = state.select(20, 1000, now);
        assert!(idx < 20);
        assert!(state.cursor < 20);
    }
}
