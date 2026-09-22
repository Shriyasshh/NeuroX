import os
from datetime import datetime, timedelta, timezone

from sqlalchemy.orm import Session

from app.auth import hash_password
from app.config import (
    DEMO_CAREGIVER_EMAIL,
    DEMO_CAREGIVER_PASSWORD,
    DEMO_PATIENT_EMAIL,
    DEMO_PATIENT_PASSWORD,
    SEED_DEMO_DATA,
)
from app.database import Base, engine
from app.models import (
    CaregiverPatientAssignment,
    EmergencyContact,
    LocationUpdate,
    Patient,
    Reminder,
    SafetySettings,
    User,
)
from app.schemas import Role


def _seed_database() -> None:
    """Idempotent development fixture seed; never runs outside development."""
    # Evaluate APP_ENV at call time as well as at import time.  This keeps a
    # long-running process (and tests that change the environment) from ever
    # creating tables or seeding fixtures after it has entered production.
    if os.getenv("APP_ENV", "development").lower() != "development" or not SEED_DEMO_DATA:
        return
    Base.metadata.create_all(bind=engine)
    with Session(bind=engine) as db:
        if not db.query(User).filter(User.email == DEMO_CAREGIVER_EMAIL).first():
            db.add(
                User(
                    id="caregiver-anita",
                    name="Anita Devi",
                    email=DEMO_CAREGIVER_EMAIL,
                    role=Role.CAREGIVER.value,
                    password_hash=hash_password(DEMO_CAREGIVER_PASSWORD),
                )
            )
            db.commit()
        if not db.query(User).filter(User.email == DEMO_PATIENT_EMAIL).first():
            db.add(
                User(
                    id="maya-demo",
                    name="Maya Devi",
                    email=DEMO_PATIENT_EMAIL,
                    role=Role.PATIENT.value,
                    password_hash=hash_password(DEMO_PATIENT_PASSWORD),
                )
            )
            db.commit()
        if not db.get(Patient, "maya-demo"):
            db.add(Patient(user_id="maya-demo", age=72, preferred_language="Assamese"))
        if (
            not db.query(CaregiverPatientAssignment)
            .filter_by(caregiver_id="caregiver-anita", patient_id="maya-demo")
            .first()
        ):
            db.add(
                CaregiverPatientAssignment(
                    caregiver_id="caregiver-anita", patient_id="maya-demo"
                )
            )
        db.commit()
        if not db.query(Reminder).filter(Reminder.patient_id == "maya-demo").first():
            today = datetime.now(timezone.utc).replace(
                hour=9, minute=0, second=0, microsecond=0
            )
            db.add_all(
                [
                    Reminder(
                        patient_id="maya-demo",
                        type="medication",
                        title="Medication",
                        description="Take morning medicine",
                        scheduled_time=today,
                        repeat_rule="daily",
                        completed=True,
                    ),
                    Reminder(
                        patient_id="maya-demo",
                        type="hydration",
                        title="Hydration",
                        description="Have a glass of water",
                        scheduled_time=today + timedelta(hours=1, minutes=30),
                        repeat_rule="daily",
                    ),
                ]
            )
            db.commit()
        if not db.query(EmergencyContact).filter_by(patient_id="maya-demo").first():
            db.add_all(
                [
                    EmergencyContact(
                        patient_id="maya-demo",
                        name="Anita Devi",
                        phone="+91 98765 43210",
                        relationship="Primary caregiver",
                        priority=1,
                    ),
                    EmergencyContact(
                        patient_id="maya-demo",
                        name="Rohan Devi",
                        phone="+91 98765 43211",
                        relationship="Secondary caregiver",
                        priority=2,
                    ),
                ]
            )
        if not db.get(SafetySettings, "maya-demo"):
            db.add(
                SafetySettings(
                    patient_id="maya-demo",
                    safe_zone_name="Home safe zone",
                    safe_zone_latitude=26.1445,
                    safe_zone_longitude=91.7362,
                    safe_zone_radius_m=250,
                    expected_return_at=datetime.now(timezone.utc) + timedelta(hours=2),
                    expected_return_note="Evening walk",
                    location_sharing_enabled=True,
                )
            )
        if not db.query(LocationUpdate).filter_by(patient_id="maya-demo").first():
            db.add(
                LocationUpdate(
                    patient_id="maya-demo",
                    latitude=26.1447,
                    longitude=91.7364,
                    accuracy_m=18,
                    connection_state="online",
                    captured_at=datetime.now(timezone.utc) - timedelta(minutes=2),
                )
            )
        db.commit()
