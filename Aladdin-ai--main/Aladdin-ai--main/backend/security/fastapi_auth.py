"""
Task 8 — FastAPI Authentication: centralized auth dependencies for all routes.
Use as: router = APIRouter(dependencies=[Depends(require_authenticated)])
"""
from __future__ import annotations

import logging
from typing import Optional

from fastapi import Depends, HTTPException, Request, Security
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .jwt_auth import verify_token

logger = logging.getLogger(__name__)

_bearer = HTTPBearer(auto_error=False)


class CurrentUser:
    def __init__(self, user_id: str, role: str, permissions: list, payload: dict):
        self.user_id = user_id
        self.role = role
        self.permissions = permissions
        self.payload = payload
        self.is_admin = role == "admin"

    def __repr__(self):
        return f"<CurrentUser id={self.user_id} role={self.role}>"


async def get_current_user(
    request: Request,
    credentials: Optional[HTTPAuthorizationCredentials] = Security(_bearer),
) -> CurrentUser:
    """FastAPI dependency — validates JWT and returns CurrentUser. Raises 401 if invalid."""
    if credentials is None or not credentials.credentials:
        raise HTTPException(
            status_code=401,
            detail={"error": "Authentication required", "code": "MISSING_TOKEN"},
            headers={"WWW-Authenticate": "Bearer"},
        )

    payload = verify_token(credentials.credentials)
    if payload is None:
        raise HTTPException(
            status_code=401,
            detail={"error": "Invalid or expired token", "code": "INVALID_TOKEN"},
            headers={"WWW-Authenticate": "Bearer"},
        )

    user_id = payload.get("sub", "")
    role = payload.get("role", "user")
    permissions = payload.get("permissions", [])
    logger.debug("Authenticated user=%s role=%s → %s", user_id, role, request.url.path)
    return CurrentUser(user_id=user_id, role=role, permissions=permissions, payload=payload)


async def require_authenticated(user: CurrentUser = Depends(get_current_user)) -> CurrentUser:
    """FastAPI dependency — equivalent to get_current_user but named for clarity."""
    return user


async def require_admin(user: CurrentUser = Depends(get_current_user)) -> CurrentUser:
    """FastAPI dependency — requires admin role."""
    if user.role != "admin":
        raise HTTPException(
            status_code=403,
            detail={"error": "Admin role required", "code": "FORBIDDEN"},
        )
    return user


def require_permission(permission: str):
    """FastAPI dependency factory — requires a specific permission."""
    async def _dep(user: CurrentUser = Depends(get_current_user)) -> CurrentUser:
        if user.role == "admin":
            return user
        if permission not in user.permissions:
            raise HTTPException(
                status_code=403,
                detail={
                    "error": f"Permission '{permission}' required",
                    "code": "INSUFFICIENT_PERMISSIONS",
                },
            )
        return user
    return _dep


def require_role(*roles: str):
    """FastAPI dependency factory — requires one of the given roles."""
    async def _dep(user: CurrentUser = Depends(get_current_user)) -> CurrentUser:
        if user.role not in roles:
            raise HTTPException(
                status_code=403,
                detail={
                    "error": "Insufficient role",
                    "code": "FORBIDDEN",
                    "required_roles": list(roles),
                    "your_role": user.role,
                },
            )
        return user
    return _dep
