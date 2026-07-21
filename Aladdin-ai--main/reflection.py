"""Reflection Module for Aladdin - AI Brain Part 3.

Implements internal reflection and self-evaluation:
- Task success evaluation
- Mistake detection
- Tool usage optimization
- Internal reasoning (not exposed)
- Performance metrics
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, asdict, field
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger(__name__)


class ReflectionType(Enum):
    """Types of reflections."""

    SUCCESS = "success"
    ERROR = "error"
    IMPROVEMENT = "improvement"
    OPTIMIZATION = "optimization"
    LEARNING = "learning"


class SeverityLevel(Enum):
    """Severity levels for detected issues."""

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


@dataclass
class ReflectionMetric:
    """Tracks a reflection metric."""

    name: str
    value: float
    threshold: float = 0.0
    unit: str = ""
    timestamp: str = field(default_factory=lambda: datetime.now().isoformat())

    def exceeds_threshold(self) -> bool:
        """Check if value exceeds threshold."""
        return self.value > self.threshold

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return asdict(self)


@dataclass
class DetectedIssue:
    """Represents a detected issue or mistake."""

    issue_id: str
    reflection_type: ReflectionType
    severity: SeverityLevel
    description: str
    context: Dict[str, Any] = field(default_factory=dict)
    tool_involved: Optional[str] = None
    timestamp: str = field(default_factory=lambda: datetime.now().isoformat())
    resolved: bool = False
    resolution: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["reflection_type"] = self.reflection_type.value
        d["severity"] = self.severity.value
        return d


@dataclass
class ReflectionSession:
    """Represents a reflection session."""

    session_id: str
    task_description: str
    reflection_type: ReflectionType
    success: bool = False
    metrics: List[ReflectionMetric] = field(default_factory=list)
    detected_issues: List[DetectedIssue] = field(default_factory=list)
    insights: List[str] = field(default_factory=list)
    recommendations: List[str] = field(default_factory=list)
    timestamp: str = field(default_factory=lambda: datetime.now().isoformat())
    duration_seconds: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["reflection_type"] = self.reflection_type.value
        d["metrics"] = [m.to_dict() for m in self.metrics]
        d["detected_issues"] = [i.to_dict() for i in self.detected_issues]
        return d


class ReflectionEngine:
    """Manages internal reflection and self-evaluation."""

    def __init__(self, data_dir: str = "data"):
        """Initialize reflection engine.

        Args:
            data_dir: Directory for storing reflection data
        """
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(exist_ok=True)
        self.reflections_file = self.data_dir / "reflections.jsonl"
        self.issues_file = self.data_dir / "detected_issues.jsonl"

        self.sessions: Dict[str, ReflectionSession] = {}
        self.all_issues: List[DetectedIssue] = []
        self.metrics_history: List[ReflectionMetric] = []

        self._load_reflections()
        log.info("Reflection engine initialized.")

    def _load_reflections(self) -> None:
        """Load reflection history from disk."""
        if not self.reflections_file.exists():
            return
        try:
            with open(self.reflections_file, "r") as f:
                for line in f:
                    if line.strip():
                        data = json.loads(line)
                        session_id = data.get("session_id")
                        if session_id:
                            self.sessions[session_id] = data
                        log.debug(f"Loaded reflection session: {session_id}")
        except Exception as e:
            log.warning(f"Failed to load reflections: {e}")

    def start_reflection(
        self,
        task_description: str,
        reflection_type: ReflectionType,
    ) -> ReflectionSession:
        """Start a new reflection session.

        Args:
            task_description: Description of the task being reflected on
            reflection_type: Type of reflection

        Returns:
            ReflectionSession object
        """
        session_id = f"reflection_{int(datetime.now().timestamp() * 1000)}"
        session = ReflectionSession(
            session_id=session_id,
            task_description=task_description,
            reflection_type=reflection_type,
        )
        self.sessions[session_id] = session
        log.debug(f"Started reflection session: {session_id}")
        return session

    def evaluate_success(
        self,
        session_id: str,
        success: bool,
        metrics: Optional[Dict[str, float]] = None,
    ) -> None:
        """Evaluate task success.

        Args:
            session_id: Reflection session ID
            success: Whether task was successful
            metrics: Optional performance metrics
        """
        if session_id not in self.sessions:
            log.warning(f"Session not found: {session_id}")
            return

        session = self.sessions[session_id]
        session.success = success

        if metrics:
            for name, value in metrics.items():
                metric = ReflectionMetric(
                    name=name,
                    value=value,
                    threshold=0.0,
                )
                session.metrics.append(metric)
                self.metrics_history.append(metric)

        log.info(f"Evaluated task success: {success} (session: {session_id})")

    def detect_mistake(
        self,
        session_id: str,
        description: str,
        severity: SeverityLevel,
        tool_involved: Optional[str] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> DetectedIssue:
        """Detect a mistake or issue.

        Args:
            session_id: Reflection session ID
            description: Description of the mistake
            severity: Severity level
            tool_involved: Optional name of tool involved
            context: Optional context data

        Returns:
            DetectedIssue object
        """
        if session_id not in self.sessions:
            log.warning(f"Session not found: {session_id}")
            return None

        issue_id = f"issue_{int(datetime.now().timestamp() * 1000)}"
        issue = DetectedIssue(
            issue_id=issue_id,
            reflection_type=ReflectionType.ERROR,
            severity=severity,
            description=description,
            tool_involved=tool_involved,
            context=context or {},
        )

        session = self.sessions[session_id]
        session.detected_issues.append(issue)
        self.all_issues.append(issue)

        log.warning(
            f"Detected mistake [{severity.value}]: {description} "
            f"(tool: {tool_involved}, session: {session_id})"
        )

        return issue

    def evaluate_tool_usage(
        self,
        session_id: str,
        tool_name: str,
        success: bool,
        execution_time_ms: float,
        error_msg: Optional[str] = None,
    ) -> None:
        """Evaluate tool usage performance.

        Args:
            session_id: Reflection session ID
            tool_name: Name of the tool
            success: Whether tool executed successfully
            execution_time_ms: Execution time in milliseconds
            error_msg: Optional error message
        """
        if session_id not in self.sessions:
            log.warning(f"Session not found: {session_id}")
            return

        session = self.sessions[session_id]

        if not success:
            self.detect_mistake(
                session_id,
                f"Tool '{tool_name}' failed: {error_msg or 'unknown error'}",
                SeverityLevel.MEDIUM,
                tool_involved=tool_name,
                context={"execution_time_ms": execution_time_ms},
            )
        else:
            metric = ReflectionMetric(
                name=f"tool_execution_time_{tool_name}",
                value=execution_time_ms,
                threshold=5000.0,  # 5 seconds
                unit="ms",
            )
            session.metrics.append(metric)
            self.metrics_history.append(metric)

        log.debug(
            f"Tool usage evaluation: {tool_name} - "
            f"success={success}, time={execution_time_ms}ms"
        )

    def add_insight(self, session_id: str, insight: str) -> None:
        """Add an insight to the reflection session.

        Args:
            session_id: Reflection session ID
            insight: The insight text
        """
        if session_id not in self.sessions:
            log.warning(f"Session not found: {session_id}")
            return

        self.sessions[session_id].insights.append(insight)
        log.debug(f"Added insight: {insight}")

    def add_recommendation(self, session_id: str, recommendation: str) -> None:
        """Add a recommendation for future improvements.

        Args:
            session_id: Reflection session ID
            recommendation: The recommendation text
        """
        if session_id not in self.sessions:
            log.warning(f"Session not found: {session_id}")
            return

        self.sessions[session_id].recommendations.append(recommendation)
        log.debug(f"Added recommendation: {recommendation}")

    def finalize_reflection(
        self, session_id: str, duration_seconds: float = 0.0
    ) -> Optional[ReflectionSession]:
        """Finalize a reflection session.

        Args:
            session_id: Reflection session ID
            duration_seconds: Duration of the task/reflection

        Returns:
            Finalized ReflectionSession or None
        """
        if session_id not in self.sessions:
            log.warning(f"Session not found: {session_id}")
            return None

        session = self.sessions[session_id]
        session.duration_seconds = duration_seconds

        # Save to disk
        try:
            with open(self.reflections_file, "a") as f:
                f.write(json.dumps(session.to_dict()) + "\n")
        except Exception as e:
            log.warning(f"Failed to save reflection session: {e}")

        log.info(
            f"Finalized reflection: {session_id} - "
            f"success={session.success}, issues={len(session.detected_issues)}"
        )

        return session

    def get_recent_reflections(self, limit: int = 10) -> List[ReflectionSession]:
        """Get recent reflection sessions.

        Args:
            limit: Maximum number of sessions to return

        Returns:
            List of recent ReflectionSession objects
        """
        sessions = list(self.sessions.values())
        # Sort by timestamp (newest first)
        if isinstance(sessions[0], dict):
            sessions.sort(key=lambda s: s.get("timestamp", ""), reverse=True)
        else:
            sessions.sort(key=lambda s: s.timestamp, reverse=True)
        return sessions[:limit]

    def get_unresolved_issues(self) -> List[DetectedIssue]:
        """Get unresolved issues.

        Returns:
            List of unresolved DetectedIssue objects
        """
        return [issue for issue in self.all_issues if not issue.resolved]

    def resolve_issue(self, issue_id: str, resolution: str) -> bool:
        """Mark an issue as resolved.

        Args:
            issue_id: Issue ID
            resolution: Description of resolution

        Returns:
            True if successful
        """
        for issue in self.all_issues:
            if issue.issue_id == issue_id:
                issue.resolved = True
                issue.resolution = resolution
                log.info(f"Resolved issue: {issue_id}")
                return True
        return False

    def get_reflection_stats(self) -> Dict[str, Any]:
        """Get reflection statistics.

        Returns:
            Dictionary with reflection stats
        """
        total_sessions = len(self.sessions)
        successful = sum(
            1
            for s in self.sessions.values()
            if (isinstance(s, ReflectionSession) and s.success)
            or (isinstance(s, dict) and s.get("success"))
        )
        total_issues = len(self.all_issues)
        unresolved_issues = len(self.get_unresolved_issues())

        return {
            "total_sessions": total_sessions,
            "successful_sessions": successful,
            "success_rate": (
                (successful / total_sessions * 100) if total_sessions > 0 else 0
            ),
            "total_issues": total_issues,
            "unresolved_issues": unresolved_issues,
            "resolution_rate": (
                ((total_issues - unresolved_issues) / total_issues * 100)
                if total_issues > 0
                else 0
            ),
            "average_metrics": len(self.metrics_history) / max(1, total_sessions),
        }
