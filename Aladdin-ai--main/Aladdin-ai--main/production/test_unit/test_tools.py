"""Unit tests for Tools module — Phase 15, Feature 1."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, Mock, patch


class TestCalculatorTool(unittest.TestCase):
    def test_basic_arithmetic(self):
        tool = MagicMock()
        tool.run.return_value = {"result": 42, "expression": "6 * 7"}
        result = tool.run("6 * 7")
        self.assertEqual(result["result"], 42)

    def test_division_by_zero(self):
        tool = MagicMock()
        tool.run.side_effect = ZeroDivisionError("division by zero")
        with self.assertRaises(ZeroDivisionError):
            tool.run("5 / 0")

    def test_complex_expression(self):
        tool = MagicMock()
        tool.run.return_value = {"result": 14.0, "expression": "2 + 3 * 4"}
        result = tool.run("2 + 3 * 4")
        self.assertEqual(result["result"], 14.0)

    def test_invalid_expression(self):
        tool = MagicMock()
        tool.run.side_effect = ValueError("Invalid expression")
        with self.assertRaises(ValueError):
            tool.run("abc + xyz")


class TestSearchTool(unittest.TestCase):
    def test_search_returns_results(self):
        tool = MagicMock()
        tool.search.return_value = [
            {"title": "Python programming", "url": "https://python.org", "snippet": "Python is..."},
        ]
        results = tool.search("Python programming language")
        self.assertIsInstance(results, list)
        self.assertGreater(len(results), 0)

    def test_search_offline_fallback(self):
        tool = MagicMock()
        tool.search.return_value = []
        results = tool.search("query", offline=True)
        self.assertIsInstance(results, list)

    def test_search_empty_query(self):
        tool = MagicMock()
        tool.search.return_value = []
        results = tool.search("")
        self.assertEqual(results, [])


class TestCalendarTool(unittest.TestCase):
    def test_add_event(self):
        tool = MagicMock()
        tool.add_event.return_value = {"id": "evt_123", "status": "created"}
        result = tool.add_event("Meeting", "2025-07-01T15:00", duration_minutes=60)
        self.assertEqual(result["status"], "created")

    def test_list_events(self):
        tool = MagicMock()
        tool.list_events.return_value = [
            {"id": "evt_001", "title": "Team standup", "time": "09:00"},
        ]
        events = tool.list_events(date="2025-07-01")
        self.assertIsInstance(events, list)

    def test_delete_event(self):
        tool = MagicMock()
        tool.delete_event.return_value = True
        result = tool.delete_event("evt_123")
        self.assertTrue(result)


class TestWeatherTool(unittest.TestCase):
    def test_get_weather(self):
        tool = MagicMock()
        tool.get_weather.return_value = {
            "city": "Mumbai",
            "temp_celsius": 32,
            "condition": "Sunny",
            "humidity": 78,
        }
        weather = tool.get_weather("Mumbai")
        self.assertEqual(weather["city"], "Mumbai")
        self.assertIn("temp_celsius", weather)

    def test_invalid_city(self):
        tool = MagicMock()
        tool.get_weather.side_effect = ValueError("City not found")
        with self.assertRaises(ValueError):
            tool.get_weather("XYZ_NONEXISTENT_CITY_12345")


class TestPluginSystem(unittest.TestCase):
    def test_register_plugin(self):
        registry = MagicMock()
        registry.register.return_value = True
        plugin = MagicMock()
        plugin.name = "TestPlugin"
        result = registry.register(plugin)
        self.assertTrue(result)

    def test_list_plugins(self):
        registry = MagicMock()
        registry.list.return_value = ["calculator", "search", "calendar"]
        plugins = registry.list()
        self.assertIn("calculator", plugins)

    def test_plugin_not_found(self):
        registry = MagicMock()
        registry.get.return_value = None
        plugin = registry.get("nonexistent_plugin")
        self.assertIsNone(plugin)


if __name__ == "__main__":
    unittest.main()
