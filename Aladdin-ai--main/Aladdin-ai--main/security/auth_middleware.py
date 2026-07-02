"""
security/auth_middleware.py — Phase 12 Feature 2
=================================================
API Authorization middleware — RBAC, decorator-based permission checking,
role hierarchy, 401/403 enforcement.

Features:
- require_auth decorator: validates JWT, injects claims
- require_permission decorator: checks RBAC permissions
- require_role decorator: role-hierarchy enforcement
- FastAPI and plain-function compatible
- Centralised deny handler
- Admin bypass for all permission checks
- Audit log hook on every check
"""

from __future__ import annotations

import functools
import logging
from typing import Callable, List, Optional

log = logging.getLogger(__name__)

# Role hierarchy: higher index = higher privilege
_ROLE_HIERARCHY = ["guest", "user", "moderator", "admin"]


def _role_rank(role: str) -> int:
    try:
        return _ROLE_HIERARCHY.index(role)
    except ValueError:
        return -1


# ── Exceptions ────────────────────────────────────────────────────────────────

class AuthenticationError(Exception):
    """JWT missing, expired, or revoked."""
    http_status: int = 401

class AuthorizationError(Exception):
    """Authenticated but lacks required permission/role."""
    http_status: int = 403


# ── Decorator helpers ─────────────────────────────────────────────────────────

def require_auth(jwt_handler=None, _test_claims=None):
    """
    Decorator: validate JWT from Authorization header and inject claims.

    Usage (FastAPI route)::

        @router.get("/protected")
        @require_auth(jwt_handler=handler)
        async def protected(claims=None):
            return {"user": claims.user_id}

    Usage (plain function)::

        @require_auth(_test_claims=mock_claims)
        def my_fn(claims):
            ...
    """
    def decorator(fn: Callable) -> Callable:
        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            if _test_claims is not None:
                return fn(*args, claims=_test_claims, **kwargs)

            token = _extract_bearer_token_fastapi(kwargs)
            if not token:
                raise AuthenticationError("Authorization header missing or malformed")

            from .jwt_handler import TokenError
            try:
                handler = jwt_handler or _get_global_jwt_handler()
                claims = handler.verify_token(token)
            except TokenError as e:
                raise AuthenticationError(str(e)) from e

            log.debug("auth: user='%s' role='%s' endpoint_fn='%s'", claims.user_id, claims.role, fn.__name__)
            return fn(*args, claims=claims, **kwargs)
        return wrapper
    return decorator


def require_permission(*permissions: str, jwt_handler=None):
    """
    Decorator: require all listed permissions (admin bypasses all).

    Usage::

        @require_permission("read_memory", "write_memory")
        def fn(claims):
            ...
    """
    def decorator(fn: Callable) -> Callable:
        @functools.wraps(fn)
        def wrapper(*args, claims=None, **kwargs):
            if claims is None:
                raise AuthenticationError("No claims — apply @require_auth first")
            _check_permissions(claims, list(permissions), fn.__name__)
            return fn(*args, claims=claims, **kwargs)
        return wrapper
    return decorator


def require_role(minimum_role: str, jwt_handler=None):
    """
    Decorator: require a minimum role in the hierarchy.

    Usage::

        @require_role("moderator")
        def fn(claims):
            ...
    """
    def decorator(fn: Callable) -> Callable:
        @functools.wraps(fn)
        def wrapper(*args, claims=None, **kwargs):
            if claims is None:
                raise AuthenticationError("No claims — apply @require_auth first")
            if _role_rank(claims.role) < _role_rank(minimum_role):
                log.warning(
                    "authz: DENIED user='%s' role='%s' required='%s' fn='%s'",
                    claims.user_id, claims.role, minimum_role, fn.__name__,
                )
                raise AuthorizationError(
                    f"Role '{minimum_role}' or higher required (you have '{claims.role}')"
                )
            log.debug("authz: role OK user='%s' role='%s' min='%s'", claims.user_id, claims.role, minimum_role)
            return fn(*args, claims=claims, **kwargs)
        return wrapper
    return decorator


def require_extra_verification(sensitive_ops: Optional[List[str]] = None):
    """
    Decorator: extra security gate for sensitive operations.
    Logs a high-severity event and can be extended with MFA checks.
    """
    _sensitive = set(sensitive_ops or ["delete", "settings_change", "secret_access"])

    def decorator(fn: Callable) -> Callable:
        @functools.wraps(fn)
        def wrapper(*args, claims=None, operation: str = "", **kwargs):
            if claims and operation in _sensitive:
                log.warning(
                    "SENSITIVE OP: user='%s' role='%s' op='%s' fn='%s'",
                    claims.user_id, claims.role, operation, fn.__name__,
                )
            return fn(*args, claims=claims, operation=operation, **kwargs)
        return wrapper
    return decorator


# ── FastAPI integration helpers ───────────────────────────────────────────────

def make_fastapi_middleware(jwt_handler, rate_limiter=None):
    """
    Return a FastAPI middleware class that validates JWT
    for all paths not in the exempt list.
    """
    from fastapi import Request
    from fastapi.responses import JSONResponse
    from starlette.middleware.base import BaseHTTPMiddleware

    EXEMPT_PATHS = {"/api/auth/login", "/api/auth/refresh", "/api/health", "/", "/docs", "/openapi.json"}

    class JWTAuthMiddleware(BaseHTTPMiddleware):
        async def dispatch(self, request: Request, call_next):
            if request.url.path in EXEMPT_PATHS or request.method == "OPTIONS":
                return await call_next(request)

            auth_header = request.headers.get("Authorization", "")
            if not auth_header.startswith("Bearer "):
                return JSONResponse(
                    {"error": "Unauthorized", "message": "Bearer token required"}, status_code=401
                )

            token = auth_header[7:]
            from .jwt_handler import TokenError
            try:
                claims = jwt_handler.verify_token(token)
                request.state.claims = claims
            except TokenError as e:
                return JSONResponse({"error": "Unauthorized", "message": str(e)}, status_code=401)

            if rate_limiter:
                from .rate_limiter import RateLimitExceeded
                try:
                    rate_limiter.check(
                        user_id=claims.user_id,
                        endpoint=request.url.path,
                        client_ip=request.client.host if request.client else ""
                    )
                except RateLimitExceeded as e:
                    return JSONResponse(
                        {"error": "Too Many Requests", "retry_after": e.retry_after}, status_code=429
                    )

            return await call_next(request)

    return JWTAuthMiddleware


def register_fastapi_exception_handlers(app) -> None:
    """Register 401/403 JSON exception handlers on a FastAPI app."""
    from fastapi import Request
    from fastapi.responses import JSONResponse

    @app.exception_handler(AuthenticationError)
    async def handle_auth(request: Request, exc: AuthenticationError):
        return JSONResponse({"error": "Unauthorized", "message": str(exc)}, status_code=401)

    @app.exception_handler(AuthorizationError)
    async def handle_authz(request: Request, exc: AuthorizationError):
        return JSONResponse({"error": "Forbidden", "message": str(exc)}, status_code=403)


# ── Backward-compat aliases for any code still calling flask_auth_error_handlers ──

def flask_auth_error_handlers(app) -> None:
    """Deprecated: use register_fastapi_exception_handlers instead."""
    log.warning("flask_auth_error_handlers is deprecated — use register_fastapi_exception_handlers()")
    register_fastapi_exception_handlers(app)


# ── Internal utilities ────────────────────────────────────────────────────────

def _check_permissions(claims, permissions: List[str], fn_name: str) -> None:
    if claims.role == "admin":
        return  # Admin bypasses all permission checks
    missing = [p for p in permissions if p not in claims.permissions]
    if missing:
        log.warning(
            "authz: DENIED user='%s' role='%s' missing_perms=%s fn='%s'",
            claims.user_id, claims.role, missing, fn_name,
        )
        raise AuthorizationError(f"Missing permissions: {', '.join(missing)}")
    log.debug("authz: permissions OK user='%s' perms=%s", claims.user_id, permissions)


def _extract_bearer_token_fastapi(kwargs: dict) -> Optional[str]:
    """Extract Bearer token from FastAPI kwargs (authorization header)."""
    # Injected via Header(default="") or request.headers
    for key in ("authorization", "Authorization"):
        val = kwargs.get(key, "")
        if isinstance(val, str) and val.startswith("Bearer "):
            return val[7:]
    return None


_global_jwt_handler = None

def set_global_jwt_handler(handler) -> None:
    global _global_jwt_handler
    _global_jwt_handler = handler

def _get_global_jwt_handler():
    if _global_jwt_handler is None:
        raise AuthenticationError("No JWT handler configured — call set_global_jwt_handler()")
    return _global_jwt_handler
