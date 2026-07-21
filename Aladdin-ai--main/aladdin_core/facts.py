"""Facts management — store and retrieve reusable user facts."""

from __future__ import annotations

import json
import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class FactsManager:
    """Manages user facts and long-term knowledge."""

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        """Initialize the facts table."""
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS facts (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                key             TEXT NOT NULL UNIQUE,
                value           TEXT NOT NULL,
                category        TEXT DEFAULT 'general',
                importance      INTEGER DEFAULT 1,
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS facts_category_idx ON facts(category)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS facts_importance_idx ON facts(importance DESC)"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # Fact management
    # ------------------------------------------------------------------

    def remember(
        self, key: str, value: str, category: str = "general", importance: int = 1
    ) -> None:
        """Store or update a fact."""
        key_lower = key.lower()
        self._db.execute(
            "INSERT INTO facts(key, value, category, importance) VALUES(?, ?, ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, importance=excluded.importance, "
            "updated_at=CURRENT_TIMESTAMP",
            (key_lower, value, category, importance),
        )
        self._db.commit()
        log.debug("Fact remembered: %s = %s [%s]", key, value, category)

    def recall(self, key: str) -> Optional[str]:
        """Retrieve a fact by key."""
        cur = self._db.execute("SELECT value FROM facts WHERE key = ?", (key.lower(),))
        row = cur.fetchone()
        return row["value"] if row else None

    def update(self, key: str, value: str, importance: int = 1) -> None:
        """Update or replace a fact."""
        key_lower = key.lower()
        self._db.execute(
            "UPDATE facts SET value = ?, importance = ?, updated_at = CURRENT_TIMESTAMP "
            "WHERE key = ?",
            (value, importance, key_lower),
        )
        self._db.commit()
        log.info("Fact updated: %s", key)

    def forget(self, key: str) -> None:
        """Delete a fact."""
        self._db.execute("DELETE FROM facts WHERE key = ?", (key.lower(),))
        self._db.commit()
        log.info("Fact forgotten: %s", key)

    # ------------------------------------------------------------------
    # Fact retrieval
    # ------------------------------------------------------------------

    def get_by_category(self, category: str) -> Dict[str, str]:
        """Get all facts in a category, sorted by importance."""
        cur = self._db.execute(
            "SELECT key, value FROM facts WHERE category = ? ORDER BY importance DESC, updated_at DESC",
            (category,),
        )
        return {row["key"]: row["value"] for row in cur.fetchall()}

    def get_all(self) -> List[Dict[str, Any]]:
        """Get all facts sorted by importance and recency."""
        cur = self._db.execute(
            "SELECT id, key, value, category, importance, created_at, updated_at "
            "FROM facts ORDER BY importance DESC, updated_at DESC"
        )
        return [dict(row) for row in cur.fetchall()]

    def search(self, query: str) -> List[Dict[str, Any]]:
        """Search facts by key or value."""
        query_lower = f"%{query.lower()}%"
        cur = self._db.execute(
            "SELECT id, key, value, category, importance, created_at, updated_at "
            "FROM facts WHERE LOWER(key) LIKE ? OR LOWER(value) LIKE ? "
            "ORDER BY importance DESC, updated_at DESC",
            (query_lower, query_lower),
        )
        return [dict(row) for row in cur.fetchall()]

    def get_count(self) -> int:
        """Get total number of facts."""
        cur = self._db.execute("SELECT COUNT(*) as cnt FROM facts")
        return cur.fetchone()["cnt"]

    def get_count_by_category(self) -> Dict[str, int]:
        """Get count of facts per category."""
        cur = self._db.execute(
            "SELECT category, COUNT(*) as cnt FROM facts GROUP BY category"
        )
        return {row["category"]: row["cnt"] for row in cur.fetchall()}

    # ------------------------------------------------------------------
    # Fact categories
    # ------------------------------------------------------------------

    def get_categories(self) -> List[str]:
        """Get all fact categories."""
        cur = self._db.execute("SELECT DISTINCT category FROM facts ORDER BY category")
        return [row["category"] for row in cur.fetchall()]

    # ------------------------------------------------------------------
    # Batch operations
    # ------------------------------------------------------------------

    def remember_batch(self, facts: List[Dict[str, Any]]) -> None:
        """Remember multiple facts at once."""
        for fact in facts:
            self.remember(
                fact["key"],
                fact["value"],
                fact.get("category", "general"),
                fact.get("importance", 1),
            )
        log.info("Remembered %d facts", len(facts))

    def clear(self) -> None:
        """Clear all facts."""
        self._db.execute("DELETE FROM facts")
        self._db.commit()
        log.warning("All facts cleared")
