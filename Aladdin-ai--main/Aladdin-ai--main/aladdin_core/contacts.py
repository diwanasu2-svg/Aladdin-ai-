"""Contacts management — store and retrieve user contacts."""

from __future__ import annotations

import json
import logging
import sqlite3
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class ContactsManager:
    """Manages user contacts."""

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()

    def _init_schema(self) -> None:
        """Initialize the contacts table."""
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS contacts (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                phone           TEXT,
                email           TEXT,
                relationship    TEXT,
                nickname        TEXT,
                notes           TEXT,
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS contacts_name_idx ON contacts(LOWER(name))"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS contacts_nickname_idx ON contacts(LOWER(nickname))"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS contacts_phone_idx ON contacts(phone)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS contacts_email_idx ON contacts(email)"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # Contact management
    # ------------------------------------------------------------------

    def add(
        self,
        name: str,
        phone: Optional[str] = None,
        email: Optional[str] = None,
        relationship: Optional[str] = None,
        nickname: Optional[str] = None,
        notes: Optional[str] = None,
    ) -> str:
        """Add a new contact and return its ID."""
        contact_id = str(uuid.uuid4())
        self._db.execute(
            "INSERT INTO contacts(id, name, phone, email, relationship, nickname, notes) "
            "VALUES(?, ?, ?, ?, ?, ?, ?)",
            (contact_id, name, phone, email, relationship, nickname, notes),
        )
        self._db.commit()
        log.info("Contact added: %s (ID: %s)", name, contact_id)
        return contact_id

    def get_by_id(self, contact_id: str) -> Optional[Dict[str, Any]]:
        """Get contact by ID."""
        cur = self._db.execute("SELECT * FROM contacts WHERE id = ?", (contact_id,))
        row = cur.fetchone()
        return dict(row) if row else None

    def update(self, contact_id: str, **kwargs) -> None:
        """Update a contact."""
        allowed_fields = {"name", "phone", "email", "relationship", "nickname", "notes"}
        updates = {
            k: v for k, v in kwargs.items() if k in allowed_fields and v is not None
        }
        if not updates:
            return

        set_clause = ", ".join([f"{k} = ?" for k in updates.keys()])
        values = list(updates.values())
        values.append(contact_id)
        self._db.execute(
            f"UPDATE contacts SET {set_clause}, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            values,
        )
        self._db.commit()
        log.info("Contact updated: %s", contact_id)

    def delete(self, contact_id: str) -> None:
        """Delete a contact."""
        self._db.execute("DELETE FROM contacts WHERE id = ?", (contact_id,))
        self._db.commit()
        log.info("Contact deleted: %s", contact_id)

    # ------------------------------------------------------------------
    # Contact retrieval
    # ------------------------------------------------------------------

    def get_all(self) -> List[Dict[str, Any]]:
        """Get all contacts sorted by name."""
        cur = self._db.execute("SELECT * FROM contacts ORDER BY name ASC")
        return [dict(row) for row in cur.fetchall()]

    def search(self, query: str) -> List[Dict[str, Any]]:
        """Search contacts by name, nickname, phone, or email."""
        query_lower = f"%{query.lower()}%"
        cur = self._db.execute(
            "SELECT * FROM contacts WHERE LOWER(name) LIKE ? OR LOWER(nickname) LIKE ? "
            "OR phone LIKE ? OR email LIKE ? ORDER BY name ASC",
            (query_lower, query_lower, f"%{query}%", query_lower),
        )
        return [dict(row) for row in cur.fetchall()]

    def get_by_phone(self, phone: str) -> Optional[Dict[str, Any]]:
        """Get contact by phone number."""
        cur = self._db.execute("SELECT * FROM contacts WHERE phone = ?", (phone,))
        row = cur.fetchone()
        return dict(row) if row else None

    def get_by_email(self, email: str) -> Optional[Dict[str, Any]]:
        """Get contact by email address."""
        cur = self._db.execute(
            "SELECT * FROM contacts WHERE LOWER(email) = LOWER(?)", (email,)
        )
        row = cur.fetchone()
        return dict(row) if row else None

    def get_by_relationship(self, relationship: str) -> List[Dict[str, Any]]:
        """Get all contacts with a specific relationship."""
        cur = self._db.execute(
            "SELECT * FROM contacts WHERE relationship = ? ORDER BY name ASC",
            (relationship,),
        )
        return [dict(row) for row in cur.fetchall()]

    def get_count(self) -> int:
        """Get total number of contacts."""
        cur = self._db.execute("SELECT COUNT(*) as cnt FROM contacts")
        return cur.fetchone()["cnt"]

    # ------------------------------------------------------------------
    # Batch operations
    # ------------------------------------------------------------------

    def add_batch(self, contacts: List[Dict[str, Any]]) -> List[str]:
        """Add multiple contacts and return their IDs."""
        ids = []
        for contact_data in contacts:
            cid = self.add(
                contact_data["name"],
                phone=contact_data.get("phone"),
                email=contact_data.get("email"),
                relationship=contact_data.get("relationship"),
                nickname=contact_data.get("nickname"),
                notes=contact_data.get("notes"),
            )
            ids.append(cid)
        log.info("Added %d contacts", len(ids))
        return ids

    def clear(self) -> None:
        """Clear all contacts."""
        self._db.execute("DELETE FROM contacts")
        self._db.commit()
        log.warning("All contacts cleared")
