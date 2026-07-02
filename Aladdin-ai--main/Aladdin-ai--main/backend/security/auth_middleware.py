"""
Phase 12 — Task 2: Authorization Middleware rewritten for FastAPI.
Replaces all Flask decorators with FastAPI Depends() and Security().
"""
from __future__ import annotations

import logging
from typing import List, Optional

from fastapi import Depends, HTTPException, Request, Security
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .jwt_auth import verify_token

logger = logging.getLogger(__name__)

ROLE_LEVELS = {"guest": 0, "user": 1, "moderator": 2, "admin": 3, "service": 4}

_bearer_scheme = HTTPBearer(auto_error=False)


class AuthenticatedUser:
    def __init__(self, user_id: str, role: str, payload: dict):
        self.user_id = user_id
        self.role = role
        self.payload = payload


async def get_current_user(
    request: Request,
    credentials: Optional[HTTPAuthorizationCredentials] = Security(_bearer_scheme),
) -> AuthenticatedUser:
    """FastAPI dependency: validate Bearer JWT and return AuthenticatedUser."""
    if credentials is None or not credentials.credentials:
        logger.warning("Auth required but no token provided — %s %s", request.method, request.url.path)
        raise HTTPException(
            status_code=401,
            detail={"error": "Authentication required", "code": "MISSING_TOKEN"},
        )

    payload = verify_token(credentials.credentials)
    if payload is None:
        logger.warning("Invalid token on %s %s", request.method, request.url.path)
        raise HTTPException(
            status_code=401,
            detail={"error": "Invalid or expired token", "code": "INVALID_TOKEN"},
        )

    user_id = payload.get("sub", "")
    role = payload.get("role", "user")
    logger.debug("Authenticated user=%s role=%s → %s", user_id, role, request.url.path)
    return AuthenticatedUser(user_id=user_id, role=role, payload=payload)


async def get_optional_user(
    request: Request,
    credentials: Optional[HTTPAuthorizationCredentials] = Security(_bearer_scheme),
) -> Optional[AuthenticatedUser]:
    """FastAPI dependency: same as get_current_user but returns None instead of 401."""
    if credentials is None or not credentials.credentials:
        return None
    payload = verify_token(credentials.credentials)
    if payload is None:
        return None
    return AuthenticatedUser(
        user_id=payload.get("sub", ""),
        role=payload.get("role", "user"),
        payload=payload,
    )


def require_role(*allowed_roles: str):
    """FastAPI dependency factory: require the authenticated user to have one of the given roles."""

    async def _dependency(current_user: AuthenticatedUser = Depends(get_current_user)) -> AuthenticatedUser:
        if current_user.role not in allowed_roles:
            logger.warning(
                "Forbidden: user=%s role=%s required=%s",
                current_user.user_id, current_user.role, allowed_roles,
            )
            raise HTTPException(
                status_code=403,
                detail={
                    "error": "Insufficient permissions",
                    "code": "FORBIDDEN",
                    "required_roles": list(allowed_roles),
                },
            )
        return current_user

    return _dependency


def require_min_role(min_role: str):
    """FastAPI dependency factory: require role level >= min_role in hierarchy."""

    async def _dependency(current_user: AuthenticatedUser = Depends(get_current_user)) -> AuthenticatedUser:
        user_level = ROLE_LEVELS.get(current_user.role, 0)
        req_level = ROLE_LEVELS.get(min_role, 999)
        if user_level < req_level:
            logger.warning(
                "Role too low: user=%s role=%s min_required=%s",
                current_user.user_id, current_user.role, min_role,
            )
            raise HTTPException(
                status_code=403,
                detail={
                    "error": "Insufficient role level",
                    "code": "ROLE_TOO_LOW",
                    "min_required": min_role,
                    "your_role": current_user.role,
                },
            )
        return current_user

    return _dependency


def require_scope(*required_scopes: str):
    """FastAPI dependency factory: require specific OAuth-style scopes in the JWT payload."""

    async def _dependency(current_user: AuthenticatedUser = Depends(get_current_user)) -> AuthenticatedUser:
        token_scopes = set(current_user.payload.get("scope", "").split(","))
        for scope in required_scopes:
            if scope and scope not in token_scopes:
                logger.warning("Missing scope '%s' for user=%s", scope, current_user.user_id)
                raise HTTPException(
                    status_code=403,
                    detail={"error": f"Missing required scope: {scope}", "code": "INSUFFICIENT_SCOPE"},
                )
        return current_user

    return _dependency


class AuthMiddleware:
    """
    ASGI middleware that globally enforces JWT authentication on /api/* routes.
    Skips public paths defined in SKIP_PATHS.
    """

    SKIP_PATHS = {
        "/",
        "/health",
        "/api/health",
        "/api/auth/login",
        "/api/auth/refresh",
        "/api/auth/register",
        "/api/status",
        "/docs",
        "/openapi.json",
        "/redoc",
    }

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")

        if path in self.SKIP_PATHS or not path.startswith("/api/"):
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        auth_header = headers.get(b"authorization", b"").decode("utf-8", errors="ignore")

        if not auth_header.startswith("Bearer "):
            await self._send_401(send, "MISSING_TOKEN", "Authorization required")
            return

        token = auth_header[7:]
        payload = verify_token(token)
        if payload is None:
            await self._send_401(send, "INVALID_TOKEN", "Invalid or expired token")
            return

        scope["user_id"] = payload.get("sub", "")
        scope["user_role"] = payload.get("role", "user")
        scope["token_payload"] = payload

        await self.app(scope, receive, send)

    @staticmethod
    async def _send_401(send, code: str, message: str):
        import json
        body = json.dumps({"error": message, "code": code}).encode()
        await send({"type": "http.response.start", "status": 401,
                    "headers": [(b"content-type", b"application/json")]})
        await send({"type": "http.response.body", "body": body})
