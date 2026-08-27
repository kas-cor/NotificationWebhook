import importlib
import sys
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).parents[1]))


def load_server(monkeypatch, tmp_path):
    monkeypatch.setenv("NOTIF_WEBHOOK_DB_DIR", str(tmp_path))
    monkeypatch.setenv("NOTIF_WEBHOOK_AUTH_TOKEN", "test-token")
    import server as server_module
    return importlib.reload(server_module)


def test_webhook_requires_bearer_token(monkeypatch, tmp_path):
    module = load_server(monkeypatch, tmp_path)
    with TestClient(module.app) as client:
        response = client.post("/webhook", json={"app_package": "com.test", "app_name": "Test"})
    assert response.status_code == 401


def test_webhook_rejects_invalid_payload(monkeypatch, tmp_path):
    module = load_server(monkeypatch, tmp_path)
    with TestClient(module.app) as client:
        response = client.post(
            "/webhook",
            headers={"Authorization": "Bearer test-token"},
            json={"app_name": "Test"},
        )
    assert response.status_code == 400


def test_webhook_stores_valid_payload(monkeypatch, tmp_path):
    module = load_server(monkeypatch, tmp_path)
    payload = {"app_package": "com.test", "app_name": "Test", "title": "Hello"}
    with TestClient(module.app) as client:
        response = client.post(
            "/webhook",
            headers={"Authorization": "Bearer test-token"},
            json=payload,
        )
    assert response.status_code == 200
    assert response.json() == {"ok": True, "id": 0}
