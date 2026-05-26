# NotifWebhook — Android Notification Forwarding via Webhook

## Project Overview

**NotifWebhook** is an Android app (API 34+, Android 14+ only) that intercepts notifications from other apps via `NotificationListenerService` and forwards them to a specified webhook as JSON (HTTP POST).

### Primary Use Case

The user installs the app, grants notification access, enters a webhook URL, and (optionally) a Bearer token for authorization. All notifications (or from selected apps) are sent to the specified URL as POST requests with a JSON payload and an `Authorization: Bearer <token>` header (if a token is configured).

---

## Tech Stack & Dependencies

| Technology | Version |
|---|---|
| Kotlin / JVM | 17 |
| compileSdk / targetSdk / minSdk | 34 (Android 14) |
| Gradle / Android Gradle Plugin | 8.5 / 8.2.2 |
| Coroutines | 1.8.1 |
| Material Components | 1.12.0 |
| AndroidX Core-KTX | 1.13.1 |
| AppCompat | 1.7.0 |
| RecyclerView | 1.3.2 |
| JaCoCo | 0.8.11 |
| JUnit | 4.13.2 |
| Mockito | 5.11.0 (inline mock maker) |

---

## Architecture

```
NotificationListenerService   ← system bind via BIND_NOTIFICATION_LISTENER_SERVICE
        │  onNotificationPosted()
        │  → dedup (LinkedHashMap, 3s window, max 50 entries)
        │  → resolveTitle() / resolveText() — fallback chain
        │  → buildPayload() → JSONObject
        │  → sendToWebhook() ← Coroutine IO dispatcher
        │    + Authorization: Bearer <token> (if set)
        ▼
   Webhook HTTP POST

ForegroundKeepAliveService   ← START_STICKY foreground service (specialUse, API 34)
        │  keeps process alive (especially on Xiaomi/Huawei)
        │  requestRebind(NLS) on start
        ▼
   Persistent notification in status bar

BootReceiver  ← BOOT_COMPLETED / MY_PACKAGE_REPLACED → ForegroundKeepAliveService.start()
AppPrefs      ← Thread-safe SharedPreferences singleton
MainActivity  ← UI: listener status, webhook URL, toggles, app list
```

### Components

| File | Description |
|---|---|
| `MainActivity.kt` | Main UI: status, webhook URL input, Bearer token, toggles, installed apps list |
| `NotificationListenerService.kt` | Core: intercept, dedup, resolveTitle/resolveText, JSON, HTTP POST |
| `ForegroundKeepAliveService.kt` | Foreground service to keep process alive (specialUse, API 34) |
| `BootReceiver.kt` | Auto-start after reboot / package update |
| `AppPrefs.kt` | Singleton wrapper around SharedPreferences |

### JSON Payload

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

---

## Unit Tests

**22 tests** for `NotificationListenerServiceTest`:

| Group | Tests | What's tested |
|---|---|---|
| `resolveTitle` | 8 | Title priority: BigTitle → Title → tickerText → "", empty/whitespace values |
| `resolveText` | 11 | Text priority: BigText → TextLines → Text → SummaryText → tickerText → "" |
| `isOngoing` | 3 | `FLAG_ONGOING_EVENT`, without flag, flag combinations |

**Run:** `./gradlew test`
**Coverage:** `./gradlew jacocoTestReport` → report at `app/build/reports/jacoco/jacocoTestReport/html/index.html`

**Mocks:** `Bundle` (final class) is mocked via inline mock maker (`mockito-extensions/org.mockito.plugins.MockMaker`). `Notification` — fields `tickerText`, `flags` are set directly on the mock object.

---

## CI/CD (GitHub Actions)

Workflow: `.github/workflows/ci.yml`

### Triggers

- **Push** to `main`
- **Pull Request** to `main`
- **Push tag** `v*` (e.g., `v1.1`)
- **Manual trigger** via `workflow_dispatch`

### Build Steps (build job)

1. **Checkout** + JDK 17 + Android SDK licenses
2. **Version bump** (only for `v*` tags):
   - `versionName` updated from tag (e.g., `v1.1` → `"1.1"`)
   - `versionCode` incremented (+1 from current)
3. **Commit version bump** (only for `v*` tags): commits changes to `app/build.gradle` on `main` as `github-actions[bot]`
4. **Gradle caching**
5. **Lint** (`lintRelease`)
6. **Unit tests** (`test`)
7. **JaCoCo coverage** (`jacocoTestReport`) → HTML and XML artifacts
8. **Debug APK build** (`assembleDebug`)
9. **Keystore decode** from `KEYSTORE_BASE64` secret
10. **Release APK build** (`assembleRelease`) with `KEYSTORE_PASSWORD`
11. **APK renaming:**
    - Debug: `app-debug.apk` → `NotifWebhook-<version>-debug.apk`
    - Release: `app-release.apk` → `NotifWebhook-<version>.apk`
    - Unsigned: `app-release-unsigned.apk` → `NotifWebhook-<version>-unsigned.apk`
12. **Artifact upload:**
    - Debug APK (7 days)
    - Signed Release APK (30 days)
    - Unsigned APK as fallback (7 days)

### Releases (release job)

On `v*` tag push, an additional `release` job runs:
- Downloads signed APK by artifact name `NotifWebhook-<version>`
- Generates changelog from commits between previous tag and current
- Creates GitHub Release with notes and APK

### GitHub Secrets

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | `notwebhook-release.jks` in base64 (`base64 -w0 notwebhook-release.jks`) |
| `KEYSTORE_PASSWORD` | Keystore password |

### Full Release Process

```bash
git add . && git commit -m "New feature" && git push origin main
# Wait for green CI

git tag v1.1 && git push origin v1.1
# CI auto: bumps version, builds APKs, creates GitHub Release
```

### APK File Names

| Type | Format | Example |
|---|---|---|
| Debug | `NotifWebhook-<version>-debug.apk` | `NotifWebhook-1.1-debug.apk` |
| Release (signed) | `NotifWebhook-<version>.apk` | `NotifWebhook-1.1.apk` |
| Release (unsigned) | `NotifWebhook-<version>-unsigned.apk` | `NotifWebhook-1.1-unsigned.apk` |

---

## Setup (User Steps)

1. **Install APK** (download from [Releases](https://github.com/kas-cor/NotificationWebhook/releases))
2. **Grant notification access:**
   - Tap "Grant access" in the app
   - In system settings, find NotifWebhook → enable
3. **Disable battery optimization:**
   - Tap the button in the app → allow
4. **Enter webhook URL** → **Save**
5. *(optional)* **Enter Bearer token** → **Save**
6. **Tap "Test POST"** — verify HTTP 200
7. **Enable "Forward notifications"**
8. *(optional)* Select specific apps (empty = all)

### For Xiaomi / HyperOS / MIUI

1. **Settings → Apps → Manage → NotifWebhook** → enable **"Auto-start"**
2. **Battery & Performance → NotifWebhook → "No restrictions"**
3. **Pin NotifWebhook** in recent apps

---

## Webhook Send History

The app stores the last **50 webhook sends** locally. Each record contains app, title/text, result (success/error), HTTP code, and timestamp.

## Exclusion Rules

Notifications can be filtered client-side before sending:
- Field: `title`, `text`, `app_name`, `app_package`
- Pattern: case-insensitive substring match
- Any rule match → notification is dropped (never sent)

## Android 14+ (API 34) Specifics

| Issue | Solution |
|---|---|
| `startService()` for NLS doesn't work | System bind via `BIND_NOTIFICATION_LISTENER_SERVICE` only |
| Service killed by OEM | `ForegroundKeepAliveService` with `START_STICKY` |
| `foregroundServiceType` required | `specialUse` in manifest |
| `POST_NOTIFICATIONS` permission | Requested in `onResume` (API 33+) |
| Aggressive battery on Xiaomi/Huawei | Exempt from optimization + manual auto-start |
| Duplicate notifications | Dedup via `LinkedHashMap`, 3s window, max 50 entries |
| NLS disabled after reboot | `requestRebind()` on service start + retry after 5s |

---

## Permissions

- `INTERNET` — HTTP requests to webhook
- `ACCESS_NETWORK_STATE` — network checks
- `FOREGROUND_SERVICE` — foreground service
- `FOREGROUND_SERVICE_SPECIAL_USE` — specialUse type (API 34)
- `POST_NOTIFICATIONS` — show notifications (API 33+)
- `RECEIVE_BOOT_COMPLETED` — auto-start
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — exempt from battery optimization
- `BIND_NOTIFICATION_LISTENER_SERVICE` — for NLS (system)

---

## Code Conventions

- **Language:** Kotlin, JVM target 17
- **Coroutines:** `Dispatchers.IO` for network, `Dispatchers.Main` for UI
- **SharedPreferences:** Thread-safe singleton via `AppPrefs`
- **Dedup:** `LinkedHashMap`, 3s window, max 50 entries
- **Network:** Standard `HttpURLConnection`, synchronous calls in IO dispatcher. If Bearer token is set, adds `Authorization: Bearer <token>` header
- **UI:** Material Components, custom layout (no Compose), `RecyclerView` for app list
- **Logging:** Tag `NLS_Webhook` for ListenerService, `KeepAliveService` for foreground service, `BootReceiver` for receiver
- **Testing:** JUnit + Mockito (inline mock maker for final classes), 22 unit tests
- **CI:** lint → test → JaCoCo → assembleDebug → assembleRelease → artifacts
- **APK signing:** via secrets `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD`

---

## Build

```bash
# Debug
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release (if secrets configured locally)
export KEYSTORE_PASSWORD='your_password'
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# Tests + coverage
./gradlew test
./gradlew jacocoTestReport
# Open: app/build/reports/jacoco/jacocoTestReport/html/index.html
```