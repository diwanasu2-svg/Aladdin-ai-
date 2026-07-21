"""Smart memory — SQLite-backed with user profile, facts, and semantic search."""

from __future__ import annotations

import json
import logging
import re
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .config import MemoryCfg

log = logging.getLogger(__name__)


class ConversationMemory:
    """
    Persistent multi-layer memory:
      - conversation turns (rolling window)
      - long-term facts extracted from conversations
      - user profile (name, preferences, projects)
      - semantic keyword search over past turns
    """

    def __init__(self, cfg: MemoryCfg):
        self.cfg = cfg
        db_path = Path(cfg.db_path)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()
        self._profile: Dict[str, Any] = self._load_profile()

    # ------------------------------------------------------------------
    # Schema
    # ------------------------------------------------------------------

    def _init_schema(self) -> None:
        self._db.executescript("""
            CREATE TABLE IF NOT EXISTS turns (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts        DATETIME DEFAULT CURRENT_TIMESTAMP,
                user      TEXT NOT NULL,
                assistant TEXT NOT NULL,
                topic     TEXT
            );

            CREATE TABLE IF NOT EXISTS facts (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts        DATETIME DEFAULT CURRENT_TIMESTAMP,
                category  TEXT NOT NULL DEFAULT 'general',
                key       TEXT NOT NULL,
                value     TEXT NOT NULL,
                importance INTEGER DEFAULT 1
            );

            CREATE UNIQUE INDEX IF NOT EXISTS facts_key_idx ON facts(key);

            CREATE TABLE IF NOT EXISTS profile (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
        """)
        self._db.commit()

    # ------------------------------------------------------------------
    # Turn management
    # ------------------------------------------------------------------

    def append(self, user: str, assistant: str, topic: Optional[str] = None) -> None:
        """Store a conversation turn and auto-extract facts."""
        self._db.execute(
            "INSERT INTO turns(user, assistant, topic) VALUES(?,?,?)",
            (user, assistant, topic),
        )
        self._db.commit()
        self._auto_extract_facts(user, assistant)

    def recent(self, n: int) -> List[Tuple[str, str]]:
        """Return the n most recent turns as (user, assistant) pairs."""
        cur = self._db.execute(
            "SELECT user, assistant FROM turns ORDER BY id DESC LIMIT ?", (n,)
        )
        rows = cur.fetchall()
        return [(r["user"], r["assistant"]) for r in reversed(rows)]

    def search(self, query: str, limit: int = 5) -> List[Tuple[str, str]]:
        """Keyword search over conversation history."""
        words = query.lower().split()
        if not words:
            return []
        like_clauses = " OR ".join(
            [f"LOWER(user) LIKE ? OR LOWER(assistant) LIKE ?" for _ in words]
        )
        params = []
        for w in words:
            params.extend([f"%{w}%", f"%{w}%"])
        params.append(limit)
        cur = self._db.execute(
            f"SELECT user, assistant FROM turns WHERE {like_clauses} ORDER BY id DESC LIMIT ?",
            params,
        )
        rows = cur.fetchall()
        return [(r["user"], r["assistant"]) for r in rows]

    def summarize_old(self, keep_recent: int = 20) -> Optional[str]:
        """Summarize old turns to keep context window manageable."""
        cur = self._db.execute("SELECT COUNT(*) as cnt FROM turns")
        total = cur.fetchone()["cnt"]
        if total <= keep_recent + self.cfg.summarize_after:
            return None
        # Archive old turns by marking them (we keep the DB clean)
        self._db.execute(
            "DELETE FROM turns WHERE id NOT IN "
            "(SELECT id FROM turns ORDER BY id DESC LIMIT ?)",
            (keep_recent,),
        )
        self._db.commit()
        log.info("Pruned old conversation turns, kept %d recent.", keep_recent)
        return f"Pruned to {keep_recent} recent turns."

    # ------------------------------------------------------------------
    # Facts / long-term memory
    # ------------------------------------------------------------------

    def remember(
        self, key: str, value: str, category: str = "general", importance: int = 1
    ) -> None:
        """Store or update a long-term fact."""
        self._db.execute(
            "INSERT INTO facts(key, value, category, importance) VALUES(?,?,?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, ts=CURRENT_TIMESTAMP",
            (key.lower(), value, category, importance),
        )
        self._db.commit()
        log.debug("Remembered: %s = %s [%s]", key, value, category)

    def recall(self, key: str) -> Optional[str]:
        """Retrieve a stored fact by key."""
        cur = self._db.execute("SELECT value FROM facts WHERE key=?", (key.lower(),))
        row = cur.fetchone()
        return row["value"] if row else None

    def recall_category(self, category: str) -> Dict[str, str]:
        """Recall all facts in a category."""
        cur = self._db.execute(
            "SELECT key, value FROM facts WHERE category=? ORDER BY importance DESC",
            (category,),
        )
        return {r["key"]: r["value"] for r in cur.fetchall()}

    def forget(self, key: str) -> None:
        """Remove a fact."""
        self._db.execute("DELETE FROM facts WHERE key=?", (key.lower(),))
        self._db.commit()

    def all_facts(self) -> List[Dict[str, Any]]:
        cur = self._db.execute(
            "SELECT key, value, category, importance, ts FROM facts ORDER BY importance DESC, ts DESC"
        )
        return [dict(r) for r in cur.fetchall()]

    # ------------------------------------------------------------------
    # User profile
    # ------------------------------------------------------------------

    def _load_profile(self) -> Dict[str, Any]:
        cur = self._db.execute("SELECT key, value FROM profile")
        return {r["key"]: json.loads(r["value"]) for r in cur.fetchall()}

    def set_profile(self, key: str, value: Any) -> None:
        self._profile[key] = value
        self._db.execute(
            "INSERT INTO profile(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, json.dumps(value)),
        )
        self._db.commit()

    def get_profile(self, key: str, default: Any = None) -> Any:
        return self._profile.get(key, default)

    @property
    def user_name(self) -> Optional[str]:
        return self.get_profile("name")

    @user_name.setter
    def user_name(self, name: str) -> None:
        self.set_profile("name", name)

    def profile_summary(self) -> str:
        """Return profile facts as a brief string for system prompt injection."""
        parts = []
        if n := self._profile.get("name"):
            parts.append(f"User's name is {n}")
        for k, v in self._profile.items():
            if k == "name":
                continue
            parts.append(f"{k}: {v}")
        return ". ".join(parts)

    # ------------------------------------------------------------------
    # Auto fact extraction
    # ------------------------------------------------------------------

    def _auto_extract_facts(self, user: str, assistant: str) -> None:
        """Simple rule-based fact extraction from conversation."""
        text = user.lower()

        # Name detection
        name_match = re.search(
            r"(?:my name is|i am|i'm|call me)\s+([a-z][a-z\s]{1,20}?)(?:\.|,|$|\s)",
            text,
        )
        if name_match:
            name = name_match.group(1).strip().title()
            self.user_name = name
            self.remember("user_name", name, category="identity", importance=5)
            log.info("Learned user name: %s", name)

        # Preference detection
        like_match = re.search(
            r"i (?:like|love|prefer|enjoy)\s+(.{5,50}?)(?:\.|,|$)", text
        )
        if like_match:
            pref = like_match.group(1).strip()
            self.remember(
                f"likes_{pref[:20]}", pref, category="preference", importance=2
            )

        dislike_match = re.search(
            r"i (?:don't like|dislike|hate)\s+(.{5,50}?)(?:\.|,|$)", text
        )
        if dislike_match:
            pref = dislike_match.group(1).strip()
            self.remember(
                f"dislikes_{pref[:20]}", pref, category="preference", importance=2
            )

    # ------------------------------------------------------------------
    # Context building
    # ------------------------------------------------------------------

    def build_context_prompt(self) -> str:
        """Build a brief memory context string to prepend to the system prompt."""
        lines = []
        profile = self.profile_summary()
        if profile:
            lines.append(f"Known facts about user: {profile}.")
        facts = self.all_facts()
        if facts:
            top = facts[:5]
            summaries = [f"{f['key']}={f['value']}" for f in top]
            lines.append(f"Remembered: {'; '.join(summaries)}.")
        return " ".join(lines)

    def stats(self) -> Dict[str, int]:
        turns = self._db.execute("SELECT COUNT(*) as c FROM turns").fetchone()["c"]
        facts = self._db.execute("SELECT COUNT(*) as c FROM facts").fetchone()["c"]
        return {"turns": turns, "facts": facts}
