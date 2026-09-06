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
    body = response.json()
    assert body["ok"] is True
    assert body["id"] == 0
    assert body["status"] == "pending"
    assert body["classification_id"]


def test_classification_pending_then_dismiss(monkeypatch, tmp_path):
    module = load_server(monkeypatch, tmp_path)
    payload = {"app_package": "com.promo", "app_name": "Shop", "title": "Sale"}
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(module.app) as client:
        r = client.post("/webhook", headers=headers, json=payload)
        cid = r.json()["classification_id"]

        # pending before agent decides
        r = client.get(f"/classification/{cid}", headers=headers)
        assert r.status_code == 200
        assert r.json()["status"] == "pending"
        assert r.json()["action"] is None

        # agent marks dismiss
        r = client.post(f"/classification/{cid}/dismiss", headers=headers)
        assert r.status_code == 200
        assert r.json()["action"] == "dismiss"

        # now done + dismiss
        r = client.get(f"/classification/{cid}", headers=headers)
        assert r.status_code == 200
        assert r.json()["status"] == "done"
        assert r.json()["action"] == "dismiss"


def test_classification_unknown_404(monkeypatch, tmp_path):
    module = load_server(monkeypatch, tmp_path)
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(module.app) as client:
        assert client.get("/classification/nope", headers=headers).status_code == 404
        assert client.post("/classification/nope/dismiss", headers=headers).status_code == 404


def test_classification_requires_auth(monkeypatch, tmp_path):
    module = load_server(monkeypatch, tmp_path)
    with TestClient(module.app) as client:
        assert client.get("/classification/abc").status_code == 401
        assert client.post("/classification/abc/dismiss").status_code == 401


def test_classification_ttl_fail_open(monkeypatch, tmp_path):
    monkeypatch.setenv("NOTIF_WEBHOOK_CLASSIFICATION_TTL_MS", "-1")  # already expired
    module = load_server(monkeypatch, tmp_path)
    payload = {"app_package": "com.promo", "app_name": "Shop", "title": "Sale"}
    headers = {"Authorization": "Bearer test-token"}
    with TestClient(module.app) as client:
        r = client.post("/webhook", headers=headers, json=payload)
        cid = r.json()["classification_id"]
        r = client.get(f"/classification/{cid}", headers=headers)
        assert r.status_code == 200
        assert r.json()["status"] == "done"
        assert r.json()["action"] == "keep"  # fail-open: never swipe unconfirmed
