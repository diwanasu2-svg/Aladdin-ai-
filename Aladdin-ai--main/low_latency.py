"""Low-latency audio pipeline with async processing."""

from __future__ import annotations

import asyncio
import logging
import queue
import threading
from typing import Callable, Optional

import numpy as np

log = logging.getLogger(__name__)


class LowLatencyPipeline:
    """Manages low-latency audio processing pipeline."""

    def __init__(
        self,
        sample_rate: int = 16000,
        target_latency_ms: int = 100,
        enable_async: bool = True,
    ):
        """Initialize low-latency pipeline.

        Args:
            sample_rate: Audio sample rate (Hz)
            target_latency_ms: Target latency (ms)
            enable_async: Use async processing
        """
        self.sample_rate = sample_rate
        self.target_latency_ms = target_latency_ms
        self.enable_async = enable_async

        # Calculate buffer sizes based on latency target
        self.chunk_duration_ms = 32  # 512 samples @ 16kHz
        self.max_buffer_chunks = max(1, target_latency_ms // self.chunk_duration_ms)
        self.max_buffer_samples = self.max_buffer_chunks * 512

        # Processing stages
        self._input_queue: queue.Queue = queue.Queue(maxsize=self.max_buffer_chunks)
        self._output_queue: queue.Queue = queue.Queue(maxsize=self.max_buffer_chunks)

        # Processing functions
        self._processors: list[Callable] = []
        self._lock = threading.RLock()

        # Async support
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._async_thread: Optional[threading.Thread] = None

    def add_processor(self, processor: Callable) -> None:
        """Add processing stage to pipeline.

        Args:
            processor: Function that takes audio and returns processed audio
        """
        with self._lock:
            self._processors.append(processor)

    def process(self, audio: np.ndarray) -> Optional[np.ndarray]:
        """Process audio through pipeline.

        Args:
            audio: Input audio chunk

        Returns:
            Processed audio or None if queue full
        """
        try:
            if self.enable_async:
                self._input_queue.put_nowait(audio)
                try:
                    return self._output_queue.get_nowait()
                except Exception:
                    return None
            else:
                # Synchronous processing
                result = audio.copy()
                with self._lock:
                    for processor in self._processors:
                        try:
                            result = processor(result)
                            if result is None:
                                break
                        except Exception as e:
                            log.error(f"Processor error: {e}")
                            break
                return result

        except Exception as e:
            log.error(f"Pipeline processing error: {e}")
            return None

    def start_async_processing(self) -> None:
        """Start async processing thread."""
        if self.enable_async and self._async_thread is None:
            self._async_thread = threading.Thread(
                target=self._async_process_loop,
                daemon=True,
            )
            self._async_thread.start()
            log.info("Async pipeline processing started")

    def _async_process_loop(self) -> None:
        """Async processing loop."""
        while True:
            try:
                # Get input with timeout
                try:
                    audio = self._input_queue.get(timeout=0.1)
                except Exception:
                    continue

                # Process
                result = audio.copy()
                with self._lock:
                    for processor in self._processors:
                        try:
                            result = processor(result)
                            if result is None:
                                break
                        except Exception as e:
                            log.error(f"Async processor error: {e}")
                            break

                # Queue output
                try:
                    self._output_queue.put_nowait(result)
                except Exception as e:
                    log.warning("Unexpected error: %s", e)

            except Exception as e:
                log.error(f"Async processing error: {e}")
                continue

    def get_latency_estimate_ms(self) -> float:
        """Get estimated pipeline latency.

        Returns:
            Latency in milliseconds
        """
        input_buffered = self._input_queue.qsize()
        output_buffered = self._output_queue.qsize()
        buffered_chunks = max(input_buffered, output_buffered)
        return buffered_chunks * self.chunk_duration_ms
