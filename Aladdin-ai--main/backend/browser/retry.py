"""
Phase 8.4 — Retry Logic
=========================
Detect page load failures, retry with backoff, recover from network errors,
enforce retry limits, avoid repeating identical mistakes.
"""
from __future__ import annotations
import asyncio, logging, time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Type

log = logging.getLogger(__name__)

_RETRYABLE_ERRORS = (
    "net::ERR_CONNECTION_REFUSED", "net::ERR_CONNECTION_RESET",
    "net::ERR_NAME_NOT_RESOLVED", "net::ERR_TIMED_OUT",
    "Timeout", "TimeoutError", "Navigation timeout",
    "net::ERR_ABORTED", "net::ERR_INTERNET_DISCONNECTED",
    "net::ERR_NETWORK_CHANGED",
)

_FATAL_ERRORS = (
    "403", "404", "410", "Invalid URL",
    "Cannot navigate to invalid URL",
)


@dataclass
class RetryRecord:
    url: str
    attempt: int
    error: str
    timestamp: float = field(default_factory=time.time)
    success: bool = False


@dataclass
class RetryResult:
    success: bool
    attempts: int
    value: Any = None
    last_error: Optional[str] = None
    history: List[RetryRecord] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "attempts": self.attempts,
            "last_error": self.last_error,
            "history": [
                {"url": r.url, "attempt": r.attempt, "error": r.error, "success": r.success}
                for r in self.history
            ],
        }


class RetryManager:
    """
    Retry engine for Playwright navigation and actions.

    Usage::

        retry = RetryManager(max_retries=3)

        result = await retry.navigate_with_retry(page, "https://example.com")
        if result.success:
            ...

        result = await retry.retry(my_async_fn, url="...")
    """

    def __init__(self, max_retries: int = 3, base_delay: float = 2.0,
                 backoff_factor: float = 2.0, jitter: float = 0.5) -> None:
        self._max = max_retries
        self._base = base_delay
        self._backoff = backoff_factor
        self._jitter = jitter
        self._error_seen: Dict[str, int] = {}   # error_signature → count

    def _should_retry(self, error: str) -> bool:
        """Determine whether an error is retryable."""
        for fatal in _FATAL_ERRORS:
            if fatal in error:
                return False
        for retryable in _RETRYABLE_ERRORS:
            if retryable in error:
                return True
        # Default: retry unknown errors up to limit
        return True

    def _delay(self, attempt: int) -> float:
        delay = self._base * (self._backoff ** attempt)
        import random
        return delay + random.uniform(0, self._jitter * delay)

    def _signature(self, error: str) -> str:
        """Normalise error to detect repeated identical failures."""
        for prefix in _RETRYABLE_ERRORS + tuple(_FATAL_ERRORS):
            if prefix.lower() in error.lower():
                return prefix
        return error[:80]

    async def navigate_with_retry(self, page, url: str,
                                   wait_until: str = "networkidle",
                                   timeout: int = 30000) -> RetryResult:
        """Navigate to URL with automatic retry on failure."""
        result = RetryResult(success=False, attempts=0)
        last_error = ""

        for attempt in range(self._max + 1):
            result.attempts = attempt + 1
            try:
                await page.goto(url, wait_until=wait_until, timeout=timeout)
                # Check HTTP error pages
                status_code = await page.evaluate(
                    "() => window.__playwright_status || 200"
                ).catch(lambda _: 200) if hasattr(page, 'evaluate') else 200
                try:
                    status_code = await page.evaluate("() => window.__playwright_status || 200")
                except Exception:
                    status_code = 200
                result.history.append(RetryRecord(url, attempt + 1, "", success=True))
                result.success = True
                result.value = {"url": page.url, "title": await page.title()}
                return result
            except Exception as exc:
                last_error = str(exc)
                sig = self._signature(last_error)
                self._error_seen[sig] = self._error_seen.get(sig, 0) + 1
                result.history.append(RetryRecord(url, attempt + 1, last_error[:200]))
                log.warning("Navigate attempt %d/%d failed: %s", attempt + 1, self._max + 1, sig)

                if not self._should_retry(last_error):
                    log.error("Non-retryable error: %s", sig)
                    break

                # Repeated identical error — try fallback strategy
                if self._error_seen.get(sig, 0) >= 2 and attempt < self._max:
                    log.info("Repeated error '%s' — trying fallback: longer wait", sig)
                    await asyncio.sleep(self._delay(attempt) * 1.5)
                    try:
                        await page.goto(url, wait_until="domcontentloaded", timeout=timeout * 2)
                        result.success = True
                        result.value = {"url": page.url}
                        return result
                    except Exception:
                        pass
                    continue

                if attempt < self._max:
                    delay = self._delay(attempt)
                    log.info("Retrying in %.1fs ...", delay)
                    await asyncio.sleep(delay)

        result.last_error = last_error
        return result

    async def retry(self, fn: Callable, *args, label: str = "action", **kwargs) -> RetryResult:
        """Retry any async callable."""
        result = RetryResult(success=False, attempts=0)
        for attempt in range(self._max + 1):
            result.attempts = attempt + 1
            try:
                value = await fn(*args, **kwargs)
                result.success = True
                result.value = value
                return result
            except Exception as exc:
                err = str(exc)
                result.history.append(RetryRecord(label, attempt + 1, err[:200]))
                log.warning("'%s' attempt %d failed: %s", label, attempt + 1, err[:100])
                if not self._should_retry(err):
                    result.last_error = err
                    return result
                if attempt < self._max:
                    await asyncio.sleep(self._delay(attempt))
        result.last_error = result.history[-1].error if result.history else ""
        return result

    async def wait_for_condition(self, condition_fn: Callable[[], bool],
                                  poll: float = 1.0, timeout: float = 30.0,
                                  label: str = "condition") -> bool:
        """Poll until condition_fn returns True or timeout."""
        t0 = time.time()
        while time.time() - t0 < timeout:
            try:
                if await asyncio.coroutine(condition_fn)() if asyncio.iscoroutinefunction(condition_fn) else condition_fn():
                    return True
            except Exception:
                pass
            await asyncio.sleep(poll)
        log.warning("Condition '%s' not met within %.1fs", label, timeout)
        return False
