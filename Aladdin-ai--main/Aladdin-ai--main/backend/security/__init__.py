"""
Security backend — JWT auth, authorization middleware, rate limiting, HTTPS.
Task 12: SecurityManager singleton added.
"""
from .jwt_auth import JWTAuthManager, create_access_token, create_refresh_token, verify_token, revoke_token, refresh_access_token
from .auth_middleware import AuthMiddleware, require_auth, require_role, require_scope
from .rate_limiter import RateLimiter, rate_limit_dependency, RateLimitMiddleware
from .https_enforcement import HTTPSEnforcerMiddleware
from .fastapi_auth import get_current_user, require_admin, require_permission, CurrentUser
from .security_manager import security_manager, SecurityManager

__all__ = [
    "JWTAuthManager",
    "create_access_token",
    "create_refresh_token",
    "verify_token",
    "revoke_token",
    "refresh_access_token",
    "AuthMiddleware",
    "require_auth",
    "require_role",
    "require_scope",
    "RateLimiter",
    "rate_limit_dependency",
    "RateLimitMiddleware",
    "HTTPSEnforcerMiddleware",
    "get_current_user",
    "require_admin",
    "require_permission",
    "CurrentUser",
    "security_manager",
    "SecurityManager",
]
