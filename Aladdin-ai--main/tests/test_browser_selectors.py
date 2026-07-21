"""
Phase 8.3 — Smart Selectors Tests
=====================================
Verify SmartSelector fallback chain, priority order, and convenience builders.
"""
from __future__ import annotations
import asyncio, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from unittest.mock import AsyncMock, MagicMock
from backend.browser.selectors import SmartSelector


class TestSmartSelector:

    def setup_method(self):
        self.selector = SmartSelector()

    # ── build_input_selectors ─────────────────────────────────────────────────

    def test_build_input_selectors_returns_list(self):
        sels = SmartSelector.build_input_selectors("username")
        assert isinstance(sels, list)
        assert len(sels) >= 3

    def test_build_input_selectors_contains_name_attr(self):
        sels = SmartSelector.build_input_selectors("email")
        assert any("email" in s for s in sels)

    def test_build_input_selectors_priority_order(self):
        sels = SmartSelector.build_input_selectors("username")
        # ID selector should come before attribute selectors
        id_idx   = next((i for i, s in enumerate(sels) if s.startswith("input[name=") or s.startswith("#")), None)
        attr_idx = next((i for i, s in enumerate(sels) if "*=" in s), None)
        if id_idx is not None and attr_idx is not None:
            assert id_idx < attr_idx, "Exact selectors must precede wildcard attribute selectors"

    # ── build_button_selectors ────────────────────────────────────────────────

    def test_build_button_selectors_returns_list(self):
        sels = SmartSelector.build_button_selectors("Submit")
        assert isinstance(sels, list)
        assert len(sels) >= 3

    def test_build_button_selectors_contains_submit_type(self):
        sels = SmartSelector.build_button_selectors("Login")
        assert any("submit" in s.lower() for s in sels)

    # ── find() fallback chain ─────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_find_returns_none_when_nothing_matches(self):
        page = AsyncMock()
        page.query_selector = AsyncMock(return_value=None)

        result, sel_used = await self.selector.find(page, "#nonexistent")
        assert result is None
        assert sel_used == ""

    @pytest.mark.asyncio
    async def test_find_returns_first_visible_match(self):
        page = AsyncMock()
        el = AsyncMock()
        el.is_visible = AsyncMock(return_value=True)

        call_count = [0]
        async def mock_query(sel):
            call_count[0] += 1
            if call_count[0] >= 2:   # second selector matches
                return el
            return None
        page.query_selector = mock_query

        result, sel_used = await self.selector.find(page, "#missing,input[name='q']")
        assert result is el

    @pytest.mark.asyncio
    async def test_find_skips_hidden_elements(self):
        page = AsyncMock()
        hidden_el = AsyncMock()
        hidden_el.is_visible = AsyncMock(return_value=False)
        visible_el = AsyncMock()
        visible_el.is_visible = AsyncMock(return_value=True)

        calls = [hidden_el, visible_el]
        idx   = [0]

        async def mock_query(sel):
            el = calls[idx[0]] if idx[0] < len(calls) else None
            idx[0] += 1
            return el

        page.query_selector = mock_query

        result, _ = await self.selector.find(page, "sel1,sel2")
        assert result is visible_el

    # ── _by_role helper ───────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_find_by_role_button(self):
        page = AsyncMock()
        el = AsyncMock()
        el.is_visible = AsyncMock(return_value=True)
        page.query_selector = AsyncMock(return_value=el)

        result, sel = await self.selector.find(page, "button")
        assert result is el
