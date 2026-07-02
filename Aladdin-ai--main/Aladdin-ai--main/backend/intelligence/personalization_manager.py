"""Phase 11.10 — Personalization Manager.

Learns and maintains user-specific preferences:
  • Speaking style (formal / casual / technical / simple)
  • Preferred language and tone
  • Favourite apps, services, and shortcuts
  • Routine-based behaviour adaptation
  • User-defined shortcuts and automations
  • Multi-user profile support
  • Privacy-preserving local storage

Implements continuous learning with preference decay for older data.
"""
from __future__ import annotations
import asyncio
import json
import logging
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple

from .config import (
    PERSONA_DB, PERSONA_PREFERENCE_DECAY_DAYS, DEFAULT_USER_ID,
)

log = logging.getLogger(__name__)

# ── Preference categories ──────────────────────────────────────────────────────
PREF_LANGUAGE = "language"
PREF_TONE = "tone"                     # formal / casual / technical / simple
PREF_VERBOSITY = "verbosity"           # minimal / normal / detailed
PREF_RESPONSE_FORMAT = "format"        # text / voice / mixed
PREF_WAKE_WORD = "wake_word"
PREF_APP = "app"
PREF_SERVICE = "service"
PREF_THEME = "theme"
PREF_NOTIFICATION = "notification"


@dataclass
class UserProfile:
    user_id: str = DEFAULT_USER_ID
    display_name: str = ""
    language: str = "en"
    tone: str = "friendly"             # formal / casual / friendly / technical / simple
    verbosity: str = "normal"          # minimal / normal / detailed
    response_format: str = "text"
    timezone: str = "UTC"
    wake_word: str = "Hey Aladdin"
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    metadata: Dict = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {
            "user_id": self.user_id, "display_name": self.display_name,
            "language": self.language, "tone": self.tone,
            "verbosity": self.verbosity, "response_format": self.response_format,
            "timezone": self.timezone, "wake_word": self.wake_word,
            "created_at": self.created_at, "updated_at": self.updated_at,
            "metadata": self.metadata,
        }


@dataclass
class Preference:
    user_id: str
    category: str
    key: str
    value: Any
    weight: float = 1.0
    source: str = "explicit"         # explicit / inferred / automatic
    timestamp: float = field(default_factory=time.time)


@dataclass
class Shortcut:
    user_id: str
    name: str                        # e.g. "morning routine"
    trigger: str                     # e.g. "start my day" / "wake up"
    actions: List[Dict]              # [{"tool": "...", "params": {...}}, ...]
    created_at: float = field(default_factory=time.time)
    use_count: int = 0
    last_used: Optional[float] = None


class PersonalizationManager:
    """Multi-user personalization with continuous learning and preference decay."""

    def __init__(self):
        self._init_db()
        self._profile_cache: Dict[str, UserProfile] = {}
        self._pref_cache: Dict[str, Dict] = {}       # user_id → {category:key → value}

    # ── Database ──────────────────────────────────────────────────────────────
    @contextmanager
    def _db(self):
        conn = sqlite3.connect(PERSONA_DB)
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_db(self):
        with self._db() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS profiles (
                    user_id TEXT PRIMARY KEY,
                    display_name TEXT DEFAULT '',
                    language TEXT DEFAULT 'en',
                    tone TEXT DEFAULT 'friendly',
                    verbosity TEXT DEFAULT 'normal',
                    response_format TEXT DEFAULT 'text',
                    timezone TEXT DEFAULT 'UTC',
                    wake_word TEXT DEFAULT 'Hey Aladdin',
                    created_at REAL, updated_at REAL,
                    metadata TEXT DEFAULT '{}'
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS preferences (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT, category TEXT, key TEXT, value TEXT,
                    weight REAL DEFAULT 1.0,
                    source TEXT DEFAULT 'explicit',
                    timestamp REAL
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS shortcuts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT, name TEXT, trigger TEXT,
                    actions TEXT, created_at REAL,
                    use_count INTEGER DEFAULT 0, last_used REAL
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS feedback_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT, event_type TEXT,
                    data TEXT, timestamp REAL
                )
            """)
            # Create default user if missing
            db.execute(
                "INSERT OR IGNORE INTO profiles(user_id,created_at,updated_at) VALUES(?,?,?)",
                (DEFAULT_USER_ID, time.time(), time.time())
            )

    # ── Profile management ────────────────────────────────────────────────────
    def get_profile(self, user_id: str = DEFAULT_USER_ID) -> UserProfile:
        if user_id in self._profile_cache:
            return self._profile_cache[user_id]
        with self._db() as db:
            row = db.execute("SELECT * FROM profiles WHERE user_id=?", (user_id,)).fetchone()
        if not row:
            profile = UserProfile(user_id=user_id)
            self.create_profile(profile)
            return profile
        meta = {}
        try:
            meta = json.loads(row["metadata"] or "{}")
        except Exception:
            pass
        profile = UserProfile(
            user_id=row["user_id"],
            display_name=row["display_name"] or "",
            language=row["language"] or "en",
            tone=row["tone"] or "friendly",
            verbosity=row["verbosity"] or "normal",
            response_format=row["response_format"] or "text",
            timezone=row["timezone"] or "UTC",
            wake_word=row["wake_word"] or "Hey Aladdin",
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            metadata=meta,
        )
        self._profile_cache[user_id] = profile
        return profile

    def create_profile(self, profile: UserProfile) -> UserProfile:
        with self._db() as db:
            db.execute(
                "INSERT OR IGNORE INTO profiles"
                "(user_id,display_name,language,tone,verbosity,response_format,"
                "timezone,wake_word,created_at,updated_at,metadata) VALUES"
                "(?,?,?,?,?,?,?,?,?,?,?)",
                (profile.user_id, profile.display_name, profile.language,
                 profile.tone, profile.verbosity, profile.response_format,
                 profile.timezone, profile.wake_word,
                 profile.created_at, profile.updated_at,
                 json.dumps(profile.metadata))
            )
        self._profile_cache[profile.user_id] = profile
        log.info("Profile created: %s", profile.user_id)
        return profile

    def update_profile(self, user_id: str = DEFAULT_USER_ID, **kwargs) -> UserProfile:
        profile = self.get_profile(user_id)
        for k, v in kwargs.items():
            if hasattr(profile, k):
                setattr(profile, k, v)
        profile.updated_at = time.time()
        with self._db() as db:
            db.execute(
                "UPDATE profiles SET display_name=?,language=?,tone=?,verbosity=?,"
                "response_format=?,timezone=?,wake_word=?,updated_at=?,metadata=? "
                "WHERE user_id=?",
                (profile.display_name, profile.language, profile.tone,
                 profile.verbosity, profile.response_format,
                 profile.timezone, profile.wake_word, profile.updated_at,
                 json.dumps(profile.metadata), user_id)
            )
        self._profile_cache[user_id] = profile
        log.info("Profile updated: %s → %s", user_id, kwargs)
        return profile

    def list_users(self) -> List[str]:
        with self._db() as db:
            rows = db.execute("SELECT user_id FROM profiles").fetchall()
        return [r["user_id"] for r in rows]

    def delete_profile(self, user_id: str):
        with self._db() as db:
            db.execute("DELETE FROM profiles WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM preferences WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM shortcuts WHERE user_id=?", (user_id,))
        self._profile_cache.pop(user_id, None)
        self._pref_cache.pop(user_id, None)
        log.info("Profile deleted: %s", user_id)

    # ── Preferences ───────────────────────────────────────────────────────────
    def set_preference(self, user_id: str, category: str, key: str,
                        value: Any, weight: float = 1.0,
                        source: str = "explicit"):
        with self._db() as db:
            db.execute(
                "INSERT INTO preferences(user_id,category,key,value,weight,source,timestamp)"
                " VALUES(?,?,?,?,?,?,?)",
                (user_id, category, key, json.dumps(value), weight, source, time.time())
            )
        # Invalidate cache
        self._pref_cache.pop(user_id, None)
        log.debug("Preference set: %s / %s:%s = %s (w=%.1f)", user_id, category, key, value, weight)

    def get_preference(self, user_id: str, category: str, key: str,
                        default: Any = None) -> Any:
        prefs = self._get_all_prefs(user_id)
        return prefs.get(f"{category}:{key}", default)

    def _get_all_prefs(self, user_id: str) -> Dict[str, Any]:
        if user_id in self._pref_cache:
            return self._pref_cache[user_id]

        cutoff = time.time() - PERSONA_PREFERENCE_DECAY_DAYS * 86400
        with self._db() as db:
            rows = db.execute(
                "SELECT category, key, value, weight, timestamp FROM preferences "
                "WHERE user_id=? AND timestamp>? ORDER BY timestamp DESC",
                (user_id, cutoff)
            ).fetchall()

        # Weighted aggregation — more recent = higher weight
        aggregated: Dict[str, Dict] = {}
        for r in rows:
            k = f"{r['category']}:{r['key']}"
            age_days = (time.time() - r["timestamp"]) / 86400
            decay = max(0.1, 1.0 - age_days / PERSONA_PREFERENCE_DECAY_DAYS)
            eff_weight = float(r["weight"]) * decay
            if k not in aggregated or eff_weight > aggregated[k]["weight"]:
                try:
                    val = json.loads(r["value"])
                except Exception:
                    val = r["value"]
                aggregated[k] = {"value": val, "weight": eff_weight}

        result = {k: v["value"] for k, v in aggregated.items()}
        self._pref_cache[user_id] = result
        return result

    def get_top_preferences(self, user_id: str, category: str,
                             top_n: int = 5) -> List[Tuple[str, Any, float]]:
        """Return top-N preferred items in a category as (key, value, weight) tuples."""
        with self._db() as db:
            rows = db.execute(
                "SELECT key, value, SUM(weight) as total_weight FROM preferences "
                "WHERE user_id=? AND category=? GROUP BY key ORDER BY total_weight DESC LIMIT ?",
                (user_id, category, top_n)
            ).fetchall()
        result = []
        for r in rows:
            try:
                val = json.loads(r["value"])
            except Exception:
                val = r["value"]
            result.append((r["key"], val, round(float(r["total_weight"]), 2)))
        return result

    # ── Shortcut management ───────────────────────────────────────────────────
    def add_shortcut(self, user_id: str, name: str, trigger: str,
                     actions: List[Dict]) -> int:
        with self._db() as db:
            cur = db.execute(
                "INSERT INTO shortcuts(user_id,name,trigger,actions,created_at) VALUES(?,?,?,?,?)",
                (user_id, name, trigger, json.dumps(actions), time.time())
            )
        log.info("Shortcut added: %s / %s → %s", user_id, name, trigger)
        return cur.lastrowid

    def match_shortcut(self, user_id: str, text: str) -> Optional[Shortcut]:
        """Check if input text matches any registered shortcut trigger."""
        with self._db() as db:
            rows = db.execute(
                "SELECT * FROM shortcuts WHERE user_id=?", (user_id,)
            ).fetchall()
        text_lower = text.lower()
        for row in rows:
            if row["trigger"].lower() in text_lower:
                actions = []
                try:
                    actions = json.loads(row["actions"])
                except Exception:
                    pass
                return Shortcut(
                    user_id=row["user_id"], name=row["name"],
                    trigger=row["trigger"], actions=actions,
                    created_at=row["created_at"],
                    use_count=row["use_count"],
                    last_used=row["last_used"]
                )
        return None

    def use_shortcut(self, shortcut_id: int):
        with self._db() as db:
            db.execute(
                "UPDATE shortcuts SET use_count=use_count+1, last_used=? WHERE id=?",
                (time.time(), shortcut_id)
            )

    def list_shortcuts(self, user_id: str) -> List[Dict]:
        with self._db() as db:
            rows = db.execute(
                "SELECT * FROM shortcuts WHERE user_id=? ORDER BY use_count DESC",
                (user_id,)
            ).fetchall()
        return [dict(r) for r in rows]

    def delete_shortcut(self, shortcut_id: int):
        with self._db() as db:
            db.execute("DELETE FROM shortcuts WHERE id=?", (shortcut_id,))

    # ── Automatic learning ────────────────────────────────────────────────────
    def observe(self, user_id: str, event_type: str, data: Dict):
        """
        Observe user behaviour and infer preferences automatically.
        event_type: "opened_app", "used_tool", "dismissed_reco", "accepted_reco", etc.
        """
        with self._db() as db:
            db.execute(
                "INSERT INTO feedback_log(user_id,event_type,data,timestamp) VALUES(?,?,?,?)",
                (user_id, event_type, json.dumps(data), time.time())
            )

        # Infer preferences
        if event_type == "opened_app":
            app = data.get("app", "")
            if app:
                self.set_preference(user_id, PREF_APP, app, True,
                                    weight=1.0, source="inferred")
        elif event_type == "accepted_reco":
            item_type = data.get("item_type", "")
            item_id = data.get("item_id", "")
            if item_type and item_id:
                self.set_preference(user_id, PREF_SERVICE, f"{item_type}:{item_id}", True,
                                    weight=2.0, source="inferred")
        elif event_type == "changed_tone":
            tone = data.get("tone", "")
            if tone:
                self.set_preference(user_id, PREF_TONE, "current", tone,
                                    weight=3.0, source="explicit")

    # ── Personalized response builder ─────────────────────────────────────────
    def personalize_response(self, user_id: str, response: str) -> str:
        """Apply user's tone and verbosity preferences to a response."""
        profile = self.get_profile(user_id)
        tone = profile.tone
        verbosity = profile.verbosity

        if verbosity == "minimal":
            # Truncate to first 2 sentences
            sentences = [s.strip() for s in response.split(".") if s.strip()]
            response = ". ".join(sentences[:2]) + ("." if sentences else "")

        if tone == "formal":
            response = response.replace("you're", "you are").replace("it's", "it is")
            response = response.replace("can't", "cannot").replace("won't", "will not")
        elif tone == "casual":
            response = response.replace("you are", "you're").replace("it is", "it's")

        return response

    def get_personalization_summary(self, user_id: str) -> Dict:
        profile = self.get_profile(user_id)
        fav_apps = self.get_top_preferences(user_id, PREF_APP, top_n=5)
        fav_services = self.get_top_preferences(user_id, PREF_SERVICE, top_n=5)
        shortcuts = self.list_shortcuts(user_id)
        return {
            "user_id": user_id,
            "profile": profile.to_dict(),
            "favorite_apps": [{"app": k, "weight": w} for k, _, w in fav_apps],
            "favorite_services": [{"service": k, "weight": w} for k, _, w in fav_services],
            "shortcuts": shortcuts[:10],
            "total_preferences": len(self._get_all_prefs(user_id)),
        }

    # ── Multi-user context switching ──────────────────────────────────────────
    async def detect_active_user(self, voice_signature: Optional[str] = None,
                                  device_id: Optional[str] = None) -> str:
        """Detect which registered user is active. Fallback: return default."""
        if device_id:
            with self._db() as db:
                row = db.execute(
                    "SELECT user_id FROM preferences "
                    "WHERE category='device' AND key=? ORDER BY timestamp DESC LIMIT 1",
                    (device_id,)
                ).fetchone()
            if row:
                return row["user_id"]
        return DEFAULT_USER_ID
