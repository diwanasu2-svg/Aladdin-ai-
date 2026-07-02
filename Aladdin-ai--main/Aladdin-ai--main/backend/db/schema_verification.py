"""
Task 39 — Database Schema Verification: check all SQLite databases for expected
tables and columns, report mismatches, and attempt auto-repair via migration.
"""
from __future__ import annotations

import logging
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# Expected schemas: {db_filename: {table_name: [expected_columns]}}
EXPECTED_SCHEMAS: Dict[str, Dict[str, List[str]]] = {
    "aladdin_memory.sqlite": {
        "semantic": ["id", "content", "embedding", "metadata", "category", "timestamp"],
        "short_term": ["id", "role", "content", "session_id", "timestamp"],
        "long_term": ["id", "content", "summary", "importance", "category", "created_at"],
    },
    "sessions.sqlite": {
        "sessions": ["session_id", "messages", "created_at", "last_active", "model", "provider", "metadata"],
    },
    "security.sqlite": {
        "blocked_ips": ["ip", "reason", "blocked_at", "expires_at"],
        "allowed_ips": ["ip", "reason", "added_at"],
        "security_events": ["id", "event_type", "ip", "user_id", "detail", "timestamp"],
    },
    "users.sqlite": {
        "users": ["id", "username", "email", "hashed_password", "salt", "role", "created_at", "last_login", "is_active"],
        "revoked_tokens": ["jti", "revoked_at", "expires_at"],
    },
    "notes.sqlite": {
        "notes": ["id", "title", "content", "pinned", "tags", "created_at", "updated_at"],
    },
    "reminders.sqlite": {
        "reminders": ["id", "title", "description", "remind_at", "recurrence", "completed", "created_at"],
    },
}


@dataclass
class SchemaIssue:
    db_path: Path
    table: str
    issue_type: str  # "missing_table" | "missing_column" | "ok"
    detail: str = ""


def get_table_columns(conn: sqlite3.Connection, table: str) -> List[str]:
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    return [row[1] for row in rows]


def get_existing_tables(conn: sqlite3.Connection) -> List[str]:
    rows = conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
    return [r[0] for r in rows]


def verify_db(db_path: Path, expected: Dict[str, List[str]]) -> List[SchemaIssue]:
    """Verify a single DB file against its expected schema."""
    issues: List[SchemaIssue] = []
    if not db_path.exists():
        logger.debug("DB does not exist yet (will be created on first use): %s", db_path)
        return issues

    try:
        conn = sqlite3.connect(str(db_path))
        conn.execute("PRAGMA foreign_keys = ON")
        try:
            existing_tables = get_existing_tables(conn)
            for table, expected_cols in expected.items():
                if table not in existing_tables:
                    issues.append(SchemaIssue(db_path, table, "missing_table", f"Table '{table}' not found"))
                    continue
                existing_cols = get_table_columns(conn, table)
                for col in expected_cols:
                    if col not in existing_cols:
                        issues.append(SchemaIssue(db_path, table, "missing_column", f"Column '{col}' missing from '{table}'"))
        finally:
            conn.close()
    except Exception as exc:
        logger.error("Schema verification failed for %s: %s", db_path, exc)
        issues.append(SchemaIssue(db_path, "N/A", "error", str(exc)))

    return issues


def verify_all_schemas(data_dir: Path) -> Tuple[bool, List[SchemaIssue]]:
    """
    Task 39: Verify all known databases against expected schemas.
    Returns (all_ok, issues).
    """
    all_issues: List[SchemaIssue] = []
    for db_filename, expected in EXPECTED_SCHEMAS.items():
        db_path = data_dir / db_filename
        issues = verify_db(db_path, expected)
        all_issues.extend(issues)

    if not all_issues:
        logger.info("Schema verification passed — all databases OK (data_dir=%s)", data_dir)
        return True, []

    error_issues = [i for i in all_issues if i.issue_type != "ok"]
    if error_issues:
        for issue in error_issues:
            logger.warning("Schema issue [%s] %s.%s: %s", issue.issue_type, issue.db_path.name, issue.table, issue.detail)
    return len(error_issues) == 0, all_issues


def attempt_repair(data_dir: Path) -> int:
    """
    Task 39: Attempt to repair schema issues by running memory migrations.
    Returns number of repair operations attempted.
    """
    try:
        from ..memory.migration import run_all_memory_migrations
        run_all_memory_migrations(data_dir)
        logger.info("Schema repair via migrations completed")
        return 1
    except Exception as exc:
        logger.error("Schema repair failed: %s", exc)
        return 0


def run_startup_schema_check(data_dir: Path, auto_repair: bool = True) -> bool:
    """
    Task 39: Run full schema check at startup. Auto-repair if enabled.
    Returns True if all schemas are valid (after any repair).
    """
    ok, issues = verify_all_schemas(data_dir)
    if not ok and auto_repair:
        logger.info("Attempting schema auto-repair...")
        attempt_repair(data_dir)
        ok, issues = verify_all_schemas(data_dir)
        if ok:
            logger.info("Schema repair succeeded")
        else:
            logger.error("Schema repair failed — %d issues remain", len(issues))
    return ok
