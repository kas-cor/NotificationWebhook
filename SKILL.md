---
name: notification-webhook
description: "Complete NotifWebhook integration for AI agents: Android client + Python server (FastAPI + SQLite) — receive, store, analyze, and monitor Android notifications"
version: 2.0.0
author: kas-cor
license: MIT
tags:
  - webhook
  - android
  - notifications
  - sqlite
  - fastapi
  - ai-agent
  - hermes
  - openclaw
  - tailscale
  - analytics
platforms: [android, linux]
setup_needed: true
required_commands:
  - python3
  - tailscale
  - curl
  - systemctl
required_environment_variables:
  - NOTIF_WEBHOOK_PORT
  - NOTIF_WEBHOOK_BIND
  - NOTIF_WEBHOOK_DB_DIR
  - NOTIF_WEBHOOK_AUTH_TOKEN
---

# NotifWebhook — AI-Agent Integration

> **One project, two components:** Android client captures phone notifications, Python server receives, stores, and analyzes them.

---

## 📦 Components

| Component | Stack | Purpose |
|-----------|-------|---------|
| **Android client** | Kotlin, API 34+ | Captures notifications via `NotificationListenerService` → HTTP POST |
| **Python server** | FastAPI + aiosqlite + uvicorn | Webhook receiver, SQLite storage, REST API |
| **CLI analyzer** | Python (argparse) | Search, reports, statistics, export |
| **Watchdog** | Python (cron) | Auto-monitoring with importance filtering |

---

## 🏗 Architecture

```
Android device                          VPS / server
┌─────────────────────┐                ┌──────────────────────────────┐
│ Any Android App    │                 │                             │
│         ↓          │  HTTP POST      │  server.py (FastAPI)        │
│ NotificationListener│ ──────────────→│  Port 8790                  │
│ Service (NLS)      │    JSON payload │  ┌─ /health                 │
│   ┌ dedup          │                 │  ├─ /webhook  ← POST        │
│   ├ resolveTitle() │                 │  └─ SQLite                  │
│   └ buildPayload() │                 │     notif_webhook.db        │
└─────────────────────┘                └──────────────────────────────┘
                                                │
                               ┌────────────────┼────────────────┐
                               ▼                ▼                ▼
                         🔍 analyze.py    🤖 watchdog.py    🔔 Cron summary
                         (CLI queries)    (monitoring)      (Telegram)
```

### Data flow

```
Android Notification → HTTP POST → server.py (/webhook) → SQLite
                                                               ↓
                                                     analyze.py (CLI) → Telegram response
                                                     watchdog.py (cron) → Telegram summary
```

---

## 🚀 Quick start for AI agents

### 1. Server setup

```bash
cd server/
bash setup.sh
```

`setup.sh` will:
- Create `~/.hermes/data/` for the database
- Generate `NOTIF_WEBHOOK_AUTH_TOKEN` and save to `.env`
- Install and start `notif-webhook.service` (systemd)
- Print the tailscale funnel command

### 2. Expose via Tailscale Funnel

```bash
sudo tailscale funnel --bg --set-path '/notif-webhook' 'http://127.0.0.1:8790'
```

Your webhook URL will be:
```
https://<tailscale-hostname>.ts.net/notif-webhook/webhook
```

### 3. Configure the Android app

In NotifWebhook on your phone, set:

- **Webhook URL:** `https://<tailscale-hostname>.ts.net/notif-webhook/webhook`
- **Bearer token:** from `~/.hermes/.env` (optional but recommended)

### 4. Verify it works

```bash
# Health check
curl http://127.0.0.1:8790/health

# Test notification
curl -X POST 'http://127.0.0.1:8790/webhook' \
  -H 'Authorization: Bearer your-token-here' \
  -H 'Content-Type: application/json' \
  -d '{"app_package":"com.test","app_name":"Test","title":"hello","text":"world"}'

# → 200 {"ok": true, "id": 1}
```

---

## 🤖 AI agent integration patterns

### When to use NotifWebhook

An AI agent (Hermes / OpenClaw) uses NotifWebhook in these scenarios:

| Scenario | What the agent does | Command |
|----------|-------------------|---------|
| **"What's new in chats?"** | Analyzes notifications for a period | `analyze.py report --since today` |
| **"Show latest notifications"** | Prints recent feed | `analyze.py latest 15` |
| **"How many notifications?"** | Counts stats | `analyze.py stats --since 7d` |
| **"Search notifications"** | Searches by text | `analyze.py search "deploy"` |
| **"Export notifications"** | Dumps to files | `analyze.py export --since yesterday` |
| **Auto-monitoring (cron)** | Hourly check for important items | `watchdog.py` (via cron) |

### Watchdog: auto-monitoring

The watchdog runs on a cron schedule (e.g. hourly) and:

1. Stores `last_processed_id` — the last processed notification ID
2. On each run, only fetches NEW notifications (`id > checkpoint`)
3. Applies importance rules:
   - `always_skip.patterns` — checked against title+text
   - `always_skip.title` — checked against title field (sender: Hermes, Claw)
   - `always_include.patterns` — checked against title+text (errors, deploy…)
   - `always_include.apps` — checked against app_name (GitHub, GitLab…)
4. If important items found → prints output (cron delivers it)
5. If nothing important or nothing new → stays silent
6. Checkpoint updates **only after** successful analysis

**State file:** `~/.hermes/data/notif_webhook_watchdog.json`

#### JSON mode (for LLM summarization)

The `--json` flag outputs deduplicated, grouped data:

```bash
python3 server/watchdog.py --json
```

Example output:
```json
{
  "period": {"from": "2026-05-20T18:05:20Z", "to": "2026-05-20T18:57:41Z"},
  "total_new": 42,
  "total_important": 5,
  "total_deduped": 3,
  "groups": {
    "org.telegram.messenger": {
      "app_name": "Telegram",
      "notifications": [
        {"title": "Dev chat", "text": "deploy passed", "reason": "include-pattern: 'deploy'", "timestamp_iso": "..."}
      ]
    }
  }
}
```

The agent receives this JSON and summarizes it into human-readable text.

#### Training importance rules

```bash
# Mark as important
python3 server/watchdog.py learn --important "deploy"

# Mark to skip
python3 server/watchdog.py learn --skip "random chat"

# Ignore sender by title
python3 server/watchdog.py learn --skip-title "Hermesa"

# Add app to important
python3 server/watchdog.py learn --important-app "GitHub"

# View current rules
python3 server/watchdog.py rules

# Reset checkpoint (re-process everything)
python3 server/watchdog.py reset-checkpoint
```

#### Default importance rules

| Type | Checked against | What's considered important |
|------|----------------|----------------------------|
| 🔴 `include.patterns` | `title + text` | Deploys, errors, 502/503, nginx, mentions, CI/CD, security, alerts |
| 📱 `include.apps` | `app_name` | GitHub, GitLab |
| ⛔ `skip.patterns` | `title + text` | Test notifications, reactions (👍, 👌), short replies, join/leave |
| 🚫 `skip.title` | `title` | Hermes, Claw (bot senders) |
| 📊 `medium_apps` | `app_name` | Telegram, Discord — medium priority by default |

---

## 🗄 Database

**File:** `~/.hermes/data/notif_webhook.db`

```sql
notifications (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    app_package TEXT NOT NULL,
    app_name    TEXT NOT NULL,
    title       TEXT NOT NULL,
    text        TEXT NOT NULL DEFAULT '',
    sub_text    TEXT NOT NULL DEFAULT '',
    category    TEXT NOT NULL DEFAULT '',
    priority    INTEGER NOT NULL DEFAULT 0,
    notif_id    INTEGER NOT NULL DEFAULT 0,
    channel_id  TEXT NOT NULL DEFAULT '',
    timestamp_iso TEXT NOT NULL DEFAULT '',
    timestamp_ms INTEGER NOT NULL DEFAULT 0,
    raw_data    TEXT NOT NULL,
    received_at INTEGER NOT NULL
)
```

**Indexes:** `app_package`, `app_name`, `received_at`.

### Direct SQL queries (when analyze.py truncates text)

`analyze.py search` only shows the `text` field, which may be truncated (especially in chat groups). Use `raw_data` for the full context:

```bash
sqlite3 /path/to/notif_webhook.db "
SELECT substr(raw_data,1,800) FROM notifications
WHERE raw_data LIKE '%search_term%'
ORDER BY timestamp_ms DESC LIMIT 5;
"
```

---

## 📋 Group/chat context reference (for summarization)

When summarizing notification JSON, use this reference:

| Group (title/app_name) | Context |
|------------------------|---------|
| **Project Dev Chat** | Internal developer group. Casual conversation mixed with work. |
| **Project Official** | Official project group. Work-only, strict tone. |
| **Hermes** / **Claw** | AI agents of the system. **Always ignore** during summarization. |

---

## 🛠 Server management commands

### Status and logs

```bash
sudo systemctl status notif-webhook.service
sudo journalctl -u notif-webhook.service -n 50 -f
sudo journalctl -u notif-webhook.service --since "1 hour ago"
```

### Restart

```bash
sudo systemctl restart notif-webhook.service
```

### Restoring dependencies (uv venv)

**Symptom:** service in restart loop, `journalctl` shows `ModuleNotFoundError`.
**Cause:** dependency dropped from uv venv (usually after Python/uv update).

```bash
uv pip install aiosqlite --python /path/to/venv/bin/python3
sudo systemctl restart notif-webhook.service
```

---

## 📊 CLI analyzer (analyze.py)

All commands run via `server/analyze.py` (or a configured alias/symlink).

### Latest N notifications

```bash
python3 server/analyze.py latest 15
```

### Today

```bash
python3 server/analyze.py today
```

### App report for a time period

```bash
python3 server/analyze.py report --app "Telegram" --since today
python3 server/analyze.py report --app "Project" --since 2026-05-18 --until 2026-05-20
```

### Apps sorted by activity

```bash
python3 server/analyze.py by-app --since today
```

### Full-text search

```bash
python3 server/analyze.py search "deploy"
```

### Statistics

```bash
python3 server/analyze.py stats --since 2026-05-13 --until 2026-05-20
python3 server/analyze.py stats --since 7d
```

Date formats: `YYYY-MM-DD`, `today`, `yesterday`, `7d` (N days), `6h` (N hours).

### Export

```bash
python3 server/analyze.py export --since yesterday              # → Markdown (by category)
python3 server/analyze.py export --since today --format json    # → JSON
```

Exports are saved to `~/.hermes/data/exports/YYYYMMDD_HHMMSS/`.

---

## 📁 Repository structure

```
NotificationWebhook/
├── SKILL.md              ← This file — AI agent integration guide
├── AGENTS.md             ← Android client documentation for agents
├── README.md             ← For humans: features, setup, screenshots
├── LICENSE               ← MIT
│
├── app/                  ← Android client (Kotlin)
│   └── src/
│       └── main/
│           └── java/com/notifwebhook/
│               ├── MainActivity.kt                     — UI
│               ├── NotificationListenerService.kt      — Core: intercept, dedup, POST
│               ├── ForegroundKeepAliveService.kt       — Foreground service
│               ├── BootReceiver.kt                     — Auto-start
│               └── AppPrefs.kt                         — SharedPreferences
│
├── server/               ← Python server (FastAPI + SQLite)
│   ├── server.py         — FastAPI webhook receiver
│   ├── analyze.py        — CLI analyzer & reports
│   ├── watchdog.py       — Cron monitoring with importance filter
│   └── setup.sh          — Installer: DB, systemd, env
│
├── build.gradle          ← Android build
├── settings.gradle
├── gradle/
├── gradlew
└── .github/
    └── workflows/
        └── ci.yml        ← CI/CD: lint → test → JaCoCo → build → release
```

---

## 📱 Android client (summary)

**Full documentation:** [AGENTS.md](AGENTS.md)

### JSON Payload (sent to server)

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

### Text resolution (fallback chain)

**Title:** `EXTRA_TITLE_BIG` → `EXTRA_CONVERSATION_TITLE` → `EXTRA_TITLE` → `tickerText` → app name

**Text:** `MessagingStyle.messages` (chats) → `EXTRA_BIG_TEXT` → `EXTRA_TEXT_LINES` → `EXTRA_TEXT` → `EXTRA_INFO_TEXT` → `EXTRA_SUMMARY_TEXT` → `tickerText` → title

### Android 14+ (API 34) specifics

| Issue | Solution |
|-------|----------|
| `startService()` for NLS doesn't work | System bind only via `BIND_NOTIFICATION_LISTENER_SERVICE` |
| Service killed by OEM | `ForegroundKeepAliveService` with `START_STICKY` |
| `foregroundServiceType` required | `specialUse` in manifest |
| Duplicate notifications | Dedup via `LinkedHashMap`, 3s window, max 50 entries |
| NLS disabled on reboot | `requestRebind()` on service start + retry after 5s |

---

## 🔧 Building the Android app

```bash
# Debug
./gradlew assembleDebug

# Release (with signing)
export KEYSTORE_PASSWORD='password'
./gradlew assembleRelease

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Tests + coverage
./gradlew test
./gradlew jacocoTestReport
```

### CI/CD (GitHub Actions)

- **Push to `main`**, **PR to `main`**, **tag `v*`** trigger the build
- Pipeline: lint → test → JaCoCo → assembleDebug → assembleRelease
- Tagged releases auto-create GitHub Release with changelog + APK

**GitHub Secrets:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`

---

## ⚠️ Pitfalls

- **Auth token:** if you change it in `.env`, restart the service (`systemctl restart`)
- **Tailscale funnel** requires `sudo` and only works while tailscale is running
- **Database doesn't auto-clean** — run `VACUUM` or delete old records manually
- **Port 8790:** make sure it's not occupied by another service
- **Server bind:** `127.0.0.1` only; expose externally only through tailscale funnel
- **Python dependencies:** after Python/uv update, reinstall `aiosqlite` via `uv pip install`