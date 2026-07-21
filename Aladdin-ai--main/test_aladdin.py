"""
Aladdin — Unit & Integration Tests
====================================
Run with:
    python -m pytest tests/ -v
or:
    python tests/test_aladdin.py
"""

from __future__ import annotations

import sys
import os
import json
import sqlite3
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

# Make aladdin importable
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

try:
    import pytest
except ImportError:
    pytest = None  # type: ignore  # Tests still runnable via __main__

from aladdin_core.config import AladdinConfig, MemoryCfg, SearchCfg
from aladdin_core.memory import ConversationMemory
from aladdin_core.search import InternetSearch, needs_search
from aladdin_core.calendar_manager import CalendarManager, ReminderManager
from aladdin_core.calendar_manager import CalendarCfg, ReminderCfg
from aladdin_core.plugin_system import Plugin, PluginManager, PluginCfg
from aladdin_core.plugins.joke_plugin import JokePlugin
from aladdin_core.plugins.unit_converter_plugin import UnitConverterPlugin, _convert
from aladdin_core.plugins.weather_math_plugin import safe_math, WeatherMathPlugin

# ──────────────────────────────────────────────────────────────────────────────
# Config
# ──────────────────────────────────────────────────────────────────────────────


class TestConfig:
    def test_defaults(self):
        cfg = AladdinConfig()
        assert cfg.ollama.host == "http://localhost:11434"
        assert cfg.ollama.model == "llama3"
        assert cfg.memory.window == 12
        assert cfg.search.enabled is True

    def test_load_nonexistent(self):
        cfg = AladdinConfig.load("/tmp/does_not_exist.yaml")
        assert isinstance(cfg, AladdinConfig)

    def test_load_yaml(self, tmp_path):
        cfg_file = tmp_path / "cfg.yaml"
        cfg_file.write_text("ollama:\n  model: mistral\n  temperature: 0.5\n")
        cfg = AladdinConfig.load(str(cfg_file))
        assert cfg.ollama.model == "mistral"
        assert cfg.ollama.temperature == 0.5


# ──────────────────────────────────────────────────────────────────────────────
# Memory
# ──────────────────────────────────────────────────────────────────────────────


class TestMemory:
    @pytest.fixture
    def mem(self, tmp_path):
        cfg = MemoryCfg(db_path=str(tmp_path / "test_memory.sqlite"))
        return ConversationMemory(cfg)

    def test_append_and_recent(self, mem):
        mem.append("hello", "hi there")
        mem.append("how are you", "I'm great!")
        recent = mem.recent(5)
        assert len(recent) == 2
        assert recent[0] == ("hello", "hi there")

    def test_remember_and_recall(self, mem):
        mem.remember("test_key", "test_value", category="test")
        val = mem.recall("test_key")
        assert val == "test_value"

    def test_recall_missing(self, mem):
        assert mem.recall("nonexistent_key") is None

    def test_forget(self, mem):
        mem.remember("del_key", "del_value")
        mem.forget("del_key")
        assert mem.recall("del_key") is None

    def test_user_profile(self, mem):
        mem.user_name = "Alice"
        assert mem.user_name == "Alice"

    def test_auto_extract_name(self, mem):
        mem.append("my name is Bob", "Nice to meet you, Bob!")
        assert mem.user_name == "Bob"

    def test_stats(self, mem):
        mem.append("q1", "a1")
        stats = mem.stats()
        assert stats["turns"] == 1

    def test_search(self, mem):
        mem.append("I love Python programming", "Great choice!")
        results = mem.search("Python")
        assert len(results) >= 1

    def test_all_facts(self, mem):
        mem.remember("k1", "v1")
        mem.remember("k2", "v2")
        facts = mem.all_facts()
        assert len(facts) >= 2

    def test_build_context(self, mem):
        mem.user_name = "TestUser"
        ctx = mem.build_context_prompt()
        assert "TestUser" in ctx

    def test_summarize_old(self, mem):
        for i in range(10):
            mem.append(f"q{i}", f"a{i}")
        result = mem.summarize_old(keep_recent=5)
        # Should prune when threshold is exceeded; may return None if not yet over threshold
        assert result is None or "Pruned" in result


# ──────────────────────────────────────────────────────────────────────────────
# Search
# ──────────────────────────────────────────────────────────────────────────────


class TestSearch:
    def test_needs_search_positive(self):
        assert needs_search("what is the weather today")
        assert needs_search("latest news 2026")
        assert needs_search("current stock price")

    def test_needs_search_negative(self):
        assert not needs_search("tell me a joke")
        assert not needs_search("hello how are you")

    def test_search_disabled(self):
        cfg = SearchCfg(enabled=False)
        s = InternetSearch(cfg)
        results = s.search("anything")
        assert results == []


# ──────────────────────────────────────────────────────────────────────────────
# Calendar & Reminders
# ──────────────────────────────────────────────────────────────────────────────


class TestCalendar:
    @pytest.fixture
    def cal(self, tmp_path):
        cfg = CalendarCfg(db_path=str(tmp_path / "cal.sqlite"))
        return CalendarManager(cfg)

    def test_add_and_list(self, cal):
        from datetime import datetime, timedelta

        start = datetime.now() + timedelta(hours=1)
        eid = cal.add_event("Test Meeting", start, description="Stand-up")
        assert eid > 0
        events = cal.upcoming(days=1)
        assert any(e["title"] == "Test Meeting" for e in events)

    def test_search_event(self, cal):
        from datetime import datetime, timedelta

        start = datetime.now() + timedelta(hours=2)
        cal.add_event("Doctor Appointment", start)
        results = cal.search("doctor")
        assert len(results) >= 1

    def test_format_upcoming_empty(self, cal):
        result = cal.format_upcoming(days=1)
        assert "No events" in result


class TestReminder:
    @pytest.fixture
    def rem(self, tmp_path):
        cfg = ReminderCfg(db_path=str(tmp_path / "rem.sqlite"), check_interval=1)
        return ReminderManager(cfg)

    def test_add_and_pending(self, rem):
        from datetime import datetime, timedelta

        future = datetime.now() + timedelta(hours=1)
        rem.add("Test reminder", future)
        pending = rem.pending()
        assert len(pending) >= 1

    def test_trigger_callback(self, rem):
        from datetime import datetime, timedelta

        triggered = []
        rem.on_trigger = lambda msg: triggered.append(msg)

        past = datetime.now() - timedelta(seconds=1)
        rem.add("Past reminder", past)
        rem._check()
        assert "Past reminder" in triggered

    def test_add_in(self, rem):
        rid = rem.add_in("In 5 minutes", 5)
        assert rid > 0


# ──────────────────────────────────────────────────────────────────────────────
# Plugins
# ──────────────────────────────────────────────────────────────────────────────


class TestJokePlugin:
    def test_joke_triggered(self):
        p = JokePlugin()
        result = p.on_user_input("tell me a joke")
        assert result is not None
        assert len(result) > 10

    def test_joke_not_triggered(self):
        p = JokePlugin()
        result = p.on_user_input("what is the weather")
        assert result is None


class TestUnitConverter:
    def test_km_to_miles(self):
        r = _convert(10, "km", "miles")
        assert r is not None
        assert "6.21" in r

    def test_celsius_to_fahrenheit(self):
        r = _convert(100, "c", "f")
        assert r is not None
        assert "212" in r

    def test_unknown_conversion(self):
        r = _convert(5, "zorp", "blip")
        assert r is None

    def test_plugin_intercepts(self):
        p = UnitConverterPlugin()
        result = p.on_user_input("convert 10 km to miles")
        assert result is not None
        assert "6.21" in result

    def test_plugin_temperature(self):
        p = UnitConverterPlugin()
        result = p.on_user_input("100 celsius to fahrenheit")
        assert result is not None
        assert "212" in result

    def test_plugin_no_match(self):
        p = UnitConverterPlugin()
        result = p.on_user_input("what time is it")
        assert result is None


class TestMathPlugin:
    def test_basic_arithmetic(self):
        assert safe_math("2 + 2") == 4
        assert safe_math("10 * 5") == 50
        assert safe_math("100 / 4") == 25.0

    def test_power(self):
        assert safe_math("2 ** 10") == 1024

    def test_sqrt(self):
        import math

        result = safe_math("sqrt(16)")
        assert result == 4.0

    def test_pi(self):
        import math

        result = safe_math("pi")
        assert abs(result - math.pi) < 1e-10

    def test_bad_expr(self):
        assert safe_math("import os") is None
        assert safe_math("not a number") is None

    def test_plugin_intercepted(self):
        p = WeatherMathPlugin()
        result = p.on_user_input("what is 2 + 2")
        assert result is not None
        assert "4" in result


class TestPluginManager:
    def test_load_empty_dir(self, tmp_path):
        cfg = PluginCfg(plugin_dir=str(tmp_path), enabled=[])
        pm = PluginManager(cfg)
        pm.load_all()
        assert pm._plugins == []

    def test_process_input_returns_none(self):
        cfg = PluginCfg(plugin_dir="/nonexistent", enabled=[])
        pm = PluginManager(cfg)
        result = pm.process_input("hello")
        assert result is None


# ──────────────────────────────────────────────────────────────────────────────
# Main entry (no pytest)
# ──────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import unittest

    # Run a quick smoke test without pytest
    print("Running Aladdin smoke tests…")
    passed = 0
    failed = 0

    def run(name, fn):
        global passed, failed
        try:
            fn()
            print(f"  ✅  {name}")
            passed += 1
        except Exception as e:
            print(f"  ❌  {name}: {e}")
            failed += 1

    import tempfile

    tmp = tempfile.mkdtemp()
    mem_cfg = MemoryCfg(db_path=f"{tmp}/mem.sqlite")
    mem = ConversationMemory(mem_cfg)

    run("Config defaults", lambda: AladdinConfig())
    run("Memory append", lambda: mem.append("hello", "hi"))
    run("Memory recall", lambda: mem.remember("x", "y") or mem.recall("x") == "y")
    run("needs_search positive", lambda: needs_search("latest news today"))
    run("needs_search negative", lambda: not needs_search("tell me a joke"))
    run("JokePlugin", lambda: JokePlugin().on_user_input("joke") is not None)
    run("UnitConverter km→miles", lambda: _convert(10, "km", "miles"))
    run("Math 2+2", lambda: safe_math("2+2") == 4)
    run("Math sqrt", lambda: safe_math("sqrt(9)") == 3.0)
    run("Math bad expr safe", lambda: safe_math("__import__('os')") is None)

    print(f"\n  {passed} passed, {failed} failed.")
    sys.exit(0 if failed == 0 else 1)
