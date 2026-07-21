"""
security — Phase 12: Security Foundation
=========================================
Provides JWT auth, RBAC, rate limiting, encryption, secure key storage,
security management, secret rotation, permission auditing, input validation,
and tamper-proof audit logging.

Quick start::

    from security import SecurityManager

    sm = SecurityManager()
    sm.initialise(secret_key="your-jwt-secret")

    pair = sm.authenticate("alice", "password", verify_fn=your_verify)
    claims = sm.jwt.verify_token(pair.access_token)
    sm.authorise(claims, "write", resource="memory/1")
"""

from .jwt_handler import JWTHandler, TokenClaims, TokenPair, TokenError, TokenExpiredError, TokenBlacklistedError, TokenInvalidError
from .auth_middleware import (
    require_auth, require_permission, require_role, require_extra_verification,
    AuthenticationError, AuthorizationError, make_flask_middleware, flask_auth_error_handlers,
)
from .rate_limiter import RateLimiter, RateLimitConfig, RateLimitExceeded
from .encryption_manager import EncryptionManager, get_tls_context, pin_certificate
from .key_storage import KeyStorage
from .security_manager import SecurityManager, SecurityPolicy, SecurityEvent
from .secret_rotator import SecretRotator
from .permission_auditor import PermissionAuditor, PermissionUsage
from .input_validator import InputValidator, ValidationResult, ValidationError
from .audit_logger import AuditLogger, AuditEntry

__all__ = [
    # JWT
    "JWTHandler", "TokenClaims", "TokenPair",
    "TokenError", "TokenExpiredError", "TokenBlacklistedError", "TokenInvalidError",
    # Auth middleware
    "require_auth", "require_permission", "require_role", "require_extra_verification",
    "AuthenticationError", "AuthorizationError",
    "make_flask_middleware", "flask_auth_error_handlers",
    # Rate limiting
    "RateLimiter", "RateLimitConfig", "RateLimitExceeded",
    # Encryption
    "EncryptionManager", "get_tls_context", "pin_certificate",
    # Key storage
    "KeyStorage",
    # Security manager (singleton)
    "SecurityManager", "SecurityPolicy", "SecurityEvent",
    # Secret rotation
    "SecretRotator",
    # Permission auditing
    "PermissionAuditor", "PermissionUsage",
    # Input validation
    "InputValidator", "ValidationResult", "ValidationError",
    # Audit logging
    "AuditLogger", "AuditEntry",
]

__version__ = "12.0.0"
