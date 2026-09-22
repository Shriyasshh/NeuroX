"""Stable v1 API composition.

Browser session endpoints intentionally remain at /auth/browser because their
refresh cookie is scoped to that narrow path. Native auth and resource APIs are
versioned here without weakening cookie isolation.
"""

from fastapi import APIRouter, Depends

from app.routers.activities import router as activities_router
from app.routers.administration import preferences_router
from app.routers.administration import router as administration_router
from app.routers.care import router as care_router
from app.routers.notifications import router as notifications_router
from app.routers.privacy import router as privacy_router
from app.routers.reports import router as reports_router
from app.routers.safety import router as safety_router
from app.routers.cognitive import router as cognitive_router
from app.routers.sync import router as sync_router
from app.routers.speech import router as speech_router
from app.rate_limit import login_limit, recovery_limit, registration_limit
from app.schemas import AuthResponse
from app.services import account_recovery, authentication
from app.catalog import LANGUAGE_CONFIG

router = APIRouter(prefix="/api/v1")


@router.get("/language-config", tags=["system"])
def language_config():
    return LANGUAGE_CONFIG


router.add_api_route(
    "/auth/register",
    authentication.register,
    methods=["POST"],
    response_model=AuthResponse,
    status_code=201,
    dependencies=[Depends(registration_limit)],
)
router.add_api_route(
    "/auth/login",
    authentication.login,
    methods=["POST"],
    response_model=AuthResponse,
    dependencies=[Depends(login_limit)],
)
router.add_api_route(
    "/auth/google",
    authentication.google_login,
    methods=["POST"],
    response_model=AuthResponse,
    dependencies=[Depends(login_limit)],
)
router.add_api_route(
    "/auth/refresh",
    authentication.refresh,
    methods=["POST"],
    response_model=AuthResponse,
)
router.add_api_route("/auth/logout", authentication.logout, methods=["POST"])
router.add_api_route("/auth/me", authentication.me, methods=["GET"])
router.add_api_route("/auth/me", authentication.update_current_user, methods=["PUT"])
router.add_api_route(
    "/auth/me/password", authentication.update_current_password, methods=["PUT"]
)
router.add_api_route("/auth/sessions", authentication.sessions, methods=["GET"])
router.add_api_route(
    "/auth/sessions/{session_id}", authentication.revoke_session, methods=["DELETE"]
)
router.add_api_route(
    "/auth/sessions", authentication.revoke_all_sessions, methods=["DELETE"]
)
router.add_api_route(
    "/auth/email-verification/request",
    account_recovery.request_email_verification,
    methods=["POST"],
    dependencies=[Depends(recovery_limit)],
)
router.add_api_route(
    "/auth/email-verification/confirm",
    account_recovery.confirm_email_verification,
    methods=["POST"],
    dependencies=[Depends(recovery_limit)],
)
router.add_api_route(
    "/auth/phone-verification/request",
    account_recovery.request_phone_verification,
    methods=["POST"],
    dependencies=[Depends(recovery_limit)],
)
router.add_api_route(
    "/auth/phone-verification/confirm",
    account_recovery.confirm_phone_verification,
    methods=["POST"],
    dependencies=[Depends(recovery_limit)],
)
router.add_api_route(
    "/auth/password-reset/request",
    account_recovery.request_password_reset,
    methods=["POST"],
    status_code=202,
    dependencies=[Depends(recovery_limit)],
)
router.add_api_route(
    "/auth/password-reset/confirm",
    account_recovery.confirm_password_reset,
    methods=["POST"],
    dependencies=[Depends(recovery_limit)],
)
router.include_router(care_router)
router.include_router(administration_router)
router.include_router(preferences_router)
router.include_router(activities_router)
router.include_router(reports_router)
router.include_router(notifications_router)
router.include_router(safety_router)
router.include_router(cognitive_router)
router.include_router(sync_router)
router.include_router(speech_router)
router.include_router(privacy_router)
