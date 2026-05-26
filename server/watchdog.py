#!/usr/bin/env python3
"""
Notification Webhook Watchdog — cron-based notification monitor.

WORKFLOW:
  1. Read checkpoint (last_processed_id) from state file
  2. Fetch new notifications (id > checkpoint)
  3. Apply importance rules (always_include / always_skip)
  4. If nothing important -> stay silent
  5. If important content found -> print summary (cron delivers it)
  6. Update checkpoint to max(id) — done last, so if analysis crashes, nothing lost

MODES:
  • Default (no flag): filter -> eval importance -> generate text summary
  • --json:            filter -> eval importance -> dedup -> group by app_package
                       -> output JSON for LLM summarization

STATE FILE: ~/.hermes/data/notif_webhook_watchdog.json
"""

import argparse
import json
import os
import sys
import sqlite3
from pathlib import Path
from datetime import datetime, timezone

# ── Config ────────────────────────────────────────────────────────────────────
DB_DIR = Path(os.environ.get("NOTIF_WEBHOOK_DB_DIR", str(Path.home() / ".hermes" / "data")))
DB_PATH = DB_DIR / "notif_webhook.db"
STATE_FILE = DB_DIR / "notif_webhook_watchdog.json"

# ── Default importance rules ──────────────────────────────────────────────────
DEFAULT_RULES = {
    "always_include": {
        "patterns": [
            "deploy", "deployment", "rollback", "release",
            "error", "fail", "failed", "failure", "crash",
            "502", "503", "504", "500", "timeout",
            "nginx", "apache", "caddy",
            "server", "ssl", "tls", "certificate",
            "migration",
            "checks passed", "checks fail", "CI", "merge ready",
            "workflow", "action",
            "security", "vuln", "breach", "alert",
            "load", "slow",
        ],
        "apps": [
            "github", "GitHub", "GitLab",
        ],
    },
    "always_skip": {
        "patterns": [
            "test notification", "test", "connection works",
            "thanks", "ok", "okay", "yes", "no",
            "joined", "left", "removed",
        ],
        "title": [
            "Hermes",
            "Claw",
        ],
    },
    "default_importance": "low",
    "medium_apps": ["Telegram", "Discord", "WhatsApp"],
}

# ── State management ──────────────────────────────────────────────────────────


def load_state():
    defaults = {
        "last_processed_id": 0,
        "importance_rules": DEFAULT_RULES.copy(),
        "version": 2,
    }
    if not STATE_FILE.exists():
        return defaults
    try:
        data = json.loads(STATE_FILE.read_text())
        result = defaults.copy()
        result.update(data)
        if "importance_rules" in data:
            for key in ["always_include", "always_skip"]:
                if key in data["importance_rules"]:
                    result["importance_rules"][key] = data["importance_rules"][key]
        return result
    except (json.JSONDecodeError, KeyError):
        return defaults


def save_state(state: dict):
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2))
    tmp.rename(STATE_FILE)


# ── Importance evaluation ────────────────────────────────────────────────────


def is_important(notification: dict, rules: dict) -> tuple:
    text = (notification.get("title", "") + " " + notification.get("text", "")).lower()
    app_name = notification.get("app_name", "")
    title = notification.get("title", "")

    include = rules.get("always_include", {})
    skip = rules.get("always_skip", {})
    default = rules.get("default_importance", "low")
    medium_apps = rules.get("medium_apps", [])

    # 1. Check skip patterns first
    for pat in skip.get("patterns", []):
        if pat.lower() in text:
            return (False, f"skip-pattern: '{pat}'")
    for sender in skip.get("title", []):
        if sender.lower() in title.lower():
            return (False, f"skip-title: '{sender}'")

    # 2. Check include patterns
    for pat in include.get("patterns", []):
        if pat.lower() in text:
            return (True, f"include-pattern: '{pat}'")
    for app in include.get("apps", []):
        if app.lower() in app_name.lower():
            return (True, f"include-app: '{app}'")

    # 3. Default importance based on app
    for app in medium_apps:
        if app.lower() in app_name.lower():
            return (default == "medium" or default == "high", f"default-{default} for app")

    # 4. Fallback
    return (default == "high", f"default-{default}")


# ── Report generation ────────────────────────────────────────────────────────


def generate_summary(important_notifs: list, new_count: int) -> str:
    lines = []
    by_app = {}
    for n in important_notifs:
        app = n["app_name"]
        if app not in by_app:
            by_app[app] = []
        by_app[app].append(n)

    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    total_important = len(important_notifs)
    lines.append(f"Notifications [{now}]")
    lines.append(f"New: {new_count} | Important: {total_important}")
    lines.append("")

    for app, notifs in sorted(by_app.items(), key=lambda x: -len(x[1])):
        lines.append(f"**{app}** — {len(notifs)}:")
        for n in notifs[:10]:
            title = n["title"]
            text = n["text"][:150] + ("..." if len(n["text"]) > 150 else "")
            if text:
                lines.append(f"  {title}")
                lines.append(f"    {text}")
            else:
                lines.append(f"  {title}")
        if len(notifs) > 10:
            lines.append(f"  ... and {len(notifs) - 10} more")
        lines.append("")

    return "\n".join(lines)


# ── JSON mode ─────────────────────────────────────────────────────────────────


def run_watchdog_json() -> str | None:
    if not DB_PATH.exists():
        return

    state = load_state()
    checkpoint = state["last_processed_id"]
    rules = state["importance_rules"]

    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row

    rows = conn.execute(
        "SELECT * FROM notifications WHERE id > ? ORDER BY id ASC",
        (checkpoint,)
    ).fetchall()

    new_count = len(rows)
    if new_count == 0:
        conn.close()
        return

    max_id = max(r["id"] for r in rows)
    from_ts = rows[0]["timestamp_iso"] or rows[0]["received_at"]
    to_ts = rows[-1]["timestamp_iso"] or rows[-1]["received_at"]

    important = []
    for r in rows:
        n = dict(r)
        imp, reason = is_important(n, rules)
        if imp:
            n["_reason"] = reason
            important.append(n)

    state["last_processed_id"] = max_id
    save_state(state)
    conn.close()

    if not important:
        return

    seen = set()
    deduped = []
    for n in important:
        key = (n.get("title", ""), n.get("text", ""))
        if key not in seen:
            seen.add(key)
            deduped.append(n)

    groups = {}
    for n in deduped:
        pkg = n.get("app_package", "unknown")
        if pkg not in groups:
            groups[pkg] = {
                "app_name": n.get("app_name", pkg),
                "notifications": []
            }
        groups[pkg]["notifications"].append({
            "title": n.get("title", ""),
            "text": n.get("text", ""),
            "sub_text": n.get("sub_text", ""),
            "timestamp_iso": n.get("timestamp_iso", ""),
            "reason": n.get("_reason", ""),
        })

    result = {
        "period": {"from": str(from_ts), "to": str(to_ts)},
        "total_new": new_count,
        "total_important": len(important),
        "total_deduped": len(deduped),
        "groups": groups,
    }

    return json.dumps(result, ensure_ascii=False, indent=2)


# ── Main watchdog run ────────────────────────────────────────────────────────


def run_watchdog():
    if not DB_PATH.exists():
        return

    state = load_state()
    checkpoint = state["last_processed_id"]
    rules = state["importance_rules"]

    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row

    rows = conn.execute(
        "SELECT * FROM notifications WHERE id > ? ORDER BY id ASC",
        (checkpoint,)
    ).fetchall()

    new_count = len(rows)
    if new_count == 0:
        conn.close()
        return

    max_id = max(r["id"] for r in rows)

    important = []
    for r in rows:
        n = dict(r)
        imp, reason = is_important(n, rules)
        if imp:
            n["_reason"] = reason
            important.append(n)

    state["last_processed_id"] = max_id
    save_state(state)
    conn.close()

    if not important:
        return

    return generate_summary(important, new_count)


# ── Learning commands ────────────────────────────────────────────────────────


def cmd_learn(args):
    state = load_state()
    rules = state["importance_rules"]
    changed = False

    if args.important:
        pat = args.important.strip().lower()
        if pat and pat not in rules["always_include"]["patterns"]:
            rules["always_include"]["patterns"].append(pat)
            changed = True
            print(f"✓ Added to always_include: '{pat}'")

    if args.skip:
        pat = args.skip.strip().lower()
        if pat and pat not in rules["always_skip"]["patterns"]:
            rules["always_skip"]["patterns"].append(pat)
            changed = True
            print(f"✓ Added to always_skip: '{pat}'")

    if args.important_app:
        app = args.important_app.strip()
        if app and app not in rules["always_include"]["apps"]:
            rules["always_include"]["apps"].append(app)
            changed = True
            print(f"✓ Added app to always_include: '{app}'")

    if args.skip_app:
        app = args.skip_app.strip()
        if app and app not in rules["always_skip"].get("apps", []):
            rules["always_skip"].setdefault("apps", []).append(app)
            changed = True
            print(f"✓ Added app to always_skip: '{app}'")

    if args.skip_title:
        sender = args.skip_title.strip()
        if sender and sender not in rules["always_skip"].get("title", []):
            rules["always_skip"].setdefault("title", []).append(sender)
            changed = True
            print(f"✓ Added to skip.title: '{sender}'")

    if changed:
        state["importance_rules"] = rules
        save_state(state)
    else:
        print(" Nothing changed — rule already exists")


def cmd_rules(args):
    state = load_state()
    rules = state["importance_rules"]

    print(f"Importance rules (checkpoint: #{state['last_processed_id']})")
    print()

    print("Always include (patterns):")
    for p in sorted(rules["always_include"]["patterns"]):
        print(f"  - {p}")
    print()

    print("Always include (apps):")
    for a in sorted(rules["always_include"]["apps"]):
        print(f"  - {a}")
    print()

    print("Always skip (patterns):")
    for p in sorted(rules["always_skip"]["patterns"]):
        print(f"  - {p}")
    print()

    print("Always skip (title):")
    for a in sorted(rules["always_skip"].get("title", [])):
        print(f"  - {a}")
    print()

    print(f"Default importance: {rules.get('default_importance', 'low')}")
    print(f"Medium apps: {', '.join(rules.get('medium_apps', []))}")


def cmd_reset_checkpoint(args):
    state = load_state()
    state["last_processed_id"] = 0
    save_state(state)
    print("Checkpoint reset. All records will be re-processed on next run.")


def cmd_set_checkpoint(args):
    state = load_state()
    state["last_processed_id"] = args.id
    save_state(state)
    print(f"Checkpoint set to #{args.id}")


# ── Main ──────────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description="NotifWebhook Watchdog")
    parser.add_argument("--json", action="store_true",
                        help="JSON mode: filter -> dedup -> group by app_package -> output JSON")
    sub = parser.add_subparsers(dest="command", required=False)

    parser.set_defaults(func=lambda a: None)

    p = sub.add_parser("learn", help="Train importance rules")
    p.add_argument("--important", help="Add pattern to always_include")
    p.add_argument("--skip", help="Add pattern to always_skip")
    p.add_argument("--important-app", help="Add app to always_include")
    p.add_argument("--skip-app", help="Add app to always_skip")
    p.add_argument("--skip-title", help="Add to skip.title")
    p.set_defaults(func=cmd_learn)

    p = sub.add_parser("rules", help="Show current rules")
    p.set_defaults(func=cmd_rules)

    p = sub.add_parser("reset-checkpoint", help="Reset checkpoint to 0")
    p.set_defaults(func=cmd_reset_checkpoint)

    p = sub.add_parser("set-checkpoint", help="Set checkpoint to specific ID")
    p.add_argument("id", type=int, help="Notification ID")
    p.set_defaults(func=cmd_set_checkpoint)

    args = parser.parse_args()

    if args.command is None:
        if args.json:
            result = run_watchdog_json()
        else:
            result = run_watchdog()
        if result:
            print(result)
    else:
        args.func(args)


if __name__ == "__main__":
    main()
