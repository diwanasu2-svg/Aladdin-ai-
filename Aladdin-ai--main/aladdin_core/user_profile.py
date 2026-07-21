"""User profile management — name, nickname, language, timezone, preferred assistant name."""

from __future__ import annotations

import json
import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional

log = logging.getLogger(__name__)


class UserProfile:
    """Manages user profile information."""

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()
        self._cache: Dict[str, Any] = {}
        self._load_cache()

    def _init_schema(self) -> None:
        """Initialize the profile table."""
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS user_profile (
                id              INTEGER PRIMARY KEY CHECK (id = 1),
                name            TEXT,
                nickname        TEXT,
                language        TEXT DEFAULT 'en',
                timezone        TEXT,
                preferred_assistant_name TEXT,
                bio             TEXT,
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        self._db.commit()
        # Ensure exactly one row exists
        cur = self._db.execute("SELECT COUNT(*) as cnt FROM user_profile")
        if cur.fetchone()["cnt"] == 0:
            self._db.execute("INSERT INTO user_profile(id, language) VALUES(1, 'en')")
            self._db.commit()

    def _load_cache(self) -> None:
        """Load profile into memory cache."""
        cur = self._db.execute("SELECT * FROM user_profile WHERE id = 1")
        row = cur.fetchone()
        if row:
            self._cache = dict(row)

    def _update_timestamp(self) -> None:
        """Update the updated_at timestamp."""
        self._db.execute(
            "UPDATE user_profile SET updated_at = CURRENT_TIMESTAMP WHERE id = 1"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # Profile getters
    # ------------------------------------------------------------------

    def get_name(self) -> Optional[str]:
        """Get user's full name."""
        return self._cache.get("name")

    def get_nickname(self) -> Optional[str]:
        """Get user's nickname."""
        return self._cache.get("nickname")

    def get_language(self) -> str:
        """Get user's preferred language."""
        return self._cache.get("language", "en")

    def get_timezone(self) -> Optional[str]:
        """Get user's timezone."""
        return self._cache.get("timezone")

    def get_preferred_assistant_name(self) -> Optional[str]:
        """Get user's preferred name for the assistant."""
        return self._cache.get("preferred_assistant_name")

    def get_bio(self) -> Optional[str]:
        """Get user's bio/description."""
        return self._cache.get("bio")

    def get_all(self) -> Dict[str, Any]:
        """Get entire profile as dict."""
        return dict(self._cache)

    # ------------------------------------------------------------------
    # Profile setters
    # ------------------------------------------------------------------

    def set_name(self, name: str) -> None:
        """Set user's full name."""
        self._cache["name"] = name
        self._db.execute("UPDATE user_profile SET name = ? WHERE id = 1", (name,))
        self._update_timestamp()
        log.info("Profile: name set to %s", name)

    def set_nickname(self, nickname: str) -> None:
        """Set user's nickname."""
        self._cache["nickname"] = nickname
        self._db.execute(
            "UPDATE user_profile SET nickname = ? WHERE id = 1", (nickname,)
        )
        self._update_timestamp()
        log.info("Profile: nickname set to %s", nickname)

    def set_language(self, language: str) -> None:
        """Set user's preferred language (e.g., 'en', 'es', 'fr')."""
        self._cache["language"] = language
        self._db.execute(
            "UPDATE user_profile SET language = ? WHERE id = 1", (language,)
        )
        self._update_timestamp()
        log.info("Profile: language set to %s", language)

    def set_timezone(self, timezone: str) -> None:
        """Set user's timezone (e.g., 'UTC', 'America/New_York')."""
        self._cache["timezone"] = timezone
        self._db.execute(
            "UPDATE user_profile SET timezone = ? WHERE id = 1", (timezone,)
        )
        self._update_timestamp()
        log.info("Profile: timezone set to %s", timezone)

    def set_preferred_assistant_name(self, name: str) -> None:
        """Set user's preferred name for the assistant."""
        self._cache["preferred_assistant_name"] = name
        self._db.execute(
            "UPDATE user_profile SET preferred_assistant_name = ? WHERE id = 1",
            (name,),
        )
        self._update_timestamp()
        log.info("Profile: preferred assistant name set to %s", name)

    def set_bio(self, bio: str) -> None:
        """Set user's bio/description."""
        self._cache["bio"] = bio
        self._db.execute("UPDATE user_profile SET bio = ? WHERE id = 1", (bio,))
        self._update_timestamp()
        log.info("Profile: bio set")

    def update_multiple(self, data: Dict[str, Any]) -> None:
        """Update multiple profile fields at once."""
        allowed_fields = {
            "name",
            "nickname",
            "language",
            "timezone",
            "preferred_assistant_name",
            "bio",
        }
        updates = {k: v for k, v in data.items() if k in allowed_fields}
        if not updates:
            return

        set_clause = ", ".join([f"{k} = ?" for k in updates.keys()])
        values = list(updates.values())
        values.append(1)  # id = 1
        self._db.execute(f"UPDATE user_profile SET {set_clause} WHERE id = 1", values)
        self._update_timestamp()
        self._cache.update(updates)
        log.info("Profile updated: %s", list(updates.keys()))

    def clear(self) -> None:
        """Clear all profile data."""
        self._cache = {"language": "en", "id": 1}
        self._db.execute(
            "UPDATE user_profile SET name = NULL, nickname = NULL, "
            "timezone = NULL, preferred_assistant_name = NULL, "
            "bio = NULL, language = 'en' WHERE id = 1"
        )
        self._update_timestamp()
        log.warning("Profile cleared")
