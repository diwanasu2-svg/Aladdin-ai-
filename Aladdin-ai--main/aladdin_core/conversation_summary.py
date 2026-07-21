"""Conversation Summary memory — automatically summarize long conversations.

Part of Phase 3 — Smart Memory Part 2.

Responsibilities:
  * Track an in-memory buffer of recent conversation messages.
  * Trigger automatic summarization once the buffer exceeds a configurable
    message count.
  * Persist summaries to SQLite for context retrieval across sessions.
  * Reduce token usage by replacing old turns with their summary in any
    prompt context built from this manager.

By default the summarizer is a deterministic, dependency-free extractive
summarizer.  A custom callable (``summarizer_fn``) can be injected to use an
LLM-based summarizer instead (e.g. via the existing Ollama client).
"""

from __future__ import annotations

import logging
import re
import sqlite3
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


SummarizerFn = Callable[[List[Dict[str, str]], int], str]


def _default_summarizer(messages: List[Dict[str, str]], max_length: int) -> str:
    """Lightweight extractive summarizer (no external deps).

    Strategy: keep the first sentence and the most "informative" sentences
    (longest unique-content) up to ``max_length`` characters.
    """
    if not messages:
        return ""
    parts: List[str] = []
    for m in messages:
        role = m.get("role", "user")
        content = (m.get("content") or "").strip()
        if not content:
            continue
        parts.append(f"{role}: {content}")
    text = " \n".join(parts)

    sentences = re.split(r"(?<=[.!?])\s+", text)
    seen_words = set()
    selected: List[str] = []
    if sentences:
        selected.append(sentences[0])
        seen_words.update(sentences[0].lower().split())

    # rank remaining sentences by # of new informative words
    ranked = []
    for s in sentences[1:]:
        words = [w for w in re.findall(r"\w+", s.lower()) if len(w) > 3]
        new = sum(1 for w in words if w not in seen_words)
        ranked.append((new, s))
    ranked.sort(key=lambda x: x[0], reverse=True)

    for _, s in ranked:
        candidate = " ".join(selected + [s]).strip()
        if len(candidate) > max_length:
            break
        selected.append(s)
        seen_words.update(s.lower().split())

    summary = " ".join(selected).strip()
    if len(summary) > max_length:
        summary = summary[: max_length - 1].rstrip() + "…"
    return summary


class ConversationSummary:
    """Automatic conversation summarization with persistent storage.

    Schema:
      conversation_messages: id, conversation_id, role, content, created_at
      conversation_summaries: id, conversation_id, summary, message_count,
                              start_at, end_at, created_at
    """

    def __init__(
        self,
        db_path: str,
        trigger_messages: int = 30,
        max_length: int = 500,
        summarizer_fn: Optional[SummarizerFn] = None,
    ):
        self.db_path = db_path
        self.trigger_messages = max(int(trigger_messages or 1), 1)
        self.max_length = max(int(max_length or 200), 50)
        self._summarizer = summarizer_fn or _default_summarizer

        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                role            TEXT NOT NULL,
                content         TEXT NOT NULL,
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS cm_conv_idx "
            "ON conversation_messages(conversation_id)"
        )
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS conversation_summaries (
                id              TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                summary         TEXT NOT NULL,
                message_count   INTEGER DEFAULT 0,
                start_at        DATETIME,
                end_at          DATETIME,
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS cs_conv_idx "
            "ON conversation_summaries(conversation_id)"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # Configuration
    # ------------------------------------------------------------------
    def set_summarizer(self, fn: SummarizerFn) -> None:
        """Plug an LLM-backed summarizer (or any callable) at runtime."""
        if fn is None:
            self._summarizer = _default_summarizer
        else:
            self._summarizer = fn

    def configure(
        self,
        trigger_messages: Optional[int] = None,
        max_length: Optional[int] = None,
    ) -> None:
        if trigger_messages is not None:
            self.trigger_messages = max(int(trigger_messages), 1)
        if max_length is not None:
            self.max_length = max(int(max_length), 50)

    # ------------------------------------------------------------------
    # Message intake
    # ------------------------------------------------------------------
    def add_message(
        self,
        role: str,
        content: str,
        conversation_id: str = "default",
    ) -> Optional[str]:
        """Record a single message. If the rolling unsummarized message
        count for the conversation reaches ``trigger_messages``, a summary
        is automatically generated and stored.  Returns the new summary id
        if one was created, otherwise None.
        """
        if not content:
            return None
        self._db.execute(
            "INSERT INTO conversation_messages(conversation_id, role, content) "
            "VALUES(?, ?, ?)",
            (conversation_id, role, content),
        )
        self._db.commit()

        if self._pending_count(conversation_id) >= self.trigger_messages:
            return self.summarize_now(conversation_id)
        return None

    # ------------------------------------------------------------------
    # Summarization
    # ------------------------------------------------------------------
    def _pending_count(self, conversation_id: str) -> int:
        last = self._latest_summary_end(conversation_id)
        if last is None:
            row = self._db.execute(
                "SELECT COUNT(*) AS c FROM conversation_messages "
                "WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
        else:
            row = self._db.execute(
                "SELECT COUNT(*) AS c FROM conversation_messages "
                "WHERE conversation_id = ? AND created_at > ?",
                (conversation_id, last),
            ).fetchone()
        return int(row["c"])

    def _latest_summary_end(self, conversation_id: str) -> Optional[str]:
        row = self._db.execute(
            "SELECT end_at FROM conversation_summaries "
            "WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1",
            (conversation_id,),
        ).fetchone()
        return row["end_at"] if row and row["end_at"] else None

    def _pending_messages(self, conversation_id: str) -> List[Dict[str, str]]:
        last = self._latest_summary_end(conversation_id)
        if last is None:
            rows = self._db.execute(
                "SELECT role, content, created_at FROM conversation_messages "
                "WHERE conversation_id = ? ORDER BY id ASC",
                (conversation_id,),
            ).fetchall()
        else:
            rows = self._db.execute(
                "SELECT role, content, created_at FROM conversation_messages "
                "WHERE conversation_id = ? AND created_at > ? ORDER BY id ASC",
                (conversation_id, last),
            ).fetchall()
        return [dict(r) for r in rows]

    def summarize_now(self, conversation_id: str = "default") -> Optional[str]:
        """Force a summary of all pending (unsummarized) messages.

        Returns the new summary id, or None if there were no messages.
        """
        pending = self._pending_messages(conversation_id)
        if not pending:
            return None
        try:
            summary_text = self._summarizer(
                [{"role": p["role"], "content": p["content"]} for p in pending],
                self.max_length,
            )
        except Exception as exc:  # pragma: no cover - defensive
            log.warning("Custom summarizer failed, falling back: %s", exc)
            summary_text = _default_summarizer(pending, self.max_length)

        if not summary_text:
            return None

        sid = str(uuid.uuid4())
        start_at = pending[0]["created_at"]
        end_at = pending[-1]["created_at"]
        self._db.execute(
            "INSERT INTO conversation_summaries(id, conversation_id, summary, "
            "message_count, start_at, end_at) VALUES(?, ?, ?, ?, ?, ?)",
            (sid, conversation_id, summary_text, len(pending), start_at, end_at),
        )
        self._db.commit()
        log.info(
            "Conversation summarized: %s (%d msgs -> %d chars)",
            conversation_id,
            len(pending),
            len(summary_text),
        )
        return sid

    # ------------------------------------------------------------------
    # Retrieval
    # ------------------------------------------------------------------
    def get_summaries(
        self, conversation_id: str = "default", limit: int = 10
    ) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT * FROM conversation_summaries WHERE conversation_id = ? "
            "ORDER BY created_at DESC LIMIT ?",
            (conversation_id, int(limit)),
        ).fetchall()
        return [dict(r) for r in rows]

    def latest_summary(
        self, conversation_id: str = "default"
    ) -> Optional[Dict[str, Any]]:
        summaries = self.get_summaries(conversation_id, limit=1)
        return summaries[0] if summaries else None

    def get_context(
        self,
        conversation_id: str = "default",
        recent_messages: int = 10,
    ) -> Dict[str, Any]:
        """Return a token-efficient context dict:

        ``{"summary": <combined past summaries>, "recent": [messages]}``
        """
        summaries = list(reversed(self.get_summaries(conversation_id, limit=5)))
        combined = " ".join(s["summary"] for s in summaries).strip()
        recent_rows = self._db.execute(
            "SELECT role, content FROM conversation_messages "
            "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
            (conversation_id, int(recent_messages)),
        ).fetchall()
        recent = [dict(r) for r in reversed(recent_rows)]
        return {"summary": combined, "recent": recent}

    def list_messages(
        self, conversation_id: str = "default", limit: int = 100
    ) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT role, content, created_at FROM conversation_messages "
            "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
            (conversation_id, int(limit)),
        ).fetchall()
        return [dict(r) for r in reversed(rows)]

    # ------------------------------------------------------------------
    # Stats / utility
    # ------------------------------------------------------------------
    def stats(self, conversation_id: Optional[str] = None) -> Dict[str, Any]:
        if conversation_id:
            msg = self._db.execute(
                "SELECT COUNT(*) AS c FROM conversation_messages "
                "WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()["c"]
            summ = self._db.execute(
                "SELECT COUNT(*) AS c FROM conversation_summaries "
                "WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()["c"]
        else:
            msg = self._db.execute(
                "SELECT COUNT(*) AS c FROM conversation_messages"
            ).fetchone()["c"]
            summ = self._db.execute(
                "SELECT COUNT(*) AS c FROM conversation_summaries"
            ).fetchone()["c"]
        return {
            "messages": msg,
            "summaries": summ,
            "trigger_messages": self.trigger_messages,
            "max_length": self.max_length,
        }

    def clear(self, conversation_id: Optional[str] = None) -> None:
        if conversation_id:
            self._db.execute(
                "DELETE FROM conversation_messages WHERE conversation_id = ?",
                (conversation_id,),
            )
            self._db.execute(
                "DELETE FROM conversation_summaries WHERE conversation_id = ?",
                (conversation_id,),
            )
        else:
            self._db.execute("DELETE FROM conversation_messages")
            self._db.execute("DELETE FROM conversation_summaries")
        self._db.commit()
        log.warning("Conversation summary cleared (conv=%s)", conversation_id or "ALL")

    def close(self) -> None:
        try:
            self._db.close()
        except Exception:  # pragma: no cover
            pass
