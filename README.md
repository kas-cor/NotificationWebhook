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
- 🌗 **Material 3 Design** — light and dark theme (system-aware)
- ✅ **Test POST** — built-in button to verify webhook connectivity
- 🔐 **Bearer token** — optional `Authorization: Bearer <token>` header

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
| `MainActivity.kt` | UI: status, webhook URL, toggles, app list |
| `ForegroundKeepAliveService.kt` | Foreground service to keep process alive |
| `BootReceiver.kt` | Auto-start after reboot / package update |
| `AppPrefs.kt` | Thread-safe SharedPreferences singleton |

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

- **Material 3** — rounded cards, accent blue color
- **Dark theme** — automatic (DayNight)
- **Sections:** STATUS, WEBHOOK URL, SETTINGS, APPS
- **Bearer token** — input field with password visibility toggle

### Webhook Send History

The app stores the last **50 webhook sends** locally. Each record includes app, title/text, success/failure, HTTP code, and timestamp.

### Exclusion Rules

Notifications can be filtered before sending:
- Fields: `title`, `text`, `app_name`, `app_package`
- Case-insensitive substring match
- Any rule match → notification is dropped

## Android 14+ (API 34) Specifics

| Issue | Solution |
|-------|----------|
| `startService()` for NLS doesn't work | System bind via `BIND_NOTIFICATION_LISTENER_SERVICE` only |
| Service killed by OEM | `ForegroundKeepAliveService` with `START_STICKY` |
| `foregroundServiceType` required | `specialUse` in manifest |
| `POST_NOTIFICATIONS` permission | Requested in `onResume` (API 33+) |
| Aggressive battery on Xiaomi/Huawei | Exempt from optimization + manual auto-start |
| Duplicate notifications | Dedup via `LinkedHashMap`, 3s window, max 50 entries |
| NLS disabled after reboot | `requestRebind()` on service start + retry after 5s |

## Tech Stack

| Technology | Version |
|------------|---------|
| Kotlin / JVM | 17 |
| compileSdk / minSdk | 34 |
| Material Components | 1.12.0 |
| Coroutines | 1.8.1 |
| AndroidX Core-KTX | 1.13.1 |
| AppCompat | 1.7.0 |
| RecyclerView | 1.3.2 |

## Testing

The project contains **22 unit tests** for `NotificationListenerService`:

| Group | Tests | What's tested |
|-------|-------|---------------|
| `resolveTitle` | 8 | Title priority: BigTitle → Title → tickerText → "" |
| `resolveText` | 11 | Text priority: BigText → TextLines → Text → SummaryText → tickerText → "" |
| `isOngoing` | 3 | Detection of `FLAG_ONGOING_EVENT` |

**Stack:** JUnit 4.13.2 + Mockito 5.11.0 (inline mock maker for `Bundle`).

```bash
# Run tests
./gradlew test

# Code coverage
./gradlew jacocoTestReport
# Open: app/build/reports/jacoco/jacocoTestReport/html/index.html
```

### CI

Every push to `main` runs tests automatically. JaCoCo report (HTML+XML) uploaded as `coverage-report` artifact (14 days).

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