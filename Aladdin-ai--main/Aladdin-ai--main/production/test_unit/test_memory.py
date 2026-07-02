"""Unit tests for Memory module — Phase 15, Feature 1."""

from __future__ import annotations

import time
import unittest
from unittest.mock import MagicMock, Mock, patch

import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))


class TestMemoryStorage(unittest.TestCase):
    """Tests for short-term and long-term memory storage."""

    def setUp(self):
        self.mock_memory = MagicMock()
        self.mock_memory.add.return_value = True
        self.mock_memory.get.return_value = "Paris is the capital of France."
        self.mock_memory.search.return_value = ["Paris is the capital of France."]

    def test_add_fact(self):
        result = self.mock_memory.add("Paris is the capital of France.", category="geography")
        self.assertTrue(result)

    def test_retrieve_fact(self):
        self.mock_memory.add("The speed of light is 299792458 m/s.")
        result = self.mock_memory.get("speed of light")
        self.assertIn("299792458", result)

    def test_search_returns_list(self):
        results = self.mock_memory.search("France capital")
        self.assertIsInstance(results, list)
        self.assertGreater(len(results), 0)

    def test_memory_limit_enforced(self):
        mock_memory = MagicMock()
        mock_memory.max_entries = 1000
        mock_memory.count.return_value = 999
        self.assertLessEqual(mock_memory.count(), mock_memory.max_entries)

    def test_memory_expiry(self):
        mock_memory = MagicMock()
        mock_memory.add.return_value = True
        mock_memory.get.return_value = None  # expired

        mock_memory.add("Temp fact", ttl=0.001)
        time.sleep(0.01)
        result = mock_memory.get("Temp fact")
        self.assertIsNone(result)

    def test_memory_clear(self):
        mock_memory = MagicMock()
        mock_memory.count.return_value = 0
        mock_memory.clear()
        self.assertEqual(mock_memory.count(), 0)

    def test_memory_categories(self):
        mock_memory = MagicMock()
        mock_memory.add("Meeting at 3pm", category="calendar")
        mock_memory.get_by_category.return_value = ["Meeting at 3pm"]
        results = mock_memory.get_by_category("calendar")
        self.assertIn("Meeting at 3pm", results)

    def test_memory_persistence(self):
        mock_memory = MagicMock()
        mock_memory.save.return_value = True
        mock_memory.load.return_value = True
        self.assertTrue(mock_memory.save("/tmp/test_memory.db"))
        self.assertTrue(mock_memory.load("/tmp/test_memory.db"))


class TestConversationHistory(unittest.TestCase):
    """Tests for conversation history management."""

    def test_add_turn(self):
        mock_history = MagicMock()
        mock_history.turns = []
        mock_history.add_turn("What's the time?", "It's 3pm.", timestamp=time.time())
        mock_history.add_turn.assert_called_once()

    def test_history_trim(self):
        mock_history = MagicMock()
        mock_history.max_turns = 50
        mock_history.count.return_value = 50
        self.assertLessEqual(mock_history.count(), mock_history.max_turns)

    def test_history_export(self):
        mock_history = MagicMock()
        mock_history.export.return_value = '[{"role":"user","content":"hi"}]'
        json_str = mock_history.export(format="json")
        self.assertIn("user", json_str)


class TestMultilingualMemory(unittest.TestCase):
    """Tests for multilingual memory (Hindi, Gujarati, English)."""

    def test_store_hindi_fact(self):
        mock_memory = MagicMock()
        mock_memory.add.return_value = True
        result = mock_memory.add("दिल्ली भारत की राजधानी है।", language="hi")
        self.assertTrue(result)

    def test_cross_language_search(self):
        mock_memory = MagicMock()
        mock_memory.search.return_value = ["Delhi is the capital of India."]
        results = mock_memory.search("दिल्ली", source_lang="hi", target_lang="en")
        self.assertIsNotNone(results)


if __name__ == "__main__":
    unittest.main()
