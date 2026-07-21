"""
Phase 8.6 — Session Manager (Login Improvements)
==================================================
Secure session persistence, cookie/token management, expiry detection,
auto re-login, MFA support with user interaction.
"""
from __future__ import annotations
import asyncio, json, logging, os, time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

_SESSION_DIR = Path("data/browser_sessions")
_SESSION_DIR.mkdir(parents=True, exist_ok=True)

_EXPIRY_SIGNALS = [
    "sign in", "log in", "login", "session expired", "please log in",
    "you have been logged out", "your session has", "unauthorized",
    "401", "authentication required", "access denied",
]


@dataclass
class SessionRecord:
    site_key: str
    url: str
    username: str
    saved_at: float = field(default_factory=time.time)
    last_used: float = field(default_factory=time.time)
    is_valid: bool = True
    mfa_required: bool = False
    storage_file: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {k: v for k, v in asdict(self).items() if k != "storage_file"}


class SessionManager:
    """
    Manages browser sessions: save, load, validate, auto-refresh.

    Usage::

        sm = SessionManager(pw_manager)
        await sm.save_session("https://github.com", "user@email.com")
        loaded = await sm.load_session("https://github.com")
        if not loaded or await sm.is_session_expired(page):
            await sm.login_and_save("https://github.com", "user", "pass")
    """

    def __init__(self, pw_manager,
                 mfa_prompt_fn: Optional[Callable[[str], str]] = None) -> None:
        self._pw = pw_manager
        self._mfa_prompt = mfa_prompt_fn  # fn(site) -> OTP string
        self._sessions: Dict[str, SessionRecord] = {}
        self._credentials: Dict[str, Dict[str, str]] = {}  # encrypted in prod
        self._load_index()

    def _site_key(self, url: str) -> str:
        from urllib.parse import urlparse
        host = urlparse(url).netloc or url
        return "".join(c for c in host if c.isalnum() or c in "-_.")

    def _storage_path(self, site_key: str) -> Path:
        return _SESSION_DIR / f"{site_key}.json"

    def _index_path(self) -> Path:
        return _SESSION_DIR / "_index.json"

    def _load_index(self) -> None:
        try:
            if self._index_path().exists():
                with open(self._index_path()) as f:
                    raw = json.load(f)
                for k, v in raw.items():
                    self._sessions[k] = SessionRecord(**v)
        except Exception as exc:
            log.debug("Session index load error: %s", exc)

    def _save_index(self) -> None:
        try:
            data = {k: r.to_dict() for k, r in self._sessions.items()}
            with open(self._index_path(), "w") as f:
                json.dump(data, f, indent=2)
        except Exception as exc:
            log.error("Session index save error: %s", exc)

    # ── Save / Load ───────────────────────────────────────────────────────────

    async def save_session(self, url: str, username: str = "") -> bool:
        """Save current browser context storage state."""
        site_key = self._site_key(url)
        path = self._storage_path(site_key)
        try:
            await self._pw._context.storage_state(path=str(path))
            rec = SessionRecord(site_key=site_key, url=url, username=username,
                                storage_file=str(path))
            self._sessions[site_key] = rec
            self._save_index()
            log.info("Session saved for %s → %s", site_key, path)
            return True
        except Exception as exc:
            log.error("Session save failed: %s", exc)
            return False

    async def load_session(self, url: str) -> bool:
        """Load saved storage state into context."""
        site_key = self._site_key(url)
        path = self._storage_path(site_key)
        if not path.exists():
            return False
        try:
            await self._pw.load_storage(str(path))
            if site_key in self._sessions:
                self._sessions[site_key].last_used = time.time()
                self._save_index()
            log.info("Session loaded for %s", site_key)
            return True
        except Exception as exc:
            log.error("Session load failed: %s", exc)
            return False

    def store_credentials(self, url: str, username: str, password: str) -> None:
        """Store credentials in memory for auto re-login (not persisted to disk)."""
        self._credentials[self._site_key(url)] = {"username": username, "password": password}

    # ── Validation ────────────────────────────────────────────────────────────

    async def is_session_expired(self, page) -> bool:
        """Check if the current page indicates an expired session."""
        try:
            url = page.url.lower()
            title = (await page.title()).lower()
            body = (await page.evaluate(
                "() => document.body ? document.body.innerText.slice(0, 2000).toLowerCase() : ''"
            ))
            combined = f"{url} {title} {body}"
            return any(sig in combined for sig in _EXPIRY_SIGNALS)
        except Exception:
            return False

    async def validate_and_restore(self, page, url: str,
                                    login_fn: Optional[Callable] = None) -> bool:
        """Check session; if expired and credentials stored, auto re-login."""
        expired = await self.is_session_expired(page)
        if not expired:
            return True
        log.info("Session expired for %s — attempting re-login", url)
        site_key = self._site_key(url)
        creds = self._credentials.get(site_key)
        if creds and login_fn:
            try:
                result = await login_fn(url, creds["username"], creds["password"])
                if result.get("success"):
                    await self.save_session(url, creds["username"])
                    return True
            except Exception as exc:
                log.error("Auto re-login failed: %s", exc)
        return False

    # ── MFA handling ──────────────────────────────────────────────────────────

    async def handle_mfa(self, page, site_url: str,
                          mfa_selector: str = "input[name*='otp'],input[name*='code'],input[name*='token'],input[placeholder*='code']",
                          timeout: float = 60.0) -> bool:
        """Detect MFA prompt, get code from user/provider, enter it."""
        mfa_signals = [
            "two-factor", "2fa", "one-time", "authenticator", "verification code",
            "otp", "enter the code", "security code",
        ]
        try:
            body = (await page.evaluate(
                "() => document.body ? document.body.innerText.toLowerCase() : ''"
            ))
            if not any(sig in body for sig in mfa_signals):
                return True  # No MFA needed
        except Exception:
            return True

        log.info("MFA detected on %s", site_url)
        if self._mfa_prompt is None:
            log.warning("MFA required but no prompt function configured")
            return False

        code = self._mfa_prompt(site_url)
        if not code:
            return False

        for sel in mfa_selector.split(","):
            sel = sel.strip()
            try:
                el = await page.query_selector(sel)
                if el:
                    await el.fill(code)
                    await page.keyboard.press("Enter")
                    await page.wait_for_load_state("networkidle", timeout=15000)
                    return True
            except Exception:
                continue
        return False

    # ── Management ────────────────────────────────────────────────────────────

    def list_sessions(self) -> List[Dict[str, Any]]:
        return [r.to_dict() for r in self._sessions.values()]

    def delete_session(self, url: str) -> bool:
        site_key = self._site_key(url)
        path = self._storage_path(site_key)
        try:
            if path.exists():
                path.unlink()
            self._sessions.pop(site_key, None)
            self._save_index()
            return True
        except Exception:
            return False

    def has_session(self, url: str) -> bool:
        return self._storage_path(self._site_key(url)).exists()
