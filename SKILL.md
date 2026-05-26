---
name: notification-webhook
description: "End-to-end notification pipeline: Android app captures phone notifications, Python server receives and stores them in SQLite with CLI analytics and cron monitoring"
version: 2.0.0
author: kas-cor
license: MIT
tags:
  - webhook
  - android
  - notifications
  - sqlite
  - fastapi
  - tailscale
  - analytics
platforms: [android, linux]
setup_needed: true
required_commands:
  - python3
  - pip
  - tailscale
  - curl
  - systemctl
required_environment_variables:
  - NOTIF_WEBHOOK_PORT
  - NOTIF_WEBHOOK_BIND
  - NOTIF_WEBHOOK_DB_DIR
  - NOTIF_WEBHOOK_AUTH_TOKEN
---

# NotifWebhook — Server-Side Integration Guide

> **Two components, one pipeline:** Android app captures notifications → Python server receives, stores, and analyzes them.

---

## 📦 Components

| Component | Stack | Purpose |
|-----------|-------|---------|
| **Android client** | Kotlin, API 34+ | Captures notifications via `NotificationListenerService` → HTTP POST |
| **Python server** | FastAPI + aiosqlite + uvicorn | Webhook receiver, SQLite storage, health endpoint |
| **CLI analyzer** | Python (argparse) | Search, reports, statistics, export from SQLite |
| **Watchdog** | Python | Cron-based auto-monitoring with importance filtering |

---

## 🏗 Architecture

```
┌─────────────────────┐     HTTP POST     ┌─────────────────────────────┐
│   Android device    │   ────────────→   │   server.py (FastAPI)      │
│  (any app with NLS) │   JSON payload    │   Port 8790                │
└─────────────────────┘                   │   ├── /health              │
                                          │   └── /webhook             │
                                          │         ↓                  │
                                          │   SQLite (notif_webhook.db)│
                                          └─────────────────────────────┘
                                                      │
                                         ┌────────────┼────────────┐
                                         ▼            ▼            ▼
                                    analyze.py   watchdog.py   Cron summary
                                  (CLI queries) (monitoring)  (any output)
```

**Data flow:**
```
Android notification → HTTP POST → server.py → SQLite
                                                ↓
                                      analyze.py / watchdog.py → reports
```

---

## 🚀 Quick Start

### Prerequisites
- Python 3.10+ with `fastapi`, `uvicorn`, `aiosqlite`
- Android device with NotifWebhook app installed
- (Optional) A way to expose the server to the internet (Tailscale, Cloudflare, ngrok, or direct IP)

### 1. Install Dependencies
```bash
cd server/
pip install fastapi uvicorn aiosqlite
```

### 2. Configure Environment
```bash
export NOTIF_WEBHOOK_PORT=8790
export NOTIF_WEBHOOK_BIND=127.0.0.1
export NOTIF_WEBHOOK_DB_DIR="$HOME/notifwebhook_data"
export NOTIF_WEBHOOK_AUTH_TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
```

Optionally save to `.env` in the project root:
```bash
cat <<EOF >> .env
NOTIF_WEBHOOK_PORT=8790
NOTIF_WEBHOOK_BIND=127.0.0.1
NOTIF_WEBHOOK_DB_DIR=$HOME/notifwebhook_data
NOTIF_WEBHOOK_AUTH_TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
EOF
```

### 3. Quick Test (inline)
```bash
python3 server/server.py &
sleep 2
curl http://127.0.0.1:8790/health  # → {"status":"ok","db":"..."}
```

### 4. Production Setup (systemd)
```bash
cd server/
bash setup.sh
```
`setup.sh` creates the DB directory, generates auth token, installs and starts `notif-webhook.service`.

### 5. Configure the Android App
- **Webhook URL:** depends on how you expose the server (see section below)
- **Bearer token:** the value of `NOTIF_WEBHOOK_AUTH_TOKEN`

### 6. Choose How to Expose the Server

The server binds to `127.0.0.1:8790` by default — only accessible locally. Pick one method below to make it reachable from your phone.

| Method | HTTPS | Cost | Best for |
|--------|-------|------|----------|
| **Tailscale Funnel** | ✅ Yes | Free | Private use, no domain needed |
| **Cloudflare Tunnel** | ✅ Yes | Free | Custom domain, team use |
| **ngrok** | ✅ Yes | Free tier | Quick testing, demos |
| **Direct (IP:Port)** | ❌ No | Free | LAN only, VPS behind reverse proxy |

#### A) Tailscale Funnel (recommended for privacy)
Requires [Tailscale](https://tailscale.com) installed on both server and phone.
```bash
sudo tailscale funnel --bg --set-path '/notif-webhook' 'http://127.0.0.1:8790'
```
Webhook URL: `https://<your-tailnet>.ts.net/notif-webhook/webhook`

#### B) Cloudflare Tunnel
Requires `cloudflared` installed.
```bash
cloudflared tunnel --url http://127.0.0.1:8790
```
Webhook URL: `https://<random>.trycloudflare.com/webhook`

#### C) ngrok
```bash
ngrok http http://127.0.0.1:8790
```
Webhook URL: `https://<random>.ngrok.io/webhook`

#### D) Direct HTTP (LAN / VPS without HTTPS)
Change bind to `0.0.0.0` and open the port:
```bash
export NOTIF_WEBHOOK_BIND=0.0.0.0
sudo ufw allow 8790/tcp  # if using UFW
```
Webhook URL: `http://<server-ip>:8790/webhook`
> ⚠ No encryption — only use on trusted networks or behind a reverse proxy (nginx/Caddy) with your own TLS.

### 7. Verify End-to-End
```bash
curl -X POST 'http://127.0.0.1:8790/webhook' \
  -H "Authorization: Bearer $NOTIF_WEBHOOK_AUTH_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"app_package":"com.test","app_name":"Test","title":"hello","text":"world"}'
# → 200 {"ok": true, "id": 1}
```

---

## 🗄 Database

**Default path:** `$NOTIF_WEBHOOK_DB_DIR/notif_webhook.db` (defaults to `./data/`)

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

### Full-Context SQL (when `analyze.py search` truncates)
```bash
sqlite3 "$NOTIF_WEBHOOK_DB_DIR/notif_webhook.db" "
SELECT substr(raw_data,1,800) FROM notifications
WHERE raw_data LIKE '%search_term%'
ORDER BY timestamp_ms DESC LIMIT 5;
"
```

---

## 📊 CLI Analyzer (`server/analyze.py`)

Commands are run from the project root: `python3 server/analyze.py <command> [args]`

| Command | Description | Example |
|---------|-------------|---------|
| `latest [N]` | Show last N notifications | `python3 server/analyze.py latest 15` |
| `today` | Notifications from today UTC | `python3 server/analyze.py today` |
| `report` | Categorized report by app | `python3 server/analyze.py report --since today` |
| `by-app` | Apps sorted by activity | `python3 server/analyze.py by-app --since 7d` |
| `search <term>` | Full-text search | `python3 server/analyze.py search "deploy"` |
| `stats` | Summary statistics | `python3 server/analyze.py stats --since 2026-05-13 --until 2026-05-20` |
| `export` | Export to Markdown/JSON | `python3 server/analyze.py export --since yesterday` |

### Date Formats
`YYYY-MM-DD`, `today`, `yesterday`, `7d` (N days ago), `6h` (N hours ago).

### Output Details
- **`report`** groups apps by category: 💬 Messengers, 🛠 Dev/DevOps, 📧 Email, 📱 Social, 🏢 Work, 📦 Other
- **`export`** (Markdown) creates one file per category in `$NOTIF_WEBHOOK_DB_DIR/exports/<timestamp>/`
- **`export --format json`** dumps all records to a single JSON file

---

## 🤖 Watchdog Auto-Monitoring (`server/watchdog.py`)

Designed for cron-based monitoring of new notifications.

### How It Works
1. Stores `last_processed_id` in a JSON state file
2. On each run, fetches only NEW records (`id > last_processed_id`)
3. Applies configurable importance rules (see below)
4. If important content found → prints summary to stdout
5. If nothing important or nothing new → exits silently
6. Checkpoint updates **only after** successful processing

**State file:** `$NOTIF_WEBHOOK_DB_DIR/notif_webhook_watchdog.json`

### JSON Mode (for LLM / script consumption)
```bash
python3 server/watchdog.py --json
```
Outputs deduplicated, grouped JSON:
```json
{
  "period": {"from": "...", "to": "..."},
  "total_new": 42,
  "total_important": 5,
  "total_deduped": 3,
  "groups": {
    "org.telegram.messenger": {
      "app_name": "Telegram",
      "notifications": [
        {"title": "...", "text": "...", "reason": "include-pattern: 'deploy'", "timestamp_iso": "..."}
      ]
    }
  }
}
```

### Training Importance Rules
```bash
# Mark a keyword as important
python3 server/watchdog.py learn --important "deploy"

# Skip notifications matching a pattern
python3 server/watchdog.py learn --skip "random chat"

# Ignore a specific sender (by title field)
python3 server/watchdog.py learn --skip-title "NotificationBot"

# Add a whole app to important
python3 server/watchdog.py learn --important-app "GitHub"

# View current rules
python3 server/watchdog.py rules

# Reset checkpoint (re-process everything)
python3 server/watchdog.py reset-checkpoint
```

### Default Importance Rules
| Type | Field checked | Matches |
|------|--------------|---------|
| 🔴 `include.patterns` | title + text | deploys, errors, 502/503, nginx, CI/CD, security, alerts |
| 📱 `include.apps` | app_name | GitHub, GitLab |
| ⛔ `skip.patterns` | title + text | test notifications, reactions (👍, 👌), short replies, join/leave |
| 🚫 `skip.title` | title field | Bot senders (customizable per deployment) |
| 📊 `medium_apps` | app_name | Telegram, Discord — medium priority by default |

All rules are editable at runtime with `learn` and persist in the state file.

---

## 🛠 Server Management

### Systemd Service
```bash
sudo systemctl status notif-webhook.service
sudo journalctl -u notif-webhook.service -n 50 -f
sudo systemctl restart notif-webhook.service
```

### Direct Run
```bash
python3 server/server.py
```

### Dependency Recovery
If the service enters a restart loop (e.g. after Python upgrade):
```bash
pip install aiosqlite
sudo systemctl restart notif-webhook.service
```

---

## 📁 Repository Structure

```
NotificationWebhook/
├── SKILL.md              ← This file — integration guide
├── AGENTS.md             ← Android client internals for agent developers
├── README.md             ← For humans: features, setup, screenshots
├── LICENSE               ← MIT
│
├── app/                  ← Android client (Kotlin)
│   └── src/main/java/com/notifwebhook/
│       ├── MainActivity.kt
│       ├── NotificationListenerService.kt
│       ├── ForegroundKeepAliveService.kt
│       ├── BootReceiver.kt
│       └── AppPrefs.kt
│
├── server/               ← Python backend
│   ├── server.py         — FastAPI webhook receiver
│   ├── analyze.py        — CLI analyzer & reports
│   ├── watchdog.py       — Cron monitoring with importance filter
│   └── setup.sh          — systemd installer
│
├── build.gradle
├── settings.gradle
├── gradle/
├── gradlew
└── .github/workflows/
    └── ci.yml            ← CI/CD: build, test, release
```

---

## 📱 Android Client — Key Details

**Full documentation:** [AGENTS.md](AGENTS.md)

### JSON Payload
```json
{
  "app_package":      "org.telegram.messenger",
  "app_name":         "Telegram",
  "title":            "Contact Name",
  "text":             "Message content",
  "sub_text":         "3 new messages",
  "category":         "msg",
  "priority":         0,
  "notification_id":  12345,
  "channel_id":       "messages",
  "timestamp_iso":    "2026-05-20T00:58:32.412Z",
  "timestamp_ms":     1779227912412
}
```

### Text Resolution Fallback Chain
**Title:** `EXTRA_TITLE_BIG` → `EXTRA_CONVERSATION_TITLE` → `EXTRA_TITLE` → `tickerText` → app name

**Text:** `MessagingStyle.messages` (chats) → `EXTRA_BIG_TEXT` → `EXTRA_TEXT_LINES` → `EXTRA_TEXT` → `EXTRA_INFO_TEXT` → `EXTRA_SUMMARY_TEXT` → `tickerText` → title

### Android 14+ (API 34) Specifics
| Issue | Solution |
|-------|----------|
| `startService()` for NLS doesn't work | System bind via `BIND_NOTIFICATION_LISTENER_SERVICE` |
| Service killed by OEM | `ForegroundKeepAliveService` with `START_STICKY` |
| `foregroundServiceType` required | `specialUse` in manifest |
| Duplicate notifications | `LinkedHashMap` dedup, 3s window, max 50 entries |
| NLS disabled on reboot | `requestRebind()` on start + retry after 5s |

### Client-Side Exclusion Rules
Notifications can be filtered on the Android side before sending:
- Field: `title`, `text`, `app_name`, or `app_package`
- Pattern: case-insensitive substring match
- Any matching rule → notification is dropped (never sent to server)

### Building the Android App
```bash
./gradlew assembleDebug                    # Debug APK
./gradlew assembleRelease                  # Release APK (requires signing)
./gradlew test                             # Unit tests (22 tests)
./gradlew jacocoTestReport                 # Coverage report
```

### CI/CD (GitHub Actions)
- Triggers: push/PR to `main`, tag `v*`
- Pipeline: lint → test → JaCoCo → assembleDebug → assembleRelease
- Tagged releases: auto-generated GitHub Release with signed APK + changelog
- **Secrets:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`

---

## ⚠️ Common Pitfalls

- **Auth token mismatch:** if you change it in `.env`, restart the server
- **Tailscale funnel** requires `sudo` and a running Tailscale daemon
- **Database growth:** SQLite doesn't auto-vacuum; periodically run `VACUUM` or archive old records
- **Port conflicts:** ensure port 8790 is free before starting
- **Security:** bind to `127.0.0.1` and expose only through Tailscale Funnel or a reverse proxy
- **Python upgrades:** reinstall dependencies (`pip install aiosqlite`) after Python version changes