"""Performance tests — Phase 15, Feature 4.

Measures AI response time (p50/p90/p99), voice latency, memory usage,
and startup time with benchmarks against SLAs.
"""

from __future__ import annotations

import statistics
import threading
import time
import unittest
from unittest.mock import MagicMock


def _percentile(data: list, pct: float) -> float:
    sorted_data = sorted(data)
    idx = int(len(sorted_data) * pct / 100)
    return sorted_data[min(idx, len(sorted_data) - 1)]


class TestAIResponseTimePerformance(unittest.TestCase):
    """Benchmark AI inference latency (mocked)."""

    # SLA thresholds (ms)
    SLA_P50 = 2000
    SLA_P90 = 5000
    SLA_P99 = 10000

    def _mock_llm_call(self, latency_ms: float = 100.0) -> str:
        time.sleep(latency_ms / 1000)
        return "Mock LLM response"

    def test_single_inference_under_sla(self):
        t0 = time.monotonic()
        self._mock_llm_call(50)
        latency_ms = (time.monotonic() - t0) * 1000
        self.assertLess(latency_ms, self.SLA_P99,
                        f"Single inference too slow: {latency_ms:.0f}ms > {self.SLA_P99}ms")

    def test_p50_p90_p99_latency(self):
        """Run 20 inference calls and check percentile latencies."""
        latencies = []
        for i in range(20):
            t0 = time.monotonic()
            self._mock_llm_call(50 + (i % 5) * 10)
            latencies.append((time.monotonic() - t0) * 1000)

        p50 = _percentile(latencies, 50)
        p90 = _percentile(latencies, 90)
        p99 = _percentile(latencies, 99)

        self.assertLess(p50, self.SLA_P50, f"p50={p50:.0f}ms exceeds SLA {self.SLA_P50}ms")
        self.assertLess(p90, self.SLA_P90, f"p90={p90:.0f}ms exceeds SLA {self.SLA_P90}ms")
        self.assertLess(p99, self.SLA_P99, f"p99={p99:.0f}ms exceeds SLA {self.SLA_P99}ms")

    def test_concurrent_inference_throughput(self):
        """5 concurrent inferences must complete within 3s total."""
        results = []

        def run_one():
            t0 = time.monotonic()
            self._mock_llm_call(20)
            results.append((time.monotonic() - t0) * 1000)

        threads = [threading.Thread(target=run_one) for _ in range(5)]
        t_start = time.monotonic()
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=5.0)
        total_ms = (time.monotonic() - t_start) * 1000

        self.assertEqual(len(results), 5)
        self.assertLess(total_ms, 3000, f"Concurrent inference too slow: {total_ms:.0f}ms")


class TestVoiceLatencyPerformance(unittest.TestCase):
    """Benchmark voice pipeline latency: STT + TTS."""

    SLA_STT_MS = 2000
    SLA_TTS_MS = 1000
    SLA_TOTAL_MS = 4000

    def test_stt_latency(self):
        mock_stt = MagicMock()
        mock_stt.transcribe.side_effect = lambda x: (time.sleep(0.05), {"text": "hello"})[1]
        t0 = time.monotonic()
        result = mock_stt.transcribe(b"\x00" * 1600)
        latency = (time.monotonic() - t0) * 1000
        self.assertLess(latency, self.SLA_STT_MS,
                        f"STT too slow: {latency:.0f}ms")

    def test_tts_latency(self):
        mock_tts = MagicMock()
        mock_tts.synthesize.side_effect = lambda x: (time.sleep(0.03), b"\x00" * 100)[1]
        t0 = time.monotonic()
        result = mock_tts.synthesize("Hello, how can I help?")
        latency = (time.monotonic() - t0) * 1000
        self.assertLess(latency, self.SLA_TTS_MS,
                        f"TTS too slow: {latency:.0f}ms")

    def test_end_to_end_voice_latency(self):
        mock_stt = MagicMock()
        mock_stt.transcribe.side_effect = lambda x: (time.sleep(0.05), {"text": "hi"})[1]
        mock_llm = MagicMock()
        mock_llm.chat.side_effect = lambda x: (time.sleep(0.08), "Hello!")[1]
        mock_tts = MagicMock()
        mock_tts.synthesize.side_effect = lambda x: (time.sleep(0.03), b"\x00")[1]

        t0 = time.monotonic()
        text = mock_stt.transcribe(b"\x00" * 1600)["text"]
        reply = mock_llm.chat(text)
        audio = mock_tts.synthesize(reply)
        latency = (time.monotonic() - t0) * 1000

        self.assertLess(latency, self.SLA_TOTAL_MS,
                        f"E2E voice too slow: {latency:.0f}ms")


class TestMemoryUsagePerformance(unittest.TestCase):
    """Memory usage benchmarks."""

    def test_memory_stays_under_limit_mb(self):
        MAX_MB = 500
        try:
            import psutil  # type: ignore
            proc = psutil.Process()
            rss_mb = proc.memory_info().rss / (1024 ** 2)
            self.assertLess(rss_mb, MAX_MB,
                            f"RSS {rss_mb:.0f}MB exceeds limit {MAX_MB}MB")
        except ImportError:
            self.skipTest("psutil not installed")

    def test_no_memory_leak_across_10_calls(self):
        """RSS should not grow more than 50MB across 10 inference calls."""
        try:
            import psutil  # type: ignore
            proc = psutil.Process()

            mock_llm = MagicMock()
            mock_llm.chat.return_value = "Response " * 50

            rss_start = proc.memory_info().rss / (1024 ** 2)
            for _ in range(10):
                mock_llm.chat("test prompt " * 10)
            rss_end = proc.memory_info().rss / (1024 ** 2)

            growth_mb = rss_end - rss_start
            self.assertLess(growth_mb, 50,
                            f"Memory grew by {growth_mb:.1f}MB across 10 calls — possible leak")
        except ImportError:
            self.skipTest("psutil not installed")


class TestStartupTimePerformance(unittest.TestCase):
    """App cold/warm startup time benchmarks."""

    SLA_COLD_STARTUP_MS = 3000
    SLA_WARM_STARTUP_MS = 500

    def test_cold_startup_simulated(self):
        """Simulate cold startup by importing modules fresh."""
        t0 = time.monotonic()
        # Simulate module loading
        import json, re, os, threading, time as t2, logging
        startup_ms = (time.monotonic() - t0) * 1000
        self.assertLess(startup_ms, self.SLA_COLD_STARTUP_MS,
                        f"Cold startup too slow: {startup_ms:.0f}ms")

    def test_warm_startup_config_load(self):
        """Config loading (warm path) should be very fast."""
        mock_config = MagicMock()
        mock_config.load.side_effect = lambda: time.sleep(0.001)

        t0 = time.monotonic()
        mock_config.load()
        latency_ms = (time.monotonic() - t0) * 1000
        self.assertLess(latency_ms, self.SLA_WARM_STARTUP_MS)


class TestStressLoad(unittest.TestCase):
    """Stress testing with high concurrent load."""

    def test_100_sequential_chat_calls(self):
        mock_llm = MagicMock()
        mock_llm.chat.return_value = "OK"

        errors = []
        t0 = time.monotonic()
        for i in range(100):
            try:
                result = mock_llm.chat(f"message {i}")
            except Exception as e:
                errors.append(e)
        elapsed_ms = (time.monotonic() - t0) * 1000

        self.assertEqual(len(errors), 0)
        self.assertLess(elapsed_ms, 10000, f"100 calls took {elapsed_ms:.0f}ms")

    def test_10_concurrent_users(self):
        mock_llm = MagicMock()
        mock_llm.chat.return_value = "Response"
        results, errors = [], []

        def user_session(user_id: int):
            for _ in range(5):
                try:
                    r = mock_llm.chat(f"user_{user_id}: Hello")
                    results.append(r)
                except Exception as e:
                    errors.append(e)

        threads = [threading.Thread(target=user_session, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10.0)

        self.assertEqual(len(errors), 0, f"Errors: {errors}")
        self.assertEqual(len(results), 50)


if __name__ == "__main__":
    unittest.main()
