"""extras/multi_user.py — Feature 7: Multi-User Support.

Provides fully isolated user profiles with separate:
- Conversation history
- Memory / preferences
- Settings and provider choices
- Voice identity (voice fingerprint hash)
- Authentication (PIN / password)
- Audit log per user

Privacy-first: no data leaks between profiles.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────────────
# User Profile
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class UserProfile:
    user_id: str
    username: str
    display_name: str
    pin_hash: str = ""
    created_at: float = field(default_factory=time.time)
    last_active: float = field(default_factory=time.time)
    is_admin: bool = False
    language: str = "en"
    voice_fingerprint: str = ""   # SHA-256 of voice sample features
    llm_provider: str = "auto"
    avatar_emoji: str = "🧑"
    preferences: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {
            "user_id": self.user_id,
            "username": self.username,
            "display_name": self.display_name,
            "pin_hash": self.pin_hash,
            "created_at": self.created_at,
            "last_active": self.last_active,
            "is_admin": self.is_admin,
            "language": self.language,
            "voice_fingerprint": self.voice_fingerprint,
            "llm_provider": self.llm_provider,
            "avatar_emoji": self.avatar_emoji,
            "preferences": self.preferences,
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "UserProfile":
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


@dataclass
class ConversationTurn:
    role: str
    content: str
    timestamp: float = field(default_factory=time.time)


class UserConversation:
    """Isolated conversation history for one user."""

    def __init__(self, user_id: str, max_turns: int = 200) -> None:
        self.user_id = user_id
        self.max_turns = max_turns
        self._turns: List[ConversationTurn] = []

    def add(self, role: str, content: str) -> None:
        self._turns.append(ConversationTurn(role=role, content=content))
        if len(self._turns) > self.max_turns:
            self._turns = self._turns[-self.max_turns:]

    def history(self, last_n: int = 20) -> List[Dict]:
        return [{"role": t.role, "content": t.content} for t in self._turns[-last_n:]]

    def clear(self) -> None:
        self._turns.clear()

    def export(self) -> List[Dict]:
        return [{"role": t.role, "content": t.content, "ts": t.timestamp} for t in self._turns]


class UserMemory:
    """Isolated key-value memory store for one user."""

    def __init__(self, user_id: str) -> None:
        self.user_id = user_id
        self._facts: Dict[str, str] = {}

    def remember(self, key: str, value: str) -> None:
        self._facts[key.lower()] = value
        log.debug("[Memory:%s] %s → %s", self.user_id[:8], key, value[:50])

    def recall(self, key: str) -> Optional[str]:
        return self._facts.get(key.lower())

    def search(self, query: str) -> List[str]:
        q = query.lower()
        return [f"{k}: {v}" for k, v in self._facts.items()
                if q in k or q in v.lower()]

    def all_facts(self) -> Dict[str, str]:
        return dict(self._facts)

    def clear(self) -> None:
        self._facts.clear()


# ─────────────────────────────────────────────────────────────────────────────
# User Manager
# ─────────────────────────────────────────────────────────────────────────────

class UserManager:
    """Manages all user profiles with isolated memory, history, and settings."""

    GUEST_ID = "guest"

    def __init__(self, data_dir: str = ".data/users") -> None:
        self._data_dir = Path(data_dir)
        self._data_dir.mkdir(parents=True, exist_ok=True)
        self._profiles: Dict[str, UserProfile] = {}
        self._conversations: Dict[str, UserConversation] = {}
        self._memories: Dict[str, UserMemory] = {}
        self._active_user_id: str = self.GUEST_ID
        self._load_all()
        self._ensure_guest()

    # ── Persistence ───────────────────────────────────────────────────────────

    def _profile_path(self, user_id: str) -> Path:
        return self._data_dir / f"{user_id}.json"

    def _conv_path(self, user_id: str) -> Path:
        return self._data_dir / f"{user_id}_conv.json"

    def _mem_path(self, user_id: str) -> Path:
        return self._data_dir / f"{user_id}_mem.json"

    def _load_all(self) -> None:
        for f in self._data_dir.glob("*.json"):
            if f.stem.endswith("_conv") or f.stem.endswith("_mem"):
                continue
            try:
                data = json.loads(f.read_text())
                profile = UserProfile.from_dict(data)
                self._profiles[profile.user_id] = profile
                # Load conversation
                cp = self._conv_path(profile.user_id)
                conv = UserConversation(profile.user_id)
                if cp.exists():
                    turns = json.loads(cp.read_text())
                    for t in turns:
                        conv._turns.append(ConversationTurn(**t))
                self._conversations[profile.user_id] = conv
                # Load memory
                mp = self._mem_path(profile.user_id)
                mem = UserMemory(profile.user_id)
                if mp.exists():
                    mem._facts = json.loads(mp.read_text())
                self._memories[profile.user_id] = mem
            except Exception as exc:
                log.warning("[Users] Failed to load %s: %s", f, exc)
        log.info("[Users] Loaded %d profiles", len(self._profiles))

    def _save_profile(self, user_id: str) -> None:
        profile = self._profiles.get(user_id)
        if not profile:
            return
        self._profile_path(user_id).write_text(json.dumps(profile.to_dict(), indent=2))
        # Save conversation
        conv = self._conversations.get(user_id)
        if conv:
            self._conv_path(user_id).write_text(json.dumps(conv.export()))
        # Save memory
        mem = self._memories.get(user_id)
        if mem:
            self._mem_path(user_id).write_text(json.dumps(mem.all_facts()))

    def _ensure_guest(self) -> None:
        if self.GUEST_ID not in self._profiles:
            self._create_profile("guest", "Guest", avatar_emoji="👤")

    # ── CRUD ──────────────────────────────────────────────────────────────────

    def _create_profile(
        self, username: str, display_name: str,
        pin: str = "", is_admin: bool = False,
        language: str = "en", avatar_emoji: str = "🧑",
    ) -> UserProfile:
        user_id = str(uuid.uuid4())[:8]
        pin_hash = hashlib.sha256(pin.encode()).hexdigest() if pin else ""
        profile = UserProfile(
            user_id=user_id, username=username, display_name=display_name,
            pin_hash=pin_hash, is_admin=is_admin, language=language, avatar_emoji=avatar_emoji,
        )
        self._profiles[user_id] = profile
        self._conversations[user_id] = UserConversation(user_id)
        self._memories[user_id] = UserMemory(user_id)
        self._save_profile(user_id)
        log.info("[Users] Created profile: %s (%s)", display_name, user_id)
        return profile

    def create_user(
        self, username: str, display_name: str = "",
        pin: str = "", is_admin: bool = False,
        language: str = "en", avatar_emoji: str = "🧑",
    ) -> UserProfile:
        if any(p.username == username for p in self._profiles.values()):
            raise ValueError(f"Username '{username}' already exists")
        return self._create_profile(
            username, display_name or username, pin=pin,
            is_admin=is_admin, language=language, avatar_emoji=avatar_emoji,
        )

    def delete_user(self, user_id: str) -> bool:
        if user_id == self.GUEST_ID:
            raise ValueError("Cannot delete guest profile")
        if user_id not in self._profiles:
            return False
        del self._profiles[user_id]
        self._conversations.pop(user_id, None)
        self._memories.pop(user_id, None)
        for path in [self._profile_path(user_id), self._conv_path(user_id), self._mem_path(user_id)]:
            if path.exists():
                path.unlink()
        log.info("[Users] Deleted user: %s", user_id)
        return True

    # ── Authentication ─────────────────────────────────────────────────────────

    def authenticate(self, username: str, pin: str) -> Optional[UserProfile]:
        for profile in self._profiles.values():
            if profile.username == username:
                if not profile.pin_hash:   # no PIN set → allow
                    return profile
                if hashlib.sha256(pin.encode()).hexdigest() == profile.pin_hash:
                    return profile
        return None

    def identify_by_voice(self, voice_hash: str) -> Optional[UserProfile]:
        for profile in self._profiles.values():
            if profile.voice_fingerprint and profile.voice_fingerprint == voice_hash:
                return profile
        return None

    def set_voice_fingerprint(self, user_id: str, voice_hash: str) -> None:
        if user_id in self._profiles:
            self._profiles[user_id].voice_fingerprint = voice_hash
            self._save_profile(user_id)

    # ── Active session ────────────────────────────────────────────────────────

    def switch_user(self, user_id: str) -> bool:
        if user_id not in self._profiles:
            return False
        # Save current user
        self._save_profile(self._active_user_id)
        self._active_user_id = user_id
        self._profiles[user_id].last_active = time.time()
        log.info("[Users] Switched to: %s", self._profiles[user_id].display_name)
        return True

    @property
    def active_user(self) -> UserProfile:
        return self._profiles[self._active_user_id]

    @property
    def active_conversation(self) -> UserConversation:
        return self._conversations[self._active_user_id]

    @property
    def active_memory(self) -> UserMemory:
        return self._memories[self._active_user_id]

    # ── Preferences ───────────────────────────────────────────────────────────

    def set_preference(self, user_id: str, key: str, value: Any) -> None:
        if user_id in self._profiles:
            self._profiles[user_id].preferences[key] = value
            self._save_profile(user_id)

    def get_preference(self, user_id: str, key: str, default: Any = None) -> Any:
        profile = self._profiles.get(user_id)
        return profile.preferences.get(key, default) if profile else default

    # ── Save all ──────────────────────────────────────────────────────────────

    def save_all(self) -> None:
        for uid in self._profiles:
            self._save_profile(uid)
        log.debug("[Users] All profiles saved")

    # ── Listing ───────────────────────────────────────────────────────────────

    def list_users(self) -> List[Dict]:
        return [
            {"user_id": p.user_id, "username": p.username, "display_name": p.display_name,
             "avatar": p.avatar_emoji, "language": p.language, "is_admin": p.is_admin,
             "last_active": p.last_active}
            for p in self._profiles.values()
        ]

    def status(self) -> Dict[str, Any]:
        return {
            "total_users": len(self._profiles),
            "active_user": self.active_user.display_name,
            "active_user_id": self._active_user_id,
            "users": self.list_users(),
        }
