"""
Phase 8.7 — Download Manager
==============================
Monitor browser downloads, track progress, manage location,
retry failed downloads, link files to AI memory.
"""
from __future__ import annotations
import asyncio, logging, os, shutil, time
from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)

_DEFAULT_DOWNLOAD_DIR = Path("data/downloads")
_DEFAULT_DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)


class DownloadStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    RETRYING = "retrying"


@dataclass
class DownloadRecord:
    download_id: str
    url: str
    suggested_filename: str
    status: DownloadStatus = DownloadStatus.PENDING
    local_path: str = ""
    size_bytes: int = 0
    started_at: float = field(default_factory=time.time)
    completed_at: Optional[float] = None
    error: Optional[str] = None
    retry_count: int = 0
    linked_to_memory: bool = False

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        d["status"] = self.status.value
        return d


class DownloadManager:
    """
    Manages Playwright downloads with tracking, retry, and memory integration.

    Wire up::

        dm = DownloadManager(download_dir="data/downloads")
        dm.set_memory_fn(lambda path, meta: memory.store(path, meta))

        # Attach to Playwright page
        page.on("download", dm.on_download_event)

        # Trigger download
        record = await dm.trigger_download(page, selector="a#download-btn")
    """

    def __init__(self, download_dir: str = "data/downloads",
                 max_retries: int = 3, retry_delay: float = 3.0) -> None:
        self._dir = Path(download_dir)
        self._dir.mkdir(parents=True, exist_ok=True)
        self._max_retries = max_retries
        self._retry_delay = retry_delay
        self._records: Dict[str, DownloadRecord] = {}
        self._memory_fn: Optional[Callable[[str, Dict], None]] = None
        self._on_complete: Optional[Callable[[DownloadRecord], None]] = None
        self._counter = 0

    def set_memory_fn(self, fn: Callable[[str, Dict[str, Any]], None]) -> None:
        self._memory_fn = fn

    def set_on_complete(self, fn: Callable[[DownloadRecord], None]) -> None:
        self._on_complete = fn

    def _new_id(self) -> str:
        self._counter += 1
        return f"dl_{int(time.time())}_{self._counter:04d}"

    # ── Playwright event handler ──────────────────────────────────────────────

    def on_download_event(self, download) -> None:
        """Attach to page.on('download', dm.on_download_event)"""
        asyncio.ensure_future(self._handle_playwright_download(download))

    async def _handle_playwright_download(self, download) -> None:
        did = self._new_id()
        fname = download.suggested_filename or f"download_{did}"
        rec = DownloadRecord(download_id=did, url=download.url,
                             suggested_filename=fname,
                             status=DownloadStatus.IN_PROGRESS)
        self._records[did] = rec

        dest = self._dir / fname
        # Avoid name collision
        if dest.exists():
            stem = dest.stem
            suffix = dest.suffix
            dest = self._dir / f"{stem}_{did}{suffix}"

        try:
            await download.save_as(str(dest))
            rec.local_path = str(dest)
            rec.size_bytes = dest.stat().st_size
            rec.status = DownloadStatus.COMPLETED
            rec.completed_at = time.time()
            log.info("Download completed: %s → %s (%d bytes)", fname, dest, rec.size_bytes)
            self._link_to_memory(rec)
            if self._on_complete:
                self._on_complete(rec)
        except Exception as exc:
            rec.error = str(exc)
            rec.status = DownloadStatus.FAILED
            log.error("Download failed: %s — %s", fname, exc)
            await self._retry_download(rec, download)

    async def _retry_download(self, rec: DownloadRecord, download) -> None:
        while rec.retry_count < self._max_retries:
            rec.retry_count += 1
            rec.status = DownloadStatus.RETRYING
            log.info("Retrying download %s (attempt %d/%d)", rec.suggested_filename,
                     rec.retry_count, self._max_retries)
            await asyncio.sleep(self._retry_delay * rec.retry_count)
            dest = self._dir / f"retry_{rec.retry_count}_{rec.suggested_filename}"
            try:
                await download.save_as(str(dest))
                rec.local_path = str(dest)
                rec.size_bytes = dest.stat().st_size
                rec.status = DownloadStatus.COMPLETED
                rec.completed_at = time.time()
                rec.error = None
                self._link_to_memory(rec)
                return
            except Exception as exc:
                rec.error = str(exc)
        rec.status = DownloadStatus.FAILED
        log.error("Download permanently failed after %d retries: %s",
                  self._max_retries, rec.suggested_filename)

    # ── Trigger download ──────────────────────────────────────────────────────

    async def trigger_download(self, page, selector: str,
                                timeout: float = 30.0) -> Optional[DownloadRecord]:
        """Click a selector and wait for the resulting download."""
        did = self._new_id()
        rec = DownloadRecord(download_id=did, url=page.url, suggested_filename="",
                             status=DownloadStatus.PENDING)
        self._records[did] = rec

        download_obj = None
        try:
            async with page.expect_download(timeout=timeout * 1000) as dl_info:
                el = await page.query_selector(selector)
                if el:
                    await el.click()
                else:
                    rec.status = DownloadStatus.FAILED
                    rec.error = f"Selector not found: {selector}"
                    return rec
            download_obj = await dl_info.value
        except Exception as exc:
            rec.status = DownloadStatus.FAILED
            rec.error = str(exc)
            return rec

        await self._handle_playwright_download(download_obj)
        # Return the last record that was processed
        return max(self._records.values(), key=lambda r: r.started_at)

    async def download_url(self, page, url: str, filename: Optional[str] = None) -> DownloadRecord:
        """Navigate to URL expecting file download."""
        did = self._new_id()
        fname = filename or url.split("/")[-1].split("?")[0] or f"file_{did}"
        rec = DownloadRecord(download_id=did, url=url, suggested_filename=fname,
                             status=DownloadStatus.IN_PROGRESS)
        self._records[did] = rec
        dest = self._dir / fname

        try:
            async with page.expect_download(timeout=30000) as dl_info:
                await page.goto(url)
            dl = await dl_info.value
            await dl.save_as(str(dest))
            rec.local_path = str(dest)
            rec.size_bytes = dest.stat().st_size if dest.exists() else 0
            rec.status = DownloadStatus.COMPLETED
            rec.completed_at = time.time()
            self._link_to_memory(rec)
        except Exception as exc:
            rec.error = str(exc)
            rec.status = DownloadStatus.FAILED
        return rec

    # ── Memory integration ────────────────────────────────────────────────────

    def _link_to_memory(self, rec: DownloadRecord) -> None:
        if self._memory_fn and rec.local_path:
            try:
                self._memory_fn(rec.local_path, {
                    "type": "browser_download",
                    "url": rec.url,
                    "filename": rec.suggested_filename,
                    "size_bytes": rec.size_bytes,
                    "downloaded_at": rec.completed_at,
                })
                rec.linked_to_memory = True
            except Exception as exc:
                log.debug("Memory link error: %s", exc)

    # ── Status & management ───────────────────────────────────────────────────

    def list_downloads(self, status: Optional[DownloadStatus] = None) -> List[Dict[str, Any]]:
        records = list(self._records.values())
        if status:
            records = [r for r in records if r.status == status]
        return [r.to_dict() for r in sorted(records, key=lambda r: r.started_at, reverse=True)]

    def get_download(self, download_id: str) -> Optional[Dict[str, Any]]:
        rec = self._records.get(download_id)
        return rec.to_dict() if rec else None

    def move_download(self, download_id: str, destination: str) -> bool:
        rec = self._records.get(download_id)
        if not rec or not rec.local_path or not Path(rec.local_path).exists():
            return False
        try:
            dest = Path(destination)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(rec.local_path, str(dest))
            rec.local_path = str(dest)
            return True
        except Exception as exc:
            log.error("Move download failed: %s", exc)
            return False

    @property
    def download_dir(self) -> str:
        return str(self._dir)
