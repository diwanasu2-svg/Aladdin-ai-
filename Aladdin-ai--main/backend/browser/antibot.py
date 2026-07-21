"""
Phase 8.2 — Anti-Bot Evasion
==============================
Natural browser behaviour: realistic delays, mouse movements, scrolling,
proper User-Agent, cookie/session management, rate-limit handling,
fallback strategies when blocked.
"""
from __future__ import annotations
import asyncio, logging, math, random, time
from typing import Any, Dict, List, Optional, Tuple

log = logging.getLogger(__name__)

# Realistic desktop user agents
_USER_AGENTS: List[str] = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
]

_BLOCK_SIGNALS: List[str] = [
    "access denied", "403 forbidden", "bot detected", "automated request",
    "rate limit", "too many requests", "blocked", "security check",
    "captcha", "unusual traffic", "please verify", "temporarily blocked",
]


def _jitter(base: float, variance: float = 0.3) -> float:
    """Return base ± variance * base, min 50ms."""
    delta = base * variance * (2 * random.random() - 1)
    return max(0.05, base + delta)


class AntiBotEvasion:
    """
    Injects human-like behaviour into a Playwright page/context.

    Usage::

        evasion = AntiBotEvasion()
        await evasion.stealth_setup(page)
        await evasion.human_delay()
        await evasion.human_click(page, selector)
    """

    def __init__(self, min_delay: float = 0.5, max_delay: float = 2.5) -> None:
        self._min = min_delay
        self._max = max_delay
        self._request_times: List[float] = []
        self._rate_limit_delay = 5.0

    # ── Stealth setup ─────────────────────────────────────────────────────────

    async def stealth_setup(self, page) -> None:
        """Apply stealth patches to a Playwright page."""
        ua = random.choice(_USER_AGENTS)
        try:
            await page.set_extra_http_headers({"User-Agent": ua})
        except Exception:
            pass
        stealth_js = """
() => {
    // Remove webdriver flag
    Object.defineProperty(navigator, 'webdriver', {get: () => undefined});

    // Fake plugins
    Object.defineProperty(navigator, 'plugins', {
        get: () => [1, 2, 3, 4, 5],
    });

    // Fake languages
    Object.defineProperty(navigator, 'languages', {
        get: () => ['en-US', 'en'],
    });

    // Fake hardware concurrency
    Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8});

    // Fake device memory
    Object.defineProperty(navigator, 'deviceMemory', {get: () => 8});

    // Override chrome object
    window.chrome = {runtime: {}};

    // Permissions override
    const origQuery = window.navigator.permissions.query;
    window.navigator.permissions.query = (parameters) =>
        parameters.name === 'notifications'
            ? Promise.resolve({state: Notification.permission})
            : origQuery(parameters);
}
"""
        try:
            await page.add_init_script(stealth_js)
        except Exception as exc:
            log.debug("Stealth JS inject error: %s", exc)

    async def configure_context(self, context) -> None:
        """Configure a Playwright BrowserContext for stealth."""
        try:
            await context.set_extra_http_headers({
                "Accept-Language": "en-US,en;q=0.9",
                "Accept-Encoding": "gzip, deflate, br",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Upgrade-Insecure-Requests": "1",
            })
        except Exception as exc:
            log.debug("Context header config error: %s", exc)

    # ── Human delays ──────────────────────────────────────────────────────────

    async def human_delay(self, min_s: Optional[float] = None,
                           max_s: Optional[float] = None) -> None:
        """Sleep for a random human-like duration."""
        lo = min_s or self._min
        hi = max_s or self._max
        await asyncio.sleep(_jitter(random.uniform(lo, hi)))

    async def typing_delay(self, char_count: int) -> None:
        """Simulate typing speed: ~200-300ms per character with variance."""
        base = 0.05 + random.uniform(0, 0.15)
        await asyncio.sleep(base * char_count)

    # ── Human mouse movements ─────────────────────────────────────────────────

    async def human_move_to(self, page, x: float, y: float, steps: int = 10) -> None:
        """Move mouse in a curved path to (x, y)."""
        try:
            current = await page.evaluate("() => [window.mouseX || 0, window.mouseY || 0]")
        except Exception:
            current = [x - 100, y - 100]
        x0, y0 = current[0], current[1]
        # Bezier curve control point
        cx = (x0 + x) / 2 + random.uniform(-80, 80)
        cy = (y0 + y) / 2 + random.uniform(-80, 80)
        for i in range(1, steps + 1):
            t = i / steps
            mx = (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t ** 2 * x
            my = (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t ** 2 * y
            try:
                await page.mouse.move(mx, my)
            except Exception:
                break
            await asyncio.sleep(random.uniform(0.01, 0.04))

    async def human_click(self, page, selector: str) -> bool:
        """Click element after human-like mouse movement and delay."""
        try:
            el = await page.wait_for_selector(selector, timeout=5000)
            if el is None:
                return False
            box = await el.bounding_box()
            if box:
                tx = box["x"] + box["width"] * random.uniform(0.3, 0.7)
                ty = box["y"] + box["height"] * random.uniform(0.3, 0.7)
                await self.human_move_to(page, tx, ty)
                await asyncio.sleep(_jitter(0.1))
            await el.click()
            return True
        except Exception as exc:
            log.debug("Human click error on '%s': %s", selector, exc)
            return False

    async def human_type(self, page, selector: str, text: str) -> bool:
        """Type text with realistic per-character delays."""
        try:
            el = await page.wait_for_selector(selector, timeout=5000)
            if el is None:
                return False
            await el.click()
            await asyncio.sleep(_jitter(0.2))
            # Clear existing content
            await page.keyboard.press("Control+a")
            await asyncio.sleep(0.05)
            for char in text:
                await page.keyboard.type(char)
                await asyncio.sleep(_jitter(0.08, 0.5))
            return True
        except Exception as exc:
            log.debug("Human type error: %s", exc)
            return False

    async def human_scroll(self, page, direction: str = "down",
                            amount: int = 3, smooth: bool = True) -> None:
        """Scroll page naturally."""
        for _ in range(amount):
            pixels = random.randint(200, 500) * (1 if direction == "down" else -1)
            if smooth:
                for chunk in range(0, abs(pixels), 50):
                    delta = min(50, abs(pixels) - chunk) * (1 if direction == "down" else -1)
                    try:
                        await page.mouse.wheel(0, delta)
                    except Exception:
                        break
                    await asyncio.sleep(random.uniform(0.02, 0.06))
            else:
                try:
                    await page.mouse.wheel(0, pixels)
                except Exception:
                    pass
            await asyncio.sleep(random.uniform(0.3, 0.8))

    # ── Block detection ───────────────────────────────────────────────────────

    async def is_blocked(self, page) -> Tuple[bool, str]:
        """Check if the page shows a block/rate-limit response."""
        try:
            status = await page.evaluate("() => window.__playwright_status || 200")
        except Exception:
            status = 200
        if str(status) in ("403", "429", "503"):
            return True, f"HTTP {status}"
        try:
            body = (await page.evaluate("() => document.body.innerText || ''")).lower()
            for sig in _BLOCK_SIGNALS:
                if sig in body:
                    return True, sig
        except Exception:
            pass
        return False, ""

    async def handle_rate_limit(self, page, retry_fn=None, max_retries: int = 3) -> bool:
        """Wait and retry when rate-limited."""
        blocked, reason = await self.is_blocked(page)
        if not blocked:
            return True
        log.warning("Bot/rate-limit detected: %s", reason)
        for attempt in range(max_retries):
            wait = self._rate_limit_delay * (2 ** attempt) + random.uniform(1, 5)
            log.info("Waiting %.1fs before retry %d/%d", wait, attempt + 1, max_retries)
            await asyncio.sleep(wait)
            if retry_fn:
                try:
                    await retry_fn()
                except Exception:
                    pass
            blocked, reason = await self.is_blocked(page)
            if not blocked:
                return True
        return False

    def track_request(self) -> None:
        """Track request time for self-imposed rate limiting."""
        now = time.time()
        self._request_times = [t for t in self._request_times if now - t < 60]
        self._request_times.append(now)

    async def respect_rate_limit(self, rps: float = 1.0) -> None:
        """Ensure we do not exceed rps requests per second."""
        self.track_request()
        if len(self._request_times) > rps * 60:
            await asyncio.sleep(_jitter(1.0 / rps))
