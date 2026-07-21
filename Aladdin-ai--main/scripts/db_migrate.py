"""Database migration runner for Aladdin AI."""
import sqlite3, logging, os
from pathlib import Path

log = logging.getLogger(__name__)
DATA_DIR = Path(os.getenv("ALADDIN_DATA_DIR", "data"))
MIGRATIONS = []

def migration(fn):
    MIGRATIONS.append(fn)
    return fn

@migration
def m001_enable_foreign_keys(conn):
    conn.execute("PRAGMA foreign_keys = ON")
    log.info("m001: foreign_keys enabled")

@migration
def m002_cleanup_orphan_data(conn):
    # Clean any orphan records in memory tables
    if table_exists(conn, "entries") and table_exists(conn, "sessions"):
        conn.execute("DELETE FROM entries WHERE session_id NOT IN (SELECT id FROM sessions)")
    log.info("m002: orphan cleanup complete")

def table_exists(conn, name):
    cur = conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?", (name,))
    return cur.fetchone() is not None

def run_migrations(db_path):
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("CREATE TABLE IF NOT EXISTS _migrations (id INTEGER PRIMARY KEY, name TEXT UNIQUE, applied_at TEXT DEFAULT CURRENT_TIMESTAMP)")
    for fn in MIGRATIONS:
        name = fn.__name__
        if not conn.execute("SELECT 1 FROM _migrations WHERE name=?", (name,)).fetchone():
            fn(conn)
            conn.execute("INSERT INTO _migrations(name) VALUES(?)", (name,))
            conn.commit()
            log.info("Applied migration: %s", name)
    conn.close()

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    if not DATA_DIR.exists():
        DATA_DIR.mkdir(parents=True, exist_ok=True)
    for db in list(DATA_DIR.glob("*.sqlite")) + list(DATA_DIR.glob("*.db")):
        log.info("Running migrations on %s", db)
        run_migrations(db)