"""
backend/routes/auth_routes.py — Tasks 3 & 4
============================================
FastAPI router for JWT auth endpoints with real SQLite-backed authentication.

Endpoints:
  POST /api/auth/register  — create new account
  POST /api/auth/login     — authenticate and get token pair
  POST /api/auth/refresh   — refresh access token
  POST /api/auth/logout    — revoke access token
  GET  /api/auth/me        — get current user info
  GET  /api/auth/status    — security subsystem status
"""
from __future__ import annotations

import logging
import time
from typing import Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field, validator

from ..security.jwt_auth import (
    create_access_token,
    create_refresh_token,
    revoke_token,
    verify_token,
    refresh_access_token,
    cleanup_expired_revoked_tokens,
)
from ..security.user_db import authenticate_user, create_user, get_user_by_id, validate_password
from ..security.fastapi_auth import get_current_user, CurrentUser

log = logging.getLogger(__name__)

router = APIRouter(prefix="/api/auth", tags=["auth"])


# ── Pydantic schemas ──────────────────────────────────────────────────────────

class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=64)
    password: str = Field(..., min_length=8)
    email: Optional[str] = Field(default="", max_length=254)
    role: Optional[str] = Field(default="user")

    @validator("username")
    def username_alphanumeric(cls, v):
        if not v.replace("_", "").replace("-", "").isalnum():
            raise ValueError("Username must contain only letters, digits, underscores, or hyphens")
        return v.strip()

    @validator("role")
    def role_allowed(cls, v):
        if v not in ("user", "admin"):
            raise ValueError("Role must be 'user' or 'admin'")
        return v


class LoginRequest(BaseModel):
    username: str
    password: str


class RefreshRequest(BaseModel):
    refresh_token: str


# ── Routes ────────────────────────────────────────────────────────────────────

@router.post("/register", status_code=201)
async def register(body: RegisterRequest) -> dict:
    """
    Create a new user account.

    Request JSON::

        {"username": "alice", "password": "SecurePass1", "email": "alice@example.com"}
    """
    valid, err = validate_password(body.password)
    if not valid:
        raise HTTPException(status_code=400, detail={"error": True, "message": err})

    try:
        user = create_user(
            username=body.username,
            password=body.password,
            email=body.email or "",
            role=body.role or "user",
        )
        log.info("User registered: username=%s", body.username)
        return {
            "error": False,
            "message": "Account created successfully",
            "user": user.to_dict(),
        }
    except ValueError as exc:
        raise HTTPException(status_code=409, detail={"error": True, "message": str(exc)})
    except Exception as exc:
        log.error("Registration error: %s", exc)
        raise HTTPException(status_code=500, detail={"error": True, "message": "Registration failed"})


@router.post("/login")
async def login(body: LoginRequest, request: Request) -> dict:
    """
    Authenticate user and return JWT token pair.

    Request JSON::

        {"username": "alice", "password": "SecurePass1"}

    Response JSON::

        {"access_token": "...", "refresh_token": "...", "token_type": "Bearer", "expires_in": 3600}
    """
    username = (body.username or "").strip()
    password = (body.password or "").strip()

    if not username or not password:
        raise HTTPException(
            status_code=400,
            detail={"error": True, "message": "username and password are required"},
        )

    client_ip = request.client.host if request.client else ""
    log.info("Login attempt: user=%s ip=%s", username, client_ip)

    user = authenticate_user(username, password)
    if user is None:
        log.warning("Login failed: user=%s ip=%s", username, client_ip)
        raise HTTPException(
            status_code=401,
            detail={"error": True, "message": "Invalid username or password"},
        )

    access_token = create_access_token(user.id, role=user.role)
    refresh_tok = create_refresh_token(user.id)
    log.info("Login successful: user=%s role=%s ip=%s", username, user.role, client_ip)

    return {
        "error": False,
        "access_token": access_token,
        "refresh_token": refresh_tok,
        "token_type": "Bearer",
        "expires_in": 3600,
        "user": user.to_dict(),
    }


@router.post("/refresh")
async def refresh(body: RefreshRequest) -> dict:
    """Exchange a refresh token for a new token pair."""
    if not body.refresh_token.strip():
        raise HTTPException(
            status_code=400,
            detail={"error": True, "message": "refresh_token required"},
        )

    new_pair = refresh_access_token(body.refresh_token)
    if new_pair is None:
        raise HTTPException(
            status_code=401,
            detail={"error": True, "message": "Invalid or expired refresh token"},
        )

    return {"error": False, **new_pair}


@router.post("/logout")
async def logout(authorization: str = Header(default="")) -> dict:
    """Revoke access token (logout). Requires Authorization: Bearer <access_token>"""
    if not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=401,
            detail={"error": True, "message": "Bearer token required"},
        )
    token = authorization[7:]
    revoke_token(token)
    cleanup_expired_revoked_tokens()
    log.info("Logout: token revoked")
    return {"error": False, "message": "Logged out successfully"}


@router.get("/me")
async def me(current_user: CurrentUser = Depends(get_current_user)) -> dict:
    """Return current user info from JWT claims."""
    user = get_user_by_id(current_user.user_id)
    return {
        "error": False,
        "user_id": current_user.user_id,
        "role": current_user.role,
        "permissions": current_user.permissions,
        "profile": user.to_dict() if user else None,
    }


@router.get("/status")
async def security_status() -> dict:
    """Security subsystem status — no auth required (health endpoint)."""
    return {
        "error": False,
        "security": {
            "jwt_ready": True,
            "auth_db_ready": True,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        },
    }
