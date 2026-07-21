"""
reliability_ext/backup_system.py — Phase 13 Feature 7
=====================================================
Scheduled backup system — databases, settings, user profiles,
conversation history. Supports full + incremental backups.

Features:
- SQLite database backup (via sqlite3.backup API)
- Settings/config JSON backup
- User profile backup
- Conversation history backup
- Scheduled automatic backups (daily/weekly)
- Incremental backup (only changed files)
- Gzip compression
- SHA-256 checksums for integrity
- Cloud backup stub (S3, Google Drive)
- Backup manifest with metadata
"""

from __future__ import annotations

import gzip
import hashlib
import json
import logging
import os
import shutil
import sqlite3
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class BackupEntry:
    name: str
    backup_path: str
    source_path: str
    size_bytes: int
    checksum_sha256: str
    compressed: bool
    backup_type: str        # "full" | "incremental"
    created_at: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["created_at_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.created_at))
        return d


@dataclass
class BackupManifest:
    manifest_version: int = 2
    created_at: float = field(default_factory=time.time)
    backup_type: str = "full"
    entries: List[BackupEntry] = field(default_factory=list)
    total_size_bytes: int = 0
    session_id: str = ""

    def to_dict(self) -> dict:
        d = asdict(self)
        d["created_at_iso"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.created_at))
        d["entries"] = [e.to_dict() for e in self.entries]
        return d


class BackupSystem:
    """
    Manages scheduled, compressed, and verified backups.

    Usage::

        bs = BackupSystem(backup_dir="backups")
        bs.register_sqlite("memory.db", "data/memory.db")
        bs.register_file("config.yaml", "config.yaml")
        bs.schedule(daily_at_hour=2)   # 2 AM daily
        bs.run_now()                   # Immediate full backup
    """

    def __init__(self, backup_dir: str = "backups", compress: bool = True) -> None:
        self._backup_dir = Path(backup_dir)
        self._backup_dir.mkdir(parents=True, exist_ok=True)
        self._compress = compress
        self._targets: List[dict] = []
        self._lock = threading.Lock()
        self._running = False
        self._scheduler_thread: Optional[threading.Thread] = None
        self._last_manifest: Optional[BackupManifest] = None
        self._previous_checksums: Dict[str, str] = {}  # For incremental detection

    # ── Registration ──────────────────────────────────────────────────────────

    def register_sqlite(self, name: str, db_path: str) -> None:
        """Register a SQLite database for backup."""
        self._targets.append({"type": "sqlite", "name": name, "path": db_path})
        log.info("BackupSystem: registered SQLite '%s' (%s)", name, db_path)

    def register_file(self, name: str, path: str) -> None:
        """Register a file or directory for backup."""
        self._targets.append({"type": "file", "name": name, "path": path})
        log.info("BackupSystem: registered file '%s' (%s)", name, path)

    def register_directory(self, name: str, path: str) -> None:
        """Register a directory (archived as .tar.gz)."""
        self._targets.append({"type": "directory", "name": name, "path": path})
        log.info("BackupSystem: registered directory '%s' (%s)", name, path)

    # ── Backup execution ──────────────────────────────────────────────────────

    def run_now(self, backup_type: str = "full") -> BackupManifest:
        """Run an immediate backup of all registered targets."""
        import uuid
        session_id = str(uuid.uuid4())[:8]
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        session_dir = self._backup_dir / f"{timestamp}_{backup_type}_{session_id}"
        session_dir.mkdir(parents=True, exist_ok=True)

        manifest = BackupManifest(backup_type=backup_type, session_id=session_id)
        entries = []
        total_size = 0

        for target in self._targets:
            try:
                entry = self._backup_target(target, session_dir, backup_type)
                if entry:
                    entries.append(entry)
                    total_size += entry.size_bytes
                    log.info("BackupSystem: backed up '%s' (%d bytes)", entry.name, entry.size_bytes)
            except Exception as e:
                log.error("BackupSystem: failed to backup '%s': %s", target["name"], e)

        manifest.entries = entries
        manifest.total_size_bytes = total_size

        # Save manifest
        manifest_path = session_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest.to_dict(), indent=2), encoding="utf-8")

        with self._lock:
            self._last_manifest = manifest

        log.info("BackupSystem: backup complete — %d items, %d bytes, session=%s",
                 len(entries), total_size, session_id)
        return manifest

    def _backup_target(self, target: dict, dest_dir: Path, backup_type: str) -> Optional[BackupEntry]:
        t = target["type"]
        name = target["name"]
        src_path = Path(target["path"])

        if t == "sqlite":
            return self._backup_sqlite(name, src_path, dest_dir, backup_type)
        elif t == "file":
            return self._backup_file(name, src_path, dest_dir, backup_type)
        elif t == "directory":
            return self._backup_directory(name, src_path, dest_dir, backup_type)
        return None

    def _backup_sqlite(self, name: str, src: Path, dest_dir: Path, backup_type: str) -> Optional[BackupEntry]:
        if not src.exists():
            log.warning("BackupSystem: SQLite '%s' not found at %s", name, src)
            return None

        # Check if changed (incremental)
        checksum = self._sha256_file(src)
        if backup_type == "incremental" and self._previous_checksums.get(name) == checksum:
            log.debug("BackupSystem: '%s' unchanged, skipping incremental", name)
            return None

        dest = dest_dir / f"{name}.db"
        if self._compress:
            dest = dest_dir / f"{name}.db.gz"
            with sqlite3.connect(str(src)) as conn:
                tmp = dest_dir / f"{name}_tmp.db"
                conn.backup(sqlite3.connect(str(tmp)))
            with open(str(tmp), "rb") as f_in, gzip.open(str(dest), "wb") as f_out:
                shutil.copyfileobj(f_in, f_out)
            tmp.unlink(missing_ok=True)
        else:
            with sqlite3.connect(str(src)) as conn:
                conn.backup(sqlite3.connect(str(dest)))

        self._previous_checksums[name] = checksum
        return BackupEntry(
            name=name, backup_path=str(dest), source_path=str(src),
            size_bytes=dest.stat().st_size, checksum_sha256=checksum,
            compressed=self._compress, backup_type=backup_type,
        )

    def _backup_file(self, name: str, src: Path, dest_dir: Path, backup_type: str) -> Optional[BackupEntry]:
        if not src.exists():
            log.warning("BackupSystem: file '%s' not found at %s", name, src)
            return None

        checksum = self._sha256_file(src)
        if backup_type == "incremental" and self._previous_checksums.get(name) == checksum:
            return None

        dest = dest_dir / src.name
        if self._compress:
            dest = dest_dir / (src.name + ".gz")
            with open(str(src), "rb") as f_in, gzip.open(str(dest), "wb") as f_out:
                shutil.copyfileobj(f_in, f_out)
        else:
            shutil.copy2(str(src), str(dest))

        self._previous_checksums[name] = checksum
        return BackupEntry(
            name=name, backup_path=str(dest), source_path=str(src),
            size_bytes=dest.stat().st_size, checksum_sha256=checksum,
            compressed=self._compress, backup_type=backup_type,
        )

    def _backup_directory(self, name: str, src: Path, dest_dir: Path, backup_type: str) -> Optional[BackupEntry]:
        if not src.exists():
            return None
        archive_base = str(dest_dir / name)
        archive_path = shutil.make_archive(archive_base, "gztar", str(src.parent), str(src.name))
        p = Path(archive_path)
        checksum = self._sha256_file(p)
        return BackupEntry(
            name=name, backup_path=archive_path, source_path=str(src),
            size_bytes=p.stat().st_size, checksum_sha256=checksum,
            compressed=True, backup_type=backup_type,
        )

    # ── Scheduler ─────────────────────────────────────────────────────────────

    def schedule(self, daily_at_hour: int = 2, weekly_day: Optional[int] = None) -> None:
        """Schedule automatic backups. daily_at_hour: 0-23. weekly_day: 0=Mon."""
        if self._running:
            return
        self._running = True
        self._scheduler_thread = threading.Thread(
            target=self._scheduler_loop,
            args=(daily_at_hour, weekly_day),
            daemon=True,
            name="BackupScheduler",
        )
        self._scheduler_thread.start()
        log.info("BackupSystem: scheduler started (daily at %02d:00)", daily_at_hour)

    def stop(self) -> None:
        self._running = False

    def _scheduler_loop(self, daily_hour: int, weekly_day: Optional[int]) -> None:
        while self._running:
            now = time.localtime()
            if now.tm_hour == daily_hour and now.tm_min == 0:
                backup_type = "full"
                if weekly_day is not None and now.tm_wday != weekly_day:
                    backup_type = "incremental"
                try:
                    self.run_now(backup_type=backup_type)
                except Exception as e:
                    log.error("BackupSystem: scheduled backup failed: %s", e)
                time.sleep(60)  # prevent double-trigger in same minute
            time.sleep(30)

    # ── Cloud upload stubs ────────────────────────────────────────────────────

    def upload_to_s3(self, manifest: BackupManifest, bucket: str, prefix: str = "backups/") -> bool:
        """Upload backup files to AWS S3."""
        try:
            import boto3
            s3 = boto3.client("s3")
            for entry in manifest.entries:
                key = f"{prefix}{Path(entry.backup_path).name}"
                s3.upload_file(entry.backup_path, bucket, key)
                log.info("BackupSystem: uploaded %s → s3://%s/%s", entry.name, bucket, key)
            return True
        except ImportError:
            log.warning("BackupSystem: boto3 not installed — pip install boto3")
        except Exception as e:
            log.error("BackupSystem: S3 upload failed: %s", e)
        return False

    def upload_to_gcs(self, manifest: BackupManifest, bucket: str, prefix: str = "backups/") -> bool:
        """Upload backup files to Google Cloud Storage."""
        try:
            from google.cloud import storage
            client = storage.Client()
            bkt = client.bucket(bucket)
            for entry in manifest.entries:
                blob = bkt.blob(f"{prefix}{Path(entry.backup_path).name}")
                blob.upload_from_filename(entry.backup_path)
                log.info("BackupSystem: uploaded %s → gs://%s/%s", entry.name, bucket, blob.name)
            return True
        except ImportError:
            log.warning("BackupSystem: google-cloud-storage not installed")
        except Exception as e:
            log.error("BackupSystem: GCS upload failed: %s", e)
        return False

    # ── Listing ───────────────────────────────────────────────────────────────

    def list_backups(self) -> List[dict]:
        """List all available backup sessions."""
        sessions = []
        for session_dir in sorted(self._backup_dir.iterdir()):
            if not session_dir.is_dir():
                continue
            manifest_path = session_dir / "manifest.json"
            if manifest_path.exists():
                try:
                    data = json.loads(manifest_path.read_text())
                    sessions.append({
                        "session_dir": str(session_dir),
                        "created_at": data.get("created_at_iso"),
                        "backup_type": data.get("backup_type"),
                        "item_count": len(data.get("entries", [])),
                        "total_size_bytes": data.get("total_size_bytes"),
                    })
                except Exception:
                    pass
        return sessions

    def get_latest_backup_dir(self) -> Optional[Path]:
        """Return the most recent backup directory path."""
        sessions = sorted(
            [d for d in self._backup_dir.iterdir() if d.is_dir() and (d / "manifest.json").exists()],
            key=lambda d: d.stat().st_mtime,
        )
        return sessions[-1] if sessions else None

    # ── Helpers ───────────────────────────────────────────────────────────────

    @staticmethod
    def _sha256_file(path: Path) -> str:
        h = hashlib.sha256()
        try:
            with open(str(path), "rb") as f:
                for chunk in iter(lambda: f.read(65536), b""):
                    h.update(chunk)
        except Exception:
            return ""
        return h.hexdigest()
