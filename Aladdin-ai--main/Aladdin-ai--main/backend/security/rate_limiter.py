"""
Phase 12 — Task 2: Rate Limiting rewritten for FastAPI.
Replaces Flask decorators with FastAPI Depends() and ASGI middleware.
Token bucket algorithm with in-memory storage.
"""
from __future__ import annotations

import asyncio
import logging
import sqlite3
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Optional, Tuple

from fastapi import Depends, HTTPException, Request

logger = logging.getLogger(__name__)

_DB_PATH = Path("data/rate_limits.sqlite")


@dataclass
class BucketState:
    tokens: float
    last_refill: float = field(default_factory=time.time)


class RateLimiter:
    """
    Token bucket rate limiter with optional SQLite persistence.
    capacity:    max tokens in the bucket
    refill_rate: tokens added per second
    """

    DEFAULT_LIMITS: Dict[str, Tuple[int, float]] = {
        "/api/auth/login":    (5, 0.05),
        "/api/auth/register": (3, 0.01),
        "/api/chat":          (60, 1.0),
        "/api/vision":        (20, 0.33),
        "/api/memory":        (100, 2.0),
        "default":            (120, 2.0),
    }

    def __init__(self, persist_path: Optional[Path] = None):
        self._buckets: Dict[str, BucketState] = {}
        self._lock = threading.Lock()
        self._persist_path = persist_path or _DB_PATH
        self._init_db()
        self._load_from_db()

    def _init_db(self):
        try:
            self._persist_path.parent.mkdir(parents=True, exist_ok=True)
            with sqlite3.connect(str(self._persist_path)) as conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS rate_limit_buckets (
                        key TEXT PRIMARY KEY,
                        tokens REAL NOT NULL,
                        last_refill REAL NOT NULL
                    )
                """)
                conn.commit()
        except Exception as exc:
            logger.warning("Rate limiter DB init failed: %s", exc)

    def _load_from_db(self):
        try:
            with sqlite3.connect(str(self._persist_path)) as conn:
                rows = conn.execute("SELECT key, tokens, last_refill FROM rate_limit_buckets").fetchall()
                for key, tokens, last_refill in rows:
                    self._buckets[key] = BucketState(tokens=tokens, last_refill=last_refill)
            logger.debug("Rate limiter: loaded %d buckets from DB", len(self._buckets))
        except Exception as exc:
            logger.warning("Rate limiter DB load failed: %s", exc)

    def _persist_bucket(self, key: str, state: BucketState):
        try:
            with sqlite3.connect(str(self._persist_path)) as conn:
                conn.execute(
                    "INSERT OR REPLACE INTO rate_limit_buckets (key, tokens, last_refill) VALUES (?, ?, ?)",
                    (key, state.tokens, state.last_refill),
                )
                conn.commit()
        except Exception as exc:
            logger.debug("Rate limiter persist failed for key=%s: %s", key, exc)

    def _get_limit(self, path: str) -> Tuple[int, float]:
        for prefix, limit in self.DEFAULT_LIMITS.items():
            if prefix != "default" and path.startswith(prefix):
                return limit
        return self.DEFAULT_LIMITS["default"]

    def _get_key(self, path: str, user_id: Optional[str], client_ip: str) -> str:
        identity = user_id or client_ip or "unknown"
        return f"{identity}:{path}"

    def _refill(self, state: BucketState, capacity: int, refill_rate: float) -> BucketState:
        now = time.time()
        diff = now - state.last_refill
        new_tokens = min(capacity, state.tokens + diff * refill_rate)
        return BucketState(tokens=new_tokens, last_refill=now)

    def check(
        self,
        path: str,
        user_id: Optional[str] = None,
        client_ip: str = "",
        consume: int = 1,
    ) -> Tuple[bool, Dict]:
        capacity, refill_rate = self._get_limit(path)
        key = self._get_key(path, user_id, client_ip)

        with self._lock:
            state = self._buckets.get(key, BucketState(tokens=float(capacity)))
            state = self._refill(state, capacity, refill_rate)

            allowed = state.tokens >= consume
            retry_after = 0
            if allowed:
                state.tokens -= consume
            else:
                needed = consume - state.tokens
                retry_after = int(needed / refill_rate) + 1

            self._buckets[key] = state

        self._persist_bucket(key, state)

        headers = {
            "X-RateLimit-Limit":     str(capacity),
            "X-RateLimit-Remaining": str(max(0, int(state.tokens))),
            "X-RateLimit-Reset":     str(int(time.time() + (capacity - state.tokens) / max(refill_rate, 0.001))),
        }
        if not allowed:
            headers["Retry-After"] = str(retry_after)
            logger.warning("Rate limit exceeded: key=%s path=%s retry_after=%ds", key, path, retry_after)

        return allowed, headers

    def reset(self, user_id: str, path: str = ""):
        with self._lock:
            keys_to_del = [k for k in self._buckets if k.startswith(f"{user_id}:{path}")]
            for k in keys_to_del:
                del self._buckets[k]
        logger.info("Rate limit reset for user=%s", user_id)

    def stats(self) -> Dict:
        with self._lock:
            return {k: {"tokens": round(v.tokens, 2)} for k, v in self._buckets.items()}

    def cleanup_expired(self, max_age_seconds: float = 3600):
        now = time.time()
        with self._lock:
            expired = [k for k, v in self._buckets.items() if (now - v.last_refill) > max_age_seconds]
            for k in expired:
                del self._buckets[k]
        if expired:
            try:
                with sqlite3.connect(str(self._persist_path)) as conn:
                    for k in expired:
                        conn.execute("DELETE FROM rate_limit_buckets WHERE key = ?", (k,))
                    conn.commit()
            except Exception as exc:
                logger.debug("Cleanup DB error: %s", exc)
            logger.debug("Cleaned up %d expired rate-limit buckets", len(expired))


_limiter = RateLimiter()


def get_limiter() -> RateLimiter:
    return _limiter


async def rate_limit_dependency(request: Request) -> None:
    """
    FastAPI dependency: apply rate limiting to a route.

    Usage:
        @router.post("/api/chat", dependencies=[Depends(rate_limit_dependency)])
        async def chat(...): ...
    """
    user_id = getattr(request.state, "user_id", None)
    client_ip = request.client.host if request.client else ""
    allowed, headers = _limiter.check(request.url.path, user_id, client_ip)

    if not allowed:
        retry_after = headers.get("Retry-After", "60")
        raise HTTPException(
            status_code=429,
            detail={
                "error": "Rate limit exceeded",
                "code": "RATE_LIMITED",
                "retry_after": retry_after,
                "message": f"Too many requests. Please wait {retry_after} seconds.",
            },
            headers=headers,
        )


class RateLimitMiddleware:
    """ASGI middleware for global rate limiting on all /api/* routes."""

    def __init__(self, app, limiter: Optional[RateLimiter] = None):
        self.app = app
        self.limiter = limiter or _limiter

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        method = scope.get("method", "")

        if method == "OPTIONS" or not path.startswith("/api/"):
            await self.app(scope, receive, send)
            return

        user_id = scope.get("user_id")
        headers = dict(scope.get("headers", []))
        client_ip = ""
        if b"x-forwarded-for" in headers:
            client_ip = headers[b"x-forwarded-for"].decode().split(",")[0].strip()

        allowed, rl_headers = self.limiter.check(path, user_id, client_ip)

        if not allowed:
            import json
            retry_after = rl_headers.get("Retry-After", "60")
            body = json.dumps({
                "error": "Rate limit exceeded",
                "code": "RATE_LIMITED",
                "retry_after": retry_after,
            }).encode()
            resp_headers = [(b"content-type", b"application/json")]
            for k, v in rl_headers.items():
                resp_headers.append((k.lower().encode(), v.encode()))
            await send({"type": "http.response.start", "status": 429, "headers": resp_headers})
            await send({"type": "http.response.body", "body": body})
            return

        await self.app(scope, receive, send)
