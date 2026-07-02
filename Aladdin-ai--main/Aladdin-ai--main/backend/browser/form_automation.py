"""
Phase 8.5 — Form Automation
=============================
Auto-identify forms, fill text/dropdowns/checkboxes/radio buttons,
detect validation errors, verify required fields, confirm submission result.
"""
from __future__ import annotations
import asyncio, logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


@dataclass
class FieldResult:
    name: str
    filled: bool
    value: str = ""
    error: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {"name": self.name, "filled": self.filled, "value": self.value, "error": self.error}


@dataclass
class FormResult:
    success: bool
    fields_filled: int = 0
    fields_failed: int = 0
    validation_errors: List[str] = field(default_factory=list)
    submitted: bool = False
    post_url: str = ""
    details: List[Dict] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "fields_filled": self.fields_filled,
            "fields_failed": self.fields_failed,
            "validation_errors": self.validation_errors,
            "submitted": self.submitted,
            "post_url": self.post_url,
            "details": self.details,
        }


class FormAutomator:
    """
    Detect and fill HTML forms intelligently.

    Usage::

        fa = FormAutomator(page)
        forms = await fa.detect_forms()
        result = await fa.fill_and_submit(
            data={"email": "user@example.com", "password": "secret"},
            submit=True
        )
    """

    def __init__(self, page, human_typing: bool = True) -> None:
        self._page = page
        self._human = human_typing

    # ── Discovery ─────────────────────────────────────────────────────────────

    async def detect_forms(self) -> List[Dict[str, Any]]:
        """Return metadata about all forms on the page."""
        js = """
() => {
    return Array.from(document.querySelectorAll('form')).map((form, fi) => {
        const fields = Array.from(form.querySelectorAll('input,select,textarea')).map(el => ({
            type: el.type || el.tagName.toLowerCase(),
            name: el.name || el.id || '',
            id: el.id || '',
            placeholder: el.placeholder || '',
            required: el.required,
            value: el.value || '',
            options: el.tagName === 'SELECT'
                ? Array.from(el.options).map(o => ({value: o.value, text: o.text}))
                : [],
        }));
        return {
            form_index: fi,
            action: form.action || '',
            method: form.method || 'get',
            field_count: fields.length,
            fields: fields,
        };
    });
}
"""
        try:
            return await self._page.evaluate(js)
        except Exception as exc:
            log.error("detect_forms error: %s", exc)
            return []

    async def get_required_fields(self) -> List[str]:
        """Return names of required form fields."""
        js = """
() => Array.from(document.querySelectorAll('input[required],select[required],textarea[required]'))
       .map(el => el.name || el.id || el.placeholder || '?')
"""
        try:
            return await self._page.evaluate(js)
        except Exception:
            return []

    # ── Field filling ─────────────────────────────────────────────────────────

    async def fill_field(self, name_or_selector: str, value: str) -> FieldResult:
        """Fill a single field; auto-detects type."""
        selectors = [
            f"input[name='{name_or_selector}']",
            f"input[id='{name_or_selector}']",
            f"textarea[name='{name_or_selector}']",
            f"select[name='{name_or_selector}']",
            f"[placeholder*='{name_or_selector}']",
            f"#{name_or_selector}",
            name_or_selector,  # as-is CSS
        ]
        for sel in selectors:
            try:
                el = await self._page.query_selector(sel)
                if el is None or not await el.is_visible():
                    continue
                tag = await el.evaluate("el => el.tagName.toLowerCase()")
                el_type = await el.evaluate("el => (el.type || '').toLowerCase()")

                if tag == "select":
                    await el.select_option(value=value)
                elif el_type == "checkbox":
                    checked = await el.is_checked()
                    should_check = value.lower() in ("true", "yes", "1", "on", "check")
                    if checked != should_check:
                        await el.click()
                elif el_type == "radio":
                    await el.click()
                else:
                    await el.triple_click()
                    if self._human:
                        for char in value:
                            await el.type(char)
                            await asyncio.sleep(0.04)
                    else:
                        await el.fill(value)
                return FieldResult(name=name_or_selector, filled=True, value=value)
            except Exception as exc:
                continue
        return FieldResult(name=name_or_selector, filled=False,
                           error=f"Element not found: {name_or_selector}")

    async def fill_form(self, data: Dict[str, str],
                         delay_between: float = 0.3) -> List[FieldResult]:
        """Fill multiple fields from a name → value mapping."""
        results = []
        for name, value in data.items():
            r = await self.fill_field(name, value)
            results.append(r)
            if not r.filled:
                log.warning("Could not fill field '%s'", name)
            await asyncio.sleep(delay_between)
        return results

    # ── Submission ────────────────────────────────────────────────────────────

    async def submit(self, submit_selector: Optional[str] = None) -> bool:
        """Submit the form using the best available method."""
        selectors = []
        if submit_selector:
            selectors.append(submit_selector)
        selectors += [
            "button[type='submit']", "input[type='submit']",
            "button:has-text('Submit')", "button:has-text('Sign In')",
            "button:has-text('Log In')", "button:has-text('Login')",
            "button:has-text('Register')", "button:has-text('Sign Up')",
            "button:has-text('Continue')", "button:has-text('Next')",
            "button:has-text('Send')", "button:has-text('Save')",
            "form button", "form input[type='button']",
        ]
        for sel in selectors:
            try:
                el = await self._page.query_selector(sel)
                if el and await el.is_visible():
                    await el.click()
                    return True
            except Exception:
                continue
        # JS submit fallback
        try:
            await self._page.evaluate("() => { const f = document.querySelector('form'); if(f) f.submit(); }")
            return True
        except Exception:
            return False

    async def fill_and_submit(self, data: Dict[str, str],
                               submit: bool = True,
                               submit_selector: Optional[str] = None,
                               wait_navigation: bool = True) -> FormResult:
        """Complete workflow: fill all fields + submit + check for errors."""
        result = FormResult(success=False)
        fill_results = await self.fill_form(data)
        result.details = [r.to_dict() for r in fill_results]
        result.fields_filled = sum(1 for r in fill_results if r.filled)
        result.fields_failed = sum(1 for r in fill_results if not r.filled)

        if submit:
            submitted = await self.submit(submit_selector)
            result.submitted = submitted
            if submitted and wait_navigation:
                try:
                    await self._page.wait_for_load_state("networkidle", timeout=15000)
                except Exception:
                    pass
                result.post_url = self._page.url
                result.validation_errors = await self.get_validation_errors()

        result.success = (result.fields_filled > 0 and
                          result.fields_failed == 0 and
                          not result.validation_errors)
        return result

    # ── Validation errors ─────────────────────────────────────────────────────

    async def get_validation_errors(self) -> List[str]:
        """Collect visible validation/error messages on the page."""
        js = """
() => {
    const selectors = [
        '.error', '.alert-danger', '.invalid-feedback', '.form-error',
        '[class*="error"]', '[class*="invalid"]', '[role="alert"]',
        '.field-error', '#error-message', '.validation-message',
    ];
    const msgs = new Set();
    selectors.forEach(sel => {
        document.querySelectorAll(sel).forEach(el => {
            const t = el.innerText.trim();
            if (t && t.length < 300) msgs.add(t);
        });
    });
    // Also check native browser validation
    document.querySelectorAll(':invalid').forEach(el => {
        if (el.validationMessage) msgs.add(el.validationMessage);
    });
    return Array.from(msgs);
}
"""
        try:
            return await self._page.evaluate(js)
        except Exception:
            return []
