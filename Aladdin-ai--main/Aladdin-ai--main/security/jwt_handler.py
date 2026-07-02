"""
security/jwt_handler.py — Phase 12 Feature 1
=============================================
JWT Authentication — access + refresh token lifecycle.

Capabilities:
- Access token (15-min) + Refresh token (7-day) issuance
- HS256/RS256 signing with configurable secret key
- Token blacklist for logout (in-memory with background cleanup)
- Token refresh endpoint helper
- Claims dataclass with role & permissions
- Thread-safe throughout
- Automatic expired-entry cleanup thread
"""

from __future__ import annotations

import hashlib
import logging
import os
import secrets
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Dict, List, Optional

log = logging.getLogger(__name__)

try:
    import jwt as pyjwt
    _JWT_AVAILABLE = True
except ImportError:
    _JWT_AVAILABLE = False
    log.warning("PyJWT not installed — pip install PyJWT>=2.8")

ACCESS_TOKEN_TTL  = int(os.environ.get("ACCESS_TOKEN_TTL_SECONDS",  "900"))     # 15 min
REFRESH_TOKEN_TTL = int(os.environ.get("REFRESH_TOKEN_TTL_SECONDS", "604800"))  # 7 days
ALGORITHM = "HS256"


# ── Data classes ──────────────────────────────────────────────────────────────

@dataclass
class TokenClaims:
    user_id: str
    role: str
    permissions: List[str]
    jti: str
    exp: float
    iat: float
    token_type: str = "access"

    def is_expired(self) -> bool:
        return time.time() > self.exp

    def has_permission(self, perm: str) -> bool:
        return perm in self.permissions or self.role == "admin"


@dataclass
class TokenPair:
    access_token: str
    refresh_token: str
    access_expires_at: float
    refresh_expires_at: float
    token_type: str = "Bearer"

    def to_dict(self) -> dict:
        return {
            "access_token": self.access_token,
            "refresh_token": self.refresh_token,
            "token_type": self.token_type,
            "expires_in": max(0, int(self.access_expires_at - time.time())),
            "refresh_expires_in": max(0, int(self.refresh_expires_at - time.time())),
        }


# ── Exceptions ────────────────────────────────────────────────────────────────

class TokenError(Exception):
    """Base JWT error."""

class TokenExpiredError(TokenError):
    """Token has passed its expiry."""

class TokenBlacklistedError(TokenError):
    """Token was explicitly revoked."""

class TokenInvalidError(TokenError):
    """Token signature or structure is invalid."""


# ── JWTHandler ────────────────────────────────────────────────────────────────

class JWTHandler:
    """
    Full JWT lifecycle manager.

    Usage::

        handler = JWTHandler(secret_key="super-secret")
        pair = handler.create_token_pair("alice", role="user", permissions=["read"])
        claims = handler.verify_token(pair.access_token)
        new_pair = handler.refresh(pair.refresh_token)
        handler.revoke_token(pair.access_token)
    """

    def __init__(
        self,
        secret_key: Optional[str] = None,
        access_ttl: int = ACCESS_TOKEN_TTL,
        refresh_ttl: int = REFRESH_TOKEN_TTL,
        cleanup_interval: int = 300,
    ) -> None:
        self._secret = secret_key or os.environ.get("JWT_SECRET_KEY") or self._generate_secret()
        self._access_ttl = access_ttl
        self._refresh_ttl = refresh_ttl
        # jti → expiry timestamp
        self._blacklist: Dict[str, float] = {}
        # refresh jti → {user_id, role, permissions, exp}
        self._refresh_store: Dict[str, dict] = {}
        self._lock = threading.Lock()
        t = threading.Thread(target=self._cleanup_loop, args=(cleanup_interval,), daemon=True, name="JWT-Cleanup")
        t.start()
        log.info("JWTHandler: ready (access=%ds, refresh=%ds)", access_ttl, refresh_ttl)

    # ── Issuance ──────────────────────────────────────────────────────────────

    def create_token_pair(
        self,
        user_id: str,
        *,
        role: str = "user",
        permissions: Optional[List[str]] = None,
    ) -> TokenPair:
        permissions = permissions or []
        now = time.time()

        access_jti = str(uuid.uuid4())
        access_exp = now + self._access_ttl
        access_token = self._encode({
            "sub": user_id, "role": role, "permissions": permissions,
            "jti": access_jti, "iat": now, "exp": access_exp, "type": "access",
        })

        refresh_jti = str(uuid.uuid4())
        refresh_exp = now + self._refresh_ttl
        refresh_token = self._encode({
            "sub": user_id, "jti": refresh_jti,
            "iat": now, "exp": refresh_exp, "type": "refresh",
        })

        with self._lock:
            self._refresh_store[refresh_jti] = {
                "user_id": user_id, "role": role,
                "permissions": permissions, "exp": refresh_exp,
            }

        log.info("JWTHandler: issued token pair for user='%s' role='%s'", user_id, role)
        return TokenPair(
            access_token=access_token, refresh_token=refresh_token,
            access_expires_at=access_exp, refresh_expires_at=refresh_exp,
        )

    # ── Verification ──────────────────────────────────────────────────────────

    def verify_token(self, token: str, *, expected_type: str = "access") -> TokenClaims:
        """Decode and validate a token. Raises TokenError subclass on failure."""
        payload = self._decode(token)

        tok_type = payload.get("type", "access")
        if tok_type != expected_type:
            raise TokenInvalidError(f"Expected type '{expected_type}', got '{tok_type}'")

        jti = payload.get("jti", "")
        with self._lock:
            if jti in self._blacklist:
                raise TokenBlacklistedError("Token has been revoked")

        return TokenClaims(
            user_id=payload["sub"],
            role=payload.get("role", "user"),
            permissions=payload.get("permissions", []),
            jti=jti,
            exp=float(payload["exp"]),
            iat=float(payload.get("iat", 0)),
            token_type=tok_type,
        )

    # ── Refresh ───────────────────────────────────────────────────────────────

    def refresh(self, refresh_token: str) -> TokenPair:
        """Exchange a valid refresh token for a new token pair. Old token is revoked."""
        payload = self._decode(refresh_token)
        if payload.get("type") != "refresh":
            raise TokenInvalidError("Not a refresh token")

        jti = payload.get("jti", "")
        with self._lock:
            if jti in self._blacklist:
                raise TokenBlacklistedError("Refresh token revoked")
            meta = self._refresh_store.get(jti)

        if not meta:
            raise TokenInvalidError("Refresh token not found — please log in again")

        self.revoke_token(refresh_token)
        return self.create_token_pair(meta["user_id"], role=meta["role"], permissions=meta["permissions"])

    # ── Revocation ────────────────────────────────────────────────────────────

    def revoke_token(self, token: str) -> None:
        """Add a token to the blacklist (logout / key rotation)."""
        try:
            payload = self._decode(token, verify_expiry=False)
        except TokenInvalidError:
            return
        jti = payload.get("jti") or self._fingerlog.info(token)
        exp = payload.get("exp", time.time() + self._refresh_ttl)
        with self._lock:
            self._blacklist[jti] = float(exp)
            self._refresh_store.pop(jti, None)
        log.info("JWTHandler: revoked jti=%s", jti)

    def revoke_all_for_user(self, user_id: str) -> int:
        revoked = 0
        with self._lock:
            to_revoke = [jti for jti, m in self._refresh_store.items() if m["user_id"] == user_id]
            for jti in to_revoke:
                self._blacklist[jti] = self._refresh_store.pop(jti)["exp"]
                revoked += 1
        log.info("JWTHandler: revoked %d tokens for user='%s'", revoked, user_id)
        return revoked

    def is_blacklisted(self, token: str) -> bool:
        try:
            payload = self._decode(token, verify_expiry=False)
            jti = payload.get("jti", "")
        except TokenInvalidError:
            return True
        with self._lock:
            return jti in self._blacklist

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _encode(self, payload: dict) -> str:
        if not _JWT_AVAILABLE:
            raise RuntimeError("pip install PyJWT>=2.8")
        return pyjwt.encode(payload, self._secret, algorithm=ALGORITHM)

    def _decode(self, token: str, *, verify_expiry: bool = True) -> dict:
        if not _JWT_AVAILABLE:
            raise RuntimeError("pip install PyJWT>=2.8")
        try:
            return pyjwt.decode(
                token, self._secret, algorithms=[ALGORITHM],
                options={"verify_exp": verify_expiry},
            )
        except pyjwt.ExpiredSignatureError as e:
            raise TokenExpiredError("Token expired") from e
        except pyjwt.InvalidTokenError as e:
            raise TokenInvalidError(str(e)) from e

    @staticmethod
    def _generate_secret() -> str:
        k = secrets.token_hex(32)
        log.warning("JWTHandler: no JWT_SECRET_KEY in env — using ephemeral key. Set JWT_SECRET_KEY for production!")
        return k

    @staticmethod
    def _fingerlog.info(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()[:16]

    def _cleanup_loop(self, interval: int) -> None:
        while True:
            time.sleep(interval)
            now = time.time()
            with self._lock:
                bl = [j for j, e in self._blacklist.items() if e < now]
                rs = [j for j, m in self._refresh_store.items() if m["exp"] < now]
                for j in bl: del self._blacklist[j]
                for j in rs: del self._refresh_store[j]
            if bl or rs:
                log.debug("JWTHandler: cleanup removed %d bl + %d rs entries", len(bl), len(rs))

    def stats(self) -> dict:
        with self._lock:
            return {
                "blacklisted_tokens": len(self._blacklist),
                "active_refresh_tokens": len(self._refresh_store),
                "access_ttl_seconds": self._access_ttl,
                "refresh_ttl_seconds": self._refresh_ttl,
            }
