import os
from pathlib import Path
import runpy
import subprocess
import sys

import pytest

CONFIG = Path(__file__).parents[1] / "app" / "config.py"


@pytest.mark.parametrize(
    "origin",
    [
        "",
        "http://care.example",
        "https://*.example",
        "https://care.example/path",
        "https://user:secret@care.example",
    ],
)
def test_deployment_requires_exact_https_origins(monkeypatch, origin):
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("CORS_ORIGINS", origin)
    with pytest.raises(RuntimeError):
        runpy.run_path(str(CONFIG))


@pytest.mark.parametrize("missing", ["SMTP_HOST", "SMTP_FROM", "PUBLIC_WEB_URL"])
def test_deployment_requires_account_email_configuration(monkeypatch, missing):
    values = {
        "SMTP_HOST": "smtp.example",
        "SMTP_FROM": "no-reply@example.com",
        "PUBLIC_WEB_URL": "https://care.example",
        "REDIS_URL": "redis://redis:6379/0",
        "SMS_GATEWAY_URL": "https://sms.example/messages",
        "SMS_GATEWAY_TOKEN": "test-provider-token",
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4318",
        "COGNITIVE_MODEL_PATH": "/run/models/model.joblib",
        "COGNITIVE_MODEL_SHA256": "a" * 64,
    }
    values.pop(missing)
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("CORS_ORIGINS", "https://care.example")
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    monkeypatch.delenv(missing, raising=False)
    with pytest.raises(RuntimeError):
        runpy.run_path(str(CONFIG))


def test_unknown_environment_is_rejected(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prodution")
    with pytest.raises(RuntimeError):
        runpy.run_path(str(CONFIG))


@pytest.mark.parametrize(
    "module,overrides,expected",
    [
        ("app.auth", {"JWT_SECRET": "short"}, "JWT_SECRET"),
        ("app.auth", {"JWT_SECRET": "replace-with-a-long-random-secret"}, "JWT_SECRET"),
        (
            "app.database",
            {"DATABASE_URL": "sqlite:///unused.db"},
            "PostgreSQL DATABASE_URL",
        ),
    ],
)
def test_deployment_rejects_unsafe_credentials_and_database(
    module, overrides, expected
):
    env = {
        **os.environ,
        "APP_ENV": "production",
        "CORS_ORIGINS": "https://care.example",
        "SMTP_HOST": "smtp.example",
        "SMTP_FROM": "no-reply@example.com",
        "PUBLIC_WEB_URL": "https://care.example",
        "REDIS_URL": "redis://redis:6379/0",
        "SMS_GATEWAY_URL": "https://sms.example/messages",
        "SMS_GATEWAY_TOKEN": "test-provider-token",
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4318",
        "COGNITIVE_MODEL_PATH": "/run/models/model.joblib",
        "COGNITIVE_MODEL_SHA256": "a" * 64,
        **overrides,
    }
    result = subprocess.run(
        [sys.executable, "-c", f"import {module}"],
        env=env,
        capture_output=True,
        text=True,
        timeout=15,
    )
    assert result.returncode != 0
    assert expected in result.stderr


def test_deployment_requires_redis(monkeypatch):
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("CORS_ORIGINS", "https://care.example")
    monkeypatch.setenv("SMTP_HOST", "smtp.example")
    monkeypatch.setenv("SMTP_FROM", "no-reply@example.com")
    monkeypatch.setenv("PUBLIC_WEB_URL", "https://care.example")
    monkeypatch.setenv("SMS_GATEWAY_URL", "https://sms.example/messages")
    monkeypatch.setenv("SMS_GATEWAY_TOKEN", "test-provider-token")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318")
    monkeypatch.setenv("COGNITIVE_MODEL_PATH", "/run/models/model.joblib")
    monkeypatch.setenv("COGNITIVE_MODEL_SHA256", "a" * 64)
    monkeypatch.delenv("REDIS_URL", raising=False)
    with pytest.raises(RuntimeError, match="REDIS_URL"):
        runpy.run_path(str(CONFIG))


@pytest.mark.parametrize(
    "model_path,checksum",
    [("", "a" * 64), ("/run/models/model.joblib", "not-a-sha256")],
)
def test_deployment_requires_verified_cognitive_model(monkeypatch, model_path, checksum):
    values = {
        "APP_ENV": "production",
        "CORS_ORIGINS": "https://care.example",
        "SMTP_HOST": "smtp.example",
        "SMTP_FROM": "no-reply@example.com",
        "PUBLIC_WEB_URL": "https://care.example",
        "REDIS_URL": "redis://redis:6379/0",
        "SMS_GATEWAY_URL": "https://sms.example/messages",
        "SMS_GATEWAY_TOKEN": "test-provider-token",
        "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4318",
        "COGNITIVE_MODEL_PATH": model_path,
        "COGNITIVE_MODEL_SHA256": checksum,
    }
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    with pytest.raises(RuntimeError, match="COGNITIVE_MODEL"):
        runpy.run_path(str(CONFIG))
