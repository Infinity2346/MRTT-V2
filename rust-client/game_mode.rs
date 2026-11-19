//! Игровой режим: те же воркеры, но трафик идёт без переупорядочивания.
//!
//! Обычный режим размазывает новые потоки по всем воркерам ради скорости:
//! игра при этом получает пакеты с разных TURN-аллокаций, и разница в
//! задержке между ними видна как «пинг скачет», а UDP-поток иногда
//! переезжает на другой воркер прямо посреди боя. Игровой режим оставляет
//! каждый поток на одном воркере на всё время жизни потока (sticky),
//! предпочитает уже прогретый воркер и не гонится за суммой скоростей.
//!
//! Режим не меняет ни транспорт, ни шифрование: он только выбирает, в какой
//! воркер положить пакет, поэтому включается и выключается на ходу командой
//! из приложения (`GAME_MODE|<вкл>|<порты игр через запятую>`).

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

/// Сколько байт поток может отдать, оставаясь на одном воркере. Выше — это уже
/// загрузка (браузер, стрим), а не игра: такой поток раскладывается по всем
/// каналам, иначе он упирается в скорость одной аллокации TURN.
pub const PIN_MAX_BYTES: u64 = 384 * 1024;
/// Окно, в котором считается объём: игра стабильно шлёт десятки килобайт в
/// секунду, загрузка — мегабайты.
pub const PIN_BYTES_WINDOW: Duration = Duration::from_secs(2);
/// Сколько поток остаётся «загрузочным» после превышения лимита: без этого
/// пакеты загрузки то закреплялись бы, то нет, и порядок всё равно ломался.
const BULK_HOLD: Duration = Duration::from_secs(2);

/// Сколько поток остаётся закреплённым за воркером после последнего пакета.
const PIN_TTL: Duration = Duration::from_secs(120);
/// Верхняя граница таблицы закреплений, чтобы режим не рос в памяти.
const MAX_PINS: usize = 4096;
/// Поток без пакетов дольше этого срока считается новым при выборе воркера.
const IDLE_RESET: Duration = Duration::from_secs(20);

/// Состояние игрового режима, общее для диспетчера и канала управления.
#[derive(Debug, Default)]
pub struct GameMode {
    enabled: AtomicBool,
    /// Порты игр из приложения: на них игровой режим включается сам.
    ports: RwLock<Vec<u16>>,
}

impl GameMode {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn set_enabled(&self, enabled: bool) -> bool {
        let previous = self.enabled.swap(enabled, Ordering::AcqRel);
        previous != enabled
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled.load(Ordering::Acquire)
    }

    /// Список портов игр: пакет на такой порт обслуживается как игровой, даже
    /// если режим выключен вручную. Пустой список ничего не включает.
    pub fn set_ports(&self, ports: Vec<u16>) {
        if let Ok(mut guard) = self.ports.write() {
            let mut deduped = ports;
            deduped.sort_unstable();
            deduped.dedup();
            *guard = deduped;
        }
    }

    pub fn ports_len(&self) -> usize {
        self.ports.read().map(|guard| guard.len()).unwrap_or(0)
    }

    pub fn matches_game_port(&self, dst_port: u16) -> bool {
        if dst_port == 0 {
            return false;
        }
        self.ports
            .read()
            .map(|guard| guard.binary_search(&dst_port).is_ok())
            .unwrap_or(false)
    }

    /// Игровой ли поток: режим включён целиком либо адрес назначения — игра.
    pub fn is_game_flow(&self, dst: Option<&SocketAddr>) -> bool {
        if self.is_enabled() {
            return true;
        }
        dst.is_some_and(|addr| self.matches_game_port(addr.port()))
    }

    /// Разбирает команду `GAME_MODE|0|3074,3478` — включает режим и задаёт порты.
    pub fn apply_command(&self, payload: &str) -> (bool, usize) {
        let mut parts = payload.split('|');
        let enabled = parts
            .next()
            .map(|value| {
                let value = value.trim();
                value == "1" || value.eq_ignore_ascii_case("true") || value == "on"
            })
            .unwrap_or(false);
        let ports = parts
            .next()
            .map(parse_ports)
            .unwrap_or_default();
        self.set_enabled(enabled);
        self.set_ports(ports);
        (enabled, self.ports_len())
    }
}

/// Порты из строки: запятые, пробелы, диапазоны вида `27000-27100`.
pub fn parse_ports(input: &str) -> Vec<u16> {
    const MAX_RANGE: u32 = 512;
    let mut ports = Vec::new();
    for token in input.split(|c: char| c == ',' || c == ';' || c.is_whitespace()) {
        let token = token.trim();
        if token.is_empty() {
            continue;
        }
        if let Some((start, end)) = token.split_once('-') {
            let (Ok(start), Ok(end)) = (start.trim().parse::<u32>(), end.trim().parse::<u32>())
            else {
                continue;
            };
            if start == 0 || end < start || end > u32::from(u16::MAX) || end - start > MAX_RANGE {
                continue;
            }
            for port in start..=end {
                ports.push(port as u16);
            }
        } else if let Ok(port) = token.parse::<u16>() {
            if port != 0 {
                ports.push(port);
            }
        }
    }
    ports.sort_unstable();
    ports.dedup();
    ports
}

/// Закрепление потока: воркер, время последнего пакета и объём в текущем окне.
#[derive(Debug)]
struct Pin {
    idx: usize,
    seen: Instant,
    window_start: Instant,
    window_bytes: u64,
    bulk_until: Option<Instant>,
}

impl Pin {
    fn new(idx: usize, now: Instant, len: usize) -> Self {
        Self {
            idx,
            seen: now,
            window_start: now,
            window_bytes: len as u64,
            bulk_until: None,
        }
    }

    /// Считает пакет и говорит, можно ли держать поток на одном воркере:
    /// игра — да, загрузка — нет (её надо разложить по всем каналам).
    fn accepts(&mut self, now: Instant, len: usize) -> bool {
        if self.is_bulk(now) {
            // Поток уже признан загрузкой: пока окно не истечёт, он идёт общим
            // водопадом, иначе закрепление и раскладка чередовались бы и порядок
            // пакетов всё равно ломался бы.
            self.window_bytes = self.window_bytes.saturating_add(len as u64);
            return false;
        }
        if now.saturating_duration_since(self.window_start) >= PIN_BYTES_WINDOW {
            self.window_start = now;
            self.window_bytes = 0;
        }
        self.window_bytes = self.window_bytes.saturating_add(len as u64);
        if self.window_bytes > PIN_MAX_BYTES {
            self.bulk_until = Some(now + BULK_HOLD);
            return false;
        }
        true
    }

    fn is_bulk(&self, now: Instant) -> bool {
        self.bulk_until.is_some_and(|until| now < until)
    }
}

#[derive(Debug, Default)]
pub struct GamePins {
    pins: HashMap<String, Pin>,
    /// Последний выбранный воркер: новый поток идёт туда же, чтобы пакеты
    /// одного матча не расходились по разным аллокациям.
    preferred: Option<usize>,
    pub pinned_flows: u64,
}

impl GamePins {
    pub fn new() -> Self {
        Self::default()
    }

    /// Забывает все закрепления (смена списка воркеров).
    pub fn reset(&mut self) {
        self.pins.clear();
        self.preferred = None;
    }

    fn key_of(dst: &SocketAddr) -> String {
        // Порт источника меняется редко, но для UDP-игр он и есть идентификатор
        // сессии: без него все клиенты на одном сервере слились бы в один поток.
        format!("{}", dst)
    }

    /// Выбирает воркер для пакета: маленький поток игры остаётся на своём
    /// канале (ровный пинг), крупная загрузка возвращается в общий водопад.
    /// `None` — решать обычному распределению.
    pub fn select(
        &mut self,
        dst: Option<&SocketAddr>,
        packet_len: usize,
        is_sendable: impl Fn(usize) -> bool,
        count: usize,
    ) -> Option<usize> {
        if count == 0 {
            return None;
        }
        let now = Instant::now();
        if let Some(dst) = dst {
            let key = Self::key_of(dst);
            if let Some(pin) = self.pins.get_mut(&key) {
                if pin.idx < count
                    && now.saturating_duration_since(pin.seen) < PIN_TTL
                    && is_sendable(pin.idx)
                {
                    pin.seen = now;
                    if pin.accepts(now, packet_len) {
                        return Some(pin.idx);
                    }
                    // Поток перерос игровой профиль: это загрузка, а не игра.
                    // Закрепление сохраняем, но помечаем поток как «загрузочный»
                    // до конца окна — иначе следующий пакет снова закрепился бы.
                    return None;
                }
                self.pins.remove(&key);
            }
            let idx = self.pick_worker(is_sendable, count)?;
            if self.pins.len() >= MAX_PINS {
                self.evict(now);
            }
            self.pins.insert(key, Pin::new(idx, now, packet_len));
            self.pinned_flows = self.pinned_flows.saturating_add(1);
            self.preferred = Some(idx);
            return Some(idx);
        }
        let idx = self.pick_worker(is_sendable, count)?;
        self.preferred = Some(idx);
        Some(idx)
    }

    fn pick_worker(&mut self, is_sendable: impl Fn(usize) -> bool, count: usize) -> Option<usize> {
        if let Some(idx) = self.preferred {
            if idx < count && is_sendable(idx) {
                return Some(idx);
            }
        }
        (0..count).find(|&idx| is_sendable(idx))
    }

    fn evict(&mut self, now: Instant) {
        self.pins
            .retain(|_, pin| now.saturating_duration_since(pin.seen) < PIN_TTL);
        if self.pins.len() >= MAX_PINS {
            self.pins.clear();
        }
    }
}

/// Игровой ли это адрес по мнению режима: нужен для логов и счётчиков.
pub fn is_game_destination(dst: Option<&SocketAddr>, mode: &GameMode) -> bool {
    match dst {
        Some(addr) => mode.is_game_flow(Some(addr)) && !matches!(addr.ip(), IpAddr::V4(ip) if ip.is_loopback()),
        None => mode.is_enabled(),
    }
}

/// Срок, после которого поток считается новым (для тестов и логов).
pub const fn idle_reset() -> Duration {
    IDLE_RESET
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dst(port: u16) -> SocketAddr {
        SocketAddr::from(([203, 0, 113, 7], port))
    }

    #[test]
    fn parses_ports_and_ranges() {
        let ports = parse_ports("27015, 3478, 27000-27002, bad, 0, 70000");
        assert_eq!(ports, vec![3478, 27000, 27001, 27002, 27015]);
    }

    #[test]
    fn command_switches_the_mode_and_ports() {
        let mode = GameMode::default();
        let (enabled, count) = mode.apply_command("1|27015,3074");
        assert!(enabled);
        assert_eq!(count, 2);
        assert!(mode.is_enabled());
        assert!(mode.matches_game_port(3074));
        assert!(!mode.matches_game_port(443));
        let (enabled, count) = mode.apply_command("0|");
        assert!(!enabled);
        assert_eq!(count, 0);
        assert!(!mode.is_enabled());
    }

    #[test]
    fn game_port_enables_routing_without_the_switch() {
        let mode = GameMode::default();
        mode.set_ports(vec![27015]);
        assert!(mode.is_game_flow(Some(&dst(27015))));
        assert!(!mode.is_game_flow(Some(&dst(443))));
        mode.set_enabled(true);
        assert!(mode.is_game_flow(Some(&dst(443))));
    }

    #[test]
    fn pins_keep_one_flow_on_one_worker() {
        let mut pins = GamePins::new();
        let target = dst(27015);
        let first = pins.select(Some(&target), 120, |_| true, 4).unwrap();
        for _ in 0..100 {
            assert_eq!(pins.select(Some(&target), 120, |_| true, 4), Some(first));
        }
    }

    #[test]
    fn new_flows_reuse_the_warm_worker() {
        let mut pins = GamePins::new();
        let a = pins.select(Some(&dst(27015)), 120, |_| true, 8).unwrap();
        let b = pins.select(Some(&dst(27016)), 120, |_| true, 8).unwrap();
        assert_eq!(a, b, "новый поток идёт на прогретый воркер");
    }

    #[test]
    fn dead_worker_moves_the_flow() {
        let mut pins = GamePins::new();
        let target = dst(27015);
        let first = pins.select(Some(&target), 120, |_| true, 3).unwrap();
        let next = pins
            .select(Some(&target), 120, |idx| idx != first, 3)
            .expect("поток переезжает на живой воркер");
        assert_ne!(next, first);
    }

    #[test]
    fn bulk_flow_leaves_the_game_lane() {
        // Браузер качает файл: 1300-байтные пакеты сотнями за окно — это не игра.
        let mut pins = GamePins::new();
        let target = dst(443);
        assert!(pins.select(Some(&target), 1300, |_| true, 4).is_some());
        let mut released = false;
        for _ in 0..400 {
            if pins.select(Some(&target), 1300, |_| true, 4).is_none() {
                released = true;
                break;
            }
        }
        assert!(released, "крупный поток должен уйти в общий водопад");
    }

    #[test]
    fn game_flow_never_leaves_the_lane() {
        // Игра: десятки пакетов по 120 байт в секунду — сколько угодно долго.
        let mut pins = GamePins::new();
        let target = dst(27015);
        let first = pins.select(Some(&target), 120, |_| true, 4).unwrap();
        for _ in 0..2000 {
            assert_eq!(pins.select(Some(&target), 120, |_| true, 4), Some(first));
        }
    }

    #[test]
    fn no_workers_means_no_choice() {
        let mut pins = GamePins::new();
        assert_eq!(pins.select(Some(&dst(27015)), 120, |_| true, 0), None);
        assert_eq!(pins.select(Some(&dst(27015)), 120, |_| false, 4), None);
    }
}
