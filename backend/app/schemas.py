# ===================================
#  Imports
# ===================================
from datetime import datetime
from enum import Enum
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from pydantic import BaseModel, Field, field_validator


# ===================================
#  User Roles
# ===================================
class Role(str, Enum):
    PATIENT = "PATIENT"
    CAREGIVER = "CAREGIVER"
    HEALTHCARE_WORKER = "HEALTHCARE_WORKER"
    ADMIN = "ADMIN"


# ===================================
#  Authentication Schemas
# ===================================
# Login Request
class LoginRequest(BaseModel):
    email: str
    password: str = Field(min_length=8)


# Sign in request for registration
class RegisterRequest(LoginRequest):
    name: str = Field(min_length=2, max_length=80)
    role: Role = Role.CAREGIVER

    @field_validator("role")
    @classmethod
    def public_registration_role(cls, role: Role) -> Role:
        if role not in {Role.PATIENT, Role.CAREGIVER}:
            raise ValueError(
                "Public registration supports patient and caregiver accounts only."
            )
        return role


# Sign in request using Google
class GoogleLoginRequest(BaseModel):
    credential: str = Field(min_length=20)


# Authnetication response containing tokens and user information
class AuthResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    user: dict


# Refresh request containing the refresh token
class RefreshRequest(BaseModel):
    refresh_token: str


class SessionView(BaseModel):
    id: str
    created_at: datetime
    last_used_at: datetime | None
    expires_at: datetime
    device_name: str


class SessionRevocationResponse(BaseModel):
    revoked: bool
    session_id: str | None = None


class PhoneVerificationRequest(BaseModel):
    phone_number: str = Field(pattern=r"^\+[1-9][0-9]{7,14}$")


class PhoneVerificationConfirm(BaseModel):
    code: str = Field(pattern=r"^[0-9]{6}$")


# Activity Completion schema representing the completion of an activity by a user
class ActivityCompletion(BaseModel):
    user_id: str
    activity_id: str
    started_at: datetime
    completed_at: datetime
    accuracy: float = Field(ge=0, le=1)
    response_time: float = Field(gt=0)
    attempts: int = Field(ge=1)
    completion_status: str
    difficulty_level: int = Field(ge=1, le=5)
    offline_created: bool = False
    event_id: str
    content_version: str = Field(default="legacy", min_length=1, max_length=32)
    interruptions: int = Field(default=0, ge=0, le=100)
    accessibility_mode: str = Field(default="standard", min_length=1, max_length=32)
    app_version: str = Field(default="unknown", min_length=1, max_length=32)
    model_version: str = Field(default="adaptive-v1", min_length=1, max_length=32)


# Starting Activity
class ActivityStart(BaseModel):
    user_id: str
    difficulty_level: int = Field(ge=1, le=5)
    started_at: datetime
    event_id: str = Field(min_length=8, max_length=64)
    offline_created: bool = False
    content_version: str = Field(default="legacy", min_length=1, max_length=32)
    accessibility_mode: str = Field(default="standard", min_length=1, max_length=32)
    app_version: str = Field(default="unknown", min_length=1, max_length=32)
    model_version: str = Field(default="adaptive-v1", min_length=1, max_length=32)


class PersonalizationOverride(BaseModel):
    """A transparent human override; null returns control to the rule engine."""
    difficulty_level: int | None = Field(default=None, ge=1, le=5)


# Create Reminder
class ReminderCreate(BaseModel):
    patient_id: str
    type: str = Field(pattern=r"^(medication|hydration|appointment|activity|exercise)$")
    title: str = Field(min_length=2, max_length=100)
    description: str | None = Field(default=None, max_length=255)
    scheduled_time: datetime
    repeat_rule: str | None = Field(default=None, max_length=64)
    enabled: bool = True
    timezone_name: str = Field(default="Asia/Kolkata", min_length=1, max_length=64)

    @field_validator("timezone_name")
    @classmethod
    def valid_timezone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as error:
            raise ValueError("timezone_name must be a valid IANA time zone") from error
        return value


# Update Reminder
class ReminderUpdate(BaseModel):
    title: str | None = Field(default=None, min_length=2, max_length=100)
    description: str | None = Field(default=None, max_length=255)
    scheduled_time: datetime | None = None
    repeat_rule: str | None = Field(default=None, max_length=64)
    enabled: bool | None = None
    completed: bool | None = None
    status: str | None = Field(default=None, pattern=r"^(upcoming|snoozed|done|missed)$")
    snoozed_until: datetime | None = None
    timezone_name: str | None = Field(default=None, min_length=1, max_length=64)

    @field_validator("timezone_name")
    @classmethod
    def valid_timezone(cls, value: str | None) -> str | None:
        if value is None:
            return None
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as error:
            raise ValueError("timezone_name must be a valid IANA time zone") from error
        return value


# Emergency Contact Schemas
class EmergencyContactCreate(BaseModel):
    name: str = Field(min_length=2, max_length=80)
    phone: str = Field(min_length=7, max_length=32)
    relationship: str = Field(min_length=2, max_length=64)
    priority: int = Field(default=1, ge=1, le=10)


# Update Emergency Contact
class EmergencyContactUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=2, max_length=80)
    phone: str | None = Field(default=None, min_length=7, max_length=32)
    relationship: str | None = Field(default=None, min_length=2, max_length=64)
    priority: int | None = Field(default=None, ge=1, le=10)
    active: bool | None = None


# Safety configuration
class SafetySettingsUpdate(BaseModel):
    safe_zone_name: str | None = Field(default=None, min_length=2, max_length=80)
    safe_zone_latitude: float | None = Field(default=None, ge=-90, le=90)
    safe_zone_longitude: float | None = Field(default=None, ge=-180, le=180)
    safe_zone_radius_m: int | None = Field(default=None, ge=50, le=5000)
    expected_return_at: datetime | None = None
    expected_return_note: str | None = Field(default=None, max_length=160)
    late_return_grace_minutes: int | None = Field(default=None, ge=0, le=180)


# Patient location update
class LocationUpdateCreate(BaseModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_m: float = Field(ge=0, le=5000)
    connection_state: str = Field(default="online", min_length=2, max_length=24)
    captured_at: datetime


# Patient SOS event
class SOSEventCreate(BaseModel):
    message: str = Field(
        default="I need help. Please check on me.",
        min_length=2,
        max_length=255,
    )
    location_update_id: str | None = None


# Alert acknowledgement
class SafetyAcknowledgement(BaseModel):
    note: str | None = Field(default=None, max_length=160)


class UserProfileUpdate(BaseModel):
    name: str = Field(min_length=2, max_length=80)


CONSENT_PURPOSES = {
    "location",
    "voice_recording",
    "caregiver_access",
    "notifications",
    "personalization",
    "cognitive_screening",
}


class ConsentUpdate(BaseModel):
    purpose: str = Field(min_length=3, max_length=48)
    granted: bool
    notice_version: str = Field(default="phase1-v1", min_length=1, max_length=32)

    @field_validator("purpose")
    @classmethod
    def supported_purpose(cls, value: str) -> str:
        if value not in CONSENT_PURPOSES:
            raise ValueError("Unsupported consent purpose.")
        return value


class LocationSharingUpdate(BaseModel):
    enabled: bool


class PasswordChangeRequest(BaseModel):
    current_password: str = Field(min_length=8)
    new_password: str = Field(min_length=8)


class PasswordResetRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)


class PasswordResetConfirm(BaseModel):
    token: str = Field(min_length=32, max_length=256)
    new_password: str = Field(min_length=8)


class EmailVerificationConfirm(BaseModel):
    token: str = Field(min_length=32, max_length=256)


class CaregiverSettingsUpdate(BaseModel):
    available: bool | None = None
    notify_sos: bool | None = None
    notify_safety_alerts: bool | None = None
    notify_reminders: bool | None = None


class AssignmentCreate(BaseModel):
    caregiver_id: str = Field(min_length=1, max_length=36)
    patient_id: str = Field(min_length=1, max_length=36)


class PrivacyReview(BaseModel):
    status: str = Field(pattern="^(approved|rejected)$")


# Offline mutation envelope shared by Android and the sync endpoint.
class SyncEventRequest(BaseModel):
    event_id: str = Field(min_length=8, max_length=64)
    event_type: str = Field(min_length=3, max_length=48)
    patient_id: str = Field(min_length=1, max_length=36)
    payload: dict
    # Envelope metadata makes an offline mutation diagnosable without relying
    # on the device clock as the authoritative server timestamp.
    schema_version: int = Field(default=1, ge=1, le=10)
    device_time: datetime | None = None
    attempt_count: int = Field(default=0, ge=0, le=1000)
    origin: str = Field(default="android", min_length=2, max_length=32)
