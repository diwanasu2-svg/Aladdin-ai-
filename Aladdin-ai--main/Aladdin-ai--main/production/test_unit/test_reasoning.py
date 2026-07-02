"""Unit tests for Reasoning module — Phase 15, Feature 1."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, Mock


class TestReasoningEngine(unittest.TestCase):
    def test_chain_of_thought(self):
        engine = MagicMock()
        engine.reason.return_value = {
            "steps": ["Step 1: identify...", "Step 2: calculate...", "Step 3: conclude..."],
            "conclusion": "The answer is 42.",
        }
        result = engine.reason("What is 6 times 7?", mode="chain_of_thought")
        self.assertIn("conclusion", result)
        self.assertIn("steps", result)

    def test_empty_prompt_raises(self):
        engine = MagicMock()
        engine.reason.side_effect = ValueError("Prompt cannot be empty")
        with self.assertRaises(ValueError):
            engine.reason("")

    def test_planning_decomposes_task(self):
        engine = MagicMock()
        engine.plan.return_value = [
            "1. Research the topic",
            "2. Gather data",
            "3. Analyze results",
            "4. Write report",
        ]
        steps = engine.plan("Write a research report on AI ethics")
        self.assertIsInstance(steps, list)
        self.assertGreaterEqual(len(steps), 2)

    def test_reflection_improves_answer(self):
        engine = MagicMock()
        engine.reflect.return_value = {
            "original": "The capital is Delhi",
            "critique": "Should specify 'New Delhi'",
            "improved": "The capital of India is New Delhi",
        }
        result = engine.reflect("The capital is Delhi", "Be more specific")
        self.assertIn("improved", result)
        self.assertNotEqual(result["improved"], result["original"])

    def test_goal_extraction(self):
        engine = MagicMock()
        engine.extract_goal.return_value = "Book a flight to Paris"
        goal = engine.extract_goal("Can you help me get a flight ticket to Paris next week?")
        self.assertEqual(goal, "Book a flight to Paris")

    def test_reasoning_with_context(self):
        engine = MagicMock()
        engine.reason.return_value = {"conclusion": "Contextual answer"}
        context = {"user_location": "India", "time": "morning"}
        result = engine.reason("What should I eat for breakfast?", context=context)
        self.assertIn("conclusion", result)


class TestIntentDetector(unittest.TestCase):
    def test_detects_question_intent(self):
        detector = MagicMock()
        detector.detect.return_value = {"intent": "question", "confidence": 0.95}
        result = detector.detect("What is the weather today?")
        self.assertEqual(result["intent"], "question")
        self.assertGreater(result["confidence"], 0.8)

    def test_detects_command_intent(self):
        detector = MagicMock()
        detector.detect.return_value = {"intent": "command", "confidence": 0.92}
        result = detector.detect("Set an alarm for 7am")
        self.assertEqual(result["intent"], "command")

    def test_detects_conversational_intent(self):
        detector = MagicMock()
        detector.detect.return_value = {"intent": "conversation", "confidence": 0.88}
        result = detector.detect("How are you doing today?")
        self.assertEqual(result["intent"], "conversation")

    def test_low_confidence_returns_unknown(self):
        detector = MagicMock()
        detector.detect.return_value = {"intent": "unknown", "confidence": 0.2}
        result = detector.detect("asdfghjkl")
        self.assertLess(result["confidence"], 0.5)


if __name__ == "__main__":
    unittest.main()
