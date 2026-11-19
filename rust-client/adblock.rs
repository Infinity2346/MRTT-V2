//! Фильтр рекламы, трекеров и телеметрии на уровне DNS.
//!
//! Списки читаются из нескольких файлов: базовый снимок из ассетов приложения и файлы
//! пользователя (свои домены и скачанные подписки). Каждый запрос DNS к перехваченному
//! домену получает локальный «пустой» ответ, поэтому трафик рекламных сетей не уходит ни
//! в туннель, ни напрямую. Фильтр работает как надстройка над domain routing: он не
//! включает маршрутизацию доменов, а только отвечает на запросы.
//!
//! Почему не NXDOMAIN: на «имени не существует» часть приложений (в том числе Telegram и
//! YouTube при загрузке превью и медиа) уходит в свою ветку «сеть недоступна» и
//! останавливает загрузку до следующей попытки. Пустой ответ NODATA с записью SOA —
//! обычный ответ «имя есть, но адреса такого типа нет»: клиент спокойно продолжает
//! загрузку остального. Так же поступают AdGuard и Pi-hole.
//!
//! Кроме доменов поддерживаются «категории» — наборы правил в духе geosite
//! (`geosite-category-ru@ads`): категория включается пользователем и грузится отдельным
//! файлом, всё содержимое добавляется к общему списку блокировки.

use std::collections::HashSet;
use std::sync::{OnceLock, RwLock};

/// Верхняя граница записей: файл читается с устройства, лишнее отбрасывается.
pub const MAX_ADBLOCK_DOMAINS: usize = 300_000;

/// Сколько файлов правил принимаем за один запуск (база + свои + подписки + категории).
pub const MAX_RULE_FILES: usize = 32;

/// Категории правил, которые понимает фильтр. Имена повторяют geosite, чтобы списки
/// MetaCubeX/v2fly подключались без переименования.
pub const CATEGORY_ADS_ALL: &str = "geosite-category-ads-all";
pub const CATEGORY_ADS_TELEMETRY: &str = "geosite-category-ads-and-telemetry";
pub const CATEGORY_TELEMETRY: &str = "geosite-category-telemetry";
pub const CATEGORY_PUBLIC_TRACKER: &str = "geosite-category-public-tracker";
pub const CATEGORY_PORN: &str = "geosite-category-porn";
pub const CATEGORY_DOH: &str = "geosite-category-doh";
pub const CATEGORY_RU_ADS: &str = "geosite-category-ru@ads";
pub const CATEGORY_RU_TELEMETRY: &str = "geosite-category-ru@telemetry";
pub const CATEGORY_RU_CN: &str = "geosite-category-ru@cn";

/// Порядок тот же, что в интерфейсе: сначала мировые наборы, потом российские.
pub const KNOWN_CATEGORIES: &[&str] = &[
    CATEGORY_ADS_ALL,
    CATEGORY_ADS_TELEMETRY,
    CATEGORY_TELEMETRY,
    CATEGORY_PUBLIC_TRACKER,
    CATEGORY_PORN,
    CATEGORY_DOH,
    CATEGORY_RU_ADS,
    CATEGORY_RU_TELEMETRY,
    CATEGORY_RU_CN,
];

static ADBLOCK_LIST: OnceLock<HashSet<String>> = OnceLock::new();
/// Исключения из списка (`@@домен` в своих правилах): такие домены и их поддомены
/// открываются всегда, даже если сети рекламы попали в набор.
static ADBLOCK_ALLOW: OnceLock<HashSet<String>> = OnceLock::new();
/// Какие категории реально попали в список: интерфейс показывает это как «включено».
static ACTIVE_CATEGORIES: OnceLock<RwLock<Vec<String>>> = OnceLock::new();

fn active_categories() -> &'static RwLock<Vec<String>> {
    ACTIVE_CATEGORIES.get_or_init(|| RwLock::new(Vec::new()))
}

/// Набор хостов, без которых не грузится обычный контент (см. [`CONTENT_HOSTS`]).
fn content_critical_set() -> &'static HashSet<String> {
    static SET: OnceLock<HashSet<String>> = OnceLock::new();
    SET.get_or_init(|| CONTENT_HOSTS.iter().map(|host| (*host).to_string()).collect())
}

/// Приводит имя категории к каноническому виду: `ads`, `ru@ads`, `geosite-category-ru@ads`
/// и `category-ru@ads` — одно и то же.
pub fn normalize_category(raw: &str) -> Option<String> {
    let mut name = raw.trim().to_ascii_lowercase();
    if name.is_empty() {
        return None;
    }
    // Точный id набора — самый частый случай (приходит из приложения).
    if let Some(known) = KNOWN_CATEGORIES.iter().find(|known| **known == name) {
        return Some((*known).to_string());
    }
    for prefix in ["geosite-", "geo-"] {
        if let Some(stripped) = name.strip_prefix(prefix) {
            name = stripped.to_string();
            break;
        }
    }
    // Запись бывает двух видов: `категория@набор` (`ru@ads`, `category-ru@ads`)
    // и просто набор (`ads`, `category-ads-all`).
    let candidate = match name.split_once('@') {
        Some((head, tag)) if !head.is_empty() && !tag.is_empty() => {
            let head = if head == "ru" || head == "category-ru" {
                "category-ru"
            } else {
                head
            };
            format!("geosite-{head}@{tag}")
        }
        Some(_) => return None,
        None => format!("geosite-{name}"),
    };
    KNOWN_CATEGORIES
        .iter()
        .find(|known| **known == candidate)
        .map(|known| (*known).to_string())
}

/// Пути к файлам категорий по имени набора из [`KNOWN_CATEGORIES`].
#[allow(dead_code)]
pub fn category_file(category: &str) -> Option<&'static str> {
    match category {
        CATEGORY_ADS_ALL => Some("adblock-geosite-ads-all.txt"),
        CATEGORY_ADS_TELEMETRY => Some("adblock-geosite-ads-and-telemetry.txt"),
        CATEGORY_TELEMETRY => Some("adblock-geosite-telemetry.txt"),
        CATEGORY_PUBLIC_TRACKER => Some("adblock-geosite-public-tracker.txt"),
        CATEGORY_PORN => Some("adblock-geosite-porn.txt"),
        CATEGORY_DOH => Some("adblock-geosite-doh.txt"),
        CATEGORY_RU_ADS => Some("adblock-geosite-ru-ads.txt"),
        CATEGORY_RU_TELEMETRY => Some("adblock-geosite-ru-telemetry.txt"),
        CATEGORY_RU_CN => Some("adblock-geosite-ru-cn.txt"),
        _ => None,
    }
}

/// Категории, которые сейчас в работе (для журнала и интерфейса).
#[allow(dead_code)]
pub fn categories_snapshot() -> Vec<String> {
    active_categories()
        .read()
        .map(|list| list.clone())
        .unwrap_or_default()
}

/// Загружает список один раз за процесс. Повторный вызов возвращает уже загруженное число.
pub fn init_from_file(path: &str) -> usize {
    init_from_files(&[path.to_owned()])
}

/// Загружает список из нескольких файлов: свои домены, подписки, категории. Файлы
/// складываются в один набор, повторы отбрасываются. Работает один раз за процесс —
/// дальше правила меняются только перезапуском туннеля.
pub fn init_from_files(paths: &[String]) -> usize {
    if ADBLOCK_LIST.get().is_some() {
        return ADBLOCK_LIST.get().map_or(0, HashSet::len);
    }
    let mut loaded = HashSet::new();
    for path in paths.iter().take(MAX_RULE_FILES) {
        if path.trim().is_empty() {
            continue;
        }
        match std::fs::read_to_string(path) {
            Ok(content) => {
                let before = loaded.len();
                merge_list(&mut loaded, &content);
                crate::log_error!(
                    "[ADBLOCK] {path}: +{} доменов (было {before}, стало {})",
                    loaded.len().saturating_sub(before),
                    loaded.len()
                );
            }
            Err(error) => crate::log_error!("[ADBLOCK] Не прочитан список {path}: {error}"),
        }
    }
    // Исключения собираем из тех же файлов: строка `@@домен` означает «не трогать».
    let mut allowed = HashSet::new();
    for path in paths.iter().take(MAX_RULE_FILES) {
        if let Ok(content) = std::fs::read_to_string(path) {
            allowed.extend(parse_allow_list(&content));
        }
    }
    install_allow_list(allowed);
    let count = loaded.len();
    // OnceLock::set отдаёт Err при гонке: тогда побеждает первый загрузивший.
    if ADBLOCK_LIST.set(loaded).is_err() {
        return ADBLOCK_LIST.get().map_or(0, HashSet::len);
    }
    crate::log_error!("[ADBLOCK] Список загружен: {count} доменов из {} файлов", paths.len());
    count
}

/// Исключения из своих правил: строки `@@домен` (допускается `@@||домен^`).
pub fn parse_allow_list(content: &str) -> HashSet<String> {
    let mut domains = HashSet::new();
    for line in content.lines() {
        let line = line.trim();
        let Some(rest) = line.strip_prefix("@@") else {
            continue;
        };
        let candidate = rest
            .trim()
            .trim_start_matches("||")
            .trim_matches('^')
            .split_whitespace()
            .next()
            .unwrap_or("");
        if let Some(domain) = normalize_domain(candidate) {
            domains.insert(domain);
        }
        if domains.len() >= MAX_ADBLOCK_DOMAINS {
            break;
        }
    }
    domains
}

/// Запоминает исключения. Вызывается вместе с загрузкой списка.
pub fn install_allow_list(domains: HashSet<String>) {
    let _ = ADBLOCK_ALLOW.set(domains);
}

/// Совпадает ли домен или один из его родителей с набором (без учёта регистра).
fn matches_suffix(set: &HashSet<String>, domain: &str) -> bool {
    let normalized = domain.trim_end_matches('.').to_ascii_lowercase();
    if normalized.is_empty() {
        return false;
    }
    let mut candidate = normalized.as_str();
    loop {
        if set.contains(candidate) {
            return true;
        }
        match candidate.find('.') {
            Some(pos) if pos + 1 < candidate.len() => candidate = &candidate[pos + 1..],
            _ => return false,
        }
    }
}

/// Дописывает в набор содержимое списка. Возвращает число новых записей.
pub fn merge_list(target: &mut HashSet<String>, content: &str) -> usize {
    let parsed = parse_list(content);
    let before = target.len();
    for domain in parsed {
        if target.len() >= MAX_ADBLOCK_DOMAINS {
            break;
        }
        target.insert(domain);
    }
    target.len().saturating_sub(before)
}

/// Запоминает, какие категории реально подключены: их же показываем пользователю.
pub fn mark_categories<S: AsRef<str>>(categories: impl IntoIterator<Item = S>) {
    let normalized: Vec<String> = categories
        .into_iter()
        .filter_map(|category| normalize_category(category.as_ref()))
        .collect();
    if let Ok(mut guard) = active_categories().write() {
        *guard = normalized;
    }
}

/// Включён ли набор «Шифрованный DNS». Только тогда имеет смысл резать DoT и DoH:
/// пока пользователь не выбрал этот набор, мы не трогаем чужие резолверы.
pub fn encrypted_dns_blocking() -> bool {
    active_categories()
        .read()
        .map(|list| list.iter().any(|category| category == CATEGORY_DOH))
        .unwrap_or(false)
}

/// Разбирает файл: по домену в строке, `#` и `!` — комментарии, hosts-строки берутся по второму полю.
pub fn parse_list(content: &str) -> HashSet<String> {
    let mut domains = HashSet::new();
    for line in content.lines() {
        if domains.len() >= MAX_ADBLOCK_DOMAINS {
            break;
        }
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        let candidate = if line.len() > 1 && (line.as_bytes()[0].is_ascii_digit() || line.starts_with("::")) {
            // Строка в формате hosts: адрес и домен через пробел.
            line.split_whitespace().nth(1).unwrap_or("")
        } else {
            line.split_whitespace().next().unwrap_or("")
        };
        if let Some(domain) = normalize_domain(candidate) {
            domains.insert(domain);
        }
    }
    domains
}

fn normalize_domain(raw: &str) -> Option<String> {
    let mut domain = raw.trim().trim_matches('.').to_ascii_lowercase();
    if let Some(stripped) = domain.strip_prefix("www.") {
        // В списке и запрос без www приводятся к одному виду.
        if stripped.contains('.') {
            domain = stripped.to_string();
        }
    }
    if domain.len() < 3 || domain.len() > 253 || !domain.contains('.') {
        return None;
    }
    if !domain
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_')
    {
        return None;
    }
    if domain.starts_with('.') || domain.contains("..") {
        return None;
    }
    Some(domain)
}

/// Хосты, которые нужны обычной загрузке контента, даже если рекламный список
/// их назвал. Их блокировка выглядит как «картинки и видео не грузятся».
///
/// `redirector.googlevideo.com` и `manifest.googlevideo.com` маршрутизируют
/// выдачу видео: без них ролик не начинается. `youtubei.googleapis.com` — сам API
/// YouTube, а не только его рекламный канал.
const CONTENT_HOSTS: &[&str] = &[
    "redirector.googlevideo.com",
    "manifest.googlevideo.com",
    "youtubei.googleapis.com",
];

/// Домен или любой его родитель есть в списке: `ads.cdn.example.com` блокируется записью `example.com`.
/// Исключения (`@@домен`) проверяются первыми и тоже действуют на поддомены.
pub fn is_blocked(domain: &str) -> bool {
    let Some(list) = ADBLOCK_LIST.get() else {
        return false;
    };
    if list.is_empty() {
        return false;
    }
    is_blocked_in(list, ADBLOCK_ALLOW.get(), domain)
}

/// Правило блокировки без глобального состояния: удобно проверять тестами и
/// держать порядок проверок в одном месте.
fn is_blocked_in(list: &HashSet<String>, allow: Option<&HashSet<String>>, domain: &str) -> bool {
    // Хосты, без которых не грузится обычный контент, не блокируются никогда.
    if matches_suffix(content_critical_set(), domain) {
        return false;
    }
    if let Some(allow) = allow
        && !allow.is_empty()
        && matches_suffix(allow, domain)
    {
        return false;
    }
    matches_suffix(list, domain)
}

pub fn is_active() -> bool {
    ADBLOCK_LIST.get().is_some_and(|list| !list.is_empty())
}

/// Домены шифрованного DNS: через них приложение может получить настоящий адрес
/// рекламного домена в обход фильтра. DoH идёт по TCP/443 и по порту неотличим
/// от обычного HTTPS, поэтому режем такие имена по SNI — клиент откатывается
/// на системный DNS, а его запросы фильтр видит.
pub fn is_encrypted_dns_host(domain: &str) -> bool {
    const ENCRYPTED_DNS: &[&str] = &[
        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "mozilla.cloudflare-dns.com",
        "one.one.one.one",
        "dns.quad9.net",
        "doh.opendns.com",
        "dns.adguard.com",
        "dns.adguard-dns.com",
        "dns.nextdns.io",
        "doh.cleanbrowsing.org",
        "common.dot.dns.yandex.net",
        "dns.yandex.com",
        "dns.mail.ru",
    ];
    let domain = domain.trim_end_matches('.').to_ascii_lowercase();
    ENCRYPTED_DNS.contains(&domain.as_str())
}

/// DoT: шифрованный DNS поверх собственного порта.
pub const ENCRYPTED_DNS_PORT: u16 = 853;

/// Вопрос из DNS-пакета: имя, его тип и класс плюс смещение конца вопроса.
pub struct DnsQuestion<'a> {
    pub name: String,
    pub qtype: u16,
    pub qclass: u16,
    /// Байты вопроса (QNAME+QTYPE+QCLASS) — ответ повторяет их дословно.
    raw: &'a [u8],
}

pub fn parse_question(payload: &[u8]) -> Option<DnsQuestion<'_>> {
    if payload.len() < 12 {
        return None;
    }
    let qdcount = u16::from_be_bytes([payload[4], payload[5]]);
    if qdcount == 0 {
        return None;
    }
    let mut name = String::new();
    let mut cursor = 12usize;
    loop {
        if cursor >= payload.len() {
            return None;
        }
        let len = payload[cursor] as usize;
        if len == 0 {
            cursor += 1;
            break;
        }
        // Сжатый вопрос не поддерживаем: ответ нельзя собрать дословно.
        if len & 0xc0 != 0 {
            return None;
        }
        cursor += 1;
        if cursor + len > payload.len() {
            return None;
        }
        if !name.is_empty() {
            name.push('.');
        }
        let label = std::str::from_utf8(&payload[cursor..cursor + len]).ok()?;
        name.push_str(&label.to_ascii_lowercase());
        cursor += len;
        if name.len() > 253 {
            return None;
        }
    }
    if cursor + 4 > payload.len() {
        return None;
    }
    let qtype = u16::from_be_bytes([payload[cursor], payload[cursor + 1]]);
    let qclass = u16::from_be_bytes([payload[cursor + 2], payload[cursor + 3]]);
    Some(DnsQuestion {
        name,
        qtype,
        qclass,
        raw: &payload[12..cursor + 4],
    })
}

/// Минимальная запись SOA в конце пустого ответа.
///
/// Зачем она нужна: пустой ответ без единой записи часть резолверов и приложений
/// считает «обрезанным» и повторяет вопрос. SOA с отрицательным TTL — штатный
/// способ сказать «адреса нет, и вот сколько это действует», после него клиент
/// не переспрашивает.
const SINK_TTL_SECONDS: u32 = 10;
/// Домен, который называется в SOA (владелец зоны). Значение не используется
/// клиентом по смыслу, важно лишь, чтобы запись была корректной.
const SINK_SOA_NAME: &[u8] = b"\x03ads\x00";

/// Длина пустого ответа без записей и без SOA.
fn empty_answer_len(question: &DnsQuestion<'_>) -> usize {
    12 + question.raw.len()
}

/// Пустой ответ NODATA на вопрос: имя «существует», но записей такого типа нет.
///
/// `with_soa` добавляет запись SOA в авторитетную секцию. Её стоит добавлять только
/// для UDP: в TCP-ответе её наличие роли не играет, а длины жалко.
pub fn build_empty_answer(
    payload: &[u8],
    question: &DnsQuestion<'_>,
    response: &mut [u8],
    with_soa: bool,
) -> Option<usize> {
    if payload.len() < 12 {
        return None;
    }
    let header = &payload[..12];
    let question_len = empty_answer_len(question);
    // Запись SOA в ответе: имя-ссылка (2) + тип (2) + класс (2) + TTL (4) + длина (2) + данные.
    let soa_record_len = 12 + SINK_SOA_NAME.len() * 2 + 20;
    let total = if with_soa { question_len + soa_record_len } else { question_len };
    if response.len() < total {
        return None;
    }
    // Флаги: ответ, recursion desired как в запросе, recursion available, код 0 (успех).
    // Ноль записей в секции ответа — это и есть NODATA.
    let request_flags = u16::from_be_bytes([header[2], header[3]]);
    let flags = 0x8000u16 | (request_flags & 0x0100) | 0x0080;
    response[0..2].copy_from_slice(&header[0..2]);
    response[2..4].copy_from_slice(&flags.to_be_bytes());
    response[4..6].copy_from_slice(&1u16.to_be_bytes());
    response[6..8].copy_from_slice(&0u16.to_be_bytes());
    response[8..10].copy_from_slice(&0u16.to_be_bytes());
    response[10..12].copy_from_slice(&if with_soa { 1u16 } else { 0u16 }.to_be_bytes());
    response[12..question_len].copy_from_slice(question.raw);
    if !with_soa {
        return Some(total);
    }
    let mut cursor = question_len;
    // Владелец записи: ссылка на имя вопроса в начале пакета (`0xC00C`).
    response[cursor] = 0xC0;
    response[cursor + 1] = 0x0C;
    cursor += 2;
    response[cursor..cursor + 2].copy_from_slice(&6u16.to_be_bytes()); // SOA
    cursor += 2;
    response[cursor..cursor + 2].copy_from_slice(&1u16.to_be_bytes()); // IN
    cursor += 2;
    response[cursor..cursor + 4].copy_from_slice(&SINK_TTL_SECONDS.to_be_bytes());
    cursor += 4;
    // Длина полезной части: MNAME + RNAME + пять полей по 4 байта.
    let rdata_len = SINK_SOA_NAME.len() * 2 + 20;
    response[cursor..cursor + 2].copy_from_slice(&(rdata_len as u16).to_be_bytes());
    cursor += 2;
    response[cursor..cursor + SINK_SOA_NAME.len()].copy_from_slice(SINK_SOA_NAME);
    cursor += SINK_SOA_NAME.len();
    response[cursor..cursor + SINK_SOA_NAME.len()].copy_from_slice(SINK_SOA_NAME);
    cursor += SINK_SOA_NAME.len();
    // serial, refresh, retry, expire и minimum. Значения произвольные, но
    // правдоподобные: главное — сам факт отрицательного ответа с TTL.
    for value in [SINK_TTL_SECONDS, SINK_TTL_SECONDS, SINK_TTL_SECONDS, 0, 0] {
        response[cursor..cursor + 4].copy_from_slice(&value.to_be_bytes());
        cursor += 4;
    }
    Some(cursor)
}

/// Удобная точка входа: блокировать ли DNS-запрос и каким ответом.
/// Возвращает длину ответа в `response` при блокировке.
#[allow(dead_code)]
pub fn blocked_answer_for_query(packet: &[u8], response: &mut [u8]) -> Option<(usize, String)> {
    if !is_active() {
        return None;
    }
    let (payload, _) = dns_udp_payload(packet)?;
    let question = parse_question(payload)?;
    if !is_blocked(&question.name) {
        return None;
    }
    let len = build_empty_answer(payload, &question, response, true)?;
    Some((len, question.name))
}

/// Смещение UDP-полезной нагрузки DNS в IP-пакете, если это DNS-запрос на порт 53.
pub fn dns_udp_payload(packet: &[u8]) -> Option<(&[u8], usize)> {
    if packet.is_empty() {
        return None;
    }
    let (ihl, protocol, total_len) = match packet[0] >> 4 {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if ihl < 20 || ihl > packet.len() {
                return None;
            }
            let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            (ihl, packet[9], total_len.min(packet.len()))
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            (40, packet[6], (40 + payload_len).min(packet.len()))
        }
        _ => return None,
    };
    if protocol != 17 || total_len <= ihl + 8 {
        return None;
    }
    let udp = &packet[ihl..total_len];
    let dst_port = u16::from_be_bytes([udp[2], udp[3]]);
    if dst_port != 53 {
        return None;
    }
    let udp_len = u16::from_be_bytes([udp[4], udp[5]]) as usize;
    if udp_len < 8 || udp.len() < udp_len {
        return None;
    }
    Some((&udp[8..udp_len], ihl + 8))
}

/// Число доменов в активном списке (0, если список не загружен).
pub fn blocked_domains_len() -> usize {
    ADBLOCK_LIST.get().map_or(0, HashSet::len)
}

/// Домены, которые пользователь добавил вручную (для проверки и подсчёта в интерфейсе).
#[allow(dead_code)]
pub fn blocked_domains_contains(domain: &str) -> bool {
    is_blocked(domain)
}

/// Число исключений в активном наборе.
#[allow(dead_code)]
pub fn allow_domains_len() -> usize {
    ADBLOCK_ALLOW.get().map_or(0, HashSet::len)
}

#[cfg(test)]
pub fn install_for_test(domains: &[&str]) {
    let list: HashSet<String> = domains.iter().map(|d| d.to_string()).collect();
    let _ = ADBLOCK_LIST.set(list);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_hosts_and_plain_lines() {
        let list = parse_list(
            "# comment\n0.0.0.0 ads.example.com\n! other\ntracker.example.org\nexample.net\n",
        );
        assert!(list.contains("ads.example.com"));
        assert!(list.contains("tracker.example.org"));
        assert!(list.contains("example.net"));
        assert_eq!(list.len(), 3);
    }

    #[test]
    fn category_names_are_normalized_like_geosite() {
        // Разные варианты записи одного набора из MetaCubeX/v2fly.
        for raw in [
            "geosite-category-ru@ads",
            "category-ru@ads",
            "ru@ads",
            "ads",
            "GEOSITE-CATEGORY-RU@ADS",
            "  ads  ",
        ] {
            assert_eq!(
                normalize_category(raw).as_deref(),
                Some("geosite-category-ru@ads"),
                "вариант {raw}"
            );
        }
        assert_eq!(
            normalize_category("telemetry").as_deref(),
            Some("geosite-category-ru@telemetry")
        );
        assert_eq!(normalize_category("cn").as_deref(), Some("geosite-category-ru@cn"));
        // Мировые наборы: и точный id, и короткая запись без префикса.
        for raw in [
            "geosite-category-ads-all",
            "category-ads-all",
            "GEOSITE-CATEGORY-ADS-ALL",
        ] {
            assert_eq!(
                normalize_category(raw).as_deref(),
                Some("geosite-category-ads-all"),
                "вариант {raw}"
            );
        }
        assert_eq!(
            normalize_category("geosite-category-porn").as_deref(),
            Some("geosite-category-porn")
        );
        // Неизвестный набор не подсовываем: лучше честно промолчать.
        assert_eq!(normalize_category("geosite-category-xx@ads"), None);
        assert_eq!(normalize_category("geosite-category-xx"), None);
        assert_eq!(normalize_category(""), None);
    }

    #[test]
    fn category_files_exist_for_every_known_category() {
        for category in KNOWN_CATEGORIES {
            assert!(
                category_file(category).is_some(),
                "у категории {category} должен быть файл"
            );
        }
        assert!(category_file("geosite-category-xx@ads").is_none());
    }

    #[test]
    fn merges_lists_without_duplicates_and_keeps_order_of_first_appearance() {
        let mut list = HashSet::new();
        assert_eq!(merge_list(&mut list, "a.example.com\nb.example.com\n"), 2);
        // Тот же домен во втором файле не должен считаться новым.
        assert_eq!(merge_list(&mut list, "b.example.com\nc.example.com\n"), 1);
        assert_eq!(list.len(), 3);
        assert!(list.contains("a.example.com"));
        assert!(list.contains("c.example.com"));
    }

    #[test]
    fn allow_rules_exempt_domains_and_their_subdomains() {
        // Свои правила: исключение отменяет блокировку, в том числе для поддоменов.
        let allow = parse_allow_list("# комментарий\n@@good.example.com\n@@||ads.example.org^\nbad.example.com\n");
        assert_eq!(allow.len(), 2);
        assert!(allow.contains("good.example.com"));
        assert!(allow.contains("ads.example.org"));
        // Без `@@` строка не считается исключением.
        assert!(!allow.contains("bad.example.com"));
        let list: HashSet<String> = ["doubleclick.net", "good.example.com", "ads.example.org"]
            .iter()
            .map(|d| d.to_string())
            .collect();
        assert!(matches_suffix(&list, "GOOD.EXAMPLE.COM."));
        assert!(!matches_suffix(&list, "notgood.example.co"));
    }

    #[test]
    fn content_hosts_are_never_blocked_by_a_list() {
        // Даже если рекламный список назвал такой хост, обычный контент важнее:
        // именно из-за этого пропадали картинки и не начиналось видео.
        let list: HashSet<String> = [
            "redirector.googlevideo.com",
            "manifest.googlevideo.com",
            "youtubei.googleapis.com",
            "doubleclick.net",
        ]
        .iter()
        .map(|d| d.to_string())
        .collect();
        assert!(!is_blocked_in(&list, None, "redirector.googlevideo.com"));
        assert!(!is_blocked_in(&list, None, "manifest.googlevideo.com"));
        assert!(!is_blocked_in(&list, None, "youtubei.googleapis.com"));
        assert!(is_blocked_in(&list, None, "doubleclick.net"));
        assert!(is_blocked_in(&list, None, "ad.doubleclick.net"));
        // Исключение `@@` по-прежнему снимает блокировку обычного домена.
        let allow: HashSet<String> = ["good.doubleclick.net".to_string()].into_iter().collect();
        assert!(!is_blocked_in(&list, Some(&allow), "good.doubleclick.net"));
    }

    #[test]
    fn encrypted_dns_port_cut_needs_the_explicit_category() {
        // По умолчанию DoT не режем: иначе системный «Приватный DNS» ломает
        // разрешение имён у всех приложений сразу.
        mark_categories(["geosite-category-ads-all"]);
        assert!(!encrypted_dns_blocking());
        mark_categories(["geosite-category-doh"]);
        assert!(encrypted_dns_blocking());
        mark_categories(Vec::<&str>::new());
        assert!(!encrypted_dns_blocking());
    }

    #[test]
    fn encrypted_dns_hosts_are_recognized() {
        assert!(is_encrypted_dns_host("dns.google"));
        assert!(is_encrypted_dns_host("DNS.GOOGLE."));
        assert!(is_encrypted_dns_host("cloudflare-dns.com"));
        assert!(!is_encrypted_dns_host("google.com"));
        assert!(!is_encrypted_dns_host("example.com"));
    }

    #[test]
    fn blocks_subdomains_of_listed_domain() {
        install_for_test(&["doubleclick.net"]);
        assert!(is_blocked("doubleclick.net"));
        assert!(is_blocked("ad.doubleclick.net"));
        assert!(is_blocked("ADS.GOOGLE.DOUBLECLICK.NET."));
        assert!(!is_blocked("notdoubleclick.net"));
        assert!(!is_blocked("example.com"));
    }

    fn dns_query(name: &str, qtype: u16, id: u16) -> Vec<u8> {
        let mut query = Vec::new();
        query.extend_from_slice(&id.to_be_bytes());
        query.extend_from_slice(&0x0100u16.to_be_bytes());
        query.extend_from_slice(&1u16.to_be_bytes());
        query.extend_from_slice(&0u16.to_be_bytes());
        query.extend_from_slice(&0u16.to_be_bytes());
        query.extend_from_slice(&0u16.to_be_bytes());
        for label in name.split('.') {
            query.push(label.len() as u8);
            query.extend_from_slice(label.as_bytes());
        }
        query.push(0);
        query.extend_from_slice(&qtype.to_be_bytes());
        query.extend_from_slice(&1u16.to_be_bytes());
        query
    }

    #[test]
    #[allow(clippy::panic, clippy::unreachable)]
    fn builds_empty_answer_with_echoed_question() {
        let query = dns_query("example.com", 1, 0x1234);
        let Some(question) = parse_question(&query) else {
            unreachable!("тестовый DNS-вопрос должен разбираться");
        };
        assert_eq!(question.name, "example.com");
        assert_eq!(question.qtype, 1);

        let mut response = vec![0u8; 512];
        let Some(len) = build_empty_answer(&query, &question, &mut response, true) else {
            unreachable!("пустой ответ должен собираться");
        };
        assert_eq!(&response[0..2], &0x1234u16.to_be_bytes());
        let flags = u16::from_be_bytes([response[2], response[3]]);
        assert_eq!(flags & 0x8000, 0x8000, "это ответ");
        // Код ответа 0 (успех). NXDOMAIN (3) ломал загрузку в приложениях.
        assert_eq!(flags & 0x000f, 0);
        assert_eq!(u16::from_be_bytes([response[4], response[5]]), 1, "один вопрос");
        assert_eq!(u16::from_be_bytes([response[6], response[7]]), 0, "записей-адресов нет");
        assert_eq!(u16::from_be_bytes([response[10], response[11]]), 1, "SOA в авторитетной секции");
        let question_end = 12 + question.raw.len();
        assert_eq!(&response[12..question_end], &query[12..]);

        // Разбор собранной записи SOA: имя-ссылка, тип 6, класс IN, TTL, длина.
        assert_eq!(&response[question_end..question_end + 2], &[0xC0, 0x0C]);
        assert_eq!(
            u16::from_be_bytes([response[question_end + 2], response[question_end + 3]]),
            6
        );
        assert_eq!(
            u16::from_be_bytes([response[question_end + 4], response[question_end + 5]]),
            1
        );
        let rdata_len = u16::from_be_bytes([response[question_end + 10], response[question_end + 11]])
            as usize;
        assert_eq!(len, question_end + 12 + rdata_len, "ответ кончается ровно на SOA");
    }

    #[test]
    fn empty_answer_without_soa_is_shorter_and_still_valid() {
        let query = dns_query("ads.example.com", 28, 7);
        let Some(question) = parse_question(&query) else {
            unreachable!()
        };
        let mut with_soa = vec![0u8; 512];
        let mut without = vec![0u8; 512];
        let full = build_empty_answer(&query, &question, &mut with_soa, true).unwrap();
        let short = build_empty_answer(&query, &question, &mut without, false).unwrap();
        assert!(short < full);
        assert_eq!(u16::from_be_bytes([without[6], without[7]]), 0);
        assert_eq!(u16::from_be_bytes([without[10], without[11]]), 0);
    }

    #[test]
    fn tiny_buffer_is_refused_instead_of_cut() {
        let query = dns_query("example.com", 1, 1);
        let Some(question) = parse_question(&query) else {
            unreachable!()
        };
        // Буфер короче ответа: лучше не ответить, чем отправить обрезанный пакет.
        let mut response = vec![0u8; 12 + question.raw.len() + 4];
        assert!(build_empty_answer(&query, &question, &mut response, true).is_none());
    }
}
