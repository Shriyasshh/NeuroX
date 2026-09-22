"""Consent-gated, ephemeral speech transcription service."""

import base64
import binascii

from fastapi import Depends, HTTPException
from sqlalchemy.orm import Session

from app.access import patient_only
from app.audit import record
from app.database import get_db
from app.models import ConsentRecord, User
from app.schemas import SpeechTranscriptionRequest
from app.services.providers import BhashiniASR, ProviderError

MAX_AUDIO_BYTES = 750_000


def _has_voice_consent(db: Session, patient_id: str) -> bool:
    latest = (
        db.query(ConsentRecord)
        .filter_by(patient_id=patient_id, purpose="voice_recording")
        .order_by(ConsentRecord.recorded_at.desc(), ConsentRecord.id.desc())
        .first()
    )
    return bool(latest and latest.granted)


def transcribe(
    request: SpeechTranscriptionRequest,
    user: User = Depends(patient_only),
    db: Session = Depends(get_db),
):
    if not _has_voice_consent(db, user.id):
        raise HTTPException(
            status_code=403,
            detail="Voice recognition consent is required. Enable it in Privacy & sharing.",
        )
    try:
        audio = base64.b64decode(request.audio_base64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=422, detail="Audio must be valid base64.") from exc
    if len(audio) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Audio clip is too large.")
    if len(audio) < 44 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
        raise HTTPException(status_code=422, detail="Audio must be a valid WAV clip.")

    try:
        transcript = BhashiniASR.from_environment().transcribe(
            audio,
            language_code=request.language_code,
            audio_format=request.audio_format,
            sampling_rate=request.sampling_rate,
        )
    except ProviderError as exc:
        if not exc.retryable and "language" in str(exc).lower():
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        raise HTTPException(
            status_code=503,
            detail="Speech recognition is temporarily unavailable.",
        ) from exc

    # Deliberately record only operational metadata. Raw audio and transcript
    # are never persisted by this endpoint.
    record(
        db,
        actor_id=user.id,
        patient_id=user.id,
        action="speech.transcribed",
        target_id="bhashini",
        metadata={
            "language": request.language_code,
            "audio_format": request.audio_format,
            "sampling_rate": request.sampling_rate,
            "retained": False,
        },
    )
    db.commit()
    return {"transcript": transcript, "provider": "bhashini", "retained": False}
