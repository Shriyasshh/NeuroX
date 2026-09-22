"""Fail closed on unknown environments and unsafe deployed origins."""

import os
from urllib.parse import urlsplit

from app.secrets import setting

APP_ENV = os.getenv("APP_ENV", "development").lower()
if APP_ENV not in {"development", "test", "staging", "production"}:
    raise RuntimeError("APP_ENV must be development, test, staging, or production.")

CORS_ORIGINS = [
    item.strip()
    for item in os.getenv("CORS_ORIGINS", "http://localhost:5173").split(",")
    if item.strip()
]
REQUIRE_EMAIL_VERIFICATION = (
    APP_ENV in {"staging", "production"}
    or os.getenv("REQUIRE_EMAIL_VERIFICATION", "false").lower() == "true"
)
RATE_LIMIT_ENABLED = (
    APP_ENV in {"staging", "production"}
    or os.getenv("RATE_LIMIT_ENABLED", "false").lower() == "true"
)
BROWSER_COOKIE_SAMESITE = os.getenv("BROWSER_COOKIE_SAMESITE", "strict").lower()
if BROWSER_COOKIE_SAMESITE not in {"strict", "lax", "none"}:
    raise RuntimeError("BROWSER_COOKIE_SAMESITE must be strict, lax, or none.")
# Demo fixtures are strictly a development convenience. They are disabled for
# every non-development environment, regardless of the value supplied here.
SEED_DEMO_DATA = APP_ENV == "development" and os.getenv("SEED_DEMO_DATA", "true").lower() == "true"
DEMO_CAREGIVER_EMAIL = os.getenv("DEMO_CAREGIVER_EMAIL", "anita@neurox.demo").strip().lower()
DEMO_CAREGIVER_PASSWORD = os.getenv("DEMO_CAREGIVER_PASSWORD", "NeuroXDemo!2026")
DEMO_PATIENT_EMAIL = os.getenv("DEMO_PATIENT_EMAIL", "maya@neurox.demo").strip().lower()
DEMO_PATIENT_PASSWORD = os.getenv("DEMO_PATIENT_PASSWORD", DEMO_CAREGIVER_PASSWORD)
REDIS_URL = setting("REDIS_URL")
COGNITIVE_MODEL_PATH = os.getenv("COGNITIVE_MODEL_PATH", "").strip()
COGNITIVE_MODEL_SHA256 = os.getenv("COGNITIVE_MODEL_SHA256", "").strip().lower()
FCM_ENABLED = os.getenv("FCM_ENABLED", "false").lower() == "true"
BHASHINI_ENABLED = os.getenv("BHASHINI_ENABLED", "false").lower() == "true"
if FCM_ENABLED:
    if not os.getenv("FIREBASE_PROJECT_ID") or not setting("FIREBASE_SERVICE_ACCOUNT_JSON"):
        raise RuntimeError("FCM_ENABLED requires FIREBASE_PROJECT_ID and a Firebase service-account secret.")
if BHASHINI_ENABLED:
    if not os.getenv("BHASHINI_USER_ID") or not setting("BHASHINI_API_KEY") or not os.getenv("BHASHINI_PIPELINE_ID"):
        raise RuntimeError("BHASHINI_ENABLED requires BHASHINI_USER_ID, BHASHINI_API_KEY, and BHASHINI_PIPELINE_ID.")
if APP_ENV in {"staging", "production"}:
    if not os.getenv("CORS_ORIGINS") or not CORS_ORIGINS:
        raise RuntimeError(
            "Explicit HTTPS CORS_ORIGINS are required outside development."
        )
    for origin in CORS_ORIGINS:
        parsed = urlsplit(origin)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or "*" in origin
            or parsed.username
            or parsed.password
            or parsed.path
            or parsed.query
            or parsed.fragment
        ):
            raise RuntimeError(
                "CORS_ORIGINS must contain exact HTTPS origins without paths or credentials."
            )
    if not os.getenv("SMTP_HOST") or not os.getenv("SMTP_FROM"):
        raise RuntimeError("SMTP_HOST and SMTP_FROM are required outside development.")
    sms_gateway_url = os.getenv("SMS_GATEWAY_URL", "")
    if not sms_gateway_url.startswith("https://") or not setting("SMS_GATEWAY_TOKEN"):
        raise RuntimeError(
            "An HTTPS SMS_GATEWAY_URL and SMS_GATEWAY_TOKEN are required outside development."
        )
    otel_endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
    if not otel_endpoint.startswith(("http://", "https://")):
        raise RuntimeError(
            "OTEL_EXPORTER_OTLP_ENDPOINT is required outside development."
        )
    if not REDIS_URL or not REDIS_URL.startswith(("redis://", "rediss://")):
        raise RuntimeError(
            "A redis:// or rediss:// REDIS_URL is required outside development."
        )
    if not COGNITIVE_MODEL_PATH:
        raise RuntimeError("COGNITIVE_MODEL_PATH is required outside development.")
    if len(COGNITIVE_MODEL_SHA256) != 64 or any(
        character not in "0123456789abcdef" for character in COGNITIVE_MODEL_SHA256
    ):
        raise RuntimeError(
            "COGNITIVE_MODEL_SHA256 must be a lowercase 64-character SHA-256 digest outside development."
        )
    public_web_url = os.getenv("PUBLIC_WEB_URL", "")
    parsed_web_url = urlsplit(public_web_url)
    if (
        parsed_web_url.scheme != "https"
        or not parsed_web_url.hostname
        or parsed_web_url.username
        or parsed_web_url.password
        or parsed_web_url.path not in {"", "/"}
        or parsed_web_url.query
        or parsed_web_url.fragment
    ):
        raise RuntimeError("PUBLIC_WEB_URL must be an exact HTTPS origin.")
