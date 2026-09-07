# NotifWebhook — Android 14+ (API 34)

[![GitHub](https://img.shields.io/badge/GitHub-kas--cor/NotificationWebhook-181717?logo=github)](https://github.com/kas-cor/NotificationWebhook)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/github/license/kas-cor/NotificationWebhook)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/kas-cor/NotificationWebhook?include_prereleases&logo=github)](https://github.com/kas-cor/NotificationWebhook/releases)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/kas-cor/NotificationWebhook/ci.yml?logo=github&label=CI)](https://github.com/kas-cor/NotificationWebhook/actions)
[![Coverage](https://img.shields.io/badge/coverage-63%25-A3D936?logo=codecov&logoColor=white&label=JaCoCo)](https://github.com/kas-cor/NotificationWebhook/actions?query=artifact%3Acoverage-report)

> 🌐 [Русская версия](README_ru.md)

An Android app that intercepts notifications from other apps via `NotificationListenerService` and forwards them to a specified webhook as JSON (HTTP POST).

## Features

- 📡 **Notification interception** from any app (Telegram, WhatsApp, Gmail, banking, etc.)
- 🌐 **Webhook delivery** — HTTP POST with JSON payload to any URL
- 🎯 **Per-app filter** — include specific apps or forward everything
- 🚫 **Skip ongoing notifications** — music, navigation, system alerts can be excluded
- 🔁 **Auto-start** — after device reboot
- 🌗 **Material 3 Design** — light and dark theme (system-aware, also manual override)
- ✅ **Test POST** — built-in button to verify webhook connectivity
- 🔐 **Bearer token** — optional `Authorization: Bearer <token>` header
- 🌐 **Localization** — system / Russian / English (persisted, applies on Activity recreate)
- 🎨 **Theme override** — system / light / dark (reactive, no restart)
- 🧹 **Auto-swipe promos** — if the server classifies a notification as promo/deal, the app swipes it away (clean notification shade)

### Auto-swipe promos (agent classification)

Flow:

1. The app POSTs the notification to `/webhook`.
2. The server stores it, creates a classification record and returns a `classification_id`.
3. The agent (LLM via the webhook prompt) decides: promo/deal → runs `promo_store.py` and marks the `classification_id` as `dismiss`.
4. After a few seconds the app polls `GET /classification/{id}`.
5. On `dismiss` the app swipes the notification (`cancelNotification`). On `keep`/error/timeout the notification stays (fail-open).

Server endpoints:

| Method | Path | Description |
|---|---|---|
| `POST` | `/webhook` | Accept a notification, returns `classification_id` |
| `GET` | `/classification/{id}` | Status: `pending` → `done` + `action` (`keep`/`dismiss`) |
| `POST` | `/classification/{id}/dismiss` | Mark as promo (called by the agent) |

Safety: a notification is swiped **only** on an explicit `dismiss` verdict. Any classifier failure, timeout or `keep` leaves it visible.

## JSON Payload

```json
{
  "app_package":      "org.telegram.messenger",
  "app_name":         "Telegram",
  "title":            "Alexander",
  "text":             "Hello!",
  "sub_text":         "3 new messages",
  "category":         "msg",
  "priority":         0,
  "notification_id":  12345,
  "channel_id":       "messages",
  "timestamp_iso":    "2026-05-20T00:58:32.412Z",
  "timestamp_ms":     1779227912412
}
```

### Text Resolution (Fallback Chain)

**Title:** `EXTRA_TITLE_BIG` → `EXTRA_CONVERSATION_TITLE` → `EXTRA_TITLE` → `tickerText` → app name

**Text:** `MessagingStyle.messages` (chats) → `EXTRA_BIG_TEXT` → `EXTRA_TEXT_LINES` → `EXTRA_TEXT` → `EXTRA_INFO_TEXT` → `EXTRA_SUMMARY_TEXT` → `tickerText` → title

## Architecture

```
NotificationListenerService  ← system bind via BIND_NOTIFICATION_LISTENER_SERVICE
        │  onNotificationPosted()
        │  → dedup (3s window)
        │  → buildPayload() → JSONObject
        │  → sendToWebhook() ← Coroutine IO dispatcher
        │    + optional promo classification request + poll
        ▼
   Webhook HTTP POST

ForegroundKeepAliveService  ← START_STICKY foreground service
        │  keeps process alive (especially on Xiaomi/Huawei)
        │  foregroundServiceType = specialUse (API 34)
        │  requestRebind(NLS) on start
        ▼
   Persistent notification in status bar
```

## Components

| File | Purpose |
|------|---------|
| `NotificationListenerService.kt` | Core: intercept, dedup, build JSON, HTTP POST |
| `MainActivity.kt` | Compose host: applies persisted locale, holds reactive theme/locale state, recreates on locale change, passes callbacks to `MainScreen` |
| `ui/MainScreen.kt`, `ui/*Tab.kt` | Compose UI: Home (status/webhook), Exclusions (apps + rules), History, Settings (toggles/locale/theme/about) |
| `ForegroundKeepAliveService.kt` | Foreground service to keep process alive |
| `BootReceiver.kt` | Auto-start after reboot / package update |
| `AppPrefs.kt` | Thread-safe SharedPreferences singleton (encrypted, AES256-GCM) |

## Settings tab

- **Forward notifications** — main on/off; requires notification access to enable
- **Skip ongoing** — music, navigation, system notifications
- **Auto-swipe promos** — classification toggle (on by default)
- **Language** — system / Russian / English
- **Theme** — system / light / dark
- **About** — version (from `BuildConfig.VERSION_NAME`), check for updates (GitHub API), GitHub repo link

## Localization

All UI strings are externalized to `res/values/strings.xml` (English default) and `res/values-ru/strings.xml`. Language choice is persisted in `AppPrefs` and applied on Activity recreate; the default follows the system language, falls back to English.

## Setup (User Steps)

1. **Install APK**
2. **Grant notification access:**
   - Tap "Grant access" in the app
   - In system settings, find **NotifWebhook** → enable
3. **Disable battery optimization:**
   - Tap the button in the app → allow
4. **Enter webhook URL** → **Save**
5. *(optional)* **Enter Bearer token** below the URL → **Save** (added as `Authorization: Bearer ...` to every request)
6. **Tap "Test POST"** — verify HTTP 200
7. **Enable "Forward notifications"**
8. *(optional)* Select specific apps (empty = all)

### For Xiaomi / HyperOS / MIUI

Xiaomi's security system aggressively blocks background services. Additional steps:

1. **Settings → Apps → Manage → NotifWebhook**
   - Enable **"Auto-start"**
2. **Battery & Performance → NotifWebhook → "No restrictions"**
3. **Pin NotifWebhook** in the recent apps list

## Design

- **Jetpack Compose (Material 3)** — rounded cards, accent blue color
- **Dark theme** — automatic (system) or manual override (light/dark)
- **Bottom navigation with 4 tabs:** Главная (status, webhook URL) / Исключения (apps + rules) / История / Настройки (toggles, localization, theme, about)
- **Bearer token** — input field with password visibility toggle
- **Localization** — EN/ru/system; theme system/light/dark

### Webhook Send History

The app stores the last **50 webhook sends** locally. Each record includes app, title/text, success/failure, HTTP code, timestamp, and optional classification status.

Classification status shown in history: `dismiss`, `keep`, `pending`, `error`, `disabled`, or other.

### Exclusion Rules

Notifications can be filtered before sending:
- Fields: `title`, `text`, `app_name`, `app_package`
- Case-insensitive substring match
- Any rule match → notification is dropped

The Exclusions tab merges app selection and exclusion rules in one screen: apps at top (empty = all forwarded), rules below, add rule via Compose dialog with field dropdown.

## Android 14+ (API 34) Specifics

| Issue | Solution |
|-------|----------|
| `startService()` for NLS doesn't work | System bind via `BIND_NOTIFICATION_LISTENER_SERVICE` only |
| Service killed by OEM | `ForegroundKeepAliveService` with `START_STICKY` |
| `foregroundServiceType` required | `specialUse` in manifest |
| `POST_NOTIFICATIONS` permission | Requested from Home tab via Compose launcher (API 33+) |
| Aggressive battery on Xiaomi/Huawei | Exempt from optimization + manual auto-start |
| Duplicate notifications | Dedup via `LinkedHashMap`, 3s window, max 50 entries |
| NLS disabled after reboot | `requestRebind()` on service start + retry after 5s |

## Tech Stack

| Technology | Version |
|------------|---------|
| Kotlin / JVM | 17 |
| compileSdk / minSdk | 34 |
| Jetpack Compose | BOM 2024.04.01 (material3 1.2.x) |
| Compose Compiler | 1.5.8 (Kotlin 1.9.22) |
| Coroutines | 1.8.1 |
| AndroidX Core-KTX | 1.13.1 |
| AndroidX Security Crypto | 1.1.0-alpha06 |

## Testing

The project contains **56 tests** in 3 suites:

- **37 unit tests** for `NotificationListenerService` (title/text resolution, ongoing detection, exclusion rules, classification parsing)
- **15 unit tests** for `AppPrefs` (history limit 50, exclusion rules CRUD, JSON roundtrips; `classifyStatus` roundtrip, null handling, unique rule id generation)
- **4 Compose UI tests** (`MainScreenUiTest`) — tab switching and add-rule dialog validation, run on the JVM via Robolectric (no emulator needed)

**Stack:** JUnit 4.13.2 + Mockito 5.11.0 (inline mock maker for `Bundle`) + Robolectric 4.13 + Compose `ui-test-junit4`.

**Note:** Compose UI tests run only on the debug variant (`testReleaseUnitTest` excludes them — they need the debug-only `ui-test-manifest`).

```bash
# Run tests
./gradlew test

# Code coverage
./gradlew jacocoTestReport
# Open: app/build/reports/jacoco/jacocoTestReport/html/index.html
```

### CI

Every push to `main` runs tests automatically (`./gradlew test`, includes the Robolectric Compose UI tests). JaCoCo report (HTML+XML) uploaded as `coverage-report` artifact (14 days).

---

## Build

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `BIND_NOTIFICATION_LISTENER_SERVICE`

---

<p align="center">
  <a href="https://github.com/kas-cor/NotificationWebhook">📦 GitHub</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/kas-cor/NotificationWebhook/issues">🐛 Report a Bug</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/kas-cor/NotificationWebhook/discussions">💬 Discussions</a>
  &nbsp;·&nbsp;
  <a href="README_ru.md">🌐 Русская версия</a>
</p>
