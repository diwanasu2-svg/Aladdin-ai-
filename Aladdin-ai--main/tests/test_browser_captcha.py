"""
Phase 8.1 — CAPTCHA Handling Tests
=====================================
Verify CaptchaHandler detection, user notification, and solve logic.
"""
from __future__ import annotations
import asyncio
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from backend.browser.captcha import CaptchaHandler, CaptchaType, CaptchaResult


class TestCaptchaDetection:
    """Tests for CaptchaHandler.detect()"""

    @pytest.mark.asyncio
    async def test_detect_no_captcha(self):
        """Returns NONE when page has no CAPTCHA elements."""
        page = AsyncMock()
        page.query_selector = AsyncMock(return_value=None)
        page.evaluate = AsyncMock(return_value="normal page content")

        handler = CaptchaHandler()
        result = await handler.detect(page)
        assert result == CaptchaType.NONE, f"Expected NONE but got {result}"

    @pytest.mark.asyncio
    async def test_detect_recaptcha_v2(self):
        """Returns RECAPTCHA_V2 when .g-recaptcha is visible."""
        page = AsyncMock()
        el = AsyncMock()
        el.is_visible = AsyncMock(return_value=True)

        async def mock_query_selector(sel):
            if sel == ".g-recaptcha":
                return el
            return None

        page.query_selector = mock_query_selector
        page.evaluate = AsyncMock(return_value="")

        handler = CaptchaHandler()
        result = await handler.detect(page)
        assert result == CaptchaType.RECAPTCHA_V2

    @pytest.mark.asyncio
    async def test_detect_hcaptcha(self):
        """Returns HCAPTCHA when .h-captcha is visible."""
        page = AsyncMock()
        el = AsyncMock()
        el.is_visible = AsyncMock(return_value=True)

        async def mock_query_selector(sel):
            if sel == ".h-captcha":
                return el
            return None

        page.query_selector = mock_query_selector
        page.evaluate = AsyncMock(return_value="")

        handler = CaptchaHandler()
        result = await handler.detect(page)
        assert result == CaptchaType.HCAPTCHA

    @pytest.mark.asyncio
    async def test_detect_cloudflare_from_body_text(self):
        """Detects Cloudflare challenge from page body text."""
        page = AsyncMock()
        page.query_selector = AsyncMock(return_value=None)
        page.evaluate = AsyncMock(return_value="just a moment, checking your browser")

        handler = CaptchaHandler()
        result = await handler.detect(page)
        assert result == CaptchaType.CLOUDFLARE

    @pytest.mark.asyncio
    async def test_detect_image_captcha(self):
        """Detects image CAPTCHA from img[src*='captcha']."""
        page = AsyncMock()
        el = AsyncMock()
        el.is_visible = AsyncMock(return_value=True)

        async def mock_query_selector(sel):
            if "captcha" in sel.lower():
                return el
            return None

        page.query_selector = mock_query_selector
        page.evaluate = AsyncMock(return_value="")

        handler = CaptchaHandler()
        result = await handler.detect(page)
        assert result in {CaptchaType.IMAGE_CAPTCHA, CaptchaType.TEXT_CAPTCHA,
                          CaptchaType.RECAPTCHA_V2, CaptchaType.HCAPTCHA}


class TestCaptchaResult:
    """Tests for CaptchaResult dataclass serialisation."""

    def test_to_dict_detected(self):
        r = CaptchaResult(detected=True, captcha_type=CaptchaType.RECAPTCHA_V2,
                          solved=False, user_notified=True)
        d = r.to_dict()
        assert d["detected"] is True
        assert d["captcha_type"] == "recaptcha_v2"
        assert d["solved"] is False
        assert d["user_notified"] is True

    def test_to_dict_not_detected(self):
        r = CaptchaResult(detected=False, captcha_type=CaptchaType.NONE)
        d = r.to_dict()
        assert d["detected"] is False
        assert d["captcha_type"] == "none"

    def test_to_dict_with_error(self):
        r = CaptchaResult(detected=True, captcha_type=CaptchaType.CLOUDFLARE,
                          error="Timeout waiting for user solve")
        d = r.to_dict()
        assert d["error"] == "Timeout waiting for user solve"


class TestNotifyFn:
    """Tests that user notification callback is called."""

    @pytest.mark.asyncio
    async def test_notify_called_on_detection(self):
        notified = []

        async def notify(msg, ctype):
            notified.append((msg, ctype))

        page = AsyncMock()
        el = AsyncMock()
        el.is_visible = AsyncMock(return_value=True)

        async def mock_query_selector(sel):
            if sel == ".g-recaptcha":
                return el
            return None

        page.query_selector = mock_query_selector
        page.evaluate = AsyncMock(return_value="")

        handler = CaptchaHandler(notify_fn=notify, user_solve_timeout=0.1)
        await handler.check_and_handle(page)
        # Notification should have been called
        assert len(notified) >= 1
