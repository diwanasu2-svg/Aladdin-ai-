"""
Phase 3 — Planner Wiring + Complete Pipeline Orchestrator
===========================================================
Connects all AI brain modules to the main pipeline:

    Wake Word → VAD → Low-Latency Buffer → Streaming STT → Intent Detection
    → Planner → Reasoning → Goal Manager → Task Decomposer → Reflection
    → LLM → Tool Calling → Streaming TTS → Low-Latency Output → Speaker

FIX 2 — low_latency.py is now wired into the pipeline.
    - LowLatencyPipeline is used to buffer and process audio before STT.
    - Dynamic buffer sizing based on target_latency_ms.
    - Audio synchronisation via processor chain.
    - Zero-copy where possible (direct ndarray pass-through).
"""

from __future__ import annotations

import logging
import time
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

import numpy as np

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Lazy imports — modules may not be installed in every environment
# ---------------------------------------------------------------------------


def _import_planner():
    from planner import PlanningEngine, PlanType, PlanStatus

    return PlanningEngine, PlanType, PlanStatus


def _import_reasoning():
    from reasoning import ReasoningEngine, ReasoningType

    return ReasoningEngine, ReasoningType


def _import_reflection():
    from reflection import ReflectionEngine, ReflectionType, SeverityLevel

    return ReflectionEngine, ReflectionType, SeverityLevel


def _import_goal_manager():
    from goal_manager import GoalManager, GoalCategory, GoalStatus

    return GoalManager, GoalCategory, GoalStatus


def _import_task_decomposer():
    from task_decomposer import TaskDecomposer, TaskStatus

    return TaskDecomposer, TaskStatus


def _import_intent_detector():
    from intent_detector import IntentDetector, IntentType

    return IntentDetector, IntentType


@dataclass
class PipelineResult:
    """Result from a full pipeline execution."""

    user_text: str
    response: str
    intent: str = "chat"
    intent_confidence: float = 0.0
    goal_id: Optional[str] = None
    plan_id: Optional[str] = None
    steps_completed: int = 0
    steps_failed: int = 0
    retries: int = 0
    duration_ms: float = 0.0
    pipeline_latency_ms: float = 0.0  # low-latency buffer estimate
    tools_called: List[str] = field(default_factory=list)
    reflection_insights: List[str] = field(default_factory=list)
    error: Optional[str] = None


class PipelineOrchestrator:
    """
    Orchestrates the complete Aladdin AI pipeline end-to-end.

    Phases wired:
        2  — Low-latency audio pipeline (LowLatencyPipeline)
        3  — Planner, Reasoning, Goal Manager, Task Decomposer, Reflection
        4  — Full-duplex / barge-in hooks (BargeInManager)
        6  — Search integration
    """

    MAX_RETRIES = 3
    RETRY_DELAY_BASE = 0.5  # seconds, doubled on each retry
    TARGET_LATENCY_MS = 500  # end-to-end latency target

    def __init__(
        self,
        llm,
        memory,
        search=None,
        calendar=None,
        reminder=None,
        data_dir: str = "data",
        target_latency_ms: int = TARGET_LATENCY_MS,
    ):
        self.llm = llm
        self.memory = memory
        self.search = search
        self.calendar = calendar
        self.reminder = reminder
        self.data_dir = data_dir
        self.target_latency_ms = target_latency_ms

        # ── FIX 2: Low-Latency Pipeline ───────────────────────────────────
        self._low_latency = self._init_low_latency(target_latency_ms)

        # ── FIX 3: Barge-in manager ───────────────────────────────────────
        self._barge_in: Optional[Any] = None  # set via set_barge_in_manager()

        # ── Brain modules ─────────────────────────────────────────────────
        try:
            PlanningEngine, PlanType, _ = _import_planner()
            self._planner = PlanningEngine(data_dir=data_dir)
            self._plan_type = PlanType
            log.info("PipelineOrchestrator: planner loaded")
        except Exception as exc:
            log.warning("Planner unavailable: %s", exc)
            self._planner = None
            self._plan_type = None

        try:
            ReasoningEngine, ReasoningType = _import_reasoning()
            self._reasoning = ReasoningEngine(data_dir=data_dir)
            self._reasoning_type = ReasoningType
            log.info("PipelineOrchestrator: reasoning engine loaded")
        except Exception as exc:
            log.warning("Reasoning engine unavailable: %s", exc)
            self._reasoning = None
            self._reasoning_type = None

        try:
            ReflectionEngine, ReflectionType, SeverityLevel = _import_reflection()
            self._reflection = ReflectionEngine(data_dir=data_dir)
            self._reflection_type = ReflectionType
            self._severity = SeverityLevel
            log.info("PipelineOrchestrator: reflection engine loaded")
        except Exception as exc:
            log.warning("Reflection engine unavailable: %s", exc)
            self._reflection = None

        try:
            GoalManager, GoalCategory, _ = _import_goal_manager()
            self._goal_manager = GoalManager(data_dir=data_dir)
            self._goal_category = GoalCategory
            log.info("PipelineOrchestrator: goal manager loaded")
        except Exception as exc:
            log.warning("Goal manager unavailable: %s", exc)
            self._goal_manager = None

        try:
            TaskDecomposer, _ = _import_task_decomposer()
            self._task_decomposer = TaskDecomposer()
            log.info("PipelineOrchestrator: task decomposer loaded")
        except Exception as exc:
            log.warning("Task decomposer unavailable: %s", exc)
            self._task_decomposer = None

        try:
            IntentDetector, IntentType = _import_intent_detector()
            self._intent_detector = IntentDetector()
            self._intent_type = IntentType
            log.info("PipelineOrchestrator: intent detector loaded")
        except Exception as exc:
            log.warning("Intent detector unavailable: %s", exc)
            self._intent_detector = None

        log.info(
            "PipelineOrchestrator ready. Target latency: %d ms",
            target_latency_ms,
        )

    # ── FIX 2: Low-Latency Pipeline initialisation ───────────────────────

    @staticmethod
    def _init_low_latency(target_latency_ms: int):
        """Initialise and start the LowLatencyPipeline."""
        try:
            from low_latency import LowLatencyPipeline

            pipeline = LowLatencyPipeline(
                sample_rate=16000,
                target_latency_ms=target_latency_ms,
                enable_async=True,
            )
            # Register audio processor stages
            pipeline.add_processor(PipelineOrchestrator._audio_normalise)
            pipeline.start_async_processing()
            log.info(
                "LowLatencyPipeline started (target=%d ms, max_buffer_chunks=%d)",
                target_latency_ms,
                pipeline.max_buffer_chunks,
            )
            return pipeline
        except ImportError:
            log.warning("low_latency.py not found — running without low-latency buffer")
            return None
        except Exception as exc:
            log.warning("LowLatencyPipeline init failed: %s", exc)
            return None

    @staticmethod
    def _audio_normalise(audio: np.ndarray) -> np.ndarray:
        """Normalise audio amplitude (zero-copy-friendly processor stage)."""
        peak = np.abs(audio).max()
        if peak > 0:
            return audio / peak
        return audio

    def push_audio_chunk(self, audio: np.ndarray) -> Optional[np.ndarray]:
        """Push a raw mic chunk through the low-latency buffer.

        Returns the processed chunk, or ``None`` if the buffer is full
        (back-pressure — caller should pace input).
        """
        if self._low_latency is not None:
            return self._low_latency.process(audio)
        return audio  # pass-through when pipeline unavailable

    def get_pipeline_latency_ms(self) -> float:
        """Return the current estimated audio buffer latency in milliseconds."""
        if self._low_latency is not None:
            return self._low_latency.get_latency_estimate_ms()
        return 0.0

    # ── FIX 3: Barge-in integration ───────────────────────────────────────

    def set_barge_in_manager(self, manager) -> None:
        """Wire up the BargeInManager (from barge_in.py) to the pipeline."""
        self._barge_in = manager
        log.info("PipelineOrchestrator: BargeInManager connected")

    # ── Main pipeline entry point ─────────────────────────────────────────

    def process(self, user_text: str) -> PipelineResult:
        """Run the complete pipeline for a user utterance."""
        t0 = time.monotonic()
        result = PipelineResult(user_text=user_text, response="")

        # Record low-latency buffer estimate
        result.pipeline_latency_ms = self.get_pipeline_latency_ms()

        # 1. Intent detection
        intent, confidence = self._detect_intent(user_text)
        result.intent = intent
        result.intent_confidence = confidence
        log.info("Intent: %s (%.2f) for: %s", intent, confidence, user_text[:60])

        # 2. Start reflection session
        session_id = self._start_reflection(user_text)

        try:
            # 3. Goal creation
            goal_id = self._create_goal(user_text, intent)
            result.goal_id = goal_id

            # 4. Planning
            plan = self._create_plan(user_text, intent)
            result.plan_id = plan.id if plan else None

            # 5. Task decomposition (complex intents)
            subtasks = self._decompose_if_needed(user_text, intent, plan)

            # 6. Execute with reasoning, tool calling, retries
            response, tools_called, steps_ok, steps_fail, retries = (
                self._execute_with_reasoning(user_text, intent, plan, subtasks)
            )
            result.response = response
            result.tools_called = tools_called
            result.steps_completed = steps_ok
            result.steps_failed = steps_fail
            result.retries = retries

            # 7. Update goal progress
            if goal_id and self._goal_manager:
                self._goal_manager.update_progress(goal_id, 100.0 if response else 0.0)

            # 8. Store in memory
            if response:
                self.memory.append(user_text, response)
                self.memory.summarize_old()

            # 9. Reflection
            insights = self._finalize_reflection(session_id, success=bool(response))
            result.reflection_insights = insights

        except Exception as exc:
            log.error("Pipeline error: %s", exc, exc_info=True)
            result.error = str(exc)
            result.response = self._llm_fallback(user_text)
            if session_id and self._reflection:
                try:
                    self._reflection.detect_mistake(
                        session_id,
                        f"Pipeline exception: {exc}",
                        self._severity.HIGH if self._severity else None,
                    )
                    self._reflection.finalize_reflection(session_id)
                except Exception:
                    pass

        result.duration_ms = (time.monotonic() - t0) * 1000
        log.info(
            "Pipeline done in %.0f ms (buf=%.0f ms): '%s…'",
            result.duration_ms,
            result.pipeline_latency_ms,
            result.response[:60],
        )
        return result

    # ── Stage implementations ─────────────────────────────────────────────

    def _detect_intent(self, text: str) -> tuple[str, float]:
        if not self._intent_detector:
            return "chat", 0.8
        try:
            scores = self._intent_detector.detect(text)
            if scores:
                top = scores[0]
                intent = (
                    top.intent.value
                    if hasattr(top.intent, "value")
                    else str(top.intent)
                )
                return intent, float(top.confidence)
        except Exception as exc:
            log.debug("Intent detection error: %s", exc)
        return "chat", 0.8

    def _start_reflection(self, user_text: str) -> Optional[str]:
        if not self._reflection:
            return None
        try:
            rt = self._reflection_type.LEARNING if self._reflection_type else None
            session = self._reflection.start_reflection(user_text, rt)
            return session.session_id
        except Exception as exc:
            log.debug("Reflection start error: %s", exc)
            return None

    def _create_goal(self, user_text: str, intent: str) -> Optional[str]:
        if not self._goal_manager:
            return None
        try:
            cat = self._goal_category.TASK if self._goal_category else None
            goal = self._goal_manager.create_goal(
                title=user_text[:80],
                description=user_text,
                category=cat,
                priority=50,
            )
            self._goal_manager.activate_goal(goal.id)
            return goal.id
        except Exception as exc:
            log.debug("Goal creation error: %s", exc)
            return None

    def _create_plan(self, user_text: str, intent: str):
        if not self._planner:
            return None
        try:
            plan_type = self._plan_type.SEQUENTIAL if self._plan_type else None
            if intent in ("planning", "automation"):
                plan_type = self._plan_type.HYBRID if self._plan_type else plan_type
            return self._planner.generate_plan(user_text, plan_type=plan_type)
        except Exception as exc:
            log.debug("Plan generation error: %s", exc)
            return None

    def _decompose_if_needed(self, user_text: str, intent: str, plan) -> list:
        if not self._task_decomposer:
            return []
        if intent not in ("planning", "automation", "coding"):
            return []
        try:
            decomposed = self._task_decomposer.decompose(user_text)
            return decomposed.subtasks if hasattr(decomposed, "subtasks") else []
        except Exception as exc:
            log.debug("Task decomposition error: %s", exc)
            return []

    def _execute_with_reasoning(
        self,
        user_text: str,
        intent: str,
        plan,
        subtasks: list,
    ) -> tuple[str, list, int, int, int]:
        """Core execution loop with search, LLM, tool calling, retries."""
        tools_called: List[str] = []
        steps_ok = 0
        steps_fail = 0
        total_retries = 0

        # Reasoning chain
        chain = None
        if self._reasoning:
            try:
                chain = self._reasoning.start_chain(user_text)
            except Exception:
                pass

        # Search augmentation
        search_context = ""
        if (
            intent == "search"
            and self.search
            and getattr(getattr(self.search, "cfg", None), "enabled", False)
        ):
            for attempt in range(2):
                try:
                    answer = self.search.answer(user_text)
                    if answer:
                        search_context = f"\n\n[Web: {answer}]"
                        tools_called.append("web_search")
                        steps_ok += 1
                    break
                except Exception as exc:
                    log.warning("Search attempt %d failed: %s", attempt + 1, exc)
                    steps_fail += 1
                    total_retries += 1
                    time.sleep(self.RETRY_DELAY_BASE * (2**attempt))

        # Build augmented input
        augmented = user_text + search_context

        # Execute plan steps if available
        response = ""
        if plan and getattr(plan, "steps", None):
            for step in plan.get_executable_steps()[:3]:  # cap at 3 steps
                for attempt in range(self.MAX_RETRIES):
                    try:
                        step_result = self._execute_step(step, augmented)
                        if step_result:
                            self._planner.update_step(
                                step.id,
                                self._plan_type.SEQUENTIAL if self._plan_type else None,
                                result=step_result,
                            )
                        steps_ok += 1
                        break
                    except Exception as exc:
                        log.warning("Step %s attempt %d: %s", step.id, attempt + 1, exc)
                        steps_fail += 1
                        total_retries += 1
                        time.sleep(self.RETRY_DELAY_BASE * (2**attempt))

        # LLM call with full history
        history = self.memory.recent(8)
        for attempt in range(self.MAX_RETRIES):
            try:
                response = self.llm.chat(augmented, history=history)
                tools_called.append("llm")
                steps_ok += 1
                break
            except Exception as exc:
                log.warning("LLM attempt %d failed: %s", attempt + 1, exc)
                steps_fail += 1
                total_retries += 1
                time.sleep(self.RETRY_DELAY_BASE * (2**attempt))
                if attempt == self.MAX_RETRIES - 1:
                    response = (
                        "I'm having trouble connecting right now. Please try again."
                    )

        # Reasoning conclusion
        if chain and self._reasoning:
            try:
                self._reasoning.add_conclusion(chain.id, response)
            except Exception:
                pass

        return response, tools_called, steps_ok, steps_fail, total_retries

    def _execute_step(self, step, user_text: str) -> Optional[str]:
        action = getattr(step, "action", "")
        if action == "search" and self.search:
            return self.search.answer(user_text)
        if action == "analyze":
            return f"Analyzed: {user_text[:50]}"
        return None

    def _llm_fallback(self, user_text: str) -> str:
        try:
            return self.llm.chat(user_text, history=[])
        except Exception as exc:
            log.error("LLM fallback failed: %s", exc)
            return "I'm sorry, I couldn't process that request."

    def _finalize_reflection(
        self,
        session_id: Optional[str],
        success: bool,
    ) -> List[str]:
        if not session_id or not self._reflection:
            return []
        try:
            self._reflection.evaluate_success(session_id, success)
            if success:
                self._reflection.add_insight(
                    session_id, "Request handled successfully."
                )
            else:
                self._reflection.add_recommendation(
                    session_id, "Consider adding more context or rephrasing."
                )
            session = self._reflection.finalize_reflection(session_id)
            if session and hasattr(session, "insights"):
                return list(session.insights)
        except Exception as exc:
            log.debug("Reflection finalize error: %s", exc)
        return []

    # ── Stats ─────────────────────────────────────────────────────────────

    def get_stats(self) -> Dict[str, Any]:
        stats: Dict[str, Any] = {
            "pipeline_latency_ms": self.get_pipeline_latency_ms(),
        }
        if self._goal_manager:
            try:
                stats["goals"] = self._goal_manager.get_goal_stats()
            except Exception:
                pass
        if self._reflection:
            try:
                stats["reflection"] = self._reflection.get_reflection_stats()
            except Exception:
                pass
        return stats
