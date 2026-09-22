from fastapi import APIRouter, Depends

from app.rate_limit import speech_transcription_limit
from app.schemas import SpeechTranscriptionResponse
from app.services import speech

router = APIRouter(tags=["speech"])
router.add_api_route(
    "/speech/transcriptions",
    speech.transcribe,
    methods=["POST"],
    response_model=SpeechTranscriptionResponse,
    dependencies=[Depends(speech_transcription_limit)],
)
