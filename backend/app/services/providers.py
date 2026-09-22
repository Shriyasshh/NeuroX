"""Production delivery and speech provider clients.

Providers are deliberately server-side and fail closed when credentials are not
configured. Callers should enqueue work and retry ``ProviderError`` rather than
blocking a domain transaction on a remote provider.
"""

from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

from app.secrets import setting


class ProviderError(RuntimeError):
    """A safe, retryable/non-retryable provider failure."""

    def __init__(self, provider: str, message: str, *, retryable: bool = True):
        super().__init__(message)
        self.provider = provider
        self.retryable = retryable


def _post_json(url: str, payload: dict[str, Any], headers: dict[str, str], *, timeout: float) -> dict[str, Any]:
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.fragment
    ):
        raise ProviderError("http", "Provider URL must be a safe HTTPS URL.", retryable=False)
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Accept": "application/json", "Content-Type": "application/json", **headers},
        method="POST",
    )
    try:
        # URL scheme, host, and credentials were validated immediately above.
        with urllib.request.urlopen(request, timeout=timeout) as response:  # nosec B310
            raw = response.read().decode("utf-8")
            if response.status < 200 or response.status >= 300:
                raise ProviderError("http", "Provider rejected the request.", retryable=response.status >= 500)
            try:
                return json.loads(raw) if raw else {}
            except json.JSONDecodeError as error:
                raise ProviderError("http", "Provider returned invalid JSON.", retryable=True) from error
    except urllib.error.HTTPError as error:
        raise ProviderError("http", "Provider request failed.", retryable=error.code >= 500) from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise ProviderError("http", "Provider is unreachable.", retryable=True) from error


@dataclass(frozen=True)
class BhashiniASR:
    """BHASHINI/ULCA synchronous ASR client.

    The pipeline and API key are selected during deployment, never from a
    client request. Audio is sent only after the caller has verified consent.
    """

    user_id: str
    api_key: str
    pipeline_id: str
    inference_url: str
    timeout_seconds: float = 20.0

    SUPPORTED_LANGUAGES = {
        "as", "bn", "brx", "gu", "hi", "kn", "ml", "mni", "mr", "or", "pa", "ta", "te", "ur"
    }

    @classmethod
    def from_environment(cls) -> "BhashiniASR":
        if os.getenv("BHASHINI_ENABLED", "false").lower() != "true":
            raise ProviderError("bhashini", "BHASHINI is not enabled.", retryable=False)
        values = (os.getenv("BHASHINI_USER_ID", "").strip(), setting("BHASHINI_API_KEY"), os.getenv("BHASHINI_PIPELINE_ID", "").strip())
        if not all(values):
            raise ProviderError("bhashini", "BHASHINI credentials are not configured.", retryable=False)
        url = os.getenv("BHASHINI_INFERENCE_URL", "https://dhruva-api.bhashini.gov.in/services/inference/pipeline")
        if not url.startswith("https://"):
            raise ProviderError("bhashini", "BHASHINI inference URL must use HTTPS.", retryable=False)
        return cls(*values, inference_url=url, timeout_seconds=float(os.getenv("BHASHINI_TIMEOUT_SECONDS", "20")))

    def transcribe(self, audio: bytes, *, language_code: str, audio_format: str = "wav", sampling_rate: int = 16000) -> str:
        if not audio:
            raise ProviderError("bhashini", "Audio payload is empty.", retryable=False)
        if len(audio) > 20 * 1024 * 1024:
            raise ProviderError("bhashini", "Audio payload exceeds the safe request limit.", retryable=False)
        language = language_code.split("-", 1)[0].lower()
        if language not in self.SUPPORTED_LANGUAGES:
            raise ProviderError("bhashini", "The requested language is not enabled for BHASHINI.", retryable=False)
        payload = {
            "pipelineTasks": [{"taskType": "asr", "config": {"language": {"sourceLanguage": language}, "serviceId": self.pipeline_id, "audioFormat": audio_format, "samplingRate": sampling_rate}}],
            "inputData": {"audio": [{"audioContent": base64.b64encode(audio).decode("ascii")}]},
        }
        response = _post_json(self.inference_url, payload, {"Authorization": self.api_key, "userID": self.user_id}, timeout=self.timeout_seconds)
        try:
            transcript = response["pipelineResponse"][0]["output"][0]["source"]
        except (KeyError, IndexError, TypeError) as error:
            raise ProviderError("bhashini", "BHASHINI returned no transcript.", retryable=True) from error
        if not isinstance(transcript, str) or not transcript.strip():
            raise ProviderError("bhashini", "BHASHINI returned an empty transcript.", retryable=False)
        return transcript.strip()


@dataclass(frozen=True)
class FcmPush:
    """FCM HTTP v1 sender using a Google service-account JSON secret."""

    project_id: str
    service_account_json: str
    timeout_seconds: float = 10.0

    @classmethod
    def from_environment(cls) -> "FcmPush":
        if os.getenv("FCM_ENABLED", "false").lower() != "true":
            raise ProviderError("fcm", "FCM is not enabled.", retryable=False)
        project_id = os.getenv("FIREBASE_PROJECT_ID", "").strip()
        service_account_json = setting("FIREBASE_SERVICE_ACCOUNT_JSON")
        if not project_id or not service_account_json:
            raise ProviderError("fcm", "Firebase service-account credentials are not configured.", retryable=False)
        return cls(project_id, service_account_json, float(os.getenv("FCM_TIMEOUT_SECONDS", "10")))

    def send(self, token: str, *, title: str, body: str, data: dict[str, str] | None = None) -> str:
        if not token or not title or not body:
            raise ProviderError("fcm", "FCM token, title, and body are required.", retryable=False)
        try:
            from google.oauth2 import service_account
            from google.auth.transport.requests import Request
            credentials = service_account.Credentials.from_service_account_info(json.loads(self.service_account_json), scopes=["https://www.googleapis.com/auth/firebase.messaging"])
            credentials.refresh(Request())
            access_token = credentials.token
        except Exception as error:
            raise ProviderError("fcm", "FCM service-account credentials are invalid.", retryable=False) from error
        response = _post_json(
            f"https://fcm.googleapis.com/v1/projects/{urllib.parse.quote(self.project_id, safe='')}/messages:send",
            {"message": {"token": token, "notification": {"title": title, "body": body}, "data": data or {}}},
            {"Authorization": f"Bearer {access_token}"}, timeout=self.timeout_seconds,
        )
        name = response.get("name")
        if not isinstance(name, str) or not name:
            raise ProviderError("fcm", "FCM returned no message ID.", retryable=True)
        return name


def provider_backoff(attempt: int) -> float:
    """Bounded exponential backoff for an outbox worker."""
    base = max(1.0, float(os.getenv("DELIVERY_RETRY_BACKOFF_SECONDS", "10")))
    return min(base * (2 ** max(0, attempt - 1)), 300.0)
