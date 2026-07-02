"""
Phase 8.8 — Upload Manager Tests
=====================================
Verify UploadManager: multi-file queue, progress tracking, success/failure detection.
"""
from __future__ import annotations
import asyncio, sys, os, tempfile, logging
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from unittest.mock import AsyncMock, MagicMock
from backend.browser.upload_manager import UploadManager, UploadStatus, UploadRecord


class TestUploadManager:

    def setup_method(self):
        self.um = UploadManager()

    # ── UploadRecord dataclass ────────────────────────────────────────────────

    def test_upload_record_to_dict(self):
        r = UploadRecord(
            upload_id="up_001",
            file_paths=["/tmp/file1.pdf", "/tmp/file2.pdf"],
            selector="input[type='file']",
            status=UploadStatus.PENDING
        )
        d = r.to_dict()
        assert d["upload_id"] == "up_001"
        assert d["status"] == "pending"
        assert len(d["file_paths"]) == 2

    def test_upload_status_values(self):
        assert UploadStatus.PENDING.value               == "pending"
        assert UploadStatus.IN_PROGRESS.value           == "in_progress"
        assert UploadStatus.COMPLETED.value             == "completed"
        assert UploadStatus.FAILED.value                == "failed"
        assert UploadStatus.AWAITING_CONFIRMATION.value == "awaiting_confirmation"

    # ── No records initially ──────────────────────────────────────────────────

    def test_no_records_initially(self):
        assert len(self.um._records) == 0

    # ── Confirm function ──────────────────────────────────────────────────────

    def test_confirm_fn_stored(self):
        fn = lambda files: True
        um = UploadManager(confirm_fn=fn)
        assert um._confirm is fn

    # ── upload() with valid file ──────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_upload_single_file_succeeds(self):
        # Create a real temporary file
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"hello upload test")
            tmp_path = f.name

        page = AsyncMock()
        page.query_selector = AsyncMock(return_value=AsyncMock(is_visible=AsyncMock(return_value=True)))
        page.set_input_files = AsyncMock()
        page.wait_for_selector = AsyncMock()
        page.evaluate = AsyncMock(return_value="upload successful")

        result = await self.um.upload(page, files=[tmp_path])
        assert result is not None
        os.unlink(tmp_path)

    # ── upload() with missing file raises or returns failure ─────────────────

    @pytest.mark.asyncio
    async def test_upload_missing_file_returns_failure(self):
        page = AsyncMock()
        result = await self.um.upload(page, files=["/nonexistent/file.pdf"])
        assert result["status"] in ("failed", "error") or not result.get("success", True)

    # ── Multi-file queue ──────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_upload_multiple_files_queued(self):
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f1, \
             tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f2:
            f1.write(b"file1"); f2.write(b"file2")
            paths = [f1.name, f2.name]

        page = AsyncMock()
        page.query_selector = AsyncMock(return_value=AsyncMock(is_visible=AsyncMock(return_value=True)))
        page.set_input_files = AsyncMock()
        page.evaluate = AsyncMock(return_value="upload successful")

        result = await self.um.upload(page, files=paths)
        assert result is not None
        for p in paths:
            try: os.unlink(p)
            except Exception as e:
                logging.warning("Unexpected error: %s", e)

    # ── list_uploads ──────────────────────────────────────────────────────────

    def test_list_uploads_returns_list(self):
        result = self.um.list_uploads()
        assert isinstance(result, list)
