import base64
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from fastapi import HTTPException

from app.schemas import SpeechTranscriptionRequest
from app.services import speech


def _wav() -> bytes:
    pcm = b"\x01\x00" * 160
    size = len(pcm)
    return (
        b"RIFF" + (36 + size).to_bytes(4, "little") + b"WAVEfmt "
        + (16).to_bytes(4, "little") + (1).to_bytes(2, "little")
        + (1).to_bytes(2, "little") + (16000).to_bytes(4, "little")
        + (32000).to_bytes(4, "little") + (2).to_bytes(2, "little")
        + (16).to_bytes(2, "little") + b"data" + size.to_bytes(4, "little") + pcm
    )


def _request() -> SpeechTranscriptionRequest:
    return SpeechTranscriptionRequest(
        audio_base64=base64.b64encode(_wav()).decode("ascii"),
        language_code="as-IN",
    )


def _db(consent: bool) -> MagicMock:
    db = MagicMock()
    db.query.return_value.filter_by.return_value.order_by.return_value.first.return_value = (
        SimpleNamespace(granted=consent)
    )
    return db


def test_transcription_requires_latest_voice_consent():
    with pytest.raises(HTTPException) as error:
        speech.transcribe(_request(), user=SimpleNamespace(id="patient-1"), db=_db(False))
    assert error.value.status_code == 403


def test_transcription_is_ephemeral_and_audited(monkeypatch):
    provider = MagicMock()
    provider.transcribe.return_value = "মোৰ সোঁৱৰণী দেখুৱাওক"
    monkeypatch.setattr(speech.BhashiniASR, "from_environment", lambda: provider)
    db = _db(True)

    result = speech.transcribe(_request(), user=SimpleNamespace(id="patient-1"), db=db)

    assert result == {
        "transcript": "মোৰ সোঁৱৰণী দেখুৱাওক",
        "provider": "bhashini",
        "retained": False,
    }
    audit_event = db.add.call_args.args[0]
    assert audit_event.action == "speech.transcribed"
    assert audit_event.metadata_json["retained"] is False
    assert "transcript" not in audit_event.metadata_json
    db.commit.assert_called_once()
