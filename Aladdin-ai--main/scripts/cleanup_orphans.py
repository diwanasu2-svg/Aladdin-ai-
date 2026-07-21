"""Orphan cleanup script for Aladdin AI."""
import sqlite3, logging, os
from pathlib import Path

log = logging.getLogger(__name__)
DATA_DIR = Path(os.getenv("ALADDIN_DATA_DIR", "data"))

def check_and_cleanup(db_path):
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA foreign_keys = ON")
    
    # Run integrity check
    cur = conn.execute("PRAGMA integrity_check")
    issues = cur.fetchall()
    if issues and issues[0][0] != "ok":
        log.warning("Integrity issues found in %s: %s", db_path, issues)
    else:
        log.info("Integrity check passed for %s", db_path)
    
    # Cleanup orphans
    tables = [row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")]
    
    if "entries" in tables and "sessions" in tables:
        cur = conn.execute("DELETE FROM entries WHERE session_id NOT IN (SELECT id FROM sessions)")
        if cur.rowcount > 0:
            log.info("Removed %d orphan entries from %s", cur.rowcount, db_path)
        conn.commit()
        
    conn.close()

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    if not DATA_DIR.exists():
        log.info("Data directory %s does not exist.", DATA_DIR)
    else:
        for db in list(DATA_DIR.glob("*.sqlite")) + list(DATA_DIR.glob("*.db")):
            log.info("Checking orphans on %s", db)
            check_and_cleanup(db)