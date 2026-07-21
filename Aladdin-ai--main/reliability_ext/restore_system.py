"""
reliability_ext/restore_system.py — Phase 13 Feature 8
=======================================================
Restore data from backups — selective restore, integrity check, rollback.

Features:
- Restore from latest or specific backup session
- Selective restore (choose individual targets)
- Verify integrity via checksums before restore
- Version-aware restore (handles compressed / uncompressed)
- SQLite restore via sqlite3.backup API
- Dry-run mode (verify without overwriting)
- Rollback: keep original before overwriting
- Restore progress reporting
"""

from __future__ import annotations

import gzip
import hashlib
import json
import logging
import shutil
import sqlite3
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class RestoreResult:
    target_name: str
    source_backup: str
    destination: str
    success: bool
    verified: bool = False
    dry_run: bool = False
    error: str = ""
    restored_at: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["restored_at_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.restored_at))
        return d


class RestoreSystem:
    """
    Restores data from backups created by BackupSystem.

    Usage::

        rs = RestoreSystem(backup_dir="backups")

        # Restore everything from latest backup
        results = rs.restore_latest()

        # Restore specific item
        result = rs.restore_item("memory.db", destination="data/memory.db")

        # Dry run (just verify)
        results = rs.restore_latest(dry_run=True)
    """

    ROLLBACK_SUFFIX = ".pre_restore"

    def __init__(self, backup_dir: str = "backups") -> None:
        self._backup_dir = Path(backup_dir)

    # ── High-level restore ────────────────────────────────────────────────────

    def restore_latest(
        self,
        *,
        targets: Optional[List[str]] = None,
        dry_run: bool = False,
    ) -> List[RestoreResult]:
        """Restore from the most recent backup session."""
        session_dir = self._get_latest_session()
        if not session_dir:
            log.error("RestoreSystem: no backup sessions found in %s", self._backup_dir)
            return []
        return self.restore_session(session_dir, targets=targets, dry_run=dry_run)

    def restore_session(
        self,
        session_dir: Path,
        *,
        targets: Optional[List[str]] = None,
        dry_run: bool = False,
    ) -> List[RestoreResult]:
        """Restore from a specific session directory."""
        manifest_path = session_dir / "manifest.json"
        if not manifest_path.exists():
            log.error("RestoreSystem: no manifest.json in %s", session_dir)
            return []

        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception as e:
            log.error("RestoreSystem: failed to read manifest: %s", e)
            return []

        entries = manifest.get("entries", [])
        if targets:
            entries = [e for e in entries if e.get("name") in targets]

        results = []
        for entry in entries:
            result = self._restore_entry(entry, session_dir, dry_run=dry_run)
            results.append(result)
            log.info("RestoreSystem: %s '%s' — %s",
                     "dry-run" if dry_run else "restored",
                     entry.get("name"), "OK" if result.success else f"FAILED: {result.error}")

        return results

    def restore_item(
        self,
        name: str,
        destination: str,
        *,
        session_dir: Optional[Path] = None,
        dry_run: bool = False,
    ) -> RestoreResult:
        """Restore a single named item from a backup session."""
        sd = session_dir or self._get_latest_session()
        if not sd:
            return RestoreResult(target_name=name, source_backup="", destination=destination,
                                 success=False, error="No backup sessions found")

        manifest_path = sd / "manifest.json"
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception as e:
            return RestoreResult(target_name=name, source_backup="", destination=destination,
                                 success=False, error=str(e))

        entry = next((e for e in manifest.get("entries", []) if e.get("name") == name), None)
        if not entry:
            return RestoreResult(target_name=name, source_backup="", destination=destination,
                                 success=False, error=f"Item '{name}' not in backup")

        entry["source_path"] = destination  # Override destination
        return self._restore_entry(entry, sd, dry_run=dry_run)

    # ── Core restore logic ────────────────────────────────────────────────────

    def _restore_entry(self, entry: dict, session_dir: Path, *, dry_run: bool) -> RestoreResult:
        name = entry.get("name", "unknown")
        backup_file = Path(entry.get("backup_path", ""))
        destination = Path(entry.get("source_path", ""))
        compressed = entry.get("compressed", False)
        expected_checksum = entry.get("checksum_sha256", "")

        if not backup_file.exists():
            # Try to find by filename in session_dir
            candidates = list(session_dir.glob(f"{name}*"))
            if candidates:
                backup_file = candidates[0]
            else:
                return RestoreResult(target_name=name, source_backup=str(backup_file),
                                     destination=str(destination), success=False,
                                     error="Backup file not found")

        # Integrity check
        verified = self._verify_file(backup_file, expected_checksum)
        if not verified:
            log.warning("RestoreSystem: checksum mismatch for '%s' — proceeding with caution", name)

        if dry_run:
            return RestoreResult(target_name=name, source_backup=str(backup_file),
                                 destination=str(destination), success=True,
                                 verified=verified, dry_run=True)

        # Determine restore method
        backup_name = backup_file.name.lower()
        if backup_name.endswith(".db") or backup_name.endswith(".db.gz"):
            return self._restore_sqlite(name, backup_file, destination, compressed, verified)
        elif backup_name.endswith(".tar.gz") or backup_name.endswith(".tgz"):
            return self._restore_archive(name, backup_file, destination, verified)
        else:
            return self._restore_plain_file(name, backup_file, destination, compressed, verified)

    def _restore_sqlite(
        self, name: str, backup: Path, dest: Path, compressed: bool, verified: bool
    ) -> RestoreResult:
        dest.parent.mkdir(parents=True, exist_ok=True)
        self._make_rollback(dest)
        try:
            if compressed:
                tmp = backup.parent / f"{name}_decomp.db"
                with gzip.open(str(backup), "rb") as f_in, open(str(tmp), "wb") as f_out:
                    shutil.copyfileobj(f_in, f_out)
                src_db = str(tmp)
            else:
                src_db = str(backup)

            with sqlite3.connect(src_db) as src_conn, sqlite3.connect(str(dest)) as dst_conn:
                src_conn.backup(dst_conn)

            if compressed:
                Path(src_db).unlink(missing_ok=True)

            return RestoreResult(target_name=name, source_backup=str(backup),
                                 destination=str(dest), success=True, verified=verified)
        except Exception as e:
            self._rollback(dest)
            return RestoreResult(target_name=name, source_backup=str(backup),
                                 destination=str(dest), success=False, error=str(e))

    def _restore_plain_file(
        self, name: str, backup: Path, dest: Path, compressed: bool, verified: bool
    ) -> RestoreResult:
        dest.parent.mkdir(parents=True, exist_ok=True)
        self._make_rollback(dest)
        try:
            if compressed:
                with gzip.open(str(backup), "rb") as f_in, open(str(dest), "wb") as f_out:
                    shutil.copyfileobj(f_in, f_out)
            else:
                shutil.copy2(str(backup), str(dest))
            return RestoreResult(target_name=name, source_backup=str(backup),
                                 destination=str(dest), success=True, verified=verified)
        except Exception as e:
            self._rollback(dest)
            return RestoreResult(target_name=name, source_backup=str(backup),
                                 destination=str(dest), success=False, error=str(e))

    def _restore_archive(self, name: str, backup: Path, dest: Path, verified: bool) -> RestoreResult:
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            shutil.unpack_archive(str(backup), str(dest.parent), "gztar")
            return RestoreResult(target_name=name, source_backup=str(backup),
                                 destination=str(dest), success=True, verified=verified)
        except Exception as e:
            return RestoreResult(target_name=name, source_backup=str(backup),
                                 destination=str(dest), success=False, error=str(e))

    # ── Rollback helpers ──────────────────────────────────────────────────────

    def _make_rollback(self, dest: Path) -> None:
        """Keep a copy of the current file before overwriting."""
        if dest.exists():
            rollback = dest.with_suffix(self.ROLLBACK_SUFFIX)
            try:
                shutil.copy2(str(dest), str(rollback))
            except Exception as e:
                log.warning("RestoreSystem: could not create rollback for %s: %s", dest, e)

    def _rollback(self, dest: Path) -> None:
        rollback = dest.with_suffix(self.ROLLBACK_SUFFIX)
        if rollback.exists():
            try:
                shutil.copy2(str(rollback), str(dest))
                rollback.unlink(missing_ok=True)
                log.info("RestoreSystem: rolled back %s", dest)
            except Exception as e:
                log.error("RestoreSystem: rollback failed for %s: %s", dest, e)

    def clear_rollback_files(self) -> int:
        """Remove all .pre_restore rollback files."""
        removed = 0
        for f in Path(".").rglob(f"*{self.ROLLBACK_SUFFIX}"):
            try:
                f.unlink()
                removed += 1
            except Exception:
                pass
        return removed

    # ── Listing ───────────────────────────────────────────────────────────────

    def list_sessions(self) -> List[dict]:
        sessions = []
        if not self._backup_dir.exists():
            return sessions
        for d in sorted(self._backup_dir.iterdir()):
            if d.is_dir() and (d / "manifest.json").exists():
                try:
                    m = json.loads((d / "manifest.json").read_text())
                    sessions.append({
                        "path": str(d),
                        "created_at": m.get("created_at_iso"),
                        "type": m.get("backup_type"),
                        "items": [e.get("name") for e in m.get("entries", [])],
                    })
                except Exception:
                    pass
        return sessions

    def _get_latest_session(self) -> Optional[Path]:
        sessions = sorted(
            [d for d in self._backup_dir.iterdir() if d.is_dir() and (d / "manifest.json").exists()],
            key=lambda d: d.stat().st_mtime,
        )
        return sessions[-1] if sessions else None

    @staticmethod
    def _verify_file(path: Path, expected_sha256: str) -> bool:
        if not expected_sha256:
            return True
        h = hashlib.sha256()
        try:
            with open(str(path), "rb") as f:
                for chunk in iter(lambda: f.read(65536), b""):
                    h.update(chunk)
        except Exception:
            return False
        return h.hexdigest() == expected_sha256
