#!/usr/bin/env python3
"""Notification Webhook Server — receives Android notification JSON, stores in SQLite."""

import os
import hmac
import time
import sqlite3
import json
from pathlib import Path
from datetime import datetime, timezone

import aiosqlite
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field, ConfigDict

# ── Configuration ──────────────────────────────────────────────────────────────
PORT = int(os.environ.get("NOTIF_WEBHOOK_PORT", "8790"))
HOST = os.environ.get("NOTIF_WEBHOOK_BIND", "127.0.0.1")
DB_DIR = Path(os.environ.get("NOTIF_WEBHOOK_DB_DIR", str(Path.cwd() / "data")))
DB_DIR.mkdir(parents=True, exist_ok=True)
DB_PATH = DB_DIR / "notif_webhook.db"

# Optional auth token — set in .env or service env
AUTH_TOKEN = os.environ.get("NOTIF_WEBHOOK_AUTH_TOKEN", "")
RETENTION_DAYS = int(os.environ.get("NOTIF_WEBHOOK_RETENTION_DAYS", "30"))
MAX_BODY_BYTES = int(os.environ.get("NOTIF_WEBHOOK_MAX_BODY_BYTES", "262144"))

# ── App ────────────────────────────────────────────────────────────────────────
app = FastAPI(title="NotifWebhook Receiver", version="1.0.0")


class NotificationPayload(BaseModel):
    model_config = ConfigDict(extra="ignore")

    app_package: str = Field(min_length=1, max_length=256)
    app_name: str = Field(min_length=1, max_length=256)
    title: str = Field(default="", max_length=4096)
    text: str = Field(default="", max_length=16384)
    sub_text: str = Field(default="", max_length=4096)
    category: str = Field(default="", max_length=128)
    priority: int = Field(default=0, ge=-2, le=2)
    notification_id: int = Field(default=0)
    channel_id: str = Field(default="", max_length=256)
    timestamp_iso: str = Field(default="", max_length=64)
    timestamp_ms: int = Field(default=0, ge=0)


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
        if HOST != "127.0.0.1" and HOST != "localhost":
            raise HTTPException(status_code=503, detail="Server authentication is not configured")
        return
    authorization = request.headers.get("Authorization", "")
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Bearer authentication required")
    token = authorization.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token, AUTH_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid auth token")


# ── Routes ─────────────────────────────────────────────────────────────────────
@app.on_event("startup")
async def startup():
    init_db_sync()


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/webhook")
async def receive_webhook(request: Request):
    await verify_auth(request)
    content_length = request.headers.get("content-length")
    if content_length:
        try:
            content_length_value = int(content_length)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid content length")
        if content_length_value > MAX_BODY_BYTES:
            raise HTTPException(status_code=413, detail="Request body too large")

    try:
        body = await request.body()
        if len(body) > MAX_BODY_BYTES:
            raise HTTPException(status_code=413, detail="Request body too large")
        payload = NotificationPayload.model_validate_json(body)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid notification payload")

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
                payload.app_package,
                payload.app_name,
                payload.title,
                payload.text,
                payload.sub_text,
                payload.category,
                payload.priority,
                payload.notification_id,
                payload.channel_id,
                payload.timestamp_iso,
                payload.timestamp_ms,
                payload.model_dump_json(ensure_ascii=False),
                now_ms,
            ),
        )
        await db.commit()
    except Exception:
        await db.close()
        raise HTTPException(status_code=500, detail="Database error")

    if RETENTION_DAYS > 0:
        cutoff = now_ms - RETENTION_DAYS * 24 * 60 * 60 * 1000
        await db.execute("DELETE FROM notifications WHERE received_at < ?", (cutoff,))
        await db.commit()
    await db.close()
    return {"ok": True, "id": payload.notification_id}


# ── CLI wrapper for systemd / direct run ──────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")