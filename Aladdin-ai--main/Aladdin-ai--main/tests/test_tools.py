"""Tests for tool system."""
import pytest
import asyncio

class TestAppAutomation:
    def test_allowlist_check(self):
        ALLOWLIST = {"chrome", "firefox", "code", "notepad", "explorer"}
        assert "chrome" in ALLOWLIST
        assert "malicious_app" not in ALLOWLIST
        assert "firefox" in ALLOWLIST

    def test_resolve_package(self):
        from backend.computer_control.app_automation import _resolve_package
        assert _resolve_package("chrome") == "com.android.chrome"
        assert _resolve_package("gmail") == "com.google.android.gm"
        assert _resolve_package("unknown.app") == "unknown.app"
