"""
Task 4 — Real Authentication: SQLite users table + User model + bcrypt hashing.
"""
from __future__ import annotations

import logging
import os
import re
import sqlite3
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

logger = logging.getLogger(__name__)

_USERS_DB_PATH = Path(os.getenv("USERS_DB", "data/users.sqlite"))

# ── Password validation ────────────────────────────────────────────────────────
_MIN_PASSWORD_LEN = 8
_PASSWORD_PATTERN = re.compile(
    r"^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$"
)


def validate_password(password: str) -> tuple[bool, str]:
    """Returns (valid, error_message)."""
    if len(password) < _MIN_PASSWORD_LEN:
        return False, f"Password must be at least {_MIN_PASSWORD_LEN} characters long"
    if not _PASSWORD_PATTERN.match(password):
        return False, "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
    return True, ""


# ── bcrypt helper ──────────────────────────────────────────────────────────────
try:
    import bcrypt as _bcrypt

    def hash_password(password: str) -> str:
        return _bcrypt.hashpw(password.encode("utf-8"), _bcrypt.gensalt()).decode("utf-8")

    def verify_password(password: str, hashed: str) -> bool:
        try:
            return _bcrypt.checkpw(password.encode("utf-8"), hashed.encode("utf-8"))
        except Exception:
            return False

    logger.info("Password hashing: using bcrypt")

except ImportError:
    import hashlib, hmac, base64, secrets
    logger.warning("bcrypt not installed — using PBKDF2-HMAC-SHA256 fallback. Install bcrypt for production.")

    def hash_password(password: str) -> str:
        salt = secrets.token_hex(32)
        dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode(), 600_000)
        return f"pbkdf2:sha256:{salt}:{base64.b64encode(dk).decode()}"

    def verify_password(password: str, hashed: str) -> bool:
        try:
            if hashed.startswith("pbkdf2:sha256:"):
                _, _, salt, stored = hashed.split(":", 3)
                dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode(), 600_000)
                return hmac.compare_digest(base64.b64encode(dk).decode(), stored)
            return False
        except Exception:
            return False


# ── User model ────────────────────────────────────────────────────────────────
@dataclass
class User:
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    username: str = ""
    email: str = ""
    password_hash: str = ""
    role: str = "user"
    is_active: bool = True
    created_at: float = field(default_factory=time.time)
    last_login: Optional[float] = None

    def to_dict(self, include_sensitive: bool = False) -> dict:
        d = {
            "id": self.id,
            "username": self.username,
            "email": self.email,
            "role": self.role,
            "is_active": self.is_active,
            "created_at": self.created_at,
            "last_login": self.last_login,
        }
        if include_sensitive:
            d["password_hash"] = self.password_hash
        return d


# ── Database ───────────────────────────────────────────────────────────────────
def _get_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(str(_USERS_DB_PATH))
    conn.execute("PRAGMA foreign_keys = ON")
    conn.row_factory = sqlite3.Row
    return conn


def init_users_db():
    try:
        _USERS_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        with _get_conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    username TEXT UNIQUE NOT NULL,
                    email TEXT UNIQUE,
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'user',
                    is_active INTEGER NOT NULL DEFAULT 1,
                    created_at REAL NOT NULL,
                    last_login REAL
                )
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_users_username ON users(username)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)")
            conn.commit()
        logger.info("Users DB initialized at %s", _USERS_DB_PATH)
    except Exception as exc:
        logger.error("Users DB init failed: %s", exc)
        raise


def create_user(username: str, password: str, email: str = "", role: str = "user") -> User:
    valid, err = validate_password(password)
    if not valid:
        raise ValueError(f"Password validation failed: {err}")

    user = User(
        username=username.strip(),
        email=email.strip(),
        password_hash=hash_password(password),
        role=role,
    )
    try:
        with _get_conn() as conn:
            conn.execute(
                "INSERT INTO users (id, username, email, password_hash, role, is_active, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (user.id, user.username, user.email, user.password_hash,
                 user.role, 1, user.created_at),
            )
            conn.commit()
        logger.info("User created: username=%s role=%s", username, role)
        return user
    except sqlite3.IntegrityError as exc:
        raise ValueError(f"Username or email already exists: {exc}") from exc


def get_user_by_username(username: str) -> Optional[User]:
    try:
        with _get_conn() as conn:
            row = conn.execute(
                "SELECT * FROM users WHERE username = ? AND is_active = 1", (username,)
            ).fetchone()
        if row is None:
            return None
        return User(
            id=row["id"], username=row["username"], email=row["email"] or "",
            password_hash=row["password_hash"], role=row["role"],
            is_active=bool(row["is_active"]), created_at=row["created_at"],
            last_login=row["last_login"],
        )
    except Exception as exc:
        logger.error("get_user_by_username error: %s", exc)
        return None


def get_user_by_id(user_id: str) -> Optional[User]:
    try:
        with _get_conn() as conn:
            row = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
        if row is None:
            return None
        return User(
            id=row["id"], username=row["username"], email=row["email"] or "",
            password_hash=row["password_hash"], role=row["role"],
            is_active=bool(row["is_active"]), created_at=row["created_at"],
            last_login=row["last_login"],
        )
    except Exception as exc:
        logger.error("get_user_by_id error: %s", exc)
        return None


def update_last_login(user_id: str):
    try:
        with _get_conn() as conn:
            conn.execute("UPDATE users SET last_login = ? WHERE id = ?", (time.time(), user_id))
            conn.commit()
    except Exception as exc:
        logger.warning("update_last_login failed: %s", exc)


def authenticate_user(username: str, password: str) -> Optional[User]:
    """Verify credentials and return User on success, None on failure."""
    user = get_user_by_username(username)
    if user is None:
        verify_password(password, "dummy-hash-to-prevent-timing-attack")
        return None
    if not verify_password(password, user.password_hash):
        logger.warning("Login failed: bad password for user=%s", username)
        return None
    update_last_login(user.id)
    logger.info("Login successful: user=%s role=%s", username, user.role)
    return user


def list_users() -> List[User]:
    try:
        with _get_conn() as conn:
            rows = conn.execute("SELECT * FROM users ORDER BY created_at DESC").fetchall()
        return [User(
            id=r["id"], username=r["username"], email=r["email"] or "",
            password_hash=r["password_hash"], role=r["role"],
            is_active=bool(r["is_active"]), created_at=r["created_at"],
            last_login=r["last_login"],
        ) for r in rows]
    except Exception as exc:
        logger.error("list_users error: %s", exc)
        return []


init_users_db()
