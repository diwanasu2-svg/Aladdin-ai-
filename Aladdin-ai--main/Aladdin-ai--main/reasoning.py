"""Multi-Step Reasoning Engine for Aladdin - AI Brain Part 2.

This module implements advanced reasoning capabilities:
- Maintains reasoning state across multiple steps
- Passes outputs between reasoning steps
- Supports long reasoning chains
- Enables chain-of-thought problem solving
- Provides reasoning trace for debugging
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime
from enum import Enum
from typing import Optional, List, Dict, Any, Callable
from pathlib import Path
from uuid import uuid4

log = logging.getLogger(__name__)


class ReasoningType(Enum):
    """Type of reasoning step."""

    ANALYZE = "analyze"  # Break down the problem
    SEARCH = "search"  # Look up information
    SYNTHESIZE = "synthesize"  # Combine information
    VERIFY = "verify"  # Validate conclusions
    DECIDE = "decide"  # Make a decision
    PLAN = "plan"  # Create an action plan
    EXECUTE = "execute"  # Execute the plan
    REFLECT = "reflect"  # Learn from results


class ReasoningStatus(Enum):
    """Status of a reasoning step."""

    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"


@dataclass
class ReasoningStep:
    """A single step in a reasoning chain."""

    id: str
    step_type: ReasoningType
    description: str
    input_data: Dict[str, Any] = field(default_factory=dict)
    output_data: Dict[str, Any] = field(default_factory=dict)
    status: ReasoningStatus = ReasoningStatus.PENDING
    reasoning: str = ""  # Explanation of this step's logic
    confidence: float = 0.0  # 0.0 to 1.0
    error: Optional[str] = None
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    completed_at: Optional[str] = None
    duration_ms: Optional[int] = None
    depends_on: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["step_type"] = self.step_type.value
        d["status"] = self.status.value
        return d


@dataclass
class ReasoningChain:
    """A complete chain of reasoning steps."""

    id: str
    goal: str
    initial_context: Dict[str, Any] = field(default_factory=dict)
    steps: List[ReasoningStep] = field(default_factory=list)
    status: ReasoningStatus = ReasoningStatus.PENDING
    final_conclusion: str = ""
    reasoning_trace: List[str] = field(default_factory=list)
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    completed_at: Optional[str] = None
    total_duration_ms: Optional[int] = None

    def add_step(self, step: ReasoningStep) -> None:
        """Add a step to the chain."""
        self.steps.append(step)

    def get_step(self, step_id: str) -> Optional[ReasoningStep]:
        """Get a step by ID."""
        for step in self.steps:
            if step.id == step_id:
                return step
        return None

    def get_executable_steps(self) -> List[ReasoningStep]:
        """Get steps ready to execute (dependencies met)."""
        completed_ids = {
            step.id for step in self.steps if step.status == ReasoningStatus.COMPLETED
        }
        executable = []
        for step in self.steps:
            if step.status == ReasoningStatus.PENDING:
                if all(dep_id in completed_ids for dep_id in step.depends_on):
                    executable.append(step)
        return executable

    def get_current_context(self) -> Dict[str, Any]:
        """Get aggregated context from all completed steps."""
        context = dict(self.initial_context)
        for step in self.steps:
            if step.status == ReasoningStatus.COMPLETED:
                context.update(step.output_data)
        return context

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = {
            "id": self.id,
            "goal": self.goal,
            "initial_context": self.initial_context,
            "steps": [step.to_dict() for step in self.steps],
            "status": self.status.value,
            "final_conclusion": self.final_conclusion,
            "reasoning_trace": self.reasoning_trace,
            "created_at": self.created_at,
            "completed_at": self.completed_at,
            "total_duration_ms": self.total_duration_ms,
        }
        return d


class ReasoningEngine:
    """Main reasoning engine for multi-step problem solving."""

    def __init__(self, data_dir: str = "data"):
        """Initialize the reasoning engine.

        Args:
            data_dir: Directory to store reasoning history
        """
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(exist_ok=True)
        self.chains_file = self.data_dir / "reasoning_chains.jsonl"
        self.current_chain: Optional[ReasoningChain] = None
        self.step_handlers: Dict[ReasoningType, Callable] = {}
        self._load_history()
        log.info("Reasoning engine initialized.")

    def _load_history(self) -> None:
        """Load reasoning history from disk."""
        if not self.chains_file.exists():
            return
        try:
            with open(self.chains_file, "r") as f:
                for line in f:
                    if line.strip():
                        data = json.loads(line)
                        log.debug(f"Loaded chain: {data.get('id')}")
        except Exception as e:
            log.warning(f"Failed to load reasoning history: {e}")

    def _save_chain(self, chain: ReasoningChain) -> None:
        """Save a reasoning chain to history."""
        try:
            with open(self.chains_file, "a") as f:
                f.write(json.dumps(chain.to_dict()) + "\n")
        except Exception as e:
            log.warning(f"Failed to save reasoning chain: {e}")

    def register_handler(
        self,
        step_type: ReasoningType,
        handler: Callable[[ReasoningStep], Dict[str, Any]],
    ) -> None:
        """Register a handler for a specific reasoning type.

        Args:
            step_type: The type of reasoning step
            handler: Callable that processes the step and returns output data
        """
        self.step_handlers[step_type] = handler
        log.info(f"Registered handler for {step_type.value}")

    def start_chain(
        self,
        goal: str,
        initial_context: Optional[Dict[str, Any]] = None,
        chain_id: Optional[str] = None,
    ) -> ReasoningChain:
        """Start a new reasoning chain.

        Args:
            goal: The goal to reason about
            initial_context: Initial context data
            chain_id: Optional custom chain ID

        Returns:
            Created ReasoningChain
        """
        if chain_id is None:
            chain_id = f"chain_{uuid4().hex[:8]}"

        chain = ReasoningChain(
            id=chain_id,
            goal=goal,
            initial_context=initial_context or {},
        )
        self.current_chain = chain
        log.info(f"Started reasoning chain {chain_id} for goal: {goal[:60]}...")
        return chain

    def add_step(
        self,
        step_type: ReasoningType,
        description: str,
        input_data: Optional[Dict[str, Any]] = None,
        depends_on: Optional[List[str]] = None,
    ) -> ReasoningStep:
        """Add a reasoning step to current chain.

        Args:
            step_type: Type of reasoning
            description: Description of what this step does
            input_data: Input data for this step
            depends_on: IDs of steps this depends on

        Returns:
            Created ReasoningStep
        """
        if not self.current_chain:
            raise RuntimeError("No active reasoning chain. Call start_chain() first.")

        step = ReasoningStep(
            id=f"step_{len(self.current_chain.steps)}",
            step_type=step_type,
            description=description,
            input_data=input_data or {},
            depends_on=depends_on or [],
        )

        self.current_chain.add_step(step)
        log.info(f"Added step {step.id}: {step_type.value}")
        return step

    def execute_step(
        self,
        step_id: str,
        processor: Optional[Callable] = None,
    ) -> bool:
        """Execute a reasoning step.

        Args:
            step_id: ID of step to execute
            processor: Optional custom processor function

        Returns:
            True if successful
        """
        if not self.current_chain:
            log.error("No active reasoning chain")
            return False

        step = self.current_chain.get_step(step_id)
        if not step:
            log.error(f"Step {step_id} not found")
            return False

        try:
            step.status = ReasoningStatus.IN_PROGRESS
            start_time = datetime.now()

            # Get context from previous steps
            context = self.current_chain.get_current_context()

            # Use custom processor or registered handler
            if processor:
                output = processor(step, context)
            elif step.step_type in self.step_handlers:
                handler = self.step_handlers[step.step_type]
                output = handler(step)
            else:
                # Default: just pass through
                output = step.input_data

            # Update step
            step.output_data = output
            step.status = ReasoningStatus.COMPLETED
            step.completed_at = datetime.now().isoformat()
            step.duration_ms = int((datetime.now() - start_time).total_seconds() * 1000)

            # Add to reasoning trace
            self.current_chain.reasoning_trace.append(
                f"{step.step_type.value}: {step.description}"
            )

            log.info(f"Executed step {step_id} in {step.duration_ms}ms")
            return True

        except Exception as e:
            step.status = ReasoningStatus.FAILED
            step.error = str(e)
            log.error(f"Failed to execute step {step_id}: {e}")
            return False

    def execute_chain(self) -> bool:
        """Execute all steps in current chain in dependency order.

        Returns:
            True if all steps completed successfully
        """
        if not self.current_chain:
            log.error("No active reasoning chain")
            return False

        start_time = datetime.now()
        self.current_chain.status = ReasoningStatus.IN_PROGRESS

        while True:
            executable = self.current_chain.get_executable_steps()
            if not executable:
                break

            # Execute first executable step
            step = executable[0]
            if not self.execute_step(step.id):
                log.error(f"Chain execution failed at step {step.id}")
                self.current_chain.status = ReasoningStatus.FAILED
                return False

        # All steps completed
        self.current_chain.status = ReasoningStatus.COMPLETED
        self.current_chain.completed_at = datetime.now().isoformat()
        self.current_chain.total_duration_ms = int(
            (datetime.now() - start_time).total_seconds() * 1000
        )

        log.info(
            f"Chain {self.current_chain.id} completed in "
            f"{self.current_chain.total_duration_ms}ms"
        )
        self._save_chain(self.current_chain)
        return True

    def conclude(self, conclusion: str) -> None:
        """Set the final conclusion of the reasoning chain.

        Args:
            conclusion: The final conclusion text
        """
        if self.current_chain:
            self.current_chain.final_conclusion = conclusion
            log.info(f"Chain conclusion: {conclusion[:100]}...")

    def get_reasoning_trace(self) -> List[str]:
        """Get the trace of reasoning steps taken.

        Returns:
            List of step descriptions
        """
        if self.current_chain:
            return self.current_chain.reasoning_trace
        return []

    def get_current_context(self) -> Dict[str, Any]:
        """Get current accumulated context.

        Returns:
            Dictionary of accumulated data from completed steps
        """
        if self.current_chain:
            return self.current_chain.get_current_context()
        return {}

    def get_chain(self, chain_id: str) -> Optional[ReasoningChain]:
        """Get a chain by ID (from current session or history).

        Args:
            chain_id: ID of chain to retrieve

        Returns:
            ReasoningChain or None if not found
        """
        if self.current_chain and self.current_chain.id == chain_id:
            return self.current_chain
        # Could load from history
        return None

    def clear_chain(self) -> None:
        """Clear the current reasoning chain."""
        if self.current_chain:
            log.info(f"Clearing chain {self.current_chain.id}")
            self.current_chain = None
