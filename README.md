<div align="center">

<img src="docs/icon.svg" width="120" alt="MRTT">

# MRTT

**Туннель через TURN-серверы звонков VK, MAX и Jitsi**

VPN на телефоне, который для провайдера выглядит как обычный звонок.

[![Скачать APK](https://img.shields.io/badge/⬇_Скачать_APK-последняя_сборка-2ea44f?style=for-the-badge)](../../releases/latest)
[![Все релизы](https://img.shields.io/badge/Все_релизы-список-0969da?style=for-the-badge)](../../releases)
[![Что нового](https://img.shields.io/badge/Что_нового-CHANGELOG-8250df?style=for-the-badge)](CHANGELOG.md)

![Android](https://img.shields.io/badge/Android-8.0+-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white)
![Лицензия](https://img.shields.io/badge/лицензия-PolyForm_Noncommercial-blue?style=flat-square)

</div>

---

<table>
<tr>
<td width="50%" valign="top">

### 🚀 Начать за минуту

1. Откройте **[последний релиз](../../releases/latest)**
2. Скачайте `app-universal-maxTest.apk`
3. Разрешите установку из этого источника и откройте файл

</td>
<td width="50%" valign="top">

### 📦 Что где лежит

| Нужно | Куда смотреть |
|---|---|
| **APK** (готовое приложение) | [Releases](../../releases) → файлы `*.apk` |
| **Что изменилось** | [CHANGELOG.md](CHANGELOG.md) |
| **Как выбирать APK** | [docs/RELEASES.md](docs/RELEASES.md) |
| **Сервер для VPS** | внутри APK, папка `assets` |

</td>
</tr>
</table>

> Обновление ставится **поверх** прошлой версии: подпись одна и та же, профили и настройки сохраняются.
> Сборки старше 39-й нужно один раз удалить — у тех APK была другая подпись.

---

## 🗂 Структура репозитория

```
Project/
│
├── 📱 app/                     Android-приложение (Kotlin + Compose)
│   ├── src/main/java/…         экраны, темы оформления, сервис VPN
│   ├── src/main/assets/        готовый сервер для VPS (нужен только при деплое)
│   └── src/main/rust-client/…  нативный клиент-туннель (Rust, собран через NDK)
│
├── 🖥 rust-server/             серверная часть: TURN-мост, установка на Linux
├── 🧩 rust-client/             туннель и фильтр рекламы на Rust
├── 🧠 shared/                  общий код и контракты между частями
│
├── 📄 docs/
│   ├── RELEASES.md             как выбрать APK, обновиться, где CHANGELOG
│   ├── icon.svg                иконка для этого README
│   └── …                       остальные заметки
│
├── 🛠 scripts/                 сборка, ключ подписи, проверки (CI и локально)
├── 🔐 signing/                 ключ подписи релизных APK
├── ⚙️ .github/workflows/       GitHub Actions: сборка и публикация релиза
│
├── 📜 CHANGELOG.md             история по сборкам
├── 📜 README_MAX_MRTT.md       транспорт MAX: настройка и ограничения
├── 📜 GITHUB_ACTIONS_GUIDE_RU.md  как работает автопубликация APK
└── ⚖️ LICENSE                  PolyForm Noncommercial 1.0.0
```

### Где лежат файлы, которые вы ищете

| Файл / папка | Что это |
|---|---|
| **Releases → `app-universal-maxTest.apk`** | универсальный APK, подходит любому телефону |
| `app-arm64-v8a-maxTest.apk` | современные телефоны (почти все) — файл меньше |
| `app-armeabi-v7a-maxTest.apk` | старые 32-битные устройства |
| `app-x86_64-maxTest.apk` | эмуляторы и x86-планшеты |
| **APK как zip-архив** | внутри `assets/` лежит готовый сервер для Linux (amd64, arm64, armv7) — отдельно скачивать не нужно |
| `app/src/main/java/com/mrtt/client/ui/design/MrttStyles.kt` | все стили оформления приложения (аниме, киберпанк, матрица и др.) |
| `.github/workflows/build-test-apk.yml` | сборка APK и публикация релиза |

## Возможности

**Три транспорта.** Трафик идёт через TURN-серверы звонков VK, MAX или Jitsi — на выбор.

**Деплой сервера из приложения.** Сервер ставится на VPS по SSH прямо из приложения, после установки оно выдаёт ссылку на подключение.

**Свой интернет с вырезанной рекламой.** Приложению можно назначить третий режим: трафик идёт напрямую с мобильного или Wi-Fi, а DNS проходит через фильтр рекламы. RuTube и подобные сервисы работают быстро и без рекламы.

**VK-аккаунт на сервере.** Привяжите VK к серверу один раз — ссылки понесут этот аккаунт, и на телефоне ничего вводить не нужно.

**Автоподключение.** VPN поднимается сам при входе в приложение, если включить это в настройках.

**Стили оформления.** В настройках — «Оформление» (светлая/тёмная/системная) и лента стилей: монохром, эспрессо, индиго, лес, океан, **аниме**, **киберпанк**, **матрица**, **закат**. Стиль меняет цвета, фон, подсветку стекла и форму карточек сразу во всём приложении.

**Игровой режим.** Отдельный список приложений показывает только то, что вы установили сами — с иконками, без системных служб.

## Документы

| Документ | О чём |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | что менялось в каждой сборке |
| [README_MAX_MRTT.md](README_MAX_MRTT.md) | транспорт MAX: настройка и ограничения |
| [CHANGES_MAX_MRTT.md](CHANGES_MAX_MRTT.md) | технические детали интеграции MAX |
| [GITHUB_ACTIONS_GUIDE_RU.md](GITHUB_ACTIONS_GUIDE_RU.md) | как работает автоматическая сборка |
| [TODO_NEXT_UPDATE.md](TODO_NEXT_UPDATE.md) | что сделано и что в планах |
| [Releases](../../releases) | все сборки, новые и старые |

## Сборка из исходников

Проект собирается в GitHub Actions: при пуше приходит APK в релиз. Локально нужны JDK 17, Android SDK 37, NDK 28.2 и Rust 1.97.1.

```bash
./gradlew :app:assembleMaxTest
```

Подпись берётся из `signing/mrtt-beta.p12` — ключ один и тот же для всех сборок. Если ключ не найден, сборка падает с ошибкой, а не собирает неподписанный APK.

## Лицензия

[PolyForm Noncommercial 1.0.0](LICENSE): код можно использовать, изменять и распространять только в некоммерческих целях. Продажа, платные сервисы и другое коммерческое использование без письменного разрешения автора CSQTT запрещены.

## Благодарности

- [CSQTT](https://github.com/amurcanov/csqtt) — туннель через TURN-серверы звонков VK
- [olcRTC](https://github.com/openlibrecommunity/olcrtc) — туннель через сервисы видеовстреч
- [webrtc-rs](https://github.com/webrtc-rs/webrtc) — WebRTC на Rust
- [netstack-smoltcp](https://github.com/cavivie/netstack-smoltcp) — сетевой стек на smoltcp

## Поддержка

Вопросы и баги — в [Issues](../../issues). Поставьте ⭐ репозиторию, если пригодилось.
