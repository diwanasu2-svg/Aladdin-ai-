"""
Phase 8.10 — Browser Profiles
================================
Multiple browser profiles: Work, Personal, Test.
Each profile has separate cookies, sessions, history.
Easy profile switching, independent settings.
"""
from __future__ import annotations
import json, logging, os, shutil, threading
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)

_PROFILES_ROOT = Path("data/browser_profiles")
_PROFILES_ROOT.mkdir(parents=True, exist_ok=True)
_PROFILES_INDEX = _PROFILES_ROOT / "_profiles.json"

_DEFAULT_PROFILES = [
    {"name": "Default", "profile_id": "default", "color": "#4A90D9",
     "description": "General purpose browsing"},
    {"name": "Work", "profile_id": "work", "color": "#27AE60",
     "description": "Work and professional tasks"},
    {"name": "Personal", "profile_id": "personal", "color": "#E74C3C",
     "description": "Personal browsing"},
    {"name": "Test", "profile_id": "test", "color": "#F39C12",
     "description": "Testing and debugging"},
]


@dataclass
class BrowserProfile:
    profile_id: str
    name: str
    color: str = "#4A90D9"
    description: str = ""
    settings: Dict[str, Any] = field(default_factory=dict)
    created_at: float = field(default_factory=lambda: __import__("time").time())
    last_used: Optional[float] = None
    user_agent: Optional[str] = None
    homepage: str = "about:blank"
    extensions: List[str] = field(default_factory=list)
    proxy: Optional[str] = None
    is_incognito: bool = False

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @property
    def data_dir(self) -> Path:
        return _PROFILES_ROOT / self.profile_id

    @property
    def session_dir(self) -> Path:
        d = self.data_dir / "sessions"
        d.mkdir(parents=True, exist_ok=True)
        return d

    @property
    def storage_state_file(self) -> Path:
        return self.data_dir / "storage_state.json"

    @property
    def history_file(self) -> Path:
        return self.data_dir / "history.json"


class ProfileManager:
    """
    Manages multiple browser profiles with isolated storage.

    Usage::

        pm = ProfileManager()
        pm.create_profile("Shopping", "shop", "#9B59B6")

        async with pm.activate_profile("work", pw_manager) as profile:
            await browser.navigate("https://docs.company.com")

        pm.switch_to("personal")
        current = pm.current_profile
    """

    def __init__(self) -> None:
        self._profiles: Dict[str, BrowserProfile] = {}
        self._active_id: str = "default"
        self._lock = threading.RLock()
        self._load()
        self._ensure_defaults()
        log.info("ProfileManager: %d profiles loaded, active=%s",
                 len(self._profiles), self._active_id)

    # ── Persistence ───────────────────────────────────────────────────────────

    def _load(self) -> None:
        if not _PROFILES_INDEX.exists():
            return
        try:
            with open(_PROFILES_INDEX, "r", encoding="utf-8") as f:
                data = json.load(f)
            with self._lock:
                for pid, pdata in data.get("profiles", {}).items():
                    self._profiles[pid] = BrowserProfile(**pdata)
                self._active_id = data.get("active_id", "default")
        except Exception as exc:
            log.error("ProfileManager load error: %s", exc)

    def _save(self) -> None:
        try:
            with self._lock:
                data = {
                    "profiles": {pid: asdict(p) for pid, p in self._profiles.items()},
                    "active_id": self._active_id,
                }
            with open(_PROFILES_INDEX, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except Exception as exc:
            log.error("ProfileManager save error: %s", exc)

    def _ensure_defaults(self) -> None:
        changed = False
        for pd in _DEFAULT_PROFILES:
            if pd["profile_id"] not in self._profiles:
                p = BrowserProfile(**pd)
                p.data_dir.mkdir(parents=True, exist_ok=True)
                with self._lock:
                    self._profiles[pd["profile_id"]] = p
                changed = True
        if changed:
            self._save()

    # ── CRUD ──────────────────────────────────────────────────────────────────

    def create_profile(self, name: str, profile_id: Optional[str] = None,
                        color: str = "#95A5A6", description: str = "",
                        homepage: str = "about:blank",
                        user_agent: Optional[str] = None,
                        proxy: Optional[str] = None,
                        is_incognito: bool = False) -> BrowserProfile:
        pid = profile_id or name.lower().replace(" ", "_")
        if pid in self._profiles:
            raise ValueError(f"Profile '{pid}' already exists")
        profile = BrowserProfile(
            profile_id=pid, name=name, color=color, description=description,
            homepage=homepage, user_agent=user_agent, proxy=proxy,
            is_incognito=is_incognito,
        )
        profile.data_dir.mkdir(parents=True, exist_ok=True)
        with self._lock:
            self._profiles[pid] = profile
        self._save()
        log.info("Profile created: %s (%s)", name, pid)
        return profile

    def get_profile(self, profile_id: str) -> Optional[BrowserProfile]:
        with self._lock:
            return self._profiles.get(profile_id)

    def list_profiles(self) -> List[Dict[str, Any]]:
        with self._lock:
            return [asdict(p) for p in self._profiles.values()]

    def delete_profile(self, profile_id: str, delete_data: bool = False) -> bool:
        if profile_id == "default":
            raise ValueError("Cannot delete the default profile")
        with self._lock:
            if profile_id not in self._profiles:
                return False
            profile = self._profiles.pop(profile_id)
        if delete_data and profile.data_dir.exists():
            shutil.rmtree(str(profile.data_dir), ignore_errors=True)
        if self._active_id == profile_id:
            self._active_id = "default"
        self._save()
        return True

    def update_profile(self, profile_id: str, **kwargs) -> Optional[BrowserProfile]:
        with self._lock:
            profile = self._profiles.get(profile_id)
            if not profile:
                return None
            for k, v in kwargs.items():
                if hasattr(profile, k):
                    setattr(profile, k, v)
        self._save()
        return profile

    # ── Switching ─────────────────────────────────────────────────────────────

    def switch_to(self, profile_id: str) -> BrowserProfile:
        with self._lock:
            if profile_id not in self._profiles:
                raise KeyError(f"Profile '{profile_id}' not found")
            import time
            self._profiles[profile_id].last_used = time.time()
            self._active_id = profile_id
        self._save()
        log.info("Switched to profile: %s", profile_id)
        return self._profiles[profile_id]

    @property
    def current_profile(self) -> BrowserProfile:
        with self._lock:
            return self._profiles.get(self._active_id, self._profiles["default"])

    @property
    def active_id(self) -> str:
        return self._active_id

    # ── Playwright context factory ────────────────────────────────────────────

    async def create_context_for_profile(self, playwright, profile_id: Optional[str] = None):
        """Create a Playwright BrowserContext isolated for the given profile."""
        pid = profile_id or self._active_id
        profile = self.get_profile(pid)
        if not profile:
            raise KeyError(f"Profile '{pid}' not found")

        # Load existing storage state if present
        storage_state = None
        if profile.storage_state_file.exists():
            storage_state = str(profile.storage_state_file)

        ctx_kwargs: Dict[str, Any] = {}
        if profile.user_agent:
            ctx_kwargs["user_agent"] = profile.user_agent
        if profile.proxy:
            ctx_kwargs["proxy"] = {"server": profile.proxy}
        if storage_state:
            ctx_kwargs["storage_state"] = storage_state
        ctx_kwargs["record_video_dir"] = None   # disabled by default

        try:
            browser = await playwright.chromium.launch(headless=False)
            context = await browser.new_context(**ctx_kwargs)
            log.info("Context created for profile '%s'", pid)
            return browser, context, profile
        except Exception as exc:
            log.error("Context creation failed for profile '%s': %s", pid, exc)
            raise

    async def save_context_state(self, context, profile_id: Optional[str] = None) -> bool:
        """Persist context storage state back to the profile's file."""
        pid = profile_id or self._active_id
        profile = self.get_profile(pid)
        if not profile:
            return False
        try:
            await context.storage_state(path=str(profile.storage_state_file))
            log.info("Storage state saved for profile '%s'", pid)
            return True
        except Exception as exc:
            log.error("Save context state error: %s", exc)
            return False

    # ── Profile history ───────────────────────────────────────────────────────

    def append_history(self, profile_id: str, url: str, title: str) -> None:
        profile = self.get_profile(profile_id)
        if not profile:
            return
        try:
            hist = []
            if profile.history_file.exists():
                with open(profile.history_file) as f:
                    hist = json.load(f)
            import time
            hist.append({"url": url, "title": title, "visited_at": time.time()})
            hist = hist[-1000:]  # keep last 1000
            with open(profile.history_file, "w") as f:
                json.dump(hist, f, indent=2)
        except Exception as exc:
            log.debug("Append history error: %s", exc)

    def get_history(self, profile_id: str, n: int = 50) -> List[Dict[str, Any]]:
        profile = self.get_profile(profile_id)
        if not profile or not profile.history_file.exists():
            return []
        try:
            with open(profile.history_file) as f:
                hist = json.load(f)
            return hist[-n:][::-1]
        except Exception:
            return []
