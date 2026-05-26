#!/bin/bash
# ── Notification Webhook — Setup Script ────────────────────────────────────────
# Installs the FastAPI server as a systemd service.
# ────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DB_DIR="${NOTIF_WEBHOOK_DB_DIR:-$HOME/.hermes/data}"
PORT="${NOTIF_WEBHOOK_PORT:-8790}"
VENV_PYTHON="${HERMES_VENV_PYTHON:-$HOME/.hermes/hermes-agent/venv/bin/python3}"

# ── 1. Create DB dir ──────────────────────────────────────────────────────────
mkdir -p "$DB_DIR"
echo "✓ DB directory: $DB_DIR"

# ── 2. Generate auth token ────────────────────────────────────────────────────
if [ -z "${NOTIF_WEBHOOK_AUTH_TOKEN:-}" ]; then
    if [ -f "$HOME/.hermes/.env" ]; then
        EXISTING=""
        if grep -q "^NOTIF_WEBHOOK_AUTH_TOKEN=" "$HOME/.hermes/.env" 2>/dev/null; then
            EXISTING=$(grep "^NOTIF_WEBHOOK_AUTH_TOKEN=" "$HOME/.hermes/.env" | cut -d= -f2-)
        fi
        if [ -n "$EXISTING" ]; then
            AUTH_TOKEN="$EXISTING"
            echo "✓ Using existing auth token from .env"
        fi
    fi
    if [ -z "${AUTH_TOKEN:-}" ]; then
        AUTH_TOKEN=$(python3 -c "import secrets; print(secrets.token_hex(24))")
        echo "NOTIF_WEBHOOK_AUTH_TOKEN=$AUTH_TOKEN" >> "$HOME/.hermes/.env"
        echo "✓ Generated new auth token and saved to ~/.hermes/.env"
    fi
else
    AUTH_TOKEN="$NOTIF_WEBHOOK_AUTH_TOKEN"
fi

# ── 3. Install & start systemd service ────────────────────────────────────────
SERVICE_NAME="notif-webhook.service"
SERVICE_PATH="/etc/systemd/system/$SERVICE_NAME"

sudo tee "$SERVICE_PATH" > /dev/null <<SERVICEEOF
[Unit]
Description=NotifWebhook Receiver — stores Android notifications in SQLite
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER
Group=$USER
WorkingDirectory=$SCRIPT_DIR
ExecStart=$VENV_PYTHON $SCRIPT_DIR/server.py
Environment=NOTIF_WEBHOOK_PORT=$PORT
Environment=NOTIF_WEBHOOK_BIND=127.0.0.1
Environment=NOTIF_WEBHOOK_DB_DIR=$DB_DIR
Environment=NOTIF_WEBHOOK_AUTH_TOKEN=$AUTH_TOKEN
Environment=PATH=$HOME/.hermes/hermes-agent/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
SERVICEEOF

sudo systemctl daemon-reload
sudo systemctl enable "$SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

echo "✓ Service $SERVICE_NAME installed and started"

sleep 2
if sudo systemctl is-active --quiet "$SERVICE_NAME"; then
    echo "✓ Service is active"
else
    echo "⚠ Service may have failed — check: sudo journalctl -u $SERVICE_NAME -n 30"
fi

# ── 4. Print funnel info ──────────────────────────────────────────────────────
FUNNEL_PATH="/notif-webhook"
FUNNEL_TARGET="http://127.0.0.1:$PORT"

echo ""
echo "  To expose via tailscale funnel, run:"
echo "    sudo tailscale funnel --bg --set-path '$FUNNEL_PATH' '$FUNNEL_TARGET'"
echo ""
echo "  After that, your webhook URL will be:"
echo "    https://<tailscale-hostname>.ts.net$FUNNEL_PATH/webhook"
echo ""
echo "  With auth token header:"
echo "    Authorization: Bearer <token from .env>"

# ── 5. Test ───────────────────────────────────────────────────────────────────
echo ""
echo "Testing health endpoint..."
sleep 1
curl -sf "http://127.0.0.1:$PORT/health" && echo "" && echo "✓ Health check OK" || echo "⚠ Health check failed"
