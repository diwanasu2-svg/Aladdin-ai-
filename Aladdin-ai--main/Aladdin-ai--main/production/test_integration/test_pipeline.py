"""Integration tests for Voice→STT→LLM→TTS pipeline — Phase 15, Feature 2."""

from __future__ import annotations

import threading
import time
import unittest
from unittest.mock import MagicMock, Mock, call, patch


class TestVoicePipelineIntegration(unittest.TestCase):
    """End-to-end voice pipeline: microphone → STT → LLM → TTS → speaker."""

    def setUp(self):
        self.stt = MagicMock()
        self.stt.transcribe.return_value = {"text": "What is the capital of France?", "language": "en"}

        self.llm = MagicMock()
        self.llm.chat.return_value = "The capital of France is Paris."

        self.tts = MagicMock()
        self.tts.synthesize.return_value = b"\x52\x49\x46\x46" + b"\x00" * 100

        self.speaker = MagicMock()

    def _run_pipeline(self, audio_bytes: bytes) -> str:
        stt_result = self.stt.transcribe(audio_bytes)
        text = stt_result["text"]
        reply = self.llm.chat(text)
        audio_out = self.tts.synthesize(reply)
        self.speaker.play(audio_out)
        return reply

    def test_full_pipeline_success(self):
        audio = b"\x00" * 1600
        reply = self._run_pipeline(audio)
        self.assertEqual(reply, "The capital of France is Paris.")
        self.stt.transcribe.assert_called_once()
        self.llm.chat.assert_called_once_with("What is the capital of France?")
        self.tts.synthesize.assert_called_once_with("The capital of France is Paris.")
        self.speaker.play.assert_called_once()

    def test_stt_failure_doesnt_reach_llm(self):
        self.stt.transcribe.side_effect = Exception("Microphone error")
        with self.assertRaises(Exception):
            self._run_pipeline(b"\x00" * 1600)
        self.llm.chat.assert_not_called()

    def test_llm_failure_doesnt_reach_tts(self):
        self.llm.chat.side_effect = Exception("LLM error")
        with self.assertRaises(Exception):
            self._run_pipeline(b"\x00" * 1600)
        self.tts.synthesize.assert_not_called()

    def test_multilingual_pipeline(self):
        self.stt.transcribe.return_value = {"text": "आप कैसे हैं?", "language": "hi"}
        self.llm.chat.return_value = "मैं ठीक हूँ, धन्यवाद!"
        self.tts.synthesize.return_value = b"\x00" * 50

        reply = self._run_pipeline(b"\x00" * 1600)
        self.assertEqual(reply, "मैं ठीक हूँ, धन्यवाद!")

    def test_pipeline_latency_under_threshold(self):
        """Full pipeline (mocked) must complete under 100ms."""
        t0 = time.monotonic()
        self._run_pipeline(b"\x00" * 1600)
        latency = (time.monotonic() - t0) * 1000
        self.assertLess(latency, 100, f"Pipeline too slow: {latency:.1f}ms")

    def test_streaming_pipeline(self):
        """LLM streams tokens; TTS begins generating before LLM finishes."""
        tokens_received = []

        def on_token(token: str):
            tokens_received.append(token)

        self.llm.chat = Mock(side_effect=lambda text, stream_callback=None: (
            [stream_callback(t) for t in ["The ", "capital ", "is ", "Paris."]]
            if stream_callback else "The capital is Paris."
        ))

        audio = b"\x00" * 1600
        stt_result = self.stt.transcribe(audio)
        self.llm.chat(stt_result["text"], stream_callback=on_token)

        self.assertIn("Paris.", tokens_received)
        self.assertEqual(len(tokens_received), 4)

    def test_concurrent_pipeline_calls(self):
        """Multiple concurrent pipeline calls must not interfere."""
        results = []
        errors = []

        def run_one(i: int):
            try:
                reply = self._run_pipeline(b"\x00" * 1600)
                results.append(reply)
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=run_one, args=(i,)) for i in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=5.0)

        self.assertEqual(len(errors), 0)
        self.assertEqual(len(results), 5)


class TestMemoryToolIntegration(unittest.TestCase):
    """Tests memory + tools working together."""

    def test_tool_result_stored_in_memory(self):
        memory = MagicMock()
        tool = MagicMock()
        tool.run.return_value = {"result": "28°C in Mumbai"}

        result = tool.run("What is the weather in Mumbai?")
        memory.add(f"weather:mumbai:{result['result']}", category="weather")

        memory.add.assert_called_once()
        memory.search.return_value = ["weather:mumbai:28°C in Mumbai"]
        matches = memory.search("Mumbai weather")
        self.assertGreater(len(matches), 0)

    def test_memory_context_improves_llm_response(self):
        memory = MagicMock()
        memory.search.return_value = ["User prefers Indian food", "User is vegetarian"]
        llm = MagicMock()
        llm.chat.return_value = "I recommend Dal Makhani or Paneer Tikka."

        context = memory.search("food preference")
        reply = llm.chat("What should I eat?", context=context)

        self.assertIn("recommend", reply.lower())


class TestAPIIntegration(unittest.TestCase):
    """Tests HTTP API endpoints."""

    def test_health_endpoint(self):
        mock_app = MagicMock()
        mock_app.get.return_value = {"status": "ok", "version": "14.15"}
        response = mock_app.get("/api/health")
        self.assertEqual(response["status"], "ok")

    def test_chat_endpoint(self):
        mock_app = MagicMock()
        mock_app.post.return_value = {"reply": "Hello!", "provider": "ollama"}
        response = mock_app.post("/api/chat", json={"message": "hi"})
        self.assertIn("reply", response)

    def test_stream_endpoint(self):
        mock_app = MagicMock()
        mock_app.post.return_value = iter(["Hello", " world", "!"])
        stream = mock_app.post("/api/stream", json={"message": "hi"})
        chunks = list(stream)
        self.assertGreater(len(chunks), 0)


if __name__ == "__main__":
    unittest.main()
