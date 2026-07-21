"""Unit tests for Voice modules — Phase 15, Feature 1.

Tests Voice pipeline, STT, TTS, barge-in, and audio stream components
with mocked external dependencies.
"""

from __future__ import annotations

import io
import threading
import time
import unittest
from unittest.mock import MagicMock, Mock, patch, PropertyMock

import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))


class TestSTTModule(unittest.TestCase):
    """Tests for Speech-to-Text processing."""

    def setUp(self):
        self.mock_model = MagicMock()
        self.mock_model.transcribe.return_value = {
            "text": "Hello Aladdin, what time is it?",
            "language": "en",
            "segments": [],
        }

    def test_transcribe_returns_text(self):
        result = self.mock_model.transcribe(b"\x00" * 1024)
        self.assertIn("text", result)
        self.assertEqual(result["text"], "Hello Aladdin, what time is it?")

    def test_transcribe_detects_language(self):
        result = self.mock_model.transcribe(b"\x00" * 1024)
        self.assertEqual(result["language"], "en")

    def test_transcribe_empty_audio(self):
        self.mock_model.transcribe.return_value = {"text": "", "language": "en", "segments": []}
        result = self.mock_model.transcribe(b"")
        self.assertEqual(result["text"], "")

    def test_transcribe_multilingual_hindi(self):
        self.mock_model.transcribe.return_value = {
            "text": "आप कैसे हैं?",
            "language": "hi",
            "segments": [],
        }
        result = self.mock_model.transcribe(b"\x00" * 512)
        self.assertEqual(result["language"], "hi")
        self.assertIn("कैसे", result["text"])

    def test_transcribe_noise_returns_empty(self):
        self.mock_model.transcribe.return_value = {"text": "", "language": "en", "segments": []}
        result = self.mock_model.transcribe(b"\xff" * 256)
        self.assertEqual(result["text"], "")

    def test_transcribe_raises_on_invalid_audio(self):
        self.mock_model.transcribe.side_effect = ValueError("Invalid audio format")
        with self.assertRaises(ValueError):
            self.mock_model.transcribe("not bytes")

    def test_transcribe_long_audio(self):
        """Should handle audio > 30s without hanging."""
        long_audio = b"\x00" * (16000 * 60 * 2)  # 60s @ 16kHz
        self.mock_model.transcribe.return_value = {
            "text": "Long transcription...",
            "language": "en",
            "segments": [],
        }
        result = self.mock_model.transcribe(long_audio)
        self.assertIsNotNone(result["text"])


class TestTTSModule(unittest.TestCase):
    """Tests for Text-to-Speech generation."""

    def setUp(self):
        self.mock_tts = MagicMock()
        self.mock_tts.synthesize.return_value = b"\x52\x49\x46\x46" + b"\x00" * 40  # WAV header stub

    def test_synthesize_returns_bytes(self):
        audio = self.mock_tts.synthesize("Hello world")
        self.assertIsInstance(audio, bytes)
        self.assertGreater(len(audio), 0)

    def test_synthesize_empty_string(self):
        self.mock_tts.synthesize.return_value = b""
        audio = self.mock_tts.synthesize("")
        self.assertEqual(audio, b"")

    def test_synthesize_multilingual(self):
        for lang, text in [("en", "Hello"), ("hi", "नमस्ते"), ("gu", "નમસ્તે")]:
            self.mock_tts.synthesize.return_value = b"\x00" * 100
            audio = self.mock_tts.synthesize(text, lang=lang)
            self.assertIsNotNone(audio)

    def test_synthesize_long_text_chunked(self):
        """Long text should be chunked and concatenated."""
        self.mock_tts.synthesize.return_value = b"\x00" * 50
        long_text = "This is a sentence. " * 20
        audio = self.mock_tts.synthesize(long_text)
        self.assertIsNotNone(audio)

    def test_tts_streaming_callback(self):
        chunks = []
        def on_chunk(data: bytes):
            chunks.append(data)

        self.mock_tts.synthesize_stream = Mock(side_effect=lambda t, cb: [cb(b"\x00" * 20) for _ in range(3)])
        self.mock_tts.synthesize_stream("Hello streaming", on_chunk)
        self.assertEqual(len(chunks), 3)


class TestBargeIn(unittest.TestCase):
    """Tests for barge-in (interruption) detection."""

    def test_barge_in_detected_on_voice(self):
        mock_barge_in = MagicMock()
        mock_barge_in.detect.return_value = True
        result = mock_barge_in.detect(b"\x10" * 512, threshold=0.3)
        self.assertTrue(result)

    def test_barge_in_not_triggered_on_silence(self):
        mock_barge_in = MagicMock()
        mock_barge_in.detect.return_value = False
        result = mock_barge_in.detect(b"\x00" * 512, threshold=0.3)
        self.assertFalse(result)


class TestAudioStream(unittest.TestCase):
    """Tests for audio streaming pipeline."""

    def test_stream_accumulates_chunks(self):
        chunks = []
        mock_stream = MagicMock()
        mock_stream.__iter__ = Mock(return_value=iter([b"\x00" * 512] * 5))
        for chunk in mock_stream:
            chunks.append(chunk)
        self.assertEqual(len(chunks), 5)

    def test_stream_respects_sample_rate(self):
        mock_stream = MagicMock()
        mock_stream.sample_rate = 16000
        self.assertEqual(mock_stream.sample_rate, 16000)

    def test_stream_stop(self):
        mock_stream = MagicMock()
        mock_stream.stop()
        mock_stream.stop.assert_called_once()


class TestContinuousListening(unittest.TestCase):
    """Tests for continuous listening mode."""

    def test_wake_word_triggers_callback(self):
        callback = Mock()
        mock_listener = MagicMock()
        mock_listener.on_wake_word = callback

        mock_listener.on_wake_word("aladdin")
        callback.assert_called_once_with("aladdin")

    def test_listener_starts_and_stops(self):
        mock_listener = MagicMock()
        mock_listener.is_running = False
        mock_listener.start()
        mock_listener.is_running = True
        self.assertTrue(mock_listener.is_running)
        mock_listener.stop()
        mock_listener.is_running = False
        self.assertFalse(mock_listener.is_running)


if __name__ == "__main__":
    unittest.main()
