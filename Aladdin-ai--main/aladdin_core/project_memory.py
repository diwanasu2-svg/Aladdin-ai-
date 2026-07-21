"""Project Memory — store, track, update and archive user projects.

Part of Phase 3 — Smart Memory Part 2.

Stores project records (name, description, goals, status, related files) and
provides utilities to automatically link conversations to projects.
"""

from __future__ import annotations

import json
import logging
import sqlite3
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


# Valid project statuses
VALID_STATUSES = ("active", "paused", "completed", "archived", "cancelled")


class ProjectMemory:
    """Manages persistent project records and conversation links.

    Schema:
      projects:   id, name, description, goals (JSON), status, related_files (JSON),
                  tags (JSON), created_at, updated_at, archived_at
      project_conversations: project_id, conversation_id, linked_at
    """

    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(db_path), check_same_thread=False)
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.row_factory = sqlite3.Row
        self._init_schema()

    # ------------------------------------------------------------------
    # Schema
    # ------------------------------------------------------------------
    def _init_schema(self) -> None:
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS projects (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL UNIQUE,
                description     TEXT,
                goals           TEXT DEFAULT '[]',
                status          TEXT DEFAULT 'active',
                related_files   TEXT DEFAULT '[]',
                tags            TEXT DEFAULT '[]',
                notes           TEXT,
                created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
                archived_at     DATETIME
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS projects_status_idx ON projects(status)"
        )
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS projects_name_idx ON projects(LOWER(name))"
        )
        self._db.execute("""
            CREATE TABLE IF NOT EXISTS project_conversations (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id      TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                linked_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
            """)
        self._db.execute(
            "CREATE INDEX IF NOT EXISTS project_conv_pid_idx "
            "ON project_conversations(project_id)"
        )
        self._db.commit()

    # ------------------------------------------------------------------
    # CRUD
    # ------------------------------------------------------------------
    def add_project(
        self,
        name: str,
        description: Optional[str] = None,
        goals: Optional[List[str]] = None,
        status: str = "active",
        related_files: Optional[List[str]] = None,
        tags: Optional[List[str]] = None,
        notes: Optional[str] = None,
    ) -> str:
        """Create a new project; returns its id. If a project with the same name
        already exists, returns the existing id without overwriting it."""
        if status not in VALID_STATUSES:
            status = "active"

        existing = self.get_by_name(name)
        if existing:
            return existing["id"]

        pid = str(uuid.uuid4())
        self._db.execute(
            "INSERT INTO projects(id, name, description, goals, status, "
            "related_files, tags, notes) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            (
                pid,
                name,
                description,
                json.dumps(goals or []),
                status,
                json.dumps(related_files or []),
                json.dumps(tags or []),
                notes,
            ),
        )
        self._db.commit()
        log.info("Project added: %s (%s)", name, pid)
        return pid

    def get(self, project_id: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM projects WHERE id = ?", (project_id,)
        ).fetchone()
        return self._row_to_dict(row) if row else None

    def get_by_name(self, name: str) -> Optional[Dict[str, Any]]:
        row = self._db.execute(
            "SELECT * FROM projects WHERE LOWER(name) = LOWER(?)", (name,)
        ).fetchone()
        return self._row_to_dict(row) if row else None

    def update_project(self, project_id: str, **fields: Any) -> bool:
        """Update one or more project fields. Returns True on success."""
        if not self.get(project_id):
            return False

        allowed = {
            "name",
            "description",
            "goals",
            "status",
            "related_files",
            "tags",
            "notes",
        }
        updates: List[str] = []
        values: List[Any] = []
        for key, val in fields.items():
            if key not in allowed:
                continue
            if key in ("goals", "related_files", "tags"):
                val = json.dumps(val if val is not None else [])
            if key == "status" and val not in VALID_STATUSES:
                continue
            updates.append(f"{key} = ?")
            values.append(val)

        if not updates:
            return False

        updates.append("updated_at = CURRENT_TIMESTAMP")
        values.append(project_id)
        self._db.execute(
            f"UPDATE projects SET {', '.join(updates)} WHERE id = ?", values
        )
        self._db.commit()
        log.info("Project updated: %s", project_id)
        return True

    def archive_project(self, project_id: str) -> bool:
        """Mark a project as archived."""
        if not self.get(project_id):
            return False
        self._db.execute(
            "UPDATE projects SET status = 'archived', "
            "archived_at = CURRENT_TIMESTAMP, "
            "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            (project_id,),
        )
        self._db.commit()
        log.info("Project archived: %s", project_id)
        return True

    def delete_project(self, project_id: str) -> bool:
        if not self.get(project_id):
            return False
        self._db.execute("DELETE FROM projects WHERE id = ?", (project_id,))
        self._db.execute(
            "DELETE FROM project_conversations WHERE project_id = ?",
            (project_id,),
        )
        self._db.commit()
        log.info("Project deleted: %s", project_id)
        return True

    # ------------------------------------------------------------------
    # Listing / search
    # ------------------------------------------------------------------
    def list_projects(
        self, status: Optional[str] = None, include_archived: bool = False
    ) -> List[Dict[str, Any]]:
        query = "SELECT * FROM projects"
        clauses: List[str] = []
        params: List[Any] = []
        if status:
            clauses.append("status = ?")
            params.append(status)
        elif not include_archived:
            clauses.append("status != 'archived'")
        if clauses:
            query += " WHERE " + " AND ".join(clauses)
        query += " ORDER BY updated_at DESC"
        rows = self._db.execute(query, params).fetchall()
        return [self._row_to_dict(r) for r in rows]

    def search(self, query: str) -> List[Dict[str, Any]]:
        like = f"%{query.lower()}%"
        rows = self._db.execute(
            "SELECT * FROM projects WHERE LOWER(name) LIKE ? "
            "OR LOWER(IFNULL(description,'')) LIKE ? "
            "OR LOWER(IFNULL(notes,'')) LIKE ? "
            "ORDER BY updated_at DESC",
            (like, like, like),
        ).fetchall()
        return [self._row_to_dict(r) for r in rows]

    # ------------------------------------------------------------------
    # Goals & files helpers
    # ------------------------------------------------------------------
    def add_goal(self, project_id: str, goal: str) -> bool:
        proj = self.get(project_id)
        if not proj:
            return False
        goals = list(proj.get("goals", []))
        if goal not in goals:
            goals.append(goal)
        return self.update_project(project_id, goals=goals)

    def remove_goal(self, project_id: str, goal: str) -> bool:
        proj = self.get(project_id)
        if not proj:
            return False
        goals = [g for g in proj.get("goals", []) if g != goal]
        return self.update_project(project_id, goals=goals)

    def add_file(self, project_id: str, file_path: str) -> bool:
        proj = self.get(project_id)
        if not proj:
            return False
        files = list(proj.get("related_files", []))
        if file_path not in files:
            files.append(file_path)
        return self.update_project(project_id, related_files=files)

    def remove_file(self, project_id: str, file_path: str) -> bool:
        proj = self.get(project_id)
        if not proj:
            return False
        files = [f for f in proj.get("related_files", []) if f != file_path]
        return self.update_project(project_id, related_files=files)

    # ------------------------------------------------------------------
    # Conversation linking
    # ------------------------------------------------------------------
    def link_conversation(self, project_id: str, conversation_id: str) -> bool:
        """Attach a conversation id to a project. Idempotent."""
        if not self.get(project_id):
            return False
        existing = self._db.execute(
            "SELECT 1 FROM project_conversations "
            "WHERE project_id = ? AND conversation_id = ?",
            (project_id, conversation_id),
        ).fetchone()
        if existing:
            return True
        self._db.execute(
            "INSERT INTO project_conversations(project_id, conversation_id) "
            "VALUES(?, ?)",
            (project_id, conversation_id),
        )
        self._db.commit()
        return True

    def list_conversations(self, project_id: str) -> List[Dict[str, Any]]:
        rows = self._db.execute(
            "SELECT conversation_id, linked_at FROM project_conversations "
            "WHERE project_id = ? ORDER BY linked_at DESC",
            (project_id,),
        ).fetchall()
        return [dict(r) for r in rows]

    def auto_link_from_text(self, text: str, conversation_id: str) -> List[str]:
        """Scan free text for known project names and link them to this
        conversation id. Returns the list of project ids that were linked.

        Matching is a simple case-insensitive substring search over project
        names — projects with very short names (<3 chars) are skipped to
        avoid noise.
        """
        if not text:
            return []
        text_l = text.lower()
        linked: List[str] = []
        for proj in self.list_projects(include_archived=False):
            name = (proj.get("name") or "").strip()
            if len(name) < 3:
                continue
            if name.lower() in text_l:
                if self.link_conversation(proj["id"], conversation_id):
                    linked.append(proj["id"])
        if linked:
            log.debug(
                "Auto-linked conversation %s to %d project(s)",
                conversation_id,
                len(linked),
            )
        return linked

    # ------------------------------------------------------------------
    # Stats / utility
    # ------------------------------------------------------------------
    def stats(self) -> Dict[str, Any]:
        rows = self._db.execute(
            "SELECT status, COUNT(*) AS c FROM projects GROUP BY status"
        ).fetchall()
        by_status = {r["status"]: r["c"] for r in rows}
        total = sum(by_status.values())
        return {"total": total, "by_status": by_status}

    def clear(self) -> None:
        self._db.execute("DELETE FROM projects")
        self._db.execute("DELETE FROM project_conversations")
        self._db.commit()
        log.warning("All project memory cleared")

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------
    @staticmethod
    def _row_to_dict(row: sqlite3.Row) -> Dict[str, Any]:
        data = dict(row)
        for k in ("goals", "related_files", "tags"):
            raw = data.get(k)
            try:
                data[k] = json.loads(raw) if raw else []
            except (json.JSONDecodeError, TypeError):
                data[k] = []
        return data

    def close(self) -> None:
        try:
            self._db.close()
        except Exception:  # pragma: no cover
            pass
