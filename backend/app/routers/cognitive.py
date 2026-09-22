from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.access import patient_access
from app.audit import record
from app.database import get_db
from app.models import ConsentRecord, Patient, User
from app.rate_limit import cognitive_screening_limit
from app.services.cognitive_model import predict, status


class CognitiveScreeningRequest(BaseModel):
    age: float | None = Field(default=None, ge=0, le=130)
    years_schooling: float | None = Field(default=None, ge=0, le=100)
    literacy: float | None = Field(default=None)
    residence: float | None = Field(default=None)
    state: str | None = Field(default=None, max_length=100)


router = APIRouter(tags=["cognitive-research"])


def _screening_consent_granted(patient_id: str, db: Session) -> bool:
    latest = (
        db.query(ConsentRecord)
        .filter_by(patient_id=patient_id, purpose="cognitive_screening")
        .order_by(ConsentRecord.recorded_at.desc())
        .first()
    )
    return bool(latest and latest.granted)


@router.get("/patients/{patient_id}/cognitive-model/status")
def cognitive_model_status(patient_id: str, _: User = Depends(patient_access)):
    return status()


@router.post(
    "/patients/{patient_id}/cognitive-screening",
    dependencies=[Depends(cognitive_screening_limit)],
)
def cognitive_screening(
    patient_id: str,
    request: CognitiveScreeningRequest,
    actor: User = Depends(patient_access),
    db: Session = Depends(get_db),
):
    if not _screening_consent_granted(patient_id, db):
        raise HTTPException(status_code=403, detail="Patient consent for cognitive screening is required.")
    patient = db.get(Patient, patient_id)
    age = request.age if request.age is not None else (patient.age if patient else None)
    if age is None:
        raise HTTPException(status_code=422, detail="Patient age is required for this research estimate.")
    try:
        result = predict({
            "dm005": age,
            "dm007": request.years_schooling,
            "dm009": request.literacy,
            "residence": request.residence,
            "state": request.state,
        })
    except (FileNotFoundError, ValueError) as exc:
        raise HTTPException(status_code=503, detail="Research model is not installed on this server.") from exc
    record(
        db,
        actor_id=actor.id,
        patient_id=patient_id,
        action="cognitive_screening.generated",
        target_id=patient_id,
        metadata={"model": result["model"], "research_only": True},
    )
    db.commit()
    return {"patientId": patient_id, **result}
