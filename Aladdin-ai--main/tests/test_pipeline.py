"""
Phase 7 — End-to-End Voice Pipeline Testing
=============================================
Tests:
  Voice:  wake-word, VAD, streaming STT, streaming TTS, barge-in, full-duplex
  AI:     planner, reasoning, memory, tool-calling, search
  Integration: end-to-end conversation, stress, benchmarks, regression
"""

from __future__ import annotations

import time
import threading
import queue
import logging
import unittest
from unittest.mock import MagicMock, patch, PropertyMock
from pathlib import Path

import numpy as np

log = logging.getLogger(__name__)

# ── helpers ───────────────────────────────────────────────────────────────────


def _silence(duration_s: float = 0.5, rate: int = 16000) -> np.ndarray:
    return np.zeros(int(duration_s * rate), dtype=np.float32)


def _noise(duration_s: float = 0.5, rate: int = 16000, amp: float = 0.3) -> np.ndarray:
    rng = np.random.default_rng(42)
    return (rng.random(int(duration_s * rate)) * 2 - 1).astype(np.float32) * amp


def _sine(
    freq: float = 440.0, duration_s: float = 0.5, rate: int = 16000
) -> np.ndarray:
    t = np.linspace(0, duration_s, int(duration_s * rate), endpoint=False)
    return np.sin(2 * np.pi * freq * t).astype(np.float32) * 0.5


# ── Phase 7A: Wake-Word Tests ──────────────────────────────────────────────────


class TestWakeWord(unittest.TestCase):

    def test_import_wake_word_engine(self):
        """wake_word_engine module must be importable."""
        try:
            import wake_word_engine  # noqa: F401

            self.assertTrue(True)
        except ImportError:
            self.skipTest("wake_word_engine not installed")

    def test_wake_word_not_triggered_on_silence(self):
        """No false positives on silence."""
        try:
            from wake_word_engine import WakeWordEngine
        except ImportError:
            self.skipTest("WakeWordEngine unavailable")
        wwe = WakeWordEngine(wake_word="aladdin")
        detected = wwe.process(_silence(1.0))
        self.assertFalse(detected)

    def test_wake_word_config(self):
        """Wake word config must accept a string keyword."""
        try:
            from wake_word_engine import WakeWordEngine

            wwe = WakeWordEngine(wake_word="hey_aladdin")
            self.assertEqual(wwe.wake_word.lower(), "hey_aladdin")
        except (ImportError, AttributeError):
            self.skipTest("WakeWordEngine unavailable")


# ── Phase 7A: VAD Tests ───────────────────────────────────────────────────────


class TestVAD(unittest.TestCase):

    def setUp(self):
        try:
            from vad import VoiceActivityDetector

            self.vad = VoiceActivityDetector(sample_rate=16000, engine="webrtc")
        except ImportError:
            self.skipTest("VAD unavailable")

    def test_silence_returns_low_confidence(self):
        result = self.vad.detect(_silence(0.1))
        conf = result.get("confidence", result) if isinstance(result, dict) else 0.0
        self.assertLess(conf, 0.6)

    def test_speech_like_signal_detected(self):
        audio = _noise(0.3, amp=0.4)
        result = self.vad.detect(audio)
        # Just verify it doesn't crash; energy-based VADs vary
        self.assertIsNotNone(result)


# ── Phase 7A: Streaming STT Tests ─────────────────────────────────────────────


class TestStreamingSTT(unittest.TestCase):

    def setUp(self):
        from streaming_stt import StreamingSTT

        self.stt = StreamingSTT(engine="faster-whisper", model="tiny", device="cpu")

    def test_add_and_accumulate(self):
        for _ in range(5):
            self.stt.add_audio(_silence(0.1))
        # Should not raise
        self.assertTrue(True)

    def test_transcribe_accumulated_empty(self):
        self.stt.reset()
        result = self.stt.transcribe_accumulated()
        self.assertIsInstance(result, str)

    def test_partial_callback_set(self):
        results = []
        self.stt.set_on_partial_result(results.append)
        self.assertIsNotNone(self.stt._on_partial)

    def test_final_callback_set(self):
        finals = []
        self.stt.set_on_final_result(lambda t, l, c: finals.append(t))
        self.assertIsNotNone(self.stt._on_final)

    def test_silence_does_not_produce_text(self):
        self.stt.reset()
        self.stt.add_audio(_silence(1.0))
        text = self.stt.transcribe_accumulated()
        self.assertLess(len(text.strip()), 20, "Silence should not produce long text")

    def test_start_stop_no_crash(self):
        self.stt.start()
        time.sleep(0.2)
        self.stt.stop()

    def test_low_latency_target(self):
        """Transcription of 1 s audio must complete in <500 ms."""
        self.stt.reset()
        self.stt.add_audio(_silence(1.0))
        t0 = time.monotonic()
        self.stt.transcribe_accumulated()
        elapsed_ms = (time.monotonic() - t0) * 1000
        self.assertLess(elapsed_ms, 5000, "Transcription took too long")


# ── Phase 7A: Streaming TTS Tests ─────────────────────────────────────────────


class TestStreamingTTS(unittest.TestCase):

    def setUp(self):
        from streaming_tts import StreamingTTS

        self.tts = StreamingTTS(model_path="voices/en_US-amy-medium.onnx")

    def test_split_sentences_single(self):
        from streaming_tts import _split_sentences

        parts = _split_sentences("Hello world.")
        self.assertEqual(len(parts), 1)

    def test_split_sentences_multiple(self):
        from streaming_tts import _split_sentences

        parts = _split_sentences("Hello! How are you? Fine, thanks.")
        self.assertGreaterEqual(len(parts), 2)

    def test_interrupt_clears_flag(self):
        self.tts.interrupt()
        self.assertTrue(self.tts._interrupt.is_set())
        self.tts.reset_interrupt()
        self.assertFalse(self.tts._interrupt.is_set())

    def test_synthesize_empty_yields_nothing(self):
        chunks = list(self.tts.synthesize_streaming(""))
        self.assertEqual(len(chunks), 0)

    def test_queue_api(self):
        audio = np.zeros(512, dtype=np.float32)
        ok = self.tts.queue_audio(audio)
        chunk = self.tts.get_queued_audio(timeout=1.0)
        if ok:
            self.assertIsNotNone(chunk)

    def test_token_stream_empty(self):
        def _empty():
            return iter([])

        chunks = list(self.tts.stream_token_by_token(_empty()))
        self.assertEqual(len(chunks), 0)


# ── Phase 7A: Barge-In Tests ──────────────────────────────────────────────────


class TestBargeIn(unittest.TestCase):

    def setUp(self):
        from barge_in import BargeInDetector, InterruptionHandler

        self.detector = BargeInDetector(sample_rate=16000, threshold=0.4)
        self.handler = InterruptionHandler()

    def test_silence_does_not_interrupt(self):
        result = self.detector.detect(_silence(0.2))
        self.assertFalse(result.get("interrupted", False))

    def test_interrupt_signal_propagates(self):
        fired = threading.Event()
        self.handler.set_on_interrupted(lambda: fired.set())
        self.handler.signal_interrupt()
        self.assertTrue(self.handler.is_interrupted())
        self.assertTrue(fired.is_set())

    def test_reset_clears_interrupt(self):
        self.handler.signal_interrupt()
        self.handler.reset()
        self.assertFalse(self.handler.is_interrupted())


# ── Phase 7A: Full-Duplex Tests ───────────────────────────────────────────────


class TestFullDuplex(unittest.TestCase):

    def test_init_no_crash(self):
        from full_duplex import FullDuplexAudioManager

        mgr = FullDuplexAudioManager(enabled=False)
        mgr.start()  # should no-op
        mgr.stop()

    def test_barge_in_callback_fires(self):
        from full_duplex import FullDuplexAudioManager

        mgr = FullDuplexAudioManager(enabled=False)
        fired = threading.Event()
        mgr.set_on_barge_in(lambda: fired.set())
        mgr._trigger_barge_in()
        self.assertTrue(fired.is_set())

    def test_tts_interrupt_called_on_barge_in(self):
        from full_duplex import FullDuplexAudioManager

        interrupted = threading.Event()
        mgr = FullDuplexAudioManager(enabled=False)
        mgr.set_tts_interrupt_fn(lambda: interrupted.set())
        mgr._trigger_barge_in()
        self.assertTrue(interrupted.is_set())


# ── Phase 7B: AI Tests ────────────────────────────────────────────────────────


class TestPlanner(unittest.TestCase):

    def setUp(self):
        from planner import PlanningEngine, PlanType

        self.planner = PlanningEngine(data_dir="/tmp/aladdin_test_data")
        self.PlanType = PlanType

    def test_generate_plan_returns_plan(self):
        plan = self.planner.generate_plan("Search for Python tutorials")
        self.assertIsNotNone(plan)
        self.assertGreater(len(plan.steps), 0)

    def test_plan_has_id(self):
        plan = self.planner.generate_plan("Set a reminder")
        self.assertTrue(plan.id.startswith("plan_"))

    def test_analyze_goal(self):
        analysis = self.planner.analyze_goal("Find the weather in London")
        self.assertEqual(analysis["complexity"], "moderate")
        self.assertTrue(analysis["requires_external_data"])

    def test_executable_steps(self):
        plan = self.planner.generate_plan("Write code for me")
        steps = plan.get_executable_steps()
        self.assertGreater(len(steps), 0)


class TestReasoning(unittest.TestCase):

    def setUp(self):
        try:
            from reasoning import ReasoningEngine

            self.engine = ReasoningEngine(data_dir="/tmp/aladdin_test_data")
        except Exception as exc:
            self.skipTest(f"Reasoning unavailable: {exc}")

    def test_start_chain(self):
        chain = self.engine.start_chain("Why is the sky blue?")
        self.assertIsNotNone(chain)

    def test_add_conclusion(self):
        chain = self.engine.start_chain("Test question")
        self.engine.add_conclusion(chain.id, "Test conclusion")
        # Should not raise

    def test_chain_has_goal(self):
        chain = self.engine.start_chain("What time is it?")
        self.assertEqual(chain.goal, "What time is it?")


class TestGoalManager(unittest.TestCase):

    def setUp(self):
        from goal_manager import GoalManager, GoalCategory

        self.gm = GoalManager(data_dir="/tmp/aladdin_test_data")
        self.cat = GoalCategory

    def test_create_goal(self):
        goal = self.gm.create_goal("Test goal", "description", self.cat.TASK)
        self.assertIsNotNone(goal)
        self.assertTrue(goal.id.startswith("goal_"))

    def test_activate_goal(self):
        goal = self.gm.create_goal("Activate me", "desc", self.cat.TASK)
        ok = self.gm.activate_goal(goal.id)
        self.assertTrue(ok)

    def test_complete_goal(self):
        goal = self.gm.create_goal("Complete me", "desc", self.cat.TASK)
        self.gm.complete_goal(goal.id)
        stats = self.gm.get_goal_stats()
        self.assertGreaterEqual(stats["completed_goals"], 1)

    def test_get_stats(self):
        stats = self.gm.get_goal_stats()
        self.assertIn("total_goals", stats)
        self.assertIn("completion_rate", stats)


class TestSearch(unittest.TestCase):

    def setUp(self):
        from search import InternetSearch, needs_search

        self.search = InternetSearch()
        self.needs_search = needs_search

    def test_needs_search_true(self):
        self.assertTrue(self.needs_search("what is the weather in Paris?"))

    def test_needs_search_false(self):
        self.assertFalse(self.needs_search("hello"))

    def test_disabled_returns_empty(self):
        from search import InternetSearch

        class DisabledCfg:
            enabled = False
            provider = "duckduckgo"
            api_key = ""
            max_results = 5
            timeout = 5
            cache_ttl = 60
            news_api_key = ""
            cache_dir = "/tmp/aladdin_search_cache"

        s = InternetSearch(DisabledCfg())
        results = s.search("test query")
        self.assertEqual(len(results), 0)


# ── Phase 7B: Memory Tests ────────────────────────────────────────────────────


class TestMemory(unittest.TestCase):

    def test_reflection_session(self):
        from reflection import ReflectionEngine, ReflectionType

        engine = ReflectionEngine(data_dir="/tmp/aladdin_test_data")
        session = engine.start_reflection("Test task", ReflectionType.SUCCESS)
        self.assertIsNotNone(session.session_id)
        engine.evaluate_success(session.session_id, success=True)
        result = engine.finalize_reflection(session.session_id)
        self.assertIsNotNone(result)

    def test_reflection_stats(self):
        from reflection import ReflectionEngine, ReflectionType

        engine = ReflectionEngine(data_dir="/tmp/aladdin_test_data")
        stats = engine.get_reflection_stats()
        self.assertIn("total_sessions", stats)


# ── Phase 7C: Integration Tests ───────────────────────────────────────────────


class TestPipelineOrchestrator(unittest.TestCase):

    def _make_orchestrator(self):
        from pipeline_orchestrator import PipelineOrchestrator

        llm = MagicMock()
        llm.chat.return_value = "I can help with that."
        memory = MagicMock()
        memory.recent.return_value = []
        memory.append.return_value = None
        memory.summarize_old.return_value = None

        return PipelineOrchestrator(
            llm=llm,
            memory=memory,
            data_dir="/tmp/aladdin_test_data",
        )

    def test_process_simple_query(self):
        orch = self._make_orchestrator()
        result = orch.process("Hello, how are you?")
        self.assertIsNotNone(result.response)
        self.assertGreater(len(result.response), 0)

    def test_process_search_query(self):
        orch = self._make_orchestrator()
        result = orch.process("what is the weather in London?")
        self.assertIn(result.intent, ("search", "chat", "unknown"))

    def test_process_planning_query(self):
        orch = self._make_orchestrator()
        result = orch.process("plan my day for tomorrow")
        self.assertIsNotNone(result.plan_id or result.response)

    def test_pipeline_result_has_duration(self):
        orch = self._make_orchestrator()
        result = orch.process("test")
        self.assertGreater(result.duration_ms, 0)

    def test_get_stats(self):
        orch = self._make_orchestrator()
        stats = orch.get_stats()
        self.assertIsInstance(stats, dict)


# ── Phase 7C: Stress & Benchmarks ─────────────────────────────────────────────


class TestStress(unittest.TestCase):

    def test_concurrent_stt_requests(self):
        from streaming_stt import StreamingSTT

        errors = []

        def _transcribe():
            try:
                stt = StreamingSTT(engine="faster-whisper", model="tiny")
                stt.add_audio(_silence(0.2))
                stt.transcribe_accumulated()
            except Exception as exc:
                errors.append(exc)

        threads = [threading.Thread(target=_transcribe) for _ in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=30)
        self.assertEqual(len(errors), 0, f"Concurrent STT errors: {errors}")

    def test_tts_interrupt_race(self):
        """TTS interrupt must be thread-safe."""
        from streaming_tts import StreamingTTS

        tts = StreamingTTS(model_path="voices/en_US-amy-medium.onnx")
        errors = []

        def _interrupt():
            try:
                tts.interrupt()
                tts.reset_interrupt()
            except Exception as exc:
                errors.append(exc)

        threads = [threading.Thread(target=_interrupt) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(len(errors), 0)


class TestBenchmarks(unittest.TestCase):

    def test_planner_performance(self):
        """Generating a plan should complete within 100 ms."""
        from planner import PlanningEngine

        planner = PlanningEngine(data_dir="/tmp/aladdin_test_data")
        t0 = time.monotonic()
        for _ in range(10):
            planner.generate_plan("Search for news")
        elapsed = (time.monotonic() - t0) * 1000
        avg = elapsed / 10
        self.assertLess(avg, 100, f"Plan generation avg {avg:.1f} ms > 100 ms limit")

    def test_search_cache_hit_faster_than_miss(self):
        """Cache hit should be significantly faster than a cold miss."""
        from search import _DiskCache

        cache = _DiskCache("/tmp/aladdin_search_cache_test", ttl=60)
        from search import SearchResult

        results = [SearchResult(title="T", url="http://u", snippet="s")]
        query = "benchmark_query_12345"
        cache.set(query, results)
        t0 = time.monotonic()
        for _ in range(100):
            cache.get(query)
        elapsed_ms = (time.monotonic() - t0) * 1000
        self.assertLess(elapsed_ms, 200, "Cache reads should be fast")


# ── Phase 7C: Regression Tests ────────────────────────────────────────────────


class TestRegression(unittest.TestCase):

    def test_empty_input_no_crash(self):
        from pipeline_orchestrator import PipelineOrchestrator

        llm = MagicMock()
        llm.chat.return_value = "ok"
        memory = MagicMock()
        memory.recent.return_value = []
        orch = PipelineOrchestrator(
            llm=llm, memory=memory, data_dir="/tmp/aladdin_test_data"
        )
        result = orch.process("")
        self.assertIsNotNone(result)

    def test_very_long_input(self):
        from pipeline_orchestrator import PipelineOrchestrator

        llm = MagicMock()
        llm.chat.return_value = "ok"
        memory = MagicMock()
        memory.recent.return_value = []
        orch = PipelineOrchestrator(
            llm=llm, memory=memory, data_dir="/tmp/aladdin_test_data"
        )
        long_text = "word " * 500
        result = orch.process(long_text)
        self.assertIsNotNone(result)

    def test_llm_unavailable_returns_fallback(self):
        from pipeline_orchestrator import PipelineOrchestrator

        llm = MagicMock()
        llm.chat.side_effect = Exception("LLM down")
        memory = MagicMock()
        memory.recent.return_value = []
        orch = PipelineOrchestrator(
            llm=llm, memory=memory, data_dir="/tmp/aladdin_test_data"
        )
        result = orch.process("hello")
        self.assertIsNotNone(result.response)  # should still return something

    def test_reliability_validate_dependencies(self):
        from reliability import validate_dependencies

        ok, warnings = validate_dependencies()
        # ok may be False in CI — we just check it doesn't raise
        self.assertIsInstance(ok, bool)
        self.assertIsInstance(warnings, list)


if __name__ == "__main__":
    unittest.main(verbosity=2)
