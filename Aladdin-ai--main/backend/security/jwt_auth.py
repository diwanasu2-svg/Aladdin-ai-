"""
Phase 12 — Task 6 (JWT Secret from ENV) + Task 23 (Use PyJWT library).
JWT authentication using PyJWT. Secret is mandatory from environment.
Task 5: Revoked tokens persisted to SQLite.
"""
from __future__ import annotations

import logging
import os
import sqlite3
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)

# ── Task 6: Mandatory JWT_SECRET from env ──────────────────────────────────────
_JWT_SECRET_RAW = os.getenv("JWT_SECRET", "")
_IS_TEST = "pytest" in sys.modules or os.getenv("TESTING", "").lower() in ("1", "true", "yes")

if not _JWT_SECRET_RAW:
    if _IS_TEST:
        _JWT_SECRET_RAW = "test-dummy-secret-do-not-use-in-production"
        logger.warning("JWT_SECRET not set — using test dummy (TESTING mode)")
    else:
        logger.critical(
            "FATAL: JWT_SECRET environment variable is not set. "
            "Set a strong random secret before starting the server."
        )
        raise RuntimeError(
            "JWT_SECRET environment variable is required. "
            "Generate one with: python -c \"import secrets; log.info(secrets.token_hex(32))\""
        )

JWT_SECRET = _JWT_SECRET_RAW
JWT_ALGORITHM = "HS256"
ACCESS_TOKEN_TTL  = int(os.getenv("ACCESS_TOKEN_TTL",  str(3600)))
REFRESH_TOKEN_TTL = int(os.getenv("REFRESH_TOKEN_TTL", str(30 * 86400)))
ISSUER = "aladdin-ai"

# ── Task 5: Revoked tokens DB ──────────────────────────────────────────────────
_REVOKED_DB_PATH = Path(os.getenv("REVOKED_TOKENS_DB", "data/revoked_tokens.sqlite"))
_revoked_jtis: set[str] = set()


def _init_revoked_db():
    try:
        _REVOKED_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(str(_REVOKED_DB_PATH)) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS revoked_tokens (
                    jti TEXT PRIMARY KEY,
                    user_id TEXT,
                    revoked_at REAL NOT NULL,
                    expires_at REAL NOT NULL
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_expires ON revoked_tokens(expires_at)")
            conn.commit()
        _load_revoked_tokens()
    except Exception as exc:
        logger.warning("Revoked tokens DB init failed: %s", exc)


def _load_revoked_tokens():
    global _revoked_jtis
    try:
        now = time.time()
        with sqlite3.connect(str(_REVOKED_DB_PATH)) as conn:
            rows = conn.execute(
                "SELECT jti FROM revoked_tokens WHERE expires_at > ?", (now,)
            ).fetchall()
        _revoked_jtis = {row[0] for row in rows}
        logger.info("Loaded %d revoked tokens from DB", len(_revoked_jtis))
    except Exception as exc:
        logger.warning("Load revoked tokens failed: %s", exc)


def _persist_revoked(jti: str, user_id: str, expires_at: float):
    try:
        with sqlite3.connect(str(_REVOKED_DB_PATH)) as conn:
            conn.execute(
                "INSERT OR REPLACE INTO revoked_tokens (jti, user_id, revoked_at, expires_at) VALUES (?,?,?,?)",
                (jti, user_id, time.time(), expires_at),
            )
            conn.commit()
    except Exception as exc:
        logger.warning("Persist revoked token failed: %s", exc)


def cleanup_expired_revoked_tokens():
    """Remove expired revoked tokens — call periodically."""
    try:
        now = time.time()
        with sqlite3.connect(str(_REVOKED_DB_PATH)) as conn:
            deleted = conn.execute(
                "DELETE FROM revoked_tokens WHERE expires_at < ?", (now,)
            ).rowcount
            conn.commit()
        if deleted:
            global _revoked_jtis
            _revoked_jtis = {jti for jti in _revoked_jtis}
            _load_revoked_tokens()
            logger.info("Cleaned up %d expired revoked tokens", deleted)
    except Exception as exc:
        logger.warning("Cleanup revoked tokens failed: %s", exc)


_init_revoked_db()

# ── Task 23: Use PyJWT library ─────────────────────────────────────────────────
try:
    import jwt as pyjwt
    _USE_PYJWT = True
    logger.info("JWT: using PyJWT library")
except ImportError:
    _USE_PYJWT = False
    logger.warning("PyJWT not installed — falling back to manual JWT implementation. "
                   "Run: pip install PyJWT")


# ── Encoding / Decoding ───────────────────────────────────────────────────────

def _encode_jwt(payload: dict) -> str:
    if _USE_PYJWT:
        return pyjwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)
    import base64, hmac as _hmac, hashlib, json
    def b64url(data: bytes) -> str:
        return base64.urlsafe_b64encode(data).rstrip(b"=").decode()
    header = b64url(json.dumps({"alg": JWT_ALGORITHM, "typ": "JWT"}).encode())
    body = b64url(json.dumps(payload).encode())
    sig = b64url(_hmac.new(JWT_SECRET.encode(), f"{header}.{body}".encode(), hashlib.sha256).digest())
    return f"{header}.{body}.{sig}"


def _decode_jwt(token: str) -> Optional[Dict[str, Any]]:
    if _USE_PYJWT:
        try:
            return pyjwt.decode(
                token, JWT_SECRET, algorithms=[JWT_ALGORITHM],
                options={"verify_exp": False},
            )
        except Exception as exc:
            logger.warning("PyJWT decode error: %s", exc)
            return None
    import base64, hmac as _hmac, hashlib, json
    try:
        parts = token.split(".")
        if len(parts) != 3:
            return None
        h, b, sig = parts
        def b64url_dec(s):
            return base64.urlsafe_b64decode(s + "=" * (4 - len(s) % 4))
        expected = base64.urlsafe_b64encode(
            _hmac.new(JWT_SECRET.encode(), f"{h}.{b}".encode(), hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        if not _hmac.compare_digest(sig, expected):
            return None
        return json.loads(b64url_dec(b))
    except Exception as exc:
        logger.error("Token decode error: %s", exc)
        return None


# ── Public API ─────────────────────────────────────────────────────────────────

def create_access_token(user_id: str, role: str = "user", scope: str = "") -> str:
    now = int(time.time())
    import base64
    jti = base64.urlsafe_b64encode(os.urandom(16)).rstrip(b"=").decode()
    payload = {
        "sub": user_id, "role": role, "scope": scope,
        "iat": now, "exp": now + ACCESS_TOKEN_TTL,
        "iss": ISSUER, "jti": jti, "type": "access",
    }
    token = _encode_jwt(payload)
    logger.info("Access token created for user=%s role=%s", user_id, role)
    return token


def create_refresh_token(user_id: str) -> str:
    now = int(time.time())
    import base64
    jti = base64.urlsafe_b64encode(os.urandom(16)).rstrip(b"=").decode()
    payload = {
        "sub": user_id, "iat": now, "exp": now + REFRESH_TOKEN_TTL,
        "iss": ISSUER, "jti": jti, "type": "refresh",
    }
    return _encode_jwt(payload)


def verify_token(token: str, expected_type: str = "access") -> Optional[Dict[str, Any]]:
    """Verify a JWT and return its payload, or None if invalid/expired/revoked."""
    payload = _decode_jwt(token)
    if payload is None:
        return None
    now = int(time.time())
    if payload.get("exp", 0) < now:
        logger.warning("Token expired for user=%s", payload.get("sub"))
        return None
    if payload.get("iss") != ISSUER:
        logger.warning("Token issuer mismatch: %s", payload.get("iss"))
        return None
    if payload.get("type") != expected_type:
        logger.warning("Token type mismatch: expected=%s got=%s", expected_type, payload.get("type"))
        return None
    jti = payload.get("jti", "")
    if jti and jti in _revoked_jtis:
        logger.warning("Token JTI is revoked: %s", jti)
        return None
    return payload


def refresh_access_token(refresh_token: str) -> Optional[Dict]:
    payload = verify_token(refresh_token, expected_type="refresh")
    if payload is None:
        logger.warning("Invalid refresh token")
        return None
    jti = payload.get("jti", "")
    if jti:
        _revoked_jtis.add(jti)
        _persist_revoked(jti, payload.get("sub", ""), payload.get("exp", 0))
    user_id = payload["sub"]
    role = payload.get("role", "user")
    scope = payload.get("scope", "")
    new_access = create_access_token(user_id, role, scope)
    new_refresh = create_refresh_token(user_id)
    logger.info("Token pair refreshed for user=%s", user_id)
    return {
        "access_token": new_access,
        "refresh_token": new_refresh,
        "token_type": "Bearer",
        "expires_in": ACCESS_TOKEN_TTL,
    }


def revoke_token(token: str) -> bool:
    payload = _decode_jwt(token)
    if payload and payload.get("jti"):
        jti = payload["jti"]
        _revoked_jtis.add(jti)
        _persist_revoked(jti, payload.get("sub", ""), payload.get("exp", time.time() + 3600))
        logger.info("Token revoked for user=%s jti=%s", payload.get("sub"), jti)
        return True
    return False


@dataclass
class TokenPair:
    access_token: str
    refresh_token: str
    token_type: str = "Bearer"
    expires_in: int = ACCESS_TOKEN_TTL

    def to_dict(self) -> dict:
        return {
            "access_token": self.access_token,
            "refresh_token": self.refresh_token,
            "token_type": self.token_type,
            "expires_in": self.expires_in,
        }


class JWTAuthManager:
    def login(self, user_id: str, role: str = "user", scope: str = "") -> TokenPair:
        return TokenPair(
            access_token=create_access_token(user_id, role, scope),
            refresh_token=create_refresh_token(user_id),
        )

    def verify(self, token: str) -> Optional[Dict[str, Any]]:
        return verify_token(token)

    def refresh(self, refresh_token: str) -> Optional[Dict]:
        return refresh_access_token(refresh_token)

    def revoke(self, token: str) -> bool:
        return revoke_token(token)
