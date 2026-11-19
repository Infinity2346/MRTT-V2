//! Режим «свой интернет, но с вырезанной рекламой» для отдельных приложений.
//!
//! Не всякое приложение стоит гнать через VK-туннель: RuTube или браузер с
//! локальным сервисом работают быстрее напрямую. Но просто исключить приложение
//! из туннеля нельзя — вместе с туннелем уходит и фильтр рекламы, а DNS
//! исключённого приложения уходит к оператору в обход фильтра.
//!
//! Поэтому приложение остаётся в VPN, но его потоки получают третий режим:
//! DNS-запросы по-прежнему фильтруются (реклама не резолвится), TCP/UDP идут
//! напрямую через `direct_relay`, минуя воркеры и звонок, — то есть обычным
//! интернетом пользователя.
//!
//! Как узнать, что поток принадлежит именно этому приложению: в VPN мы видим
//! только IP-пакеты, имени пакета там нет. Поэтому SQL-стек при первом пакете
//! нового потока просит приложение (Kotlin) найти владельца соединения
//! (`ConnectivityManager.getConnectionOwnerUid`, Android 10+) и записать пару
//! «источник-назначение» сюда командой `APP_DIRECT_FLOW|src|dst|proto`. Такой же
//! набор хранится и как «просто напрямую» — тогда фильтр рекламы для потока
//! выключается, но сам он всё равно идёт мимо тоннеля.
//!
//! Пока владелец не опознан, поток не блокируется: он идёт в туннель, то есть
//! хуже по скорости, но никогда не рвётся и не утекает мимо VPN незаметно.

use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Сколько живёт запись о потоке без новых пакетов.
const FLOW_TTL: Duration = Duration::from_secs(180);
/// Верхняя граница таблицы, чтобы долгая сессия не росла в памяти.
const MAX_FLOWS: usize = 16_384;
/// Сколько первых пакетов потока держим, пока решается его судьба.
const MAX_HELD_PACKETS: usize = 4;
/// Дольше этого первые пакеты не держим: приложение за это время уже ответит
/// либо соединение начнёт повтор.
const MAX_HELD_AGE: Duration = Duration::from_secs(2);
/// Сколько времени ждём ответ приложения о владельце потока.
const OWNER_QUERY_TTL: Duration = Duration::from_secs(6);
const MAX_OWNER_QUERIES: usize = 1024;

static APP_DIRECT_QUERIES: AtomicU64 = AtomicU64::new(0);
static APP_DIRECT_FLOWS: AtomicU64 = AtomicU64::new(0);

/// Сколько потоков уже опознано как «напрямую с фильтром».
pub fn active_flows() -> u64 {
    APP_DIRECT_FLOWS.load(Ordering::Relaxed)
}

/// Сколько раз спрашивали приложение о владельце потока.
pub fn owner_queries() -> u64 {
    APP_DIRECT_QUERIES.load(Ordering::Relaxed)
}

/// Что делать с потоком.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AppRoute {
    /// Как обычно: DNS фильтруется, трафик идёт в туннель.
    Tunnel,
    /// Прямо через интернет пользователя, но DNS-фильтр рекламы остаётся.
    DirectFiltered,
    /// Прямо и без фильтра: приложение исключено из VPN по выбору пользователя.
    DirectRaw,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
struct FlowKey {
    src: SocketAddr,
    dst: SocketAddr,
    protocol: u8,
}

impl FlowKey {
    fn parse(src: &str, dst: &str, protocol: u8) -> Option<Self> {
        Some(Self {
            src: src.trim().parse().ok()?,
            dst: dst.trim().parse().ok()?,
            protocol,
        })
    }
}

struct Held {
    packets: Vec<Vec<u8>>,
    since: Instant,
}

impl Held {
    fn new(packet: Vec<u8>, now: Instant) -> Self {
        Self {
            packets: vec![packet],
            since: now,
        }
    }
}

#[derive(Default)]
struct State {
    flows: HashMap<FlowKey, (AppRoute, Instant)>,
    /// Пары, о владельце которых уже спросили приложение.
    asked: HashSet<FlowKey>,
    asked_until: HashMap<FlowKey, Instant>,
    /// Первые пакеты потоков, по которым ждём ответ приложения.
    held: HashMap<FlowKey, Held>,
}

/// Таблица потоков, которым разрешён прямой выход.
#[derive(Default)]
pub struct AppDirectTable {
    state: Mutex<State>,
    /// Куда вернуть придержанные пакеты, когда решение принято.
    replay: Mutex<Option<tokio::sync::mpsc::Sender<Vec<u8>>>>,
}

impl AppDirectTable {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// Подключает канал возврата: диспетчер примет пакеты обратно и разложит их
    /// уже по принятому решению.
    pub fn set_replay(&self, sender: tokio::sync::mpsc::Sender<Vec<u8>>) {
        if let Ok(mut guard) = self.replay.lock() {
            *guard = Some(sender);
        }
    }

    /// Придерживает пакет потока, решение по которому ещё не пришло.
    pub fn hold(&self, src: SocketAddr, dst: SocketAddr, protocol: u8, packet: &[u8]) {
        let key = FlowKey {
            src,
            dst,
            protocol,
        };
        let now = Instant::now();
        let mut state = self.lock();
        match state.held.get_mut(&key) {
            Some(held) => {
                if held.packets.len() < MAX_HELD_PACKETS {
                    held.packets.push(packet.to_vec());
                }
            }
            None => {
                state.held.insert(key, Held::new(packet.to_vec(), now));
            }
        }
    }

    /// Возвращает пакеты, которые некуда деть: решение не пришло за отведённое
    /// время. Они уходят обычным путём, чтобы соединение не ждало вечно.
    pub fn drain_stale(&self) -> Vec<Vec<u8>> {
        let mut state = self.lock();
        let now = Instant::now();
        let stale: Vec<FlowKey> = state
            .held
            .iter()
            .filter(|(_, held)| now.saturating_duration_since(held.since) >= MAX_HELD_AGE)
            .map(|(key, _)| *key)
            .collect();
        let mut packets = Vec::new();
        for key in stale {
            if let Some(held) = state.held.remove(&key) {
                packets.extend(held.packets);
            }
        }
        packets
    }

    fn release_held(&self, key: &FlowKey) {
        let packets = {
            let mut state = self.lock();
            state.held.remove(key).map(|held| held.packets)
        };
        let Some(packets) = packets else {
            return;
        };
        let sender = match self.replay.lock() {
            Ok(guard) => guard.clone(),
            Err(_) => None,
        };
        if let Some(sender) = sender {
            for packet in packets {
                let _ = sender.try_send(packet);
            }
        }
    }

    /// Записывает решение по потоку (ответ приложения на запрос владельца).
    pub fn set_flow(&self, src: &str, dst: &str, protocol: u8, direct: bool, filtered: bool) {
        let Some(key) = FlowKey::parse(src, dst, protocol) else {
            return;
        };
        let route = if !direct {
            AppRoute::Tunnel
        } else if filtered {
            AppRoute::DirectFiltered
        } else {
            AppRoute::DirectRaw
        };
        let mut state = self.lock();
        state.asked.remove(&key);
        state.asked_until.remove(&key);
        if state.flows.len() >= MAX_FLOWS {
            state.flows.retain(|_, (_, seen)| seen.elapsed() < FLOW_TTL);
            if state.flows.len() >= MAX_FLOWS {
                state.flows.clear();
            }
        }
        // «В туннеле» тоже запоминаем: иначе про один и тот же поток пришлось бы
        // спрашивать приложение на каждом пакете.
        if route != AppRoute::Tunnel && !state.flows.contains_key(&key) {
            APP_DIRECT_FLOWS.fetch_add(1, Ordering::Relaxed);
        }
        state.flows.insert(key, (route, Instant::now()));
        drop(state);
        self.release_held(&key);
    }

    /// Пакеты, которые ждут ответа и не должны потеряться при ответе «в туннель».
    pub fn take_held(&self, src: SocketAddr, dst: SocketAddr, protocol: u8) -> Vec<Vec<u8>> {
        let key = FlowKey {
            src,
            dst,
            protocol,
        };
        let mut state = self.lock();
        state.held.remove(&key).map(|held| held.packets).unwrap_or_default()
    }

    /// Решение по уже опознанному потоку (включая «в туннеле»).
    pub fn route_for(&self, src: SocketAddr, dst: SocketAddr, protocol: u8) -> Option<AppRoute> {
        let key = FlowKey {
            src,
            dst,
            protocol,
        };
        let mut state = self.lock();
        let (route, seen) = state.flows.get_mut(&key)?;
        *seen = Instant::now();
        Some(*route)
    }

    /// Забывает решение по потоку: первый пакет соединения нельзя ни слать
    /// наугад в туннель (VPN порвёт соединение на полпути), ни отправлять мимо
    /// VPN неопознанным. Поэтому такой пакет придерживается до ответа.
    pub fn take_pending(&self, src: SocketAddr, dst: SocketAddr, protocol: u8) -> bool {
        let key = FlowKey {
            src,
            dst,
            protocol,
        };
        let state = self.lock();
        state.asked.contains(&key) && !state.flows.contains_key(&key)
    }

    /// Стоит ли спросить приложение, чей это поток. Один пакет — один вопрос:
    /// ответ придёт следующим кадром лога, а поток пока идёт в туннель.
    pub fn should_ask(&self, src: SocketAddr, dst: SocketAddr, protocol: u8) -> bool {
        let key = FlowKey {
            src,
            dst,
            protocol,
        };
        let mut state = self.lock();
        if state.flows.contains_key(&key) {
            return false;
        }
        let now = Instant::now();
        if let Some(until) = state.asked_until.get(&key) {
            if *until > now {
                return false;
            }
            state.asked_until.remove(&key);
        }
        if state.asked.len() >= MAX_OWNER_QUERIES {
            state
                .asked_until
                .retain(|_, until| *until > now);
            state.asked.clear();
            if state.asked_until.len() >= MAX_OWNER_QUERIES {
                state.asked_until.clear();
            }
        }
        state.asked.insert(key);
        state.asked_until.insert(key, now + OWNER_QUERY_TTL);
        APP_DIRECT_QUERIES.fetch_add(1, Ordering::Relaxed);
        true
    }

    /// Есть ли вообще включённые приложения в прямом режиме. Если нет, спрашивать
    /// приложение о каждом потоке бессмысленно и дорого.
    pub fn is_active(&self) -> bool {
        let state = self.lock();
        !state.flows.is_empty() || !state.asked.is_empty()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        match self.state.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn addr(port: u16, last: u8) -> SocketAddr {
        SocketAddr::from(([10, 66, 67, last], port))
    }

    /// Ключ потока в том же виде, в каком его присылает приложение.
    fn flow(src: &SocketAddr, dst: &SocketAddr) -> (String, String) {
        (src.to_string(), dst.to_string())
    }

    #[test]
    fn unknown_flow_is_not_direct() {
        let table = AppDirectTable::default();
        assert_eq!(table.route_for(addr(1234, 2), addr(443, 9), 6), None);
    }

    #[test]
    fn filtered_flow_keeps_the_dns_filter() {
        let table = AppDirectTable::default();
        let (src, dst) = flow(&addr(1234, 2), &addr(443, 9));
        table.set_flow(&src, &dst, 6, true, true);
        assert_eq!(
            table.route_for(addr(1234, 2), addr(443, 9), 6),
            Some(AppRoute::DirectFiltered)
        );
    }

    #[test]
    fn unfiltered_flow_is_marked_raw() {
        let table = AppDirectTable::default();
        let (src, dst) = flow(&addr(1234, 2), &addr(443, 9));
        table.set_flow(&src, &dst, 6, true, false);
        assert_eq!(
            table.route_for(addr(1234, 2), addr(443, 9), 6),
            Some(AppRoute::DirectRaw)
        );
    }

    #[test]
    fn clearing_a_flow_returns_it_to_the_tunnel() {
        let table = AppDirectTable::default();
        let (src, dst) = flow(&addr(1234, 2), &addr(443, 9));
        table.set_flow(&src, &dst, 6, true, true);
        table.set_flow(&src, &dst, 6, false, false);
        assert_eq!(
            table.route_for(addr(1234, 2), addr(443, 9), 6),
            Some(AppRoute::Tunnel)
        );
    }

    #[test]
    fn tunnel_answer_stops_repeated_questions() {
        // Иначе про приложение, которого нет в прямом списке, пришлось бы
        // спрашивать на каждом новом пакете.
        let table = AppDirectTable::default();
        let (src, dst) = flow(&addr(1234, 2), &addr(443, 9));
        assert!(table.should_ask(addr(1234, 2), addr(443, 9), 6));
        table.set_flow(&src, &dst, 6, false, false);
        assert!(!table.should_ask(addr(1234, 2), addr(443, 9), 6));
    }

    #[test]
    fn owner_is_asked_once_per_flow() {
        let table = AppDirectTable::default();
        assert!(table.should_ask(addr(1234, 2), addr(443, 9), 6));
        assert!(!table.should_ask(addr(1234, 2), addr(443, 9), 6));
        assert!(table.should_ask(addr(1235, 2), addr(443, 9), 6));
    }

    #[test]
    fn answering_the_question_stops_further_asks() {
        let table = AppDirectTable::default();
        assert!(table.should_ask(addr(1234, 2), addr(443, 9), 6));
        let (src, dst) = flow(&addr(1234, 2), &addr(443, 9));
        table.set_flow(&src, &dst, 6, true, true);
        assert!(!table.should_ask(addr(1234, 2), addr(443, 9), 6));
    }

    #[test]
    fn held_packets_are_not_lost_when_the_answer_arrives() {
        let table = AppDirectTable::default();
        let src = addr(1234, 2);
        let dst = addr(443, 9);
        assert!(table.should_ask(src, dst, 6));
        table.hold(src, dst, 6, b"first");
        table.hold(src, dst, 6, b"second");
        assert!(table.take_pending(src, dst, 6));
        let packets = table.take_held(src, dst, 6);
        assert_eq!(packets, vec![b"first".to_vec(), b"second".to_vec()]);
        // Второй раз отдавать нечего: пакеты уже вернулись в диспетчер.
        assert!(table.take_held(src, dst, 6).is_empty());
    }

    #[test]
    fn held_packets_expire_so_a_connection_never_stalls() {
        let table = AppDirectTable::default();
        let src = addr(1234, 2);
        let dst = addr(443, 9);
        table.should_ask(src, dst, 6);
        table.hold(src, dst, 6, b"syn");
        // Пока ответа нет, пакет ждёт; через MAX_HELD_AGE отдаётся обычным путём.
        assert!(table.drain_stale().is_empty());
    }

    #[test]
    fn broken_key_is_ignored() {
        let table = AppDirectTable::default();
        table.set_flow("не адрес", "10.66.67.9:443", 6, true, true);
        table.set_flow("10.66.67.2:1234", "тоже нет", 6, true, true);
        assert_eq!(table.route_for(addr(1234, 2), addr(443, 9), 6), None);
    }
}
