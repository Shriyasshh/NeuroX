"""Distributed fixed-window request limits with a testable local backend."""

import hashlib
import hmac
import threading
import time
from collections.abc import Callable

from fastapi import HTTPException, Request

from app.config import APP_ENV, RATE_LIMIT_ENABLED, REDIS_URL
from app.secrets import setting

_memory_lock = threading.Lock()
_memory_windows: dict[str, tuple[int, int]] = {}


def clear_local_limits() -> None:
    with _memory_lock:
        _memory_windows.clear()


def _client_key(request: Request, scope: str) -> str:
    address = request.client.host if request.client else "unknown"
    secret = setting("RATE_LIMIT_KEY") or setting("JWT_SECRET", "development")
    digest = hmac.new(secret.encode(), address.encode(), hashlib.sha256).hexdigest()
    return f"neurox:rate:{scope}:{digest}"


def _memory_increment(key: str, window_seconds: int) -> tuple[int, int]:
    now = int(time.time())
    window_end = now - (now % window_seconds) + window_seconds
    with _memory_lock:
        count, stored_end = _memory_windows.get(key, (0, window_end))
        if stored_end <= now:
            count, stored_end = 0, window_end
        count += 1
        _memory_windows[key] = (count, stored_end)
    return count, max(1, stored_end - now)


def _redis_increment(key: str, window_seconds: int) -> tuple[int, int]:
    from redis import Redis

    client = Redis.from_url(REDIS_URL, socket_connect_timeout=2, socket_timeout=2)
    count = int(client.incr(key))
    if count == 1:
        client.expire(key, window_seconds)
    ttl = int(client.ttl(key))
    return count, max(1, ttl if ttl > 0 else window_seconds)


def limit(scope: str, requests: int, window_seconds: int) -> Callable:
    def enforce(request: Request) -> None:
        if not RATE_LIMIT_ENABLED:
            return
        key = _client_key(request, scope)
        if APP_ENV in {"staging", "production"}:
            try:
                count, retry_after = _redis_increment(key, window_seconds)
            except Exception as exc:
                raise HTTPException(
                    status_code=503,
                    detail="Request protection is temporarily unavailable.",
                ) from exc
        else:
            count, retry_after = _memory_increment(key, window_seconds)
        if count > requests:
            raise HTTPException(
                status_code=429,
                detail="Too many requests. Try again later.",
                headers={"Retry-After": str(retry_after)},
            )

    return enforce


login_limit = limit("login", requests=10, window_seconds=60)
registration_limit = limit("registration", requests=5, window_seconds=900)
recovery_limit = limit("account_recovery", requests=5, window_seconds=900)
cognitive_screening_limit = limit("cognitive_screening", requests=10, window_seconds=3600)
speech_transcription_limit = limit("speech_transcription", requests=20, window_seconds=300)
