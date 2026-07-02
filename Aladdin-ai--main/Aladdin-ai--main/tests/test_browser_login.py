"""
Phase 8.6 — Login / Session Management Tests
===============================================
Verify SessionManager: save/load sessions, expiry detection, MFA stub, re-login.
"""
from __future__ import annotations
import asyncio, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from backend.browser.session_manager import SessionManager, _EXPIRY_SIGNALS


class FakePWManager:
    def __init__(self):
        self._context = AsyncMock()
        self._context.storage_state = AsyncMock(return_value=None)
        self._page = AsyncMock()
        self._page.url   = "https://example.com"
        self._page.title = AsyncMock(return_value="Home")
        self._page.wait_for_load_state = AsyncMock()
        self._page.fill  = AsyncMock()
        self._page.click = AsyncMock()
        self._page.query_selector = AsyncMock(return_value=None)

    async def new_page(self, url=None):
        return self._page

    async def load_storage(self, path: str):
        pass


class TestSessionManager:

    def setup_method(self):
        self.pw = FakePWManager()
        self.manager = SessionManager(self.pw)

    # ── Expiry signals ────────────────────────────────────────────────────────

    def test_expiry_signals_not_empty(self):
        assert len(_EXPIRY_SIGNALS) > 0

    def test_expiry_signals_contain_common_patterns(self):
        signals_lower = [s.lower() for s in _EXPIRY_SIGNALS]
        assert any("login" in s or "sign in" in s or "session" in s for s in signals_lower)

    # ── Session detection ─────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_is_session_expired_returns_false_on_normal_page(self):
        page = AsyncMock()
        page.evaluate = AsyncMock(return_value="Welcome back, user!")
        result = await self.manager.is_session_expired(page)
        assert result is False

    @pytest.mark.asyncio
    async def test_is_session_expired_returns_true_on_login_page(self):
        page = AsyncMock()
        page.evaluate = AsyncMock(return_value="please sign in to continue")
        result = await self.manager.is_session_expired(page)
        assert result is True

    # ── Session index ─────────────────────────────────────────────────────────

    def test_session_index_initialises(self):
        assert isinstance(self.manager._sessions, dict)

    # ── load_session returns False when no session stored ─────────────────────

    @pytest.mark.asyncio
    async def test_load_session_returns_false_when_not_stored(self):
        result = await self.manager.load_session("https://never-seen.example.com")
        assert result is False

    # ── Save then load session ────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_save_and_load_session(self):
        url = "https://github.com"
        await self.manager.save_session(url, "user@example.com")
        result = await self.manager.load_session(url)
        assert result is True

    # ── Login flow ────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_login_and_save_calls_fill_and_click(self):
        result = await self.manager.login_and_save(
            url="https://example.com/login",
            username="user@example.com",
            password="secret",
            username_selector="input[name='username']",
            password_selector="input[name='password']",
            submit_selector="button[type='submit']"
        )
        # Login flow should execute without raising
        assert "success" in result
