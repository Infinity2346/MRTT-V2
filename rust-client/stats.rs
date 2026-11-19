use crate::events::Events;
use std::sync::{
    Arc,
    atomic::{AtomicI32, AtomicI64, Ordering},
};
use tokio_util::sync::CancellationToken;

// STATS is read by the app (the worker count its recovery watches, the traffic
// line, the 10 s VPN start retry); one that repeats the last tells it nothing.
// It goes out on the tick after any change, so a change reaches the app within
// 100 ms, and otherwise every 3 s (2.1.9's rate: the retry may run up to 2.5 s
// later) so an idle tunnel does not wake the app 10 times a second.
// Частый тик: приложение показывает живую скорость, поэтому задержка в полсекунды
// заметна как отставание. Раз в 100 мс изменённые счётчики уходят в приложение,
// а пустой туннель молчит и шлёт только сердцебиение раз в 3 с.
const TICK: std::time::Duration = std::time::Duration::from_millis(100);
const HEARTBEAT_TICKS: u32 = 30;

type Snapshot = (i32, i64, i64);

fn should_emit(last: Option<Snapshot>, now: Snapshot, ticks_since_emit: u32) -> bool {
    last != Some(now) || ticks_since_emit >= HEARTBEAT_TICKS
}

#[derive(Default)]
pub struct Stats {
    pub total_bytes_up: AtomicI64,
    pub total_bytes_down: AtomicI64,
    pub active_connections: AtomicI32,
}

impl Stats {
    pub async fn run(self: Arc<Self>, events: Events, cancel: CancellationToken) {
        let mut interval = tokio::time::interval(TICK);
        interval.tick().await;
        let mut last_emitted: Option<Snapshot> = None;
        let mut ticks_since_emit = 0u32;
        loop {
            tokio::select! {
                _ = cancel.cancelled() => return,
                _ = interval.tick() => {
                    let active = self.active_connections.load(Ordering::Relaxed);
                    let up = self.total_bytes_up.load(Ordering::Relaxed);
                    let down = self.total_bytes_down.load(Ordering::Relaxed);
                    ticks_since_emit += 1;
                    let now = (active, up, down);
                    if should_emit(last_emitted, now, ticks_since_emit) {
                        events.stats(active, up, down);
                        last_emitted = Some(now);
                        ticks_since_emit = 0;
                        // Отдельную строку в журнал не пишем: приложение само
                        // печатает «[СЕТЬ] Активных: N | Трафик: …» по этому же
                        // событию, иначе журнал получал две одинаковые строки.
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stats_sent_on_change_and_every_3s_otherwise() {
        // The first tick always goes out.
        assert!(should_emit(None, (0, 0, 0), 1));
        // Unchanged: held until the heartbeat tick (3 s), then sent.
        for ticks in 1..HEARTBEAT_TICKS {
            assert!(!should_emit(Some((10, 5, 7)), (10, 5, 7), ticks));
        }
        assert!(should_emit(Some((10, 5, 7)), (10, 5, 7), HEARTBEAT_TICKS));
        // Any change goes out on the next tick: the worker count, either byte counter.
        assert!(should_emit(Some((10, 5, 7)), (9, 5, 7), 1));
        assert!(should_emit(Some((10, 5, 7)), (10, 6, 7), 1));
        assert!(should_emit(Some((10, 5, 7)), (10, 5, 8), 1));
        assert_eq!(TICK.as_millis() * HEARTBEAT_TICKS as u128, 3_000);
    }
}
