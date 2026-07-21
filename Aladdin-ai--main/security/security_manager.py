"""
security/security_manager.py — Phase 12 Feature 6
==================================================
Centralised SecurityManager singleton — integrates all security subsystems.

Features:
- Singleton pattern
- Authenticates users and issues token pairs
- Authorises operations via RBAC
- Enforces rate limits
- Validates inputs
- Guards file and shell access
- Before/after hooks for audit trail
- Event emission to AuditLogger
"""

from __future__ import annotations

import logging
import os
import threading
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class SecurityEvent(str, Enum):
    LOGIN_SUCCESS   = "login_success"
    LOGIN_FAILURE   = "login_failure"
    TOKEN_ISSUED    = "token_issued"
    TOKEN_REVOKED   = "token_revoked"
    ACCESS_GRANTED  = "access_granted"
    ACCESS_DENIED   = "access_denied"
    RATE_LIMITED    = "rate_limited"
    INPUT_REJECTED  = "input_rejected"
    FILE_ACCESS     = "file_access"
    SHELL_COMMAND   = "shell_command"
    SECRET_ACCESSED = "secret_accessed"


@dataclass
class SecurityPolicy:
    name: str
    required_role: str = "user"
    required_permissions: List[str] = field(default_factory=list)
    audit_required: bool = True
    extra_verification: bool = False


DEFAULT_POLICIES: Dict[str, SecurityPolicy] = {
    "read":         SecurityPolicy("read",         required_role="guest"),
    "write":        SecurityPolicy("write",        required_role="user",  required_permissions=["write"]),
    "delete":       SecurityPolicy("delete",       required_role="user",  required_permissions=["delete"],   extra_verification=True),
    "admin":        SecurityPolicy("admin",        required_role="admin", required_permissions=["admin"]),
    "file_access":  SecurityPolicy("file_access",  required_role="user"),
    "shell_command":SecurityPolicy("shell_command",required_role="admin", extra_verification=True),
    "secret_access":SecurityPolicy("secret_access",required_role="admin", extra_verification=True),
}

_ROLE_HIERARCHY = {"guest": 0, "user": 1, "moderator": 2, "admin": 3}


class SecurityManager:
    """
    Centralised security control.

    Usage::

        sm = SecurityManager()
        sm.initialise(secret_key="jwt-key")

        pair = sm.authenticate("alice", "pwd", verify_fn=my_verify)
        claims = sm.jwt.verify_token(pair.access_token)
        sm.authorise(claims, "delete", resource="record/42")
        sm.check_rate_limit(claims.user_id, "/api/delete")
    """

    _instance: Optional["SecurityManager"] = None
    _lock = threading.Lock()

    def __new__(cls, *args, **kwargs):
        with cls._lock:
            if cls._instance is None:
                cls._instance = super().__new__(cls)
                cls._instance._ready = False
        return cls._instance

    def __init__(self) -> None:
        if self._ready:
            return
        self._policies: Dict[str, SecurityPolicy] = dict(DEFAULT_POLICIES)
        self.jwt = None
        self.rate_limiter = None
        self.audit_logger = None
        self.input_validator = None
        self.key_storage = None
        self.encryption_manager = None
        self._hooks_before: List[Callable] = []
        self._hooks_after: List[Callable] = []
        self._ready = True
        log.info("SecurityManager: singleton created — call initialise()")

    def initialise(
        self,
        *,
        secret_key: Optional[str] = None,
        log_path: Optional[str] = None,
        redis_url: Optional[str] = None,
    ) -> None:
        """Wire up all security subsystems."""
        from .jwt_handler import JWTHandler
        from .rate_limiter import RateLimiter, RateLimitConfig
        from .audit_logger import AuditLogger
        from .input_validator import InputValidator
        from .key_storage import KeyStorage
        from .encryption_manager import EncryptionManager

        self.encryption_manager = EncryptionManager()
        self.jwt = JWTHandler(secret_key=secret_key or os.environ.get("JWT_SECRET_KEY"))
        self.rate_limiter = RateLimiter(
            default_config=RateLimitConfig(100, 60, 30),
            redis_url=redis_url or os.environ.get("REDIS_URL"),
        )
        self.audit_logger = AuditLogger(log_path=log_path or os.environ.get("AUDIT_LOG_PATH", "logs/audit.log"))
        self.input_validator = InputValidator()
        self.key_storage = KeyStorage(encryption_manager=self.encryption_manager)
        self.key_storage.load_env()
        log.info("SecurityManager: all subsystems initialised")

    # ── Before/after hooks ────────────────────────────────────────────────────

    def add_before_hook(self, fn: Callable) -> None:
        self._hooks_before.append(fn)

    def add_after_hook(self, fn: Callable) -> None:
        self._hooks_after.append(fn)

    def _run_hooks(self, hooks: List[Callable], *args) -> None:
        for h in hooks:
            try:
                h(*args)
            except Exception as e:
                log.warning("SecurityManager hook error: %s", e)

    # ── Authentication ────────────────────────────────────────────────────────

    def authenticate(
        self,
        user_id: str,
        credential: str,
        *,
        verify_fn: Optional[Callable] = None,
    ):
        """
        Verify credentials and issue a token pair.

        verify_fn(user_id, credential) → (success: bool, role: str, permissions: list)
        """
        self._run_hooks(self._hooks_before, "authenticate", user_id)

        if verify_fn is None:
            self._emit(SecurityEvent.LOGIN_FAILURE, user_id=user_id, detail="no verify_fn — fail-closed")
            raise PermissionError(
                "SecurityManager.authenticate() requires verify_fn(user_id, credential) "
                "→ (bool, role, permissions). Pass your credential-checking function."
            )

        try:
            result = verify_fn(user_id, credential)
            success, role, permissions = (result if isinstance(result, tuple) and len(result) == 3
                                          else (bool(result), "user", []))
        except Exception as e:
            self._emit(SecurityEvent.LOGIN_FAILURE, user_id=user_id, detail=str(e))
            raise PermissionError("Authentication failed") from e

        if not success:
            self._emit(SecurityEvent.LOGIN_FAILURE, user_id=user_id, detail="bad credentials")
            raise PermissionError("Authentication failed")

        self._emit(SecurityEvent.LOGIN_SUCCESS, user_id=user_id)
        pair = self.jwt.create_token_pair(user_id, role=role, permissions=permissions)
        self._emit(SecurityEvent.TOKEN_ISSUED, user_id=user_id)
        self._run_hooks(self._hooks_after, "authenticate", user_id)
        return pair

    def logout(self, access_token: str) -> None:
        from .jwt_handler import TokenError
        try:
            claims = self.jwt.verify_token(access_token)
            self.jwt.revoke_token(access_token)
            self._emit(SecurityEvent.TOKEN_REVOKED, user_id=claims.user_id)
        except TokenError as e:
            log.warning("logout: %s", e)

    # ── Authorisation ─────────────────────────────────────────────────────────

    def authorise(self, claims, policy_name: str, resource: str = "") -> None:
        """
        Check if user is authorised. Raises PermissionError on denial.
        Admin role bypasses all policy checks.
        """
        policy = self._policies.get(policy_name, SecurityPolicy(policy_name))
        role_ok = _ROLE_HIERARCHY.get(claims.role, -1) >= _ROLE_HIERARCHY.get(policy.required_role, 0)
        perms_ok = (claims.role == "admin" or
                    all(p in claims.permissions for p in policy.required_permissions))

        if not (role_ok and perms_ok):
            self._emit(SecurityEvent.ACCESS_DENIED, user_id=claims.user_id,
                       detail=f"policy={policy_name} resource={resource}")
            raise PermissionError(f"Access denied for policy '{policy_name}'")

        if policy.extra_verification:
            log.warning("SENSITIVE: user=%s policy=%s resource=%s", claims.user_id, policy_name, resource)

        if policy.audit_required:
            self._emit(SecurityEvent.ACCESS_GRANTED, user_id=claims.user_id,
                       detail=f"policy={policy_name} resource={resource}")

    def add_policy(self, policy: SecurityPolicy) -> None:
        self._policies[policy.name] = policy

    # ── Rate limiting ─────────────────────────────────────────────────────────

    def check_rate_limit(self, user_id: str, endpoint: str = "", client_ip: str = "") -> None:
        if self.rate_limiter:
            self.rate_limiter.check(user_id=user_id, endpoint=endpoint, client_ip=client_ip)

    # ── File / shell / secret guards ──────────────────────────────────────────

    def verify_file_access(self, claims, path: str, mode: str = "r") -> None:
        self.authorise(claims, "file_access", resource=f"{mode}:{path}")
        self._emit(SecurityEvent.FILE_ACCESS, user_id=claims.user_id, detail=f"{mode}:{path}")

    def verify_shell_command(self, claims, command: str) -> None:
        self.authorise(claims, "shell_command", resource=command)
        self._emit(SecurityEvent.SHELL_COMMAND, user_id=claims.user_id, detail=command)

    def get_secret(self, claims, secret_name: str) -> Optional[str]:
        self.authorise(claims, "secret_access", resource=secret_name)
        self._emit(SecurityEvent.SECRET_ACCESSED, user_id=claims.user_id, detail=secret_name)
        if self.key_storage:
            return self.key_storage.get(secret_name)
        return os.environ.get(secret_name)

    # ── Input validation ──────────────────────────────────────────────────────

    def validate_input(self, data: Any, context: str = "") -> Any:
        if self.input_validator is None:
            return data
        result = self.input_validator.validate(data, context=context)
        if not result.valid:
            self._emit(SecurityEvent.INPUT_REJECTED, detail=f"context={context} reason={result.error}")
        return result.sanitized

    # ── Internal ──────────────────────────────────────────────────────────────

    def _emit(self, event: SecurityEvent, *, user_id: str = "system", detail: str = "") -> None:
        if self.audit_logger:
            self.audit_logger.log(event.value, user_id=user_id, detail=detail)
        else:
            log.info("SEC: %s user=%s %s", event.value, user_id, detail)

    def status(self) -> dict:
        return {
            "jwt_ready": self.jwt is not None,
            "rate_limiter_ready": self.rate_limiter is not None,
            "audit_logger_ready": self.audit_logger is not None,
            "input_validator_ready": self.input_validator is not None,
            "key_storage_ready": self.key_storage is not None,
            "policies": list(self._policies.keys()),
        }
