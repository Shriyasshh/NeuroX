from app.auth import hash_password
from app.database import SessionLocal
from app.main import app
from app.models import CaregiverPatientAssignment, Patient, User
from app.routers import cognitive
from app.schemas import Role
from fastapi.testclient import TestClient


def _ensure_screening_fixture() -> None:
    """Make this integration test independent of demo-seed/test ordering."""
    with SessionLocal() as db:
        patient = db.get(User, "maya-demo")
        if patient is None:
            patient = User(
                id="maya-demo",
                name="Maya Devi",
                email="maya@neurox.demo",
                role=Role.PATIENT.value,
                password_hash=hash_password("NeuroXDemo!2026"),
            )
            db.add(patient)
        else:
            patient.email = "maya@neurox.demo"
            patient.role = Role.PATIENT.value
            patient.password_hash = hash_password("NeuroXDemo!2026")

        caregiver = db.get(User, "caregiver-anita")
        if caregiver is None:
            caregiver = db.query(User).filter(User.email == "anita@neurox.demo").first()
        if caregiver is None:
            caregiver = User(
                id="caregiver-anita",
                name="Anita Devi",
                email="anita@neurox.demo",
                role=Role.CAREGIVER.value,
                password_hash=hash_password("NeuroXDemo!2026"),
            )
            db.add(caregiver)
        else:
            caregiver.email = "anita@neurox.demo"
            caregiver.role = Role.CAREGIVER.value
            caregiver.password_hash = hash_password("NeuroXDemo!2026")

        db.flush()
        if db.get(Patient, "maya-demo") is None:
            db.add(Patient(user_id="maya-demo", age=72, preferred_language="Assamese"))
        assignment = db.query(CaregiverPatientAssignment).filter_by(
            caregiver_id=caregiver.id, patient_id="maya-demo"
        ).first()
        if assignment is None:
            db.add(CaregiverPatientAssignment(
                caregiver_id=caregiver.id, patient_id="maya-demo", active=True
            ))
        else:
            assignment.active = True
        db.commit()


def _auth(client: TestClient, email: str) -> dict[str, str]:
    response = client.post(
        "/api/v1/auth/login",
        json={"email": email, "password": "NeuroXDemo!2026"},
    )
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


def test_screening_requires_consent_and_returns_research_only_result(monkeypatch):
    _ensure_screening_fixture()
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
