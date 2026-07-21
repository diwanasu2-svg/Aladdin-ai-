"""Compatibility copy of the legacy ConversationMemory API.

Keeps Smart Memory Part 1 behavior importable from ``aladdin_core.memory``
while the newer ``MemoryManager`` continues to live beside it.
"""

from __future__ import annotations

import json
import logging
import re
import sqlite3
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

try:  # pragma: no cover - import path flexibility
    from .config import MemoryCfg  # type: ignore
except Exception:  # pragma: no cover
    from config import MemoryCfg  # type: ignore

log = logging.getLogger(__name__)


class ConversationMemory:
    """Persistent multi-layer memory with rolling conversation context."""

    def __init__(self, cfg: MemoryCfg):
        self.cfg = cfg
        db_path = Path(cfg.db_path)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()
        self._profile: Dict[str, Any] = self._load_profile()

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
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                ts         DATETIME DEFAULT CURRENT_TIMESTAMP,
                category   TEXT NOT NULL DEFAULT 'general',
                key        TEXT NOT NULL,
                value      TEXT NOT NULL,
                importance INTEGER DEFAULT 1
            );

            CREATE UNIQUE INDEX IF NOT EXISTS facts_key_idx ON facts(key);

            CREATE TABLE IF NOT EXISTS profile (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """)
        self._db.commit()

    def append(self, user: str, assistant: str, topic: Optional[str] = None) -> None:
        self._db.execute(
            "INSERT INTO turns(user, assistant, topic) VALUES(?,?,?)",
            (user, assistant, topic),
        )
        self._db.commit()
        self._auto_extract_facts(user, assistant)

    def recent(self, n: int) -> List[Tuple[str, str]]:
        cur = self._db.execute(
            "SELECT user, assistant FROM turns ORDER BY id DESC LIMIT ?", (n,)
        )
        rows = cur.fetchall()
        return [(row["user"], row["assistant"]) for row in reversed(rows)]

    def search(self, query: str, limit: int = 5) -> List[Tuple[str, str]]:
        words = query.lower().split()
        if not words:
            return []
        like_clauses = " OR ".join(
            ["LOWER(user) LIKE ? OR LOWER(assistant) LIKE ?" for _ in words]
        )
        params: List[Any] = []
        for word in words:
            params.extend([f"%{word}%", f"%{word}%"])
        params.append(limit)
        cur = self._db.execute(
            f"SELECT user, assistant FROM turns WHERE {like_clauses} ORDER BY id DESC LIMIT ?",
            params,
        )
        return [(row["user"], row["assistant"]) for row in cur.fetchall()]

    def summarize_old(self, keep_recent: int = 20) -> Optional[str]:
        total = self._db.execute("SELECT COUNT(*) AS cnt FROM turns").fetchone()["cnt"]
        if total <= keep_recent + self.cfg.summarize_after:
            return None
        self._db.execute(
            "DELETE FROM turns WHERE id NOT IN (SELECT id FROM turns ORDER BY id DESC LIMIT ?)",
            (keep_recent,),
        )
        self._db.commit()
        log.info("Pruned old conversation turns, kept %d recent.", keep_recent)
        return f"Pruned to {keep_recent} recent turns."

    def remember(
        self, key: str, value: str, category: str = "general", importance: int = 1
    ) -> None:
        self._db.execute(
            "INSERT INTO facts(key, value, category, importance) VALUES(?,?,?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, ts=CURRENT_TIMESTAMP",
            (key.lower(), value, category, importance),
        )
        self._db.commit()

    def recall(self, key: str) -> Optional[str]:
        row = self._db.execute(
            "SELECT value FROM facts WHERE key=?", (key.lower(),)
        ).fetchone()
        return row["value"] if row else None

    def recall_category(self, category: str) -> Dict[str, str]:
        rows = self._db.execute(
            "SELECT key, value FROM facts WHERE category=? ORDER BY importance DESC",
            (category,),
        ).fetchall()
        return {row["key"]: row["value"] for row in rows}

    def forget(self, key: str) -> None:
        self._db.execute("DELETE FROM facts WHERE key=?", (key.lower(),))
        self._db.commit()

    def all_facts(self) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT key, value, category, importance, ts FROM facts ORDER BY importance DESC, ts DESC"
        ).fetchall()
        return [dict(row) for row in rows]

    def _load_profile(self) -> Dict[str, Any]:
        rows = self._db.execute("SELECT key, value FROM profile").fetchall()
        return {row["key"]: json.loads(row["value"]) for row in rows}

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
        parts = []
        if self._profile.get("name"):
            parts.append(f"User's name is {self._profile['name']}")
        for key, value in self._profile.items():
            if key == "name":
                continue
            parts.append(f"{key}: {value}")
        return ". ".join(parts)

    def _auto_extract_facts(self, user: str, assistant: str) -> None:  # noqa: ARG002
        text = user.lower()
        name_match = re.search(
            r"(?:my name is|i am|i'm|call me)\s+([a-z][a-z\s]{1,20}?)(?:\.|,|$|\s)",
            text,
        )
        if name_match:
            name = name_match.group(1).strip().title()
            self.user_name = name
            self.remember("user_name", name, category="identity", importance=5)

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

    def build_context_prompt(self) -> str:
        lines = []
        profile = self.profile_summary()
        if profile:
            lines.append(f"Known facts about user: {profile}.")
        facts = self.all_facts()
        if facts:
            top = facts[:5]
            lines.append(
                "Remembered: "
                + "; ".join(f"{item['key']}={item['value']}" for item in top)
                + "."
            )
        return " ".join(lines)

    def stats(self) -> Dict[str, int]:
        turns = self._db.execute("SELECT COUNT(*) AS c FROM turns").fetchone()["c"]
        facts = self._db.execute("SELECT COUNT(*) AS c FROM facts").fetchone()["c"]
        return {"turns": turns, "facts": facts}
