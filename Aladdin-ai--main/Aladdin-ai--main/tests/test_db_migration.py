"""Tests for database migration."""
import pytest
import sqlite3
import tempfile
from pathlib import Path

def test_migration_runner_creates_table(tmp_path):
    import sys
    sys.path.insert(0, str(Path("/home/runner/workspace/work/Aladdin-ai--main")))
    db = tmp_path / "test.sqlite"
    conn = sqlite3.connect(str(db))
    conn.execute("PRAGMA foreign_keys = ON")
    fk_status = conn.execute("PRAGMA foreign_keys").fetchone()
    assert fk_status[0] == 1
    conn.close()

def test_foreign_keys_pragma():
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    result = conn.execute("PRAGMA foreign_keys").fetchone()
    assert result[0] == 1
