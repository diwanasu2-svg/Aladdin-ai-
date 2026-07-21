"""
Phase 8.8 — Upload Manager
============================
Auto-select correct files, monitor progress, support multiple uploads,
detect success/failure, ask user confirmation when needed.
"""
from __future__ import annotations
import asyncio, logging, os
from dataclasses import asdict, dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class UploadStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    AWAITING_CONFIRMATION = "awaiting_confirmation"


@dataclass
class UploadRecord:
    upload_id: str
    file_paths: List[str]
    selector: str
    status: UploadStatus = UploadStatus.PENDING
    success_indicator: Optional[str] = None
    error: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        d["status"] = self.status.value
        return d


_SUCCESS_SIGNALS = [
    "upload successful", "file uploaded", "uploaded successfully",
    "upload complete", "success", "thank you", "received",
    "file saved", "attached",
]
_FAILURE_SIGNALS = [
    "upload failed", "error uploading", "invalid file", "file too large",
    "unsupported format", "upload error", "could not upload",
]

_UPLOAD_INPUT_SELECTORS = [
    "input[type='file']",
    "input[accept]",
    "[data-testid*='upload'] input",
    ".upload-area input",
    "#file-input",
    "[class*='upload'] input[type='file']",
    "[class*='dropzone'] input",
]


class UploadManager:
    """
    Handles file uploads in Playwright with validation, progress and confirmation.

    Usage::

        um = UploadManager(confirm_fn=lambda files: input(f"Upload {files}? y/n") == 'y')
        result = await um.upload(page, files=["/path/to/file.pdf"])
        log.info(result)

    """

    def __init__(self, confirm_fn: Optional[Callable[[List[str]], bool]] = None) -> None:
        self._confirm = confirm_fn
        self._records: Dict[str, UploadRecord] = {}
        self._counter = 0

    def _new_id(self) -> str:
        self._counter += 1
        return f"up_{self._counter:04d}"

    # ── Find upload element ───────────────────────────────────────────────────

    async def find_upload_input(self, page,
                                 custom_selector: Optional[str] = None) -> Optional[Any]:
        """Find the file input element using common selectors."""
        selectors = ([custom_selector] if custom_selector else []) + _UPLOAD_INPUT_SELECTORS
        for sel in selectors:
            try:
                el = await page.query_selector(sel)
                if el:
                    return el, sel
            except Exception:
                continue
        return None, ""

    # ── Validate files ────────────────────────────────────────────────────────

    def validate_files(self, file_paths: List[str],
                        allowed_extensions: Optional[List[str]] = None,
                        max_size_mb: float = 50.0) -> List[str]:
        """Return list of validation errors; empty means all OK."""
        errors = []
        for path in file_paths:
            p = Path(path)
            if not p.exists():
                errors.append(f"File not found: {path}")
                continue
            if p.stat().st_size > max_size_mb * 1024 * 1024:
                errors.append(f"File too large (>{max_size_mb}MB): {p.name}")
            if allowed_extensions:
                if p.suffix.lower() not in [e.lower() for e in allowed_extensions]:
                    errors.append(f"Unsupported format {p.suffix}: {p.name}")
        return errors

    # ── Main upload ───────────────────────────────────────────────────────────

    async def upload(self, page,
                     files: List[str],
                     selector: Optional[str] = None,
                     confirm_before: bool = False,
                     allowed_extensions: Optional[List[str]] = None,
                     max_size_mb: float = 50.0,
                     submit_after: bool = False,
                     submit_selector: Optional[str] = None,
                     wait_success_ms: int = 10000) -> UploadRecord:
        """
        Upload one or more files.

        Args:
            page: Playwright Page
            files: local file paths to upload
            selector: CSS selector for file input (auto-detected if None)
            confirm_before: ask user confirmation before uploading
            allowed_extensions: e.g. ['.pdf', '.png']
            max_size_mb: file size limit
            submit_after: click submit after setting files
            submit_selector: CSS selector for submit button
        """
        uid = self._new_id()
        rec = UploadRecord(upload_id=uid, file_paths=files,
                           selector=selector or "auto")
        self._records[uid] = rec

        # Validate
        errors = self.validate_files(files, allowed_extensions, max_size_mb)
        if errors:
            rec.status = UploadStatus.FAILED
            rec.error = "; ".join(errors)
            log.error("Upload validation failed: %s", rec.error)
            return rec

        # Confirmation
        if confirm_before and self._confirm:
            rec.status = UploadStatus.AWAITING_CONFIRMATION
            fnames = [Path(f).name for f in files]
            approved = self._confirm(fnames)
            if not approved:
                rec.status = UploadStatus.FAILED
                rec.error = "Upload cancelled by user"
                return rec

        rec.status = UploadStatus.IN_PROGRESS

        # Find input element
        el, used_sel = await self.find_upload_input(page, selector)
        if el is None:
            rec.status = UploadStatus.FAILED
            rec.error = "No file input element found on page"
            return rec
        rec.selector = used_sel

        # Set files
        try:
            await el.set_input_files(files)
            log.info("Files set on input: %s", [Path(f).name for f in files])
        except Exception as exc:
            rec.status = UploadStatus.FAILED
            rec.error = f"set_input_files failed: {exc}"
            return rec

        # Submit if requested
        if submit_after:
            submit_sels = ([submit_selector] if submit_selector else []) + [
                "button[type='submit']", "input[type='submit']",
                "button:has-text('Upload')", "button:has-text('Submit')",
                "button:has-text('Send')", "button:has-text('Attach')",
            ]
            for ss in submit_sels:
                try:
                    btn = await page.query_selector(ss)
                    if btn and await btn.is_visible():
                        await btn.click()
                        break
                except Exception:
                    continue

        # Detect success / failure
        try:
            await page.wait_for_load_state("networkidle", timeout=wait_success_ms)
        except Exception:
            pass

        status_text, indicator = await self._detect_status(page)
        rec.success_indicator = indicator
        rec.status = status_text
        if status_text == UploadStatus.COMPLETED:
            log.info("Upload completed: %s", [Path(f).name for f in files])
        else:
            log.warning("Upload status unclear or failed: %s", indicator)
        return rec

    # ── Detect upload outcome ─────────────────────────────────────────────────

    async def _detect_status(self, page) -> tuple:
        try:
            body = (await page.evaluate(
                "() => document.body ? document.body.innerText.toLowerCase() : ''"
            ))
            for sig in _SUCCESS_SIGNALS:
                if sig in body:
                    return UploadStatus.COMPLETED, sig
            for sig in _FAILURE_SIGNALS:
                if sig in body:
                    return UploadStatus.FAILED, sig
        except Exception:
            pass
        return UploadStatus.COMPLETED, "no_indicator"  # optimistic default

    # ── Multi-file uploads ────────────────────────────────────────────────────

    async def upload_multiple_sets(self, page,
                                    file_sets: List[Dict[str, Any]]) -> List[UploadRecord]:
        """
        Upload multiple file sets sequentially.
        file_sets: [{"files": [...], "selector": "...", "submit_after": True}, ...]
        """
        results = []
        for fset in file_sets:
            rec = await self.upload(
                page,
                files=fset.get("files", []),
                selector=fset.get("selector"),
                submit_after=fset.get("submit_after", False),
                submit_selector=fset.get("submit_selector"),
            )
            results.append(rec)
            await asyncio.sleep(1.0)
        return results

    def list_uploads(self) -> List[Dict[str, Any]]:
        return [r.to_dict() for r in self._records.values()]

    def get_upload(self, upload_id: str) -> Optional[Dict[str, Any]]:
        rec = self._records.get(upload_id)
        return rec.to_dict() if rec else None
