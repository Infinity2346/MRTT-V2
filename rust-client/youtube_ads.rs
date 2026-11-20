
use std::sync::atomic::{AtomicU64, Ordering};

const AD_MARKERS: &[&str] = &[
    "/api/stats/ads",
    "/pagead/",
    "/ptracking",
    "adformat=",
    "&oad=",
    "ad_type=",
    "/get_midroll_",
];

const AD_HOSTS: &[&str] = &[
    "s.youtube.com",
    "ad.youtube.com",
    "pagead2.googlesyndication.com",
];

static YOUTUBE_ADS_BLOCKED: AtomicU64 = AtomicU64::new(0);

pub fn blocked_count() -> u64 {
    YOUTUBE_ADS_BLOCKED.load(Ordering::Relaxed)
}

pub fn is_ad_sni(sni: &str) -> bool {
    let host = sni.trim().trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        return false;
    }
    is_ad_service_host(&host)
}

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

pub fn is_ad_service_host(sni: &str) -> bool {
    let host = sni.trim().trim_end_matches('.').to_ascii_lowercase();
    AD_HOSTS.iter().any(|ad| host == *ad)
}

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
