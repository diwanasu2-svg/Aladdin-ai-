import pytest
import sys
from pathlib import Path
# Ensure project root is on path
sys.path.insert(0, str(Path(__file__).parent.parent))

@pytest.fixture
def tmp_sqlite(tmp_path):
    import sqlite3
    db = tmp_path / "test.sqlite"
    conn = sqlite3.connect(str(db))
    conn.execute("PRAGMA foreign_keys = ON")
    yield db, conn
    conn.close()
