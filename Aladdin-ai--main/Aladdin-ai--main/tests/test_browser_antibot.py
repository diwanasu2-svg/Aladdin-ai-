"""
Phase 8.2 — Anti-Bot Evasion Tests
======================================
Verify AntiBotEvasion: stealth setup, human delays, mouse movements,
cookie rotation, block signal detection.
"""
from __future__ import annotations
import asyncio, sys, os, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from unittest.mock import AsyncMock, MagicMock
from backend.browser.antibot import AntiBotEvasion, _USER_AGENTS, _BLOCK_SIGNALS


class TestAntiBotEvasion:

    def setup_method(self):
        self.evasion = AntiBotEvasion(min_delay=0.01, max_delay=0.05)

    # ── User-agent pool ───────────────────────────────────────────────────────

    def test_user_agents_not_empty(self):
        assert len(_USER_AGENTS) > 0, "User agent pool must not be empty"

    def test_user_agents_contain_chrome(self):
        assert any("Chrome" in ua for ua in _USER_AGENTS), \
            "At least one Chrome user-agent must be present"

    # ── Block signals ─────────────────────────────────────────────────────────

    def test_block_signals_not_empty(self):
        assert len(_BLOCK_SIGNALS) > 0

    # ── Human delay ───────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_human_delay_completes(self):
        start = time.monotonic()
        await self.evasion.human_delay()
        elapsed = time.monotonic() - start
        assert elapsed >= 0.005, "Human delay must sleep at least 5ms"

    # ── Stealth setup ─────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_stealth_setup_sets_headers(self):
        page = AsyncMock()
        page.set_extra_http_headers = AsyncMock()
        page.add_init_script = AsyncMock()

        await self.evasion.stealth_setup(page)

        page.set_extra_http_headers.assert_called_once()
        call_args = page.set_extra_http_headers.call_args[0][0]
        assert "User-Agent" in call_args

    @pytest.mark.asyncio
    async def test_stealth_setup_injects_script(self):
        page = AsyncMock()
        page.set_extra_http_headers = AsyncMock()
        page.add_init_script = AsyncMock()

        await self.evasion.stealth_setup(page)

        page.add_init_script.assert_called_once()
        script = page.add_init_script.call_args[0][0]
        assert "webdriver" in script.lower() or "navigator" in script.lower()

    # ── Block detection ───────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_detect_block_on_blocked_text(self):
        page = AsyncMock()
        page.evaluate = AsyncMock(return_value="access denied, bot detected")
        page.title = AsyncMock(return_value="Blocked")

        result = await self.evasion.is_blocked(page)
        assert result is True

    @pytest.mark.asyncio
    async def test_no_block_on_normal_page(self):
        page = AsyncMock()
        page.evaluate = AsyncMock(return_value="Welcome to our store!")
        page.title = AsyncMock(return_value="Home")

        result = await self.evasion.is_blocked(page)
        assert result is False

    # ── Cookie rotation ───────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_configure_context_sets_headers(self):
        context = AsyncMock()
        context.set_extra_http_headers = AsyncMock()
        await self.evasion.configure_context(context)
        context.set_extra_http_headers.assert_called_once()
