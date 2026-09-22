"""SMTP boundary for account emails; tokens are never logged or persisted raw."""

import os
import smtplib
import json
import urllib.request
from email.message import EmailMessage
from urllib.parse import urlencode

from app.secrets import setting


def send_account_link(email: str, purpose: str, token: str) -> None:
    host = os.getenv("SMTP_HOST")
    sender = os.getenv("SMTP_FROM")
    public_web_url = os.getenv("PUBLIC_WEB_URL")
    if not host or not sender or not public_web_url:
        raise RuntimeError("Account email delivery is not configured.")
    path = "/verify-email" if purpose == "email_verification" else "/reset-password"
    link = f"{public_web_url.rstrip('/')}{path}?{urlencode({'token': token})}"
    message = EmailMessage()
    message["Subject"] = (
        "Verify your NeuroX email"
        if purpose == "email_verification"
        else "Reset your NeuroX password"
    )
    message["From"] = sender
    message["To"] = email
    message.set_content(
        f"Open this one-time NeuroX link to continue:\n\n{link}\n\n"
        "If you did not request this, you can ignore this email."
    )
    port = int(os.getenv("SMTP_PORT", "587"))
    with smtplib.SMTP(host, port, timeout=10) as smtp:
        if os.getenv("SMTP_STARTTLS", "true").lower() == "true":
            smtp.starttls()
        username = os.getenv("SMTP_USERNAME")
        password = setting("SMTP_PASSWORD")
        if username and password:
            smtp.login(username, password)
        smtp.send_message(message)


def send_phone_code(phone_number: str, code: str) -> None:
    """Send an OTP through a provider-neutral HTTPS JSON webhook."""
    gateway = os.getenv("SMS_GATEWAY_URL")
    api_token = setting("SMS_GATEWAY_TOKEN")
    if not gateway or not gateway.startswith("https://") or not api_token:
        raise RuntimeError("Phone verification delivery is not configured.")
    request = urllib.request.Request(
        gateway,
        data=json.dumps(
            {
                "to": phone_number,
                "message": f"Your NeuroX verification code is {code}. It expires in 10 minutes.",
            }
        ).encode(),
        headers={
            "Authorization": f"Bearer {api_token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    # The gateway is required to begin with https:// above; no local/file URL
    # can reach this sink.
    with urllib.request.urlopen(request, timeout=10) as response:  # nosec B310
        if response.status < 200 or response.status >= 300:
            raise RuntimeError("Phone verification provider rejected the request.")
