#!/bin/bash
# ── Notification Webhook — Setup Script ────────────────────────────────────────
# Installs the FastAPI server as a systemd service.
# ────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DB_DIR="${NOTIF_WEBHOOK_DB_DIR:-$HOME/notifwebhook_data}"
PORT="${NOTIF_WEBHOOK_PORT:-8790}"
VENV_PYTHON="${NOTIF_WEBHOOK_PYTHON:-python3}"

# ── 1. Create DB dir ──────────────────────────────────────────────────────────
mkdir -p "$DB_DIR"
echo "✓ DB directory: $DB_DIR"

# ── 2. Generate auth token ────────────────────────────────────────────────────
ENV_FILE="${NOTIF_WEBHOOK_ENV_FILE:-.env}"

if [ -z "${NOTIF_WEBHOOK_AUTH_TOKEN:-}" ]; then
    if [ -f "$ENV_FILE" ]; then
        EXISTING=""
        if grep -q "^NOTIF_WEBHOOK_AUTH_TOKEN=" "$ENV_FILE" 2>/dev/null; then
            EXISTING=$(grep "^NOTIF_WEBHOOK_AUTH_TOKEN=" "$ENV_FILE" | cut -d= -f2-)
        fi
        if [ -n "$EXISTING" ]; then
            AUTH_TOKEN="$EXISTING"
            echo "✓ Using existing auth token from $ENV_FILE"
        fi
    fi
    if [ -z "${AUTH_TOKEN:-}" ]; then
        AUTH_TOKEN=$(python3 -c "import secrets; print(secrets.token_hex(24))")
        echo "NOTIF_WEBHOOK_AUTH_TOKEN=$AUTH_TOKEN" >> "$ENV_FILE"
        echo "✓ Generated new auth token and saved to $ENV_FILE"
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

# ── 4. Print tunnel / exposure info ──────────────────────────────────────────
echo ""
echo "  Webhook endpoint: http://127.0.0.1:$PORT/webhook"
echo "  Health:           http://127.0.0.1:$PORT/health"
echo ""
echo "  To expose publicly, choose one:"
echo ""
echo "  1) Tailscale Funnel (free, HTTPS):"
echo "     sudo tailscale funnel --bg --set-path '/notif-webhook' 'http://127.0.0.1:$PORT'"
echo ""
echo "  2) Cloudflare Tunnel (free, HTTPS, custom domain):"
echo "     cloudflared tunnel --url http://127.0.0.1:$PORT"
echo ""
echo "  3) ngrok (free tier, HTTPS):"
echo "     ngrok http http://127.0.0.1:$PORT"
echo ""
echo "  4) Direct (no HTTPS — LAN only, or behind reverse proxy):"
echo "     Set NOTIF_WEBHOOK_BIND=0.0.0.0 and open firewall port $PORT"
echo "     Then: http://<your-ip>:$PORT/webhook"
echo ""

# ── 5. Test ───────────────────────────────────────────────────────────────────
echo "Testing health endpoint..."
sleep 1
curl -sf "http://127.0.0.1:$PORT/health" && echo "" && echo "✓ Health check OK" || echo "⚠ Health check failed"