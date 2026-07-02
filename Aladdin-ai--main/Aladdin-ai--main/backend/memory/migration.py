"""
Task 28 — Memory Migration: schema versioning and safe migration for all memory DBs.
Supports adding columns, creating tables, and migrating data without losing content.
"""
from __future__ import annotations

import logging
import sqlite3
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# Registry of all migrations: (version, description, SQL or callable)
Migration = Tuple[int, str, str | Callable[[sqlite3.Connection], None]]


def _get_schema_version(conn: sqlite3.Connection) -> int:
    """Get current schema version from user_version pragma."""
    return conn.execute("PRAGMA user_version").fetchone()[0]


def _set_schema_version(conn: sqlite3.Connection, version: int):
    conn.execute(f"PRAGMA user_version = {version}")


def run_migrations(db_path: Path, migrations: List[Migration]) -> int:
    """
    Apply pending migrations to the DB at db_path.
    Migrations are applied in order, skipping those already applied.
    Returns the new schema version.
    """
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        current = _get_schema_version(conn)
        logger.info("DB %s: current schema version = %d", db_path.name, current)

        applied = 0
        for version, description, migration in sorted(migrations, key=lambda m: m[0]):
            if version <= current:
                continue
            logger.info("Applying migration v%d: %s", version, description)
            try:
                if callable(migration):
                    migration(conn)
                else:
                    conn.executescript(migration)
                _set_schema_version(conn, version)
                conn.commit()
                applied += 1
                logger.info("Migration v%d applied successfully", version)
            except Exception as exc:
                conn.rollback()
                logger.error("Migration v%d failed: %s — rolling back", version, exc)
                raise

        if applied == 0:
            logger.info("DB %s: no migrations needed (at v%d)", db_path.name, current)
        else:
            new_version = _get_schema_version(conn)
            logger.info("DB %s: migrated to v%d (%d migrations applied)", db_path.name, new_version, applied)

        return _get_schema_version(conn)
    finally:
        conn.close()


def add_column_if_missing(conn: sqlite3.Connection, table: str, column: str, definition: str):
    """Safely add a column if it doesn't exist yet."""
    existing = [row[1] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()]
    if column not in existing:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
        logger.debug("Added column %s.%s", table, column)


def table_exists(conn: sqlite3.Connection, table: str) -> bool:
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?", (table,)
    ).fetchone()
    return row is not None


# ── Predefined migration sets for each memory DB ─────────────────────────────

SEMANTIC_MIGRATIONS: List[Migration] = [
    (1, "Initial schema", """
        CREATE TABLE IF NOT EXISTS semantic (
            id TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            embedding BLOB,
            metadata TEXT DEFAULT '{}',
            category TEXT DEFAULT 'general',
            timestamp REAL NOT NULL
        );
    """),
    (2, "Add source column", lambda conn: add_column_if_missing(conn, "semantic", "source", "TEXT DEFAULT ''")),
    (3, "Add relevance_score column", lambda conn: add_column_if_missing(conn, "semantic", "relevance_score", "REAL DEFAULT 0.0")),
]

SHORT_TERM_MIGRATIONS: List[Migration] = [
    (1, "Initial schema", """
        CREATE TABLE IF NOT EXISTS short_term (
            id TEXT PRIMARY KEY,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            session_id TEXT,
            timestamp REAL NOT NULL
        );
    """),
    (2, "Add tokens column", lambda conn: add_column_if_missing(conn, "short_term", "token_count", "INTEGER DEFAULT 0")),
]

LONG_TERM_MIGRATIONS: List[Migration] = [
    (1, "Initial schema", """
        CREATE TABLE IF NOT EXISTS long_term (
            id TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            summary TEXT DEFAULT '',
            importance REAL DEFAULT 0.5,
            category TEXT DEFAULT 'general',
            created_at REAL NOT NULL,
            last_accessed REAL
        );
    """),
    (2, "Add access count", lambda conn: add_column_if_missing(conn, "long_term", "access_count", "INTEGER DEFAULT 0")),
    (3, "Add tags column", lambda conn: add_column_if_missing(conn, "long_term", "tags", "TEXT DEFAULT ''")),
]

SESSION_MIGRATIONS: List[Migration] = [
    (1, "Initial session schema", """
        CREATE TABLE IF NOT EXISTS sessions (
            session_id TEXT PRIMARY KEY,
            messages TEXT NOT NULL DEFAULT '[]',
            created_at REAL NOT NULL,
            last_active REAL NOT NULL,
            model TEXT,
            provider TEXT,
            metadata TEXT NOT NULL DEFAULT '{}'
        );
        CREATE INDEX IF NOT EXISTS idx_sessions_last_active ON sessions(last_active);
    """),
    (2, "Add user_id column", lambda conn: add_column_if_missing(conn, "sessions", "user_id", "TEXT DEFAULT ''")),
]


def run_all_memory_migrations(data_dir: Path):
    """Run all memory DB migrations. Call at startup after _init_memory()."""
    migrations_map = {
        "semantic.sqlite": SEMANTIC_MIGRATIONS,
        "short_term.sqlite": SHORT_TERM_MIGRATIONS,
        "long_term.sqlite": LONG_TERM_MIGRATIONS,
        "sessions.sqlite": SESSION_MIGRATIONS,
    }
    for db_name, migrations in migrations_map.items():
        db_path = data_dir / db_name
        try:
            if db_path.exists():
                run_migrations(db_path, migrations)
        except Exception as exc:
            logger.error("Migration failed for %s: %s", db_name, exc)
