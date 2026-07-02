"""
Phase 8.4 — Retry Logic Tests
================================
Verify RetryManager: exponential backoff, max attempts, timeout,
retryable vs fatal error classification.
"""
from __future__ import annotations
import asyncio, sys, os, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from backend.browser.retry import RetryManager, RetryResult, _RETRYABLE_ERRORS, _FATAL_ERRORS


class TestRetryManager:

    def setup_method(self):
        self.manager = RetryManager(max_attempts=3, base_delay=0.01, timeout=5.0)

    # ── Error classification ───────────────────────────────────────────────────

    def test_retryable_errors_list_not_empty(self):
        assert len(_RETRYABLE_ERRORS) > 0

    def test_fatal_errors_list_not_empty(self):
        assert len(_FATAL_ERRORS) > 0

    def test_is_retryable_for_timeout_error(self):
        assert self.manager.is_retryable("Timeout"), "Timeout must be retryable"

    def test_is_retryable_for_connection_refused(self):
        assert self.manager.is_retryable("net::ERR_CONNECTION_REFUSED")

    def test_is_not_retryable_for_404(self):
        assert not self.manager.is_retryable("404"), "404 is a fatal error — must not retry"

    def test_is_not_retryable_for_403(self):
        assert not self.manager.is_retryable("403")

    # ── Successful operation — no retries needed ──────────────────────────────

    @pytest.mark.asyncio
    async def test_execute_success_on_first_attempt(self):
        called = [0]

        async def op():
            called[0] += 1
            return "ok"

        result = await self.manager.execute(op, url="http://example.com")
        assert result.success is True
        assert result.attempts == 1
        assert called[0] == 1

    # ── Retryable failure then success ────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_execute_retries_on_timeout(self):
        attempts = [0]

        async def op():
            attempts[0] += 1
            if attempts[0] < 3:
                raise Exception("Timeout")
            return "ok"

        result = await self.manager.execute(op, url="http://example.com")
        assert result.success is True
        assert result.attempts == 3

    # ── Fatal error stops immediately ─────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_execute_stops_on_fatal_error(self):
        attempts = [0]

        async def op():
            attempts[0] += 1
            raise Exception("404")

        result = await self.manager.execute(op, url="http://example.com")
        assert result.success is False
        assert attempts[0] == 1, "Fatal errors must not be retried"

    # ── Max attempts enforced ─────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_execute_stops_at_max_attempts(self):
        attempts = [0]

        async def op():
            attempts[0] += 1
            raise Exception("net::ERR_CONNECTION_RESET")

        result = await self.manager.execute(op, url="http://example.com")
        assert result.success is False
        assert attempts[0] <= self.manager.max_attempts, \
            f"Should not exceed {self.manager.max_attempts} attempts"

    # ── RetryResult dataclass ────────────────────────────────────────────────

    def test_retry_result_success_fields(self):
        r = RetryResult(success=True, attempts=2, error=None)
        assert r.success is True
        assert r.attempts == 2
        assert r.error is None

    def test_retry_result_failure_fields(self):
        r = RetryResult(success=False, attempts=5, error="Timeout")
        assert r.success is False
        assert r.error == "Timeout"
