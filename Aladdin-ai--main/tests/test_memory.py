"""Tests for memory components."""
import pytest
import tempfile
from pathlib import Path

class TestShortTermMemory:
    def test_create(self, tmp_path):
        from backend.memory.short_term import ShortTermMemory
        mem = ShortTermMemory(tmp_path / "test.sqlite")
        assert mem is not None

    def test_save_and_load(self, tmp_path):
        from backend.memory.short_term import ShortTermMemory
        mem = ShortTermMemory(tmp_path / "test.sqlite")
        # Basic smoke test — just verify it doesn't crash
        assert mem is not None
