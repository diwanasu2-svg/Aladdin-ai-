"""Multilingual Conversation Memory — Feature 10, 11.

Ensures conversation memory works correctly across Hindi, Gujarati, and English:
  - Language tagging per message
  - Unicode-safe storage (UTF-8 throughout)
  - Language-filtered retrieval
  - Context preservation across language switches
  - Devanagari and Gujarati script display support
"""

from __future__ import annotations

import sqlite3
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

from language_detector import LANG_ENGLISH, LANG_GUJARATI, LANG_HINDI


@dataclass
class MultilingualMessage:
    """A single conversation message with language metadata."""

    role: str  # "user" | "assistant"
    content: str  # Original text (UTF-8, any script)
    language: str  # "hi" | "gu" | "en"
    is_mixed: bool = False  # Hinglish / Gujlish
    confidence: float = 1.0  # Language detection confidence
    timestamp: str = field(
        default_factory=lambda: datetime.now(timezone.utc).isoformat()
    )
    id: Optional[int] = None

    def to_dict(self) -> dict:
        return {
            "role": self.role,
            "content": self.content,
            "language": self.language,
            "is_mixed": self.is_mixed,
            "confidence": self.confidence,
            "timestamp": self.timestamp,
        }

    @classmethod
    def from_row(cls, row: tuple) -> "MultilingualMessage":
        return cls(
            id=row[0],
            role=row[1],
            content=row[2],
            language=row[3],
            is_mixed=bool(row[4]),
            confidence=row[5],
            timestamp=row[6],
        )


class MultilingualMemory:
    """SQLite-backed multilingual conversation memory.

    Features:
      - All text stored as UTF-8 (Devanagari, Gujarati, Latin)
      - Language-tagged messages
      - Language-filtered context retrieval
      - Unicode normalization on insert
      - Cross-language context window support
    """

    CREATE_SQL = """
    CREATE TABLE IF NOT EXISTS multilingual_messages (
        id        INTEGER PRIMARY KEY AUTOINCREMENT,
        role      TEXT    NOT NULL,
        content   TEXT    NOT NULL,
        language  TEXT    NOT NULL DEFAULT 'en',
        is_mixed  INTEGER NOT NULL DEFAULT 0,
        confidence REAL   NOT NULL DEFAULT 1.0,
        timestamp TEXT    NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_lang ON multilingual_messages(language);
    CREATE INDEX IF NOT EXISTS idx_ts   ON multilingual_messages(timestamp);
    """

    def __init__(self, db_path: str = "data/multilingual_memory.sqlite"):
        self._db_path = Path(db_path)
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self._db_path), check_same_thread=False)
        self._conn.execute("PRAGMA foreign_keys = ON")
        self._conn.executescript(self.CREATE_SQL)
        self._conn.commit()

    # ── Write ─────────────────────────────────────────────────────────────────

    def add(self, message: MultilingualMessage) -> int:
        """Store *message* and return its row ID."""
        # Normalize Unicode before storing
        content = unicodedata.normalize("NFC", message.content)
        cursor = self._conn.execute(
            """INSERT INTO multilingual_messages
               (role, content, language, is_mixed, confidence, timestamp)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (
                message.role,
                content,
                message.language,
                int(message.is_mixed),
                message.confidence,
                message.timestamp,
            ),
        )
        self._conn.commit()
        return cursor.lastrowid

    def add_user(
        self,
        content: str,
        language: str,
        is_mixed: bool = False,
        confidence: float = 1.0,
    ) -> int:
        return self.add(
            MultilingualMessage(
                role="user",
                content=content,
                language=language,
                is_mixed=is_mixed,
                confidence=confidence,
            )
        )

    def add_assistant(
        self,
        content: str,
        language: str,
        is_mixed: bool = False,
    ) -> int:
        return self.add(
            MultilingualMessage(
                role="assistant",
                content=content,
                language=language,
                is_mixed=is_mixed,
            )
        )

    # ── Read ──────────────────────────────────────────────────────────────────

    def get_context(
        self,
        limit: int = 12,
        language: Optional[str] = None,
        include_cross_language: bool = True,
    ) -> List[MultilingualMessage]:
        """Retrieve recent conversation context.

        Args:
            limit:                  Maximum number of messages to return.
            language:               If set, prefer messages in this language.
            include_cross_language: If True, include messages from other
                                    languages (for context continuity).
        """
        if language and not include_cross_language:
            rows = self._conn.execute(
                """SELECT id, role, content, language, is_mixed, confidence, timestamp
                   FROM multilingual_messages
                   WHERE language = ?
                   ORDER BY id DESC LIMIT ?""",
                (language, limit),
            ).fetchall()
        else:
            rows = self._conn.execute(
                """SELECT id, role, content, language, is_mixed, confidence, timestamp
                   FROM multilingual_messages
                   ORDER BY id DESC LIMIT ?""",
                (limit,),
            ).fetchall()

        messages = [MultilingualMessage.from_row(r) for r in reversed(rows)]
        return messages

    def get_context_for_llm(
        self,
        limit: int = 12,
        language: Optional[str] = None,
    ) -> List[dict]:
        """Return context formatted for LLM API (list of role/content dicts)."""
        messages = self.get_context(limit=limit, language=language)
        return [{"role": m.role, "content": m.content} for m in messages]

    def get_recent_languages(self, window: int = 5) -> List[str]:
        """Return the languages used in the last *window* user turns."""
        rows = self._conn.execute(
            """SELECT language FROM multilingual_messages
               WHERE role = 'user'
               ORDER BY id DESC LIMIT ?""",
            (window,),
        ).fetchall()
        return [r[0] for r in rows]

    def language_stats(self) -> dict:
        """Return message counts per language."""
        rows = self._conn.execute(
            """SELECT language, COUNT(*) FROM multilingual_messages GROUP BY language"""
        ).fetchall()
        return {r[0]: r[1] for r in rows}

    # ── Cleanup ───────────────────────────────────────────────────────────────

    def prune(self, keep_latest: int = 200) -> int:
        """Delete old messages, keeping the most recent *keep_latest*."""
        cursor = self._conn.execute(
            """DELETE FROM multilingual_messages
               WHERE id NOT IN (
                   SELECT id FROM multilingual_messages ORDER BY id DESC LIMIT ?
               )""",
            (keep_latest,),
        )
        self._conn.commit()
        return cursor.rowcount

    def clear(self) -> None:
        self._conn.execute("DELETE FROM multilingual_messages")
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
