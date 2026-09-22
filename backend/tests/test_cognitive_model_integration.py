from fastapi.testclient import TestClient

from app.main import app
from app.routers import cognitive


def _auth(client: TestClient, email: str) -> dict[str, str]:
    response = client.post(
        "/api/v1/auth/login",
        json={"email": email, "password": "NeuroXDemo!2026"},
    )
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


def test_screening_requires_consent_and_returns_research_only_result(monkeypatch):
    monkeypatch.setattr(cognitive, "predict", lambda _: {
        "low_delayed_recall_probability": 0.31,
        "model": "test-model-v1",
        "target": "low delayed-word recall",
        "disclaimer": "Research only; not a diagnosis.",
    })
    with TestClient(app) as client:
        patient = _auth(client, "maya@neurox.demo")
        caregiver = _auth(client, "anita@neurox.demo")
        consent_url = "/api/v1/patients/me/privacy/consents"
        endpoint = "/api/v1/patients/maya-demo/cognitive-screening"

        denied_consent = client.put(consent_url, headers=patient, json={
            "purpose": "cognitive_screening", "granted": False, "notice_version": "research-v1",
        })
        assert denied_consent.status_code == 200
        assert client.post(endpoint, headers=caregiver, json={"years_schooling": 8}).status_code == 403

        granted_consent = client.put(consent_url, headers=patient, json={
            "purpose": "cognitive_screening", "granted": True, "notice_version": "research-v1",
        })
        assert granted_consent.status_code == 200
        result = client.post(endpoint, headers=caregiver, json={"years_schooling": 8})
        assert result.status_code == 200, result.text
        assert result.json()["low_delayed_recall_probability"] == 0.31
        assert "not a diagnosis" in result.json()["disclaimer"]


def test_unassigned_caregiver_cannot_request_screening():
    with TestClient(app) as test_client:
        registration = test_client.post("/api/v1/auth/register", json={
            "name": "Unassigned Model User",
            "email": "unassigned-model@neurox.test",
            "password": "ModelTest!2026",
            "role": "CAREGIVER",
        })
        if registration.status_code == 409:
            login = test_client.post("/api/v1/auth/login", json={
                "email": "unassigned-model@neurox.test", "password": "ModelTest!2026",
            })
            token = login.json()["access_token"]
        else:
            token = registration.json()["access_token"]
        response = test_client.post(
            "/api/v1/patients/maya-demo/cognitive-screening",
            headers={"Authorization": f"Bearer {token}"},
            json={"age": 70},
        )
        assert response.status_code == 403
