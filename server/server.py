#!/usr/bin/env python3
"""Notification Webhook Server — receives Android notification JSON, stores in SQLite."""

import json
import os
import time
import sqlite3
import asyncio
from pathlib import Path
from datetime import datetime, timezone

import aiosqlite
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

# ── Configuration ──────────────────────────────────────────────────────────────
PORT = int(os.environ.get("NOTIF_WEBHOOK_PORT", "8790"))
HOST = os.environ.get("NOTIF_WEBHOOK_BIND", "127.0.0.1")
DB_DIR = Path(os.environ.get("NOTIF_WEBHOOK_DB_DIR", str(Path.cwd() / "data")))
DB_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = DB_DIR / "notif_webhook.db"

# Optional auth token — set in .env or service env
AUTH_TOKEN = os.environ.get("NOTIF_WEBHOOK_AUTH_TOKEN", "")

# ── App ────────────────────────────────────────────────────────────────────────
app = FastAPI(title="NotifWebhook Receiver", version="1.0.0")


# ── Database ───────────────────────────────────────────────────────────────────
def init_db_sync():
    """Initialise SQLite DB synchronously (called at import time)."""
    conn = sqlite3.connect(str(DB_PATH))
    conn.execute("""
        CREATE TABLE IF NOT EXISTS notifications (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            app_package TEXT    NOT NULL,
            app_name    TEXT    NOT NULL,
            title       TEXT    NOT NULL,
            text        TEXT    NOT NULL DEFAULT '',
            sub_text    TEXT    NOT NULL DEFAULT '',
            category    TEXT    NOT NULL DEFAULT '',
            priority    INTEGER NOT NULL DEFAULT 0,
            notif_id    INTEGER NOT NULL DEFAULT 0,
            channel_id  TEXT    NOT NULL DEFAULT '',
            timestamp_iso TEXT  NOT NULL DEFAULT '',
            timestamp_ms INTEGER NOT NULL DEFAULT 0,
            raw_data    TEXT    NOT NULL,
            received_at INTEGER NOT NULL
        )
    """)
    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_notifs_app_pkg
        ON notifications(app_package)
    """)
    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_notifs_received
        ON notifications(received_at)
    """)
    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_notifs_app_name
        ON notifications(app_name)
    """)
    conn.commit()
    conn.close()


async def get_db():
    db = await aiosqlite.connect(str(DB_PATH))
    db.row_factory = aiosqlite.Row
    return db


# ── Auth middleware ────────────────────────────────────────────────────────────
async def verify_auth(request: Request):
    if not AUTH_TOKEN:
        return
    token = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if token != AUTH_TOKEN:
        raise HTTPException(status_code=403, detail="Invalid auth token")


# ── Routes ─────────────────────────────────────────────────────────────────────
@app.on_event("startup")
async def startup():
    init_db_sync()


@app.get("/health")
async def health():
    return {"status": "ok", "db": str(DB_PATH)}


@app.post("/webhook")
async def receive_webhook(request: Request):
    await verify_auth(request)

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    payload = body if isinstance(body, dict) else {}
    now_ms = int(time.time() * 1000)

    db = await get_db()
    try:
        await db.execute(
            """INSERT INTO notifications
               (app_package, app_name, title, text, sub_text, category,
                priority, notif_id, channel_id, timestamp_iso, timestamp_ms,
                raw_data, received_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                payload.get("app_package", ""),
                payload.get("app_name", ""),
                payload.get("title", ""),
                payload.get("text", ""),
                payload.get("sub_text", ""),
                payload.get("category", ""),
                payload.get("priority", 0),
                payload.get("notification_id", 0),
                payload.get("channel_id", ""),
                payload.get("timestamp_iso", ""),
                payload.get("timestamp_ms", 0),
                json.dumps(payload, ensure_ascii=False),
                now_ms,
            ),
        )
        await db.commit()
    except Exception as e:
        await db.close()
        raise HTTPException(status_code=500, detail=f"DB error: {e}")

    await db.close()
    return {"ok": True, "id": payload.get("notification_id")}


# ── CLI wrapper for systemd / direct run ──────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")