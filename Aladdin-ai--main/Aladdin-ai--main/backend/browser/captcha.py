"""
Phase 8.1 — CAPTCHA Handling
==============================
Detect CAPTCHA presence, notify user, resume automation after solve,
handle timeouts and failures gracefully.
"""
import logging
from __future__ import annotations
import asyncio, logging, time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger(__name__)


class CaptchaType(str, Enum):
    RECAPTCHA_V2 = "recaptcha_v2"
    RECAPTCHA_V3 = "recaptcha_v3"
    HCAPTCHA = "hcaptcha"
    CLOUDFLARE = "cloudflare"
    IMAGE_CAPTCHA = "image_captcha"
    TEXT_CAPTCHA = "text_captcha"
    TURNSTILE = "turnstile"
    UNKNOWN = "unknown"
    NONE = "none"


# CSS/XPath selectors for each CAPTCHA type
_CAPTCHA_SIGNATURES: Dict[CaptchaType, List[str]] = {
    CaptchaType.RECAPTCHA_V2: [
        ".g-recaptcha", "#g-recaptcha", "iframe[src*='recaptcha']",
        "div[class*='recaptcha']",
    ],
    CaptchaType.RECAPTCHA_V3: [
        ".grecaptcha-badge", "script[src*='recaptcha/api.js']",
    ],
    CaptchaType.HCAPTCHA: [
        ".h-captcha", "#h-captcha", "iframe[src*='hcaptcha']",
        "div[class*='hcaptcha']",
    ],
    CaptchaType.CLOUDFLARE: [
        "#challenge-form", ".cf-challenge", "#cf-hcaptcha-container",
        "div[id*='cf-challenge']",
    ],
    CaptchaType.TURNSTILE: [
        ".cf-turnstile", "div[class*='turnstile']",
        "iframe[src*='challenges.cloudflare.com']",
    ],
    CaptchaType.IMAGE_CAPTCHA: [
        "img[src*='captcha']", "img[alt*='captcha']", "img[id*='captcha']",
        ".captcha-image", "#captchaImage",
    ],
    CaptchaType.TEXT_CAPTCHA: [
        "input[id*='captcha']", "input[name*='captcha']",
        ".captcha-input", "[placeholder*='captcha']",
    ],
}


@dataclass
class CaptchaResult:
    detected: bool
    captcha_type: CaptchaType
    solved: bool = False
    user_notified: bool = False
    solve_time_seconds: float = 0.0
    error: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "detected": self.detected,
            "captcha_type": self.captcha_type.value,
            "solved": self.solved,
            "user_notified": self.user_notified,
            "solve_time_seconds": round(self.solve_time_seconds, 2),
            "error": self.error,
        }


class CaptchaHandler:
    """
    Detect CAPTCHA on a Playwright page and wait for user to solve it.

    Usage::

        handler = CaptchaHandler(notify_fn=lambda msg: log.info(msg))
        result = await handler.check_and_handle(page)
        if result.detected and result.solved:
            # continue automation
    """

    def __init__(
        self,
        notify_fn: Optional[Callable[[str, str], None]] = None,
        user_solve_timeout: float = 120.0,
        poll_interval: float = 2.0,
    ) -> None:
        self._notify = notify_fn          # (message, captcha_type)
        self._user_timeout = user_solve_timeout
        self._poll = poll_interval

    async def detect(self, page) -> CaptchaType:
        """Return the detected CAPTCHA type or NONE."""
        for ctype, selectors in _CAPTCHA_SIGNATURES.items():
            for sel in selectors:
                try:
                    el = await page.query_selector(sel)
                    if el and await el.is_visible():
                        log.info("CAPTCHA detected: %s  selector=%s", ctype.value, sel)
                        return ctype
                except Exception:
                    continue
        # Check page title / body text for challenge pages
        try:
            body = await page.evaluate("() => document.body.innerText.toLowerCase()")
            for kw in ("just a moment", "checking your browser", "captcha", "are you human"):
                if kw in body:
                    return CaptchaType.CLOUDFLARE
        except Exception:
            pass
        return CaptchaType.NONE

    async def check_and_handle(self, page, action_name: str = "") -> CaptchaResult:
        """Check page for CAPTCHA and wait for the user to solve it."""
        ctype = await self.detect(page)
        if ctype == CaptchaType.NONE:
            return CaptchaResult(detected=False, captcha_type=CaptchaType.NONE, solved=True)

        result = CaptchaResult(detected=True, captcha_type=ctype)
        msg = (
            f"🔒 CAPTCHA detected ({ctype.value})"
            + (f" while: {action_name}" if action_name else "")
            + "\nPlease solve the CAPTCHA in the browser window. "
            "Automation will resume automatically once solved."
        )
        log.warning("CAPTCHA detected: %s", ctype.value)
        if self._notify:
            try:
                self._notify(msg, ctype.value)
            except Exception:
                pass
        result.user_notified = True

        # Poll until CAPTCHA disappears or timeout
        t0 = time.time()
        while time.time() - t0 < self._user_timeout:
            await asyncio.sleep(self._poll)
            remaining = await self.detect(page)
            if remaining == CaptchaType.NONE:
                result.solved = True
                result.solve_time_seconds = time.time() - t0
                log.info("CAPTCHA solved in %.1fs", result.solve_time_seconds)
                return result
            # Also check if page navigated away (sometimes indicates solved)
            try:
                await page.wait_for_load_state("networkidle", timeout=1000)
                if await self.detect(page) == CaptchaType.NONE:
                    result.solved = True
                    result.solve_time_seconds = time.time() - t0
                    return result
            except Exception:
                pass

        result.error = f"CAPTCHA not solved within {self._user_timeout}s timeout"
        log.error(result.error)
        return result

    async def handle_cloudflare_wait(self, page, max_wait: float = 30.0) -> bool:
        """Wait for Cloudflare JS challenge to auto-resolve (no interaction needed)."""
        t0 = time.time()
        while time.time() - t0 < max_wait:
            ctype = await self.detect(page)
            if ctype not in (CaptchaType.CLOUDFLARE, CaptchaType.TURNSTILE):
                return True
            await asyncio.sleep(2.0)
        return False
