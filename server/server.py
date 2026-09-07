#!/usr/bin/env python3
"""Notification Webhook Server — receives Android notification JSON, stores in SQLite."""

import os
import hmac
import time
import uuid
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

# Classification: how long a notification may wait for an agent verdict
# before the client treats it as "keep" (fail-open). The agent marks dismiss
# via promo_store.py. Set generously (5 min) because the LLM agent can take
# many seconds (multiple API calls) to classify.
CLASSIFICATION_TTL_MS = int(os.environ.get("NOTIF_WEBHOOK_CLASSIFICATION_TTL_MS", "300000"))

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
    # Classification table: links a received notification to the agent verdict.
    # status: pending | done | error
    # action: keep | dismiss (NULL until decided) — dismiss = swipe on device
    conn.execute("""
        CREATE TABLE IF NOT EXISTS classifications (
            id                INTEGER PRIMARY KEY AUTOINCREMENT,
            classification_id TEXT    NOT NULL UNIQUE,
            notification_row  INTEGER NOT NULL,
            app_package       TEXT    NOT NULL,
            app_name          TEXT    NOT NULL DEFAULT '',
            title             TEXT    NOT NULL DEFAULT '',
            text              TEXT    NOT NULL DEFAULT '',
            status            TEXT    NOT NULL DEFAULT 'pending',
            action            TEXT,
            created_at        INTEGER NOT NULL,
            decided_at        INTEGER
        )
    """)
    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_class_status
        ON classifications(status)
    """)
    conn.commit()
    conn.close()


async def get_db():
    db = await aiosqlite.connect(str(DB_PATH))
    db.row_factory = aiosqlite.Row
    return db


async def create_classification(db, notification_row: int, payload) -> str:
    """Insert a pending classification, return its UUID."""
    cid = str(uuid.uuid4())
    await db.execute(
        """INSERT INTO classifications
           (classification_id, notification_row, app_package, app_name, title, text,
            status, created_at)
           VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)""",
        (
            cid,
            notification_row,
            payload.get("app_package", ""),
            payload.get("app_name", ""),
            payload.get("title", ""),
            payload.get("text", ""),
            int(time.time() * 1000),
        ),
    )
    return cid


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
        cursor = await db.execute(
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
        notification_row = cursor.lastrowid
        classification_id = await create_classification(
            db, notification_row, payload.model_dump()
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
    return {
        "ok": True,
        "id": payload.notification_id,
        "classification_id": classification_id,
        "status": "pending",
    }


@app.get("/classification/{classification_id}")
async def get_classification(classification_id: str, request: Request):
    """Return the current classification status for a notification.

    Client polls this after POST /webhook. Fail-open: once the TTL passes
    without a dismiss verdict, the notification is treated as "keep".
    """
    await verify_auth(request)
    db = await get_db()
    try:
        row = await (await db.execute(
            "SELECT * FROM classifications WHERE classification_id = ?",
            (classification_id,),
        )).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Unknown classification")
        now_ms = int(time.time() * 1000)
        status = row["status"]
        action = row["action"]
        if status == "pending" and (now_ms - row["created_at"]) > CLASSIFICATION_TTL_MS:
            status = "done"
            action = "keep"  # fail-open: unconfirmed stays
        return {
            "classification_id": classification_id,
            "status": status,
            "action": action,
            "ttl_ms": CLASSIFICATION_TTL_MS,
            "created_at": row["created_at"],
            "decided_at": row["decided_at"],
        }
    finally:
        await db.close()


@app.post("/classification/{classification_id}/dismiss")
async def dismiss_classification(classification_id: str, request: Request):
    """Mark a notification as dismiss (swipe). Called by the agent after it
    classifies the notification as promo/spam via promo_store.py."""
    await verify_auth(request)
    db = await get_db()
    try:
        row = await (await db.execute(
            "SELECT id FROM classifications WHERE classification_id = ?",
            (classification_id,),
        )).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Unknown classification")
        await db.execute(
            """UPDATE classifications
               SET status = 'done', action = 'dismiss', decided_at = ?
               WHERE classification_id = ?""",
            (int(time.time() * 1000), classification_id),
        )
        await db.commit()
        return {"ok": True, "classification_id": classification_id, "action": "dismiss"}
    finally:
        await db.close()


# ── CLI wrapper for systemd / direct run ──────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")