"""Native token authentication and browser cookie sessions."""

from datetime import datetime, timedelta, timezone
from uuid import uuid4
from fastapi import Depends, Header, HTTPException, Request, Response
from sqlalchemy import update
from sqlalchemy.orm import Session
from app.auth import (
    REFRESH_TOKEN_EXPIRE_DAYS,
    create_access_token,
    create_refresh_token,
    decode_access_token,
    hash_password,
    verify_password,
)
from app.config import APP_ENV, BROWSER_COOKIE_SAMESITE, CORS_ORIGINS, REQUIRE_EMAIL_VERIFICATION
from app.database import get_db
from app.models import AuditEvent, RefreshSession, User
from app.schemas import (
    AuthResponse,
    GoogleLoginRequest,
    LoginRequest,
    RefreshRequest,
    RegisterRequest,
    Role,
    UserProfileUpdate,
    PasswordChangeRequest,
    SessionRevocationResponse,
    SessionView,
)
from app.services.google_auth import verify_google_credential

LOGIN_FAILURE_LIMIT = 5
LOCKOUT_MINUTES = 15


def public_user(user: User) -> dict:
    return {
        "id": user.id,
        "name": user.name,
        "email": user.email,
        "role": user.role,
        "emailVerified": user.email_verified,
        "phoneVerified": user.phone_verified,
    }


def session_for(
    user: User,
    db: Session,
    family_id: str | None = None,
    device_name: str | None = None,
) -> AuthResponse:
    session_id = str(uuid4())
    issued_at = datetime.now(timezone.utc)
    db.add(
        RefreshSession(
            id=session_id,
            user_id=user.id,
            family_id=family_id or session_id,
            created_at=issued_at,
            expires_at=issued_at + timedelta(days=REFRESH_TOKEN_EXPIRE_DAYS),
            device_name=(device_name or "Unknown client").strip()[:80]
            or "Unknown client",
        )
    )
    db.commit()
    return AuthResponse(
        access_token=create_access_token(user),
        refresh_token=create_refresh_token(user, session_id),
        user=public_user(user),
    )


def authenticated_user(
    authorization: str | None = Header(default=None), db: Session = Depends(get_db)
) -> User:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Sign in is required.")
    payload = decode_access_token(authorization.removeprefix("Bearer "))
    if payload.get("type") != "access":
        raise HTTPException(status_code=401, detail="An access token is required.")
    user = db.get(User, payload["sub"])
    if not user:
        raise HTTPException(status_code=401, detail="Account not found.")
    return user


def current_user(user: User = Depends(authenticated_user)) -> User:
    if REQUIRE_EMAIL_VERIFICATION and not user.email_verified:
        raise HTTPException(
            status_code=403,
            detail="Verify your email address to continue.",
        )
    return user


def register(
    request: RegisterRequest,
    db: Session = Depends(get_db),
    x_client_name: str | None = Header(default=None, alias="X-Client-Name"),
):
    email = request.email.strip().lower()
    if db.query(User).filter(User.email == email).first():
        raise HTTPException(
            status_code=409, detail="An account already exists for this email."
        )
    user = User(
        name=request.name.strip(),
        email=email,
        role=request.role.value,
        password_hash=hash_password(request.password),
        email_verified=not REQUIRE_EMAIL_VERIFICATION,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return session_for(user, db, device_name=x_client_name)


# User Login
def login(
    request: LoginRequest,
    db: Session = Depends(get_db),
    x_client_name: str | None = Header(default=None, alias="X-Client-Name"),
):
    user = db.query(User).filter(User.email == request.email.strip().lower()).first()
    current_time = datetime.now(timezone.utc)
    if user and user.locked_until:
        locked_until = user.locked_until
        if locked_until.tzinfo is None:
            locked_until = locked_until.replace(tzinfo=timezone.utc)
        if locked_until > current_time:
            retry_after = max(1, int((locked_until - current_time).total_seconds()))
            raise HTTPException(
                status_code=429,
                detail="Too many sign-in attempts. Try again later.",
                headers={"Retry-After": str(retry_after)},
            )
        user.failed_login_attempts = 0
        user.locked_until = None
    if (
        not user
        or not user.password_hash
        or not verify_password(request.password, user.password_hash)
    ):
        if user:
            user.failed_login_attempts += 1
            if user.failed_login_attempts >= LOGIN_FAILURE_LIMIT:
                user.locked_until = current_time + timedelta(minutes=LOCKOUT_MINUTES)
            db.commit()
            if user.locked_until:
                raise HTTPException(
                    status_code=429,
                    detail="Too many sign-in attempts. Try again later.",
                    headers={"Retry-After": str(LOCKOUT_MINUTES * 60)},
                )
        raise HTTPException(status_code=401, detail="Incorrect email or password.")
    if user.failed_login_attempts or user.locked_until:
        user.failed_login_attempts = 0
        user.locked_until = None
        db.commit()
    if REQUIRE_EMAIL_VERIFICATION and not user.email_verified:
        raise HTTPException(
            status_code=403,
            detail="Verify your email address before signing in.",
        )
    return session_for(user, db, device_name=x_client_name)


# Google Login
def google_login(
    request: GoogleLoginRequest,
    db: Session = Depends(get_db),
    x_client_name: str | None = Header(default=None, alias="X-Client-Name"),
):
    identity = verify_google_credential(request.credential)
    user = db.query(User).filter(User.email == identity["email"]).first()
    if not user:
        user = User(
            name=identity["name"],
            email=identity["email"],
            role=Role.CAREGIVER.value,
            google_subject=identity["google_subject"],
            email_verified=True,
        )
        db.add(user)
        db.commit()
        db.refresh(user)
    elif user.google_subject not in {None, identity["google_subject"]}:
        raise HTTPException(
            status_code=409, detail="This email is linked to another Google account."
        )
    elif not user.google_subject:
        user.google_subject = identity["google_subject"]
        user.email_verified = True
        db.commit()
    return session_for(user, db, device_name=x_client_name)


# User Session Refresh
def refresh(request: RefreshRequest, db: Session = Depends(get_db)):
    payload = decode_access_token(request.refresh_token)
    if payload.get("type") != "refresh":
        raise HTTPException(status_code=401, detail="A refresh token is required.")
    token_session = db.get(RefreshSession, payload.get("sid"))
    if not token_session:
        raise HTTPException(
            status_code=401, detail="Your session has expired. Please sign in again."
        )
    user = db.get(User, token_session.user_id)
    if not user or payload.get("sub") != token_session.user_id:
        raise HTTPException(status_code=401, detail="Account not found.")
    if token_session.revoked:
        if token_session.revoke_reason == "rotated":
            _revoke_token_family(token_session, user, db)
            raise HTTPException(
                status_code=401,
                detail="Refresh token reuse was detected. Please sign in again.",
            )
        raise HTTPException(
            status_code=401, detail="Your session has expired. Please sign in again."
        )
    expires_at = token_session.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    if expires_at < datetime.now(timezone.utc):
        raise HTTPException(
            status_code=401, detail="Your session has expired. Please sign in again."
        )
    # Only one concurrent request may consume this refresh session. Issuing the
    # replacement and revoking the previous session commit together below.
    used_at = datetime.now(timezone.utc)
    claimed = db.execute(
        update(RefreshSession)
        .where(RefreshSession.id == token_session.id, RefreshSession.revoked.is_(False))
        .values(
            revoked=True,
            revoked_at=used_at,
            last_used_at=used_at,
            revoke_reason="rotated",
        ),
        execution_options={"synchronize_session": False},
    )
    if claimed.rowcount != 1:
        db.rollback()
        token_session = db.get(RefreshSession, payload.get("sid"))
        if token_session and token_session.revoke_reason == "rotated":
            _revoke_token_family(token_session, user, db)
        raise HTTPException(
            status_code=401,
            detail="Refresh token reuse was detected. Please sign in again.",
        )
    return session_for(
        user,
        db,
        family_id=token_session.family_id,
        device_name=token_session.device_name,
    )


def _revoke_token_family(
    token_session: RefreshSession, user: User, db: Session
) -> None:
    detected_at = datetime.now(timezone.utc)
    db.execute(
        update(RefreshSession)
        .where(
            RefreshSession.family_id == token_session.family_id,
            RefreshSession.revoked.is_(False),
        )
        .values(
            revoked=True,
            revoked_at=detected_at,
            revoke_reason="reuse_detected",
        ),
        execution_options={"synchronize_session": False},
    )
    db.add(
        AuditEvent(
            patient_id=user.id if user.role == Role.PATIENT.value else None,
            actor_id=user.id,
            action="auth.refresh_reuse_detected",
            target_id=token_session.family_id,
            metadata_json={},
        )
    )
    db.commit()


def logout(request: RefreshRequest, db: Session = Depends(get_db)):
    payload = decode_access_token(request.refresh_token)
    if payload.get("type") != "refresh":
        raise HTTPException(status_code=401, detail="A refresh token is required.")
    token_session = db.get(RefreshSession, payload.get("sid"))
    if token_session:
        token_session.revoked = True
        token_session.revoked_at = datetime.now(timezone.utc)
        token_session.revoke_reason = "logout"
        db.commit()
    return {"loggedOut": True}


# User Profile
def me(user: User = Depends(current_user)):
    return public_user(user)


def update_current_user(
    request: UserProfileUpdate,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    user.name = request.name.strip()
    db.commit()
    db.refresh(user)
    return public_user(user)


def update_current_password(
    request: PasswordChangeRequest,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    if not user.password_hash or not verify_password(
        request.current_password, user.password_hash
    ):
        raise HTTPException(status_code=400, detail="Current password is incorrect.")
    if request.current_password == request.new_password:
        raise HTTPException(status_code=400, detail="New password must be different.")
    user.password_hash = hash_password(request.new_password)
    db.execute(
        update(RefreshSession)
        .where(RefreshSession.user_id == user.id, RefreshSession.revoked.is_(False))
        .values(
            revoked=True,
            revoked_at=datetime.now(timezone.utc),
            revoke_reason="password_changed",
        ),
        execution_options={"synchronize_session": False},
    )
    db.commit()
    return {"updated": True}


def sessions(
    user: User = Depends(current_user), db: Session = Depends(get_db)
) -> list[SessionView]:
    current_time = datetime.now(timezone.utc)
    return [
        SessionView.model_validate(session, from_attributes=True)
        for session in db.query(RefreshSession)
        .filter(
            RefreshSession.user_id == user.id,
            RefreshSession.revoked.is_(False),
            RefreshSession.expires_at > current_time,
        )
        .order_by(RefreshSession.created_at.desc())
        .all()
    ]


def revoke_session(
    session_id: str,
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
) -> SessionRevocationResponse:
    session = (
        db.query(RefreshSession)
        .filter(
            RefreshSession.id == session_id,
            RefreshSession.user_id == user.id,
            RefreshSession.revoked.is_(False),
        )
        .first()
    )
    if not session:
        raise HTTPException(status_code=404, detail="Session not found.")
    session.revoked = True
    session.revoked_at = datetime.now(timezone.utc)
    session.revoke_reason = "user_revoked"
    db.commit()
    return SessionRevocationResponse(revoked=True, session_id=session.id)


def revoke_all_sessions(
    user: User = Depends(current_user), db: Session = Depends(get_db)
) -> SessionRevocationResponse:
    db.execute(
        update(RefreshSession)
        .where(RefreshSession.user_id == user.id, RefreshSession.revoked.is_(False))
        .values(
            revoked=True,
            revoked_at=datetime.now(timezone.utc),
            revoke_reason="user_revoked_all",
        ),
        execution_options={"synchronize_session": False},
    )
    db.commit()
    return SessionRevocationResponse(revoked=True)


COOKIE_NAME = "neurox_refresh"
COOKIE_PATH = "/auth/browser"


def browser_origin(request: Request) -> None:
    # Check even login: CORS alone does not prevent login/logout CSRF.
    if request.headers.get("origin") not in CORS_ORIGINS:
        raise HTTPException(
            status_code=403, detail="This browser origin is not allowed."
        )


def browser_session(data: AuthResponse, response: Response) -> dict:
    response.set_cookie(
        COOKIE_NAME,
        data.refresh_token,
        httponly=True,
        secure=APP_ENV != "development",
        samesite=BROWSER_COOKIE_SAMESITE,
        path=COOKIE_PATH,
        max_age=REFRESH_TOKEN_EXPIRE_DAYS * 86400,
    )
    response.headers["Cache-Control"] = "no-store"
    return {
        "access_token": data.access_token,
        "token_type": data.token_type,
        "user": data.user,
    }


def browser_login(
    body: LoginRequest,
    response: Response,
    request: Request,
    db: Session = Depends(get_db),
):
    return browser_session(
        login(body, db, request.headers.get("X-Client-Name")), response
    )


def browser_register(
    body: RegisterRequest,
    response: Response,
    request: Request,
    db: Session = Depends(get_db),
):
    """Create a public account and establish the protected browser session."""
    data = register(body, db, request.headers.get("X-Client-Name"))
    if REQUIRE_EMAIL_VERIFICATION and not data.user["emailVerified"]:
        response.headers["Cache-Control"] = "no-store"
        return {
            "access_token": data.access_token,
            "token_type": data.token_type,
            "user": data.user,
        }
    return browser_session(data, response)


def browser_google(
    body: GoogleLoginRequest,
    response: Response,
    request: Request,
    db: Session = Depends(get_db),
):
    return browser_session(
        google_login(body, db, request.headers.get("X-Client-Name")), response
    )


def browser_refresh(
    request: Request, response: Response, db: Session = Depends(get_db)
):
    token = request.cookies.get(COOKIE_NAME)
    if not token:
        raise HTTPException(status_code=401, detail="Please sign in.")
    return browser_session(refresh(RefreshRequest(refresh_token=token), db), response)


def browser_logout(request: Request, response: Response, db: Session = Depends(get_db)):
    token = request.cookies.get(COOKIE_NAME)
    if token:
        try:
            logout(RefreshRequest(refresh_token=token), db)
        except HTTPException as error:
            if error.status_code != 401:
                raise
    response.delete_cookie(
        COOKIE_NAME,
        path=COOKIE_PATH,
        httponly=True,
        secure=APP_ENV != "development",
        samesite=BROWSER_COOKIE_SAMESITE,
    )
    response.headers["Cache-Control"] = "no-store"
    return {"loggedOut": True}
