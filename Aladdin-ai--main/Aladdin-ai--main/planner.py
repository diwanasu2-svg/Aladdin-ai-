"""Planning Engine for Aladdin - AI Brain Part 1.

This module implements a modular planning engine that:
- Analyzes user goals
- Generates execution plans
- Creates ordered execution steps
- Supports simple and multi-step planning
- Allows future planner extensions
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime
from enum import Enum
from typing import Optional, List, Dict, Any
from pathlib import Path

log = logging.getLogger(__name__)


class PlanStatus(Enum):
    """Status of a plan or step."""

    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"


class PlanType(Enum):
    """Type of plan."""

    SIMPLE = "simple"  # Single action
    SEQUENTIAL = "sequential"  # Steps done in order
    PARALLEL = "parallel"  # Steps can run simultaneously
    CONDITIONAL = "conditional"  # Steps depend on conditions
    HYBRID = "hybrid"  # Mix of sequential and parallel


@dataclass
class PlanStep:
    """A single step in an execution plan."""

    id: str
    action: str
    description: str
    priority: int = 0
    depends_on: List[str] = field(default_factory=list)  # IDs of prerequisite steps
    parameters: Dict[str, Any] = field(default_factory=dict)
    status: PlanStatus = PlanStatus.PENDING
    result: Optional[str] = None
    error: Optional[str] = None
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    completed_at: Optional[str] = None
    duration_ms: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return asdict(self)


@dataclass
class ExecutionPlan:
    """A complete execution plan."""

    id: str
    goal: str
    description: str
    plan_type: PlanType
    steps: List[PlanStep] = field(default_factory=list)
    status: PlanStatus = PlanStatus.PENDING
    confidence: float = 0.0  # 0.0 to 1.0
    estimated_duration_ms: Optional[int] = None
    actual_duration_ms: Optional[int] = None
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["plan_type"] = self.plan_type.value
        d["status"] = self.status.value
        d["steps"] = [step.to_dict() for step in self.steps]
        return d

    def add_step(self, step: PlanStep) -> None:
        """Add a step to the plan."""
        self.steps.append(step)

    def get_step(self, step_id: str) -> Optional[PlanStep]:
        """Get a step by ID."""
        for step in self.steps:
            if step.id == step_id:
                return step
        return None

    def get_executable_steps(self) -> List[PlanStep]:
        """Get steps that are ready to execute (dependencies met)."""
        completed_ids = {
            step.id for step in self.steps if step.status == PlanStatus.COMPLETED
        }
        executable = []
        for step in self.steps:
            if step.status == PlanStatus.PENDING:
                if all(dep_id in completed_ids for dep_id in step.depends_on):
                    executable.append(step)
        # Sort by priority
        return sorted(executable, key=lambda s: -s.priority)

    def completion_percentage(self) -> float:
        """Return completion percentage."""
        if not self.steps:
            return 0.0
        completed = sum(1 for s in self.steps if s.status == PlanStatus.COMPLETED)
        return (completed / len(self.steps)) * 100.0


class PlanningEngine:
    """Main planning engine for goal analysis and plan generation."""

    def __init__(self, data_dir: str = "data"):
        """Initialize the planning engine.

        Args:
            data_dir: Directory to store plan history
        """
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(exist_ok=True)
        self.plans_file = self.data_dir / "plans.jsonl"
        self.current_plan: Optional[ExecutionPlan] = None
        self.plan_history: List[ExecutionPlan] = []
        self._load_history()
        log.info("Planning engine initialized.")

    def _load_history(self) -> None:
        """Load plan history from disk."""
        if not self.plans_file.exists():
            return
        try:
            with open(self.plans_file, "r") as f:
                for line in f:
                    if line.strip():
                        data = json.loads(line)
                        # Convert back to objects if needed
                        log.debug(f"Loaded plan: {data.get('id')}")
        except Exception as e:
            log.warning(f"Failed to load plan history: {e}")

    def _save_plan(self, plan: ExecutionPlan) -> None:
        """Save a plan to history."""
        try:
            with open(self.plans_file, "a") as f:
                f.write(json.dumps(plan.to_dict()) + "\n")
        except Exception as e:
            log.warning(f"Failed to save plan: {e}")

    def analyze_goal(self, goal: str) -> Dict[str, Any]:
        """Analyze a user goal and extract key information.

        Args:
            goal: The user's stated goal

        Returns:
            Analysis dictionary with goal components
        """
        analysis = {
            "original_goal": goal,
            "complexity": self._estimate_complexity(goal),
            "requires_external_data": self._check_external_data(goal),
            "requires_user_input": self._check_user_input(goal),
            "time_sensitive": self._check_time_sensitivity(goal),
            "keywords": self._extract_keywords(goal),
        }
        log.info(
            f"Analyzed goal: {goal[:60]}... → complexity: {analysis['complexity']}"
        )
        return analysis

    def generate_plan(
        self,
        goal: str,
        plan_type: PlanType = PlanType.SEQUENTIAL,
        plan_id: Optional[str] = None,
    ) -> ExecutionPlan:
        """Generate an execution plan for a goal.

        Args:
            goal: The user's goal
            plan_type: Type of plan to generate
            plan_id: Optional custom plan ID

        Returns:
            Generated ExecutionPlan
        """
        if plan_id is None:
            plan_id = f"plan_{int(datetime.now().timestamp() * 1000)}"

        analysis = self.analyze_goal(goal)

        # Create plan
        plan = ExecutionPlan(
            id=plan_id,
            goal=goal,
            description=f"Plan for: {goal}",
            plan_type=plan_type,
            confidence=self._estimate_confidence(goal, analysis),
            metadata=analysis,
        )

        # Generate steps based on complexity
        steps = self._generate_steps(goal, analysis, plan_type)
        for step in steps:
            plan.add_step(step)

        # Estimate duration
        plan.estimated_duration_ms = self._estimate_duration(plan)

        self.current_plan = plan
        self._save_plan(plan)
        log.info(f"Generated plan {plan.id} with {len(plan.steps)} steps")

        return plan

    def _estimate_complexity(self, goal: str) -> str:
        """Estimate goal complexity."""
        word_count = len(goal.split())
        if word_count < 5:
            return "simple"
        elif word_count < 15:
            return "moderate"
        else:
            return "complex"

    def _check_external_data(self, goal: str) -> bool:
        """Check if goal needs external data (search, API, etc)."""
        keywords = [
            "search",
            "find",
            "look",
            "check",
            "what",
            "weather",
            "news",
            "latest",
        ]
        return any(kw in goal.lower() for kw in keywords)

    def _check_user_input(self, goal: str) -> bool:
        """Check if goal requires user input."""
        keywords = ["ask", "tell", "remind", "confirm", "verify"]
        return any(kw in goal.lower() for kw in keywords)

    def _check_time_sensitivity(self, goal: str) -> bool:
        """Check if goal is time-sensitive."""
        keywords = ["now", "urgent", "asap", "quickly", "immediately", "today"]
        return any(kw in goal.lower() for kw in keywords)

    def _extract_keywords(self, goal: str) -> List[str]:
        """Extract important keywords from goal."""
        stop_words = {"the", "a", "is", "to", "and", "or", "in", "on", "at", "for"}
        words = [
            w.lower()
            for w in goal.split()
            if w.lower() not in stop_words and len(w) > 2
        ]
        return words[:10]  # Return top 10

    def _estimate_confidence(self, goal: str, analysis: Dict[str, Any]) -> float:
        """Estimate confidence in plan execution."""
        confidence = 0.8  # Start at 0.8
        if analysis["complexity"] == "complex":
            confidence -= 0.2
        if analysis["requires_user_input"]:
            confidence -= 0.1
        return max(0.0, min(1.0, confidence))

    def _generate_steps(
        self,
        goal: str,
        analysis: Dict[str, Any],
        plan_type: PlanType,
    ) -> List[PlanStep]:
        """Generate execution steps for a goal."""
        steps = []

        # Always start with understanding
        steps.append(
            PlanStep(
                id="step_0_understand",
                action="analyze",
                description="Understand the goal and requirements",
                priority=100,
            )
        )

        # Add external data gathering if needed
        if analysis["requires_external_data"]:
            steps.append(
                PlanStep(
                    id="step_1_gather",
                    action="search",
                    description="Gather external data or information",
                    priority=90,
                    depends_on=["step_0_understand"],
                )
            )

        # Add main execution step
        execute_id = "step_2_execute" if len(steps) > 1 else "step_1_execute"
        depends = steps[-1].id if steps else None
        deps = [depends] if depends else []

        steps.append(
            PlanStep(
                id=execute_id,
                action="execute",
                description=f"Execute main action: {goal}",
                priority=80,
                depends_on=deps,
            )
        )

        # Add validation
        steps.append(
            PlanStep(
                id="step_final_validate",
                action="validate",
                description="Validate results and completion",
                priority=70,
                depends_on=[execute_id],
            )
        )

        return steps

    def _estimate_duration(self, plan: ExecutionPlan) -> int:
        """Estimate execution duration in milliseconds."""
        # Simple heuristic: 2 seconds per step
        return max(1000, len(plan.steps) * 2000)

    def update_step(
        self,
        step_id: str,
        status: PlanStatus,
        result: Optional[str] = None,
        error: Optional[str] = None,
    ) -> bool:
        """Update step status."""
        if not self.current_plan:
            return False

        step = self.current_plan.get_step(step_id)
        if not step:
            return False

        step.status = status
        if result:
            step.result = result
        if error:
            step.error = error
        if status == PlanStatus.COMPLETED:
            step.completed_at = datetime.now().isoformat()

        log.info(f"Updated step {step_id} → {status.value}")
        return True

    def get_current_plan(self) -> Optional[ExecutionPlan]:
        """Get the current active plan."""
        return self.current_plan

    def get_plan(self, plan_id: str) -> Optional[ExecutionPlan]:
        """Get a plan by ID."""
        if self.current_plan and self.current_plan.id == plan_id:
            return self.current_plan
        # Could load from history
        return None

    def clear_plan(self) -> None:
        """Clear the current plan."""
        if self.current_plan:
            log.info(f"Clearing plan {self.current_plan.id}")
            self.current_plan = None
