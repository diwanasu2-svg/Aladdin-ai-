"""Intent Detection Module for Aladdin - AI Brain Part 1.

This module detects user intents such as:
- Chat: General conversation
- Search: Information retrieval
- Tool: Tool/action invocation
- Coding: Programming-related queries
- Planning: Goal planning and decomposition
- Reminder: Reminder/notification setting
- Memory: Fact storage and recall
- Vision: Image analysis (future)
- File: File operations
- Automation: Task automation

Returns confidence scores and supports custom intents.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime
from enum import Enum
from typing import Optional, List, Dict, Tuple, Any
from pathlib import Path

log = logging.getLogger(__name__)


class IntentType(Enum):
    """Standard intent types."""

    CHAT = "chat"
    SEARCH = "search"
    TOOL = "tool"
    CODING = "coding"
    PLANNING = "planning"
    REMINDER = "reminder"
    MEMORY = "memory"
    VISION = "vision"
    FILE = "file"
    AUTOMATION = "automation"
    UNKNOWN = "unknown"


@dataclass
class IntentScore:
    """Score for a detected intent."""

    intent: IntentType
    confidence: float  # 0.0 to 1.0
    keywords: List[str] = field(default_factory=list)
    context: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["intent"] = self.intent.value
        return d


@dataclass
class IntentAnalysis:
    """Result of intent detection analysis."""

    primary_intent: IntentScore
    secondary_intents: List[IntentScore] = field(default_factory=list)
    user_input: str = ""
    timestamp: str = field(default_factory=lambda: datetime.now().isoformat())
    is_custom_intent: bool = False
    custom_intent_name: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return {
            "primary_intent": self.primary_intent.to_dict(),
            "secondary_intents": [s.to_dict() for s in self.secondary_intents],
            "user_input": self.user_input,
            "timestamp": self.timestamp,
            "is_custom_intent": self.is_custom_intent,
            "custom_intent_name": self.custom_intent_name,
        }


class IntentDetector:
    """Detects user intents from input text."""

    # Intent-specific keywords
    INTENT_KEYWORDS = {
        IntentType.SEARCH: [
            "search",
            "find",
            "look",
            "look up",
            "what is",
            "who is",
            "where is",
            "when is",
            "google",
            "lookup",
            "information",
            "how",
            "tell me about",
        ],
        IntentType.TOOL: [
            "set",
            "create",
            "add",
            "delete",
            "remove",
            "call",
            "run",
            "execute",
            "open",
            "close",
            "start",
            "stop",
            "send",
            "make",
            "get",
            "fetch",
        ],
        IntentType.CODING: [
            "code",
            "program",
            "function",
            "script",
            "python",
            "javascript",
            "debug",
            "error",
            "bug",
            "compile",
            "syntax",
            "algorithm",
            "database",
            "api",
        ],
        IntentType.PLANNING: [
            "plan",
            "break down",
            "split",
            "decompose",
            "steps",
            "process",
            "workflow",
            "schedule",
            "organize",
            "how to",
            "what's the plan",
        ],
        IntentType.REMINDER: [
            "remind",
            "reminder",
            "remember",
            "don't forget",
            "alert",
            "notification",
            "schedule",
            "set a reminder",
            "in",
        ],
        IntentType.MEMORY: [
            "remember",
            "forget",
            "recall",
            "my",
            "i like",
            "i prefer",
            "my name",
            "fact",
            "store",
            "remember this",
        ],
        IntentType.VISION: [
            "image",
            "picture",
            "photo",
            "see",
            "look at",
            "describe",
            "read",
            "recognize",
            "identify",
            "ocr",
        ],
        IntentType.FILE: [
            "file",
            "folder",
            "directory",
            "save",
            "load",
            "read",
            "write",
            "open",
            "document",
            "pdf",
            "text",
            "upload",
            "download",
        ],
        IntentType.AUTOMATION: [
            "automate",
            "automation",
            "trigger",
            "workflow",
            "batch",
            "repeat",
            "schedule",
            "periodic",
            "every",
            "daily",
            "weekly",
        ],
        IntentType.CHAT: [
            "hi",
            "hello",
            "hey",
            "how are you",
            "what's up",
            "good morning",
            "good afternoon",
            "good evening",
        ],
    }

    def __init__(self, data_dir: str = "data"):
        """Initialize intent detector.

        Args:
            data_dir: Directory for storing intent history
        """
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(exist_ok=True)
        self.intents_file = self.data_dir / "intents.jsonl"
        self.custom_intents: Dict[str, List[str]] = {}
        self._load_custom_intents()
        log.info("Intent detector initialized.")

    def _load_custom_intents(self) -> None:
        """Load custom intents from disk."""
        custom_file = self.data_dir / "custom_intents.json"
        if custom_file.exists():
            try:
                self.custom_intents = json.loads(custom_file.read_text())
                log.info(f"Loaded {len(self.custom_intents)} custom intents")
            except Exception as e:
                log.warning(f"Failed to load custom intents: {e}")

    def _save_intent_record(self, analysis: IntentAnalysis) -> None:
        """Save intent detection record."""
        try:
            with open(self.intents_file, "a") as f:
                f.write(json.dumps(analysis.to_dict()) + "\n")
        except Exception as e:
            log.warning(f"Failed to save intent record: {e}")

    def register_custom_intent(self, intent_name: str, keywords: List[str]) -> None:
        """Register a custom intent.

        Args:
            intent_name: Name of the custom intent
            keywords: List of keywords that trigger this intent
        """
        self.custom_intents[intent_name] = keywords
        custom_file = self.data_dir / "custom_intents.json"
        try:
            custom_file.write_text(json.dumps(self.custom_intents, indent=2))
            log.info(f"Registered custom intent: {intent_name}")
        except Exception as e:
            log.warning(f"Failed to save custom intent: {e}")

    def detect(self, user_input: str) -> IntentAnalysis:
        """Detect intent(s) from user input.

        Args:
            user_input: The user's text input

        Returns:
            IntentAnalysis with primary and secondary intents
        """
        # Lowercase for matching
        text_lower = user_input.lower()

        # Score all standard intents
        scores: List[Tuple[IntentScore, float]] = []
        for intent_type in IntentType:
            if intent_type == IntentType.UNKNOWN:
                continue
            score = self._score_intent(intent_type, text_lower, user_input)
            if score.confidence > 0.0:
                scores.append((score, score.confidence))

        # Check custom intents
        for custom_name, keywords in self.custom_intents.items():
            confidence = self._calculate_keyword_confidence(keywords, text_lower)
            if confidence > 0.0:
                score = IntentScore(
                    intent=IntentType.UNKNOWN,
                    confidence=confidence,
                    keywords=keywords,
                    metadata={"custom_intent": custom_name},
                )
                scores.append((score, confidence))

        # Sort by confidence
        scores.sort(key=lambda x: x[1], reverse=True)

        # If no intents detected, default to CHAT
        if not scores:
            primary = IntentScore(intent=IntentType.CHAT, confidence=0.3, keywords=[])
            analysis = IntentAnalysis(
                primary_intent=primary,
                secondary_intents=[],
                user_input=user_input,
            )
        else:
            primary = scores[0][0]
            secondary = [s[0] for s in scores[1:4]]  # Top 3 secondary

            # Check if it's a custom intent
            is_custom = primary.intent == IntentType.UNKNOWN
            custom_name = primary.metadata.get("custom_intent") if is_custom else None

            analysis = IntentAnalysis(
                primary_intent=primary,
                secondary_intents=secondary,
                user_input=user_input,
                is_custom_intent=is_custom,
                custom_intent_name=custom_name,
            )

        self._save_intent_record(analysis)
        log.debug(
            f"Detected intent: {primary.intent.value} (confidence: {primary.confidence:.2f})"
        )

        return analysis

    def _score_intent(
        self, intent_type: IntentType, text_lower: str, original_text: str
    ) -> IntentScore:
        """Score a specific intent."""
        keywords = self.INTENT_KEYWORDS.get(intent_type, [])
        found_keywords = [kw for kw in keywords if kw in text_lower]

        confidence = self._calculate_keyword_confidence(keywords, text_lower)

        return IntentScore(
            intent=intent_type,
            confidence=confidence,
            keywords=found_keywords,
            context={
                "text_length": len(original_text),
                "word_count": len(original_text.split()),
            },
        )

    def _calculate_keyword_confidence(
        self, keywords: List[str], text_lower: str
    ) -> float:
        """Calculate confidence based on keyword matches."""
        if not keywords:
            return 0.0

        matches = sum(1 for kw in keywords if kw in text_lower)
        base_confidence = matches / len(keywords)

        # Boost confidence if multiple keywords match
        if matches > 1:
            base_confidence = min(1.0, base_confidence * 1.2)

        return base_confidence

    def get_intent_distribution(self, text: str) -> Dict[str, float]:
        """Get confidence scores for all intents.

        Returns a dictionary mapping intent names to confidence scores.
        """
        text_lower = text.lower()
        distribution = {}

        for intent_type in IntentType:
            if intent_type == IntentType.UNKNOWN:
                continue
            score = self._score_intent(intent_type, text_lower, text)
            if score.confidence > 0.0:
                distribution[intent_type.value] = score.confidence

        return distribution
