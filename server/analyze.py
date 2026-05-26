#!/usr/bin/env python3
"""
Notification Webhook Analyzer — CLI tool for querying and reporting on saved notifications.

Usage:
  python analyze.py latest [N]             — last N notifications (default 10)
  python analyze.py today                  — notifications from today (UTC)
  python analyze.py report [--since YYYY-MM-DD|Nd|Nh] [--until YYYY-MM-DD] [--app NAME]
                                          — categorized report, optionally per app
  python analyze.py by-app [--since ...] [--until ...]
                                          — list apps sorted by activity count
  python analyze.py search QUERY          — full-text search in titles and text
  python analyze.py stats [--since ...] [--until ...]
                                          — summary stats for a period
  python analyze.py export [--since ...] [--until ...] [--format md|json]
                                          — export to markdown files or JSON

Date filters: --since, --until take YYYY-MM-DD, "today", "yesterday", or "Nd" (N days ago), "Nh" (N hours ago)
"""

import argparse
import json
import os
import re
import sqlite3
import sys
from pathlib import Path
from datetime import datetime, timedelta, timezone

# ── Config ────────────────────────────────────────────────────────────────────
DB_DIR = Path(os.environ.get("NOTIF_WEBHOOK_DB_DIR", str(Path.cwd() / "data")))
DB_PATH = DB_DIR / "notif_webhook.db"

# Apps that map to meaningful categories for reporting
CATEGORY_MAP = {
    # Messengers / Chat
    "org.telegram.messenger": "messenger",
    "com.whatsapp": "messenger",
    "com.discord": "messenger",
    "org.thoughtcrime.securesms": "messenger",
    "com.slack": "messenger",
    "im.signal": "messenger",
    # Development / DevOps
    "com.github.android": "dev",
    "com.gitlab": "dev",
    # Social
    "com.twitter.android": "social",
    "com.instagram.android": "social",
    "com.reddit.frontpage": "social",
    # Email
    "com.google.android.gm": "email",
    "com.microsoft.office.outlook": "email",
    "com.fsck.k9": "email",
}


def get_conn():
    if not DB_PATH.exists():
        print(f"✗ Database not found: {DB_PATH}", file=sys.stderr)
        print("  Start the webhook server first (systemd: notif-webhook.service)", file=sys.stderr)
        sys.exit(1)
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def parse_date_arg(s):
    """Parse YYYY-MM-DD, 'today', 'yesterday', '3d' (N days ago), '6h' (N hours ago) into datetime."""
    now = datetime.now(timezone.utc)
    if s == "today":
        return now.replace(hour=0, minute=0, second=0, microsecond=0)
    if s == "yesterday":
        return (now - timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    m = re.match(r'^(\d+)([dhms])$', s)
    if m:
        n = int(m.group(1))
        unit = m.group(2)
        if unit == 'd':
            return (now - timedelta(days=n)).replace(hour=0, minute=0, second=0, microsecond=0)
        elif unit == 'h':
            return (now - timedelta(hours=n))
        elif unit == 'm':
            return (now - timedelta(minutes=n))
        elif unit == 's':
            return (now - timedelta(seconds=n))
    try:
        parts = s.split("-")
        return datetime(int(parts[0]), int(parts[1]), int(parts[2]), tzinfo=timezone.utc)
    except (ValueError, IndexError):
        print(f"✗ Invalid date: {s} (use YYYY-MM-DD, 'today', 'yesterday', '3d', '6h')", file=sys.stderr)
        sys.exit(1)


def parse_date_end(s):
    d = parse_date_arg(s)
    m = re.match(r'^(\d+)([hms])$', s) if isinstance(s, str) else None
    if m:
        return d
    return d.replace(hour=23, minute=59, second=59, microsecond=999999)


def build_time_filter(since, until):
    clauses = []
    params = []
    if since:
        since_dt = parse_date_arg(since)
        clauses.append("received_at >= ?")
        params.append(int(since_dt.timestamp() * 1000))
    if until:
        until_dt = parse_date_end(until)
        clauses.append("received_at <= ?")
        params.append(int(until_dt.timestamp() * 1000))
    if clauses:
        return " AND " + " AND ".join(clauses), params
    return "", []


def get_category(app_package, app_name):
    if app_package in CATEGORY_MAP:
        return CATEGORY_MAP[app_package]
    name_lower = app_name.lower()
    if any(w in name_lower for w in ["telegram", "whatsapp", "discord", "signal", "chat"]):
        return "messenger"
    if any(w in name_lower for w in ["git", "dev", "code", "github"]):
        return "dev"
    if any(w in name_lower for w in ["mail", "gmail", "outlook"]):
        return "email"
    if any(w in name_lower for w in ["twitter", "instagram", "reddit", "social"]):
        return "social"
    if any(w in name_lower for w in ["gnu", "gnumetrics", "gnusite", "techcd"]):
        return "work"
    return "other"


def cmd_latest(args):
    conn = get_conn()
    n = args.N or 10
    rows = conn.execute(
        "SELECT id, app_name, title, text, timestamp_iso, received_at "
        "FROM notifications ORDER BY received_at DESC LIMIT ?",
        (n,)
    ).fetchall()
    conn.close()
    if not rows:
        print("No notifications")
        return
    print(f"Latest {len(rows)} notifications:\n")
    for r in rows:
        dt = datetime.fromtimestamp(r["received_at"] / 1000, tz=timezone.utc)
        print(f"  #{r['id']} | {r['app_name']:20s} | {dt.strftime('%Y-%m-%d %H:%M:%S')} UTC")
        print(f"       {r['title']}")
        text = r['text'][:120] + ("..." if len(r['text']) > 120 else "")
        if text:
            print(f"       {text}")
        print()


def cmd_today(args):
    args.since = "today"
    args.until = "today"
    args.app = getattr(args, "app", None)
    cmd_report(args)


def cmd_report(args):
    conn = get_conn()
    where, params = build_time_filter(args.since, args.until)
    if args.app:
        app_clause = " AND (app_name LIKE ? OR app_package LIKE ?)"
        params.append(f"%{args.app}%")
        params.append(f"%{args.app}%")
    else:
        app_clause = ""
    count = conn.execute(
        f"SELECT COUNT(*) as cnt FROM notifications WHERE 1=1{where}{app_clause}", params
    ).fetchone()["cnt"]
    rows = conn.execute(
        f"SELECT app_package, app_name, COUNT(*) as cnt, "
        f"MIN(received_at) as first_seen, MAX(received_at) as last_seen "
        f"FROM notifications WHERE 1=1{where}{app_clause} "
        f"GROUP BY app_package, app_name ORDER BY cnt DESC", params
    ).fetchall()
    conn.close()
    period = "all time"
    if args.since and args.until:
        period = f"{args.since} -> {args.until}"
    elif args.since:
        period = f"since {args.since}"
    print(f"Notification report | {period}")
    print(f"   Total: {count} records\n")
    if not rows:
        print("   No data for the selected period.")
        return
    by_cat = {}
    for r in rows:
        cat = get_category(r["app_package"], r["app_name"])
        by_cat.setdefault(cat, []).append(r)
    cat_labels = {"messenger": "Messengers", "dev": "Dev/DevOps", "email": "Email", "social": "Social", "work": "Work", "other": "Other"}
    for cat in ["messenger", "dev", "work", "email", "social", "other"]:
        items = by_cat.get(cat, [])
        if not items:
            continue
        print(f"  {cat_labels.get(cat, cat)}")
        for r in items:
            first = datetime.fromtimestamp(r["first_seen"] / 1000, tz=timezone.utc)
            last = datetime.fromtimestamp(r["last_seen"] / 1000, tz=timezone.utc)
            print(f"    {r['app_name']:25s}  {r['cnt']:4d}  [{first.strftime('%H:%M')} -> {last.strftime('%H:%M')}]")
        print()
    if args.app:
        print(f"\nDetails for '{args.app}':")
        conn = get_conn()
        details = conn.execute(
            f"SELECT id, app_name, title, text, sub_text, timestamp_iso "
            f"FROM notifications WHERE 1=1{where}{app_clause} ORDER BY received_at DESC LIMIT 20", params
        ).fetchall()
        conn.close()
        for d in details:
            print(f"\n  [{d['timestamp_iso']}] {d['title']}")
            text = d['text'][:200] + ("..." if len(d['text']) > 200 else "")
            if text:
                print(f"    {text}")


def cmd_by_app(args):
    conn = get_conn()
    where, params = build_time_filter(args.since, args.until)
    rows = conn.execute(
        f"SELECT app_name, app_package, COUNT(*) as cnt FROM notifications WHERE 1=1{where} "
        f"GROUP BY app_package, app_name ORDER BY cnt DESC", params
    ).fetchall()
    conn.close()
    print(f"Apps by activity:\n")
    for r in rows:
        cat = get_category(r["app_package"], r["app_name"])
        print(f"  {r['app_name']:25s}  {r['cnt']:4d}  [{cat}]")


def cmd_search(args):
    conn = get_conn()
    query = args.QUERY
    like = f"%{query}%"
    rows = conn.execute(
        "SELECT id, app_name, title, text, timestamp_iso, received_at "
        "FROM notifications WHERE title LIKE ? OR text LIKE ? OR app_name LIKE ? "
        "ORDER BY received_at DESC LIMIT 30", (like, like, like)
    ).fetchall()
    conn.close()
    if not rows:
        print(f"Nothing found for '{query}'")
        return
    print(f"Found {len(rows)} results for '{query}':\n")
    for r in rows:
        dt = datetime.fromtimestamp(r["received_at"] / 1000, tz=timezone.utc)
        print(f"  [{dt.strftime('%Y-%m-%d %H:%M')}] {r['app_name']}")
        print(f"    {r['title']}")
        text = r['text'][:150] + ("..." if len(r['text']) > 150 else "")
        if text:
            print(f"    {text}")
        print()


def cmd_stats(args):
    conn = get_conn()
    where, params = build_time_filter(args.since, args.until)
    total = conn.execute(f"SELECT COUNT(*) as c FROM notifications WHERE 1=1{where}", params).fetchone()["c"]
    apps = conn.execute(f"SELECT COUNT(DISTINCT app_package) as c FROM notifications WHERE 1=1{where}", params).fetchone()["c"]
    peak = conn.execute(
        f"SELECT CAST((received_at / 3600000) % 24 AS INTEGER) as hour, COUNT(*) as cnt "
        f"FROM notifications WHERE 1=1{where} GROUP BY hour ORDER BY cnt DESC LIMIT 5", params
    ).fetchall()
    bounds = conn.execute(
        f"SELECT MIN(received_at) as first_ts, MAX(received_at) as last_ts FROM notifications WHERE 1=1{where}", params
    ).fetchone()
    conn.close()
    period = "all time"
    if args.since and args.until:
        period = f"{args.since} -> {args.until}"
    elif args.since:
        period = f"since {args.since}"
    print(f"Statistics | {period}")
    print(f"  Total records: {total}")
    print(f"  Unique apps:   {apps}")
    if bounds["first_ts"]:
        first = datetime.fromtimestamp(bounds["first_ts"] / 1000, tz=timezone.utc)
        last = datetime.fromtimestamp(bounds["last_ts"] / 1000, tz=timezone.utc)
        print(f"  First:         {first.strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"  Last:          {last.strftime('%Y-%m-%d %H:%M:%S')}")
    if peak:
        print(f"\n  Peak hours:")
        for p in peak:
            print(f"    {p['hour']:02d}:00 - {p['cnt']} notif.")


def cmd_export(args):
    conn = get_conn()
    where, params = build_time_filter(args.since, args.until)
    rows = conn.execute(f"SELECT * FROM notifications WHERE 1=1{where} ORDER BY received_at ASC", params).fetchall()
    conn.close()
    if not rows:
        print("No data to export")
        return
    fmt = args.format or "md"
    out_dir = DB_DIR / "exports" / datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    out_dir.mkdir(parents=True, exist_ok=True)
    if fmt == "json":
        out = [{k: v for k, v in dict(r).items() if k not in ("id", "raw_data")} for r in rows]
        (out_dir / "notifications.json").write_text(json.dumps(out, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        print(f"Exported {len(out)} records to {out_dir / 'notifications.json'}")
    else:
        by_cat = {}
        for r in rows:
            cat = get_category(r["app_package"], r["app_name"])
            by_cat.setdefault(cat, []).append(r)
        cat_labels = {"messenger": "Messengers", "dev": "Development", "email": "Email", "social": "Social", "work": "Work Projects", "other": "Other"}
        for cat, items in by_cat.items():
            label = cat_labels.get(cat, cat)
            md_lines = [f"# {label}\n", f"Total: {len(items)} notifications\n", "| Time | App | Title | Text |", "|------|-----|-------|------|"]
            for r in items[:100]:
                dt = datetime.fromtimestamp(r["received_at"] / 1000, tz=timezone.utc)
                title = r["title"].replace("|", "\\|")[:60]
                text = r["text"].replace("\n", " ").replace("|", "\\|")[:100]
                md_lines.append(f"| {dt.strftime('%H:%M:%S')} | {r['app_name']} | {title} | {text} |")
            if len(items) > 100:
                md_lines.append(f"\n*Showing first 100 of {len(items)}*")
            (out_dir / f"{cat}.md").write_text("\n".join(md_lines), encoding="utf-8")
            print(f"{cat}.md - {len(items)} records")
    print(f"\nExported to: {out_dir}")


def main():
    parser = argparse.ArgumentParser(description="Notification Webhook Analyzer")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("latest", help="Show N latest notifications")
    p.add_argument("N", nargs="?", type=int, default=10)
    p.set_defaults(func=cmd_latest)
    p = sub.add_parser("today", help="Notifications from today")
    p.set_defaults(func=cmd_today)
    p = sub.add_parser("report", help="Categorized report")
    p.add_argument("--since", default=None)
    p.add_argument("--until", default=None)
    p.add_argument("--app", default=None)
    p.set_defaults(func=cmd_report)
    p = sub.add_parser("by-app", help="List apps by activity")
    p.add_argument("--since", default=None)
    p.add_argument("--until", default=None)
    p.set_defaults(func=cmd_by_app)
    p = sub.add_parser("search", help="Full-text search")
    p.add_argument("QUERY", help="Search term")
    p.set_defaults(func=cmd_search)
    p = sub.add_parser("stats", help="Summary statistics")
    p.add_argument("--since", default=None)
    p.add_argument("--until", default=None)
    p.set_defaults(func=cmd_stats)
    p = sub.add_parser("export", help="Export notifications")
    p.add_argument("--since", default=None)
    p.add_argument("--until", default=None)
    p.add_argument("--format", choices=["md", "json"], default="md")
    p.set_defaults(func=cmd_export)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
