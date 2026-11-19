//! Фильтр рекламных вставок YouTube.
//!
//! Устроен по принципу «не трогай то, в чём не уверен». Раньше здесь было два
//! слоя, которые рвали обычную загрузку:
//!
//! 1. обрыв TLS к узлам `r<цифры>---sn-…googlevideo.com` по одному признаку в SNI.
//!    Разные кэши Google отдают с таких имён и рекламу, и обычные куски видео,
//!    превью и постеры: в итоге страница YouTube оставалась без картинок, а
//!    ролик «не догружался». Такой обрыв убран совсем.
//! 2. `redirector.googlevideo.com` в списке служебных хостов. Он маршрутизирует
//!    выдачу видео, и его блокировка останавливает воспроизведение. Убран.
//!
//! Осталось то, что относится только к рекламной подсистеме: статистика
//! показов и рекламные баннеры. Их блокировка не влияет на видео и картинки.
//!
//! Важно: список хостов намеренно короткий и точный. Всё, что может отдавать
//! обычный контент, здесь появляться не должно.

use std::sync::atomic::{AtomicU64, Ordering};

/// Заголовки, которые просит рекламный клиент YouTube. Такие запросы идут
/// plaintext только в HTTP (TLS-трафик мы не читаем), поэтому в обычной работе
/// срабатывает SNI-слой, а этот — для редких незашифрованных запросов.
const AD_MARKERS: &[&str] = &[
    "/api/stats/ads",
    "/pagead/",
    "/ptracking",
    "adformat=",
    "&oad=",
    "ad_type=",
    "/get_midroll_",
];

/// Служебные хосты, которые нужны только рекламной подсистеме YouTube.
///
/// Здесь нет `redirector.googlevideo.com` (маршрутизация видео — его блокировка
/// ломает воспроизведение) и нет имён CDN вроде `ytimg`/`ggpht`/`googlevideo`.
const AD_HOSTS: &[&str] = &[
    "s.youtube.com",
    "ad.youtube.com",
    "pagead2.googlesyndication.com",
];

static YOUTUBE_ADS_BLOCKED: AtomicU64 = AtomicU64::new(0);

/// Сколько соединений с рекламой уже оборвано (для лога и сводки).
pub fn blocked_count() -> u64 {
    YOUTUBE_ADS_BLOCKED.load(Ordering::Relaxed)
}

/// Рекламный ли сервер по имени из SNI.
///
/// Смотрим только точный список служебных хостов. Узлы CDN (`googlevideo.com`)
/// не трогаем вовсе: по имени нельзя надёжно отличить рекламный поток от
/// обычного, а ошибка стоит пользователю картинок и видео.
pub fn is_ad_sni(sni: &str) -> bool {
    let host = sni.trim().trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        return false;
    }
    is_ad_service_host(&host)
}

/// `r<цифры>---sn-<...>.googlevideo.com` — узел CDN, который может отдавать и
/// рекламу, и обычные куски видео. Оставлено для тестов и диагностики: по этому
/// признаку мы больше ничего не рвём.
#[cfg(test)]
pub fn is_ad_googlevideo_host(host: &str) -> bool {
    let Some(label_end) = host.find('.') else {
        return false;
    };
    let label = &host[..label_end];
    let rest = &host[label_end + 1..];
    if !rest.starts_with("sn-") {
        return false;
    }
    if !host.ends_with(".googlevideo.com") && host != "googlevideo.com" {
        return false;
    }
    // ровно одна `r`, затем цифры и `---`
    let bytes = label.as_bytes();
    if bytes.first() != Some(&b'r') {
        return false;
    }
    let tail = &label[1..];
    let Some((digits, suffix)) = tail.split_once('-') else {
        return false;
    };
    !digits.is_empty()
        && digits.bytes().all(|b| b.is_ascii_digit())
        && suffix.starts_with("--")
        && suffix.as_bytes().iter().all(|b| *b == b'-')
}

/// Служебный хост рекламы YouTube (его можно просто не пускать никуда).
pub fn is_ad_service_host(sni: &str) -> bool {
    let host = sni.trim().trim_end_matches('.').to_ascii_lowercase();
    AD_HOSTS.iter().any(|ad| host == *ad)
}

/// Запрос несёт рекламные маркеры (только для незашифрованного HTTP).
pub fn has_ad_markers(payload: &[u8]) -> bool {
    if payload.len() < 16 || payload.len() > 8192 {
        return false;
    }
    let head = &payload[..payload.len().min(2048)];
    let Ok(text) = std::str::from_utf8(head) else {
        return false;
    };
    if !text.starts_with("GET ") && !text.starts_with("POST ") {
        return false;
    }
    let lowered = text.to_ascii_lowercase();
    AD_MARKERS.iter().any(|marker| lowered.contains(marker))
}

/// Учитывает оборванное соединение и пишет в лог первую и каждую сотую.
pub fn note_blocked(sni: &str) -> u64 {
    let total = YOUTUBE_ADS_BLOCKED.fetch_add(1, Ordering::Relaxed) + 1;
    if total <= 5 || total.is_multiple_of(100) {
        crate::log_error!("[YOUTUBE] Рекламное соединение оборвано: {sni} · всего {total}");
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cdn_hosts_are_never_cut() {
        // Узлы CDN отдают и рекламу, и обычный контент: рвать их нельзя.
        // Именно из-за этого пропадали картинки и недогружалось видео.
        assert!(!is_ad_sni("r1---sn-4g5e6nez.googlevideo.com"));
        assert!(!is_ad_sni("r20---sn-a5mekn6s.googlevideo.com"));
        assert!(!is_ad_sni("r3---sn-o097znsd.googlevideo.com."));
        assert!(!is_ad_sni("rr3---sn-4g5e6nez.googlevideo.com"));
        assert!(!is_ad_sni("redirector.googlevideo.com"));
        assert!(!is_ad_sni("manifest.googlevideo.com"));
        assert!(!is_ad_sni("www.youtube.com"));
        assert!(!is_ad_sni("i.ytimg.com"));
        assert!(!is_ad_sni("yt3.ggpht.com"));
        assert!(!is_ad_sni("youtubei.googleapis.com"));
        assert!(!is_ad_sni(""));
    }

    #[test]
    fn ad_service_hosts_are_recognized() {
        assert!(is_ad_sni("s.youtube.com"));
        assert!(is_ad_sni("ad.youtube.com"));
        assert!(is_ad_sni("pagead2.googlesyndication.com"));
        assert!(is_ad_sni("S.YOUTUBE.COM."));
    }

    #[test]
    fn cdn_pattern_is_kept_for_diagnostics() {
        // Признак остался как справочный: по нему только пишем в журнал.
        assert!(is_ad_googlevideo_host("r1---sn-4g5e6nez.googlevideo.com"));
        assert!(!is_ad_googlevideo_host("rr3---sn-4g5e6nez.googlevideo.com"));
        assert!(!is_ad_googlevideo_host("i.ytimg.com"));
    }

    #[test]
    fn ad_marker_detection_is_strict() {
        assert!(has_ad_markers(b"GET /api/stats/ads?x=1 HTTP/1.1\r\nHost: s.youtube.com\r\n\r\n"));
        assert!(has_ad_markers(b"POST /ptracking?ad_type=video HTTP/1.1\r\n\r\n"));
        assert!(!has_ad_markers(b"GET /videoplayback?itag=18 HTTP/1.1\r\n\r\n"));
        assert!(!has_ad_markers(b"not a request at all"));
    }
}
