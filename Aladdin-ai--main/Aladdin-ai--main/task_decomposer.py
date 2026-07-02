"""Task Decomposition Module for Aladdin - AI Brain Part 1.

This module automatically splits complex tasks into manageable subtasks.

Features:
- Generate subtasks
- Track dependencies
- Maintain execution order
- Merge results automatically
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime
from enum import Enum
from typing import Optional, List, Dict, Set, Any
from pathlib import Path

log = logging.getLogger(__name__)


class TaskStatus(Enum):
    """Status of a task."""

    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"
    BLOCKED = "blocked"
    CANCELLED = "cancelled"


class TaskPriority(Enum):
    """Task priority levels."""

    CRITICAL = 5
    HIGH = 4
    NORMAL = 3
    LOW = 2
    MINIMAL = 1


@dataclass
class SubTask:
    """A subtask within a larger task."""

    id: str
    title: str
    description: str
    parent_task_id: str
    priority: TaskPriority = TaskPriority.NORMAL
    depends_on: List[str] = field(default_factory=list)  # IDs of prerequisite subtasks
    estimated_effort: int = 1  # Effort points
    status: TaskStatus = TaskStatus.PENDING
    assigned_to: Optional[str] = None  # Tool or agent name
    input_data: Dict[str, Any] = field(default_factory=dict)
    output_data: Dict[str, Any] = field(default_factory=dict)
    error_message: Optional[str] = None
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    started_at: Optional[str] = None
    completed_at: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["priority"] = self.priority.value
        d["status"] = self.status.value
        return d


@dataclass
class DecomposedTask:
    """A task decomposed into subtasks."""

    id: str
    title: str
    description: str
    subtasks: List[SubTask] = field(default_factory=list)
    status: TaskStatus = TaskStatus.PENDING
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    merged_result: Optional[Dict[str, Any]] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["status"] = self.status.value
        d["subtasks"] = [st.to_dict() for st in self.subtasks]
        return d

    def add_subtask(self, subtask: SubTask) -> None:
        """Add a subtask."""
        self.subtasks.append(subtask)

    def get_subtask(self, task_id: str) -> Optional[SubTask]:
        """Get a subtask by ID."""
        for st in self.subtasks:
            if st.id == task_id:
                return st
        return None

    def get_executable_subtasks(self) -> List[SubTask]:
        """Get subtasks ready for execution (dependencies met)."""
        completed_ids = {
            st.id for st in self.subtasks if st.status == TaskStatus.COMPLETED
        }
        executable = []
        for st in self.subtasks:
            if st.status == TaskStatus.PENDING:
                if all(dep_id in completed_ids for dep_id in st.depends_on):
                    executable.append(st)
        # Sort by priority
        return sorted(executable, key=lambda t: -t.priority.value)

    def get_blocked_subtasks(self) -> List[SubTask]:
        """Get subtasks blocked by dependencies."""
        completed_ids = {
            st.id for st in self.subtasks if st.status == TaskStatus.COMPLETED
        }
        blocked = []
        for st in self.subtasks:
            if st.status == TaskStatus.PENDING:
                if not all(dep_id in completed_ids for dep_id in st.depends_on):
                    blocked.append(st)
        return blocked

    def completion_percentage(self) -> float:
        """Return completion percentage."""
        if not self.subtasks:
            return 0.0
        completed = sum(1 for st in self.subtasks if st.status == TaskStatus.COMPLETED)
        return (completed / len(self.subtasks)) * 100.0

    def total_effort(self) -> int:
        """Calculate total effort for all subtasks."""
        return sum(st.estimated_effort for st in self.subtasks)

    def completed_effort(self) -> int:
        """Calculate completed effort."""
        return sum(
            st.estimated_effort
            for st in self.subtasks
            if st.status == TaskStatus.COMPLETED
        )


class TaskDecomposer:
    """Decomposes complex tasks into manageable subtasks."""

    def __init__(self, data_dir: str = "data"):
        """Initialize task decomposer.

        Args:
            data_dir: Directory for storing decomposition history
        """
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(exist_ok=True)
        self.tasks_file = self.data_dir / "decomposed_tasks.jsonl"
        self.current_task: Optional[DecomposedTask] = None
        log.info("Task decomposer initialized.")

    def decompose(
        self,
        task_title: str,
        task_description: str,
        task_id: Optional[str] = None,
        complexity_level: str = "medium",
    ) -> DecomposedTask:
        """Decompose a task into subtasks.

        Args:
            task_title: Title of the task
            task_description: Description of the task
            task_id: Optional custom task ID
            complexity_level: 'simple', 'medium', or 'complex'

        Returns:
            DecomposedTask with generated subtasks
        """
        if task_id is None:
            task_id = f"task_{int(datetime.now().timestamp() * 1000)}"

        task = DecomposedTask(
            id=task_id,
            title=task_title,
            description=task_description,
            metadata={"complexity_level": complexity_level},
        )

        # Generate subtasks based on complexity
        subtasks = self._generate_subtasks(
            task_id, task_title, task_description, complexity_level
        )
        for subtask in subtasks:
            task.add_subtask(subtask)

        # Establish dependencies
        self._establish_dependencies(task)

        self.current_task = task
        self._save_task(task)
        log.info(f"Decomposed task '{task_title}' into {len(task.subtasks)} subtasks")

        return task

    def _generate_subtasks(
        self,
        parent_id: str,
        title: str,
        description: str,
        complexity_level: str,
    ) -> List[SubTask]:
        """Generate subtasks based on task characteristics."""
        subtasks = []

        # Always start with understanding
        subtasks.append(
            SubTask(
                id=f"{parent_id}_sub_0_understand",
                title="Understand Requirements",
                description="Analyze and understand the task requirements",
                parent_task_id=parent_id,
                priority=TaskPriority.CRITICAL,
                estimated_effort=2,
            )
        )

        if complexity_level in ["medium", "complex"]:
            # Planning phase
            subtasks.append(
                SubTask(
                    id=f"{parent_id}_sub_1_plan",
                    title="Create Plan",
                    description="Create a detailed plan for task execution",
                    parent_task_id=parent_id,
                    priority=TaskPriority.HIGH,
                    depends_on=[subtasks[-1].id],
                    estimated_effort=3,
                )
            )

            # Preparation phase
            subtasks.append(
                SubTask(
                    id=f"{parent_id}_sub_2_prepare",
                    title="Prepare Resources",
                    description="Gather and prepare necessary resources",
                    parent_task_id=parent_id,
                    priority=TaskPriority.HIGH,
                    depends_on=[subtasks[-1].id],
                    estimated_effort=2,
                )
            )

        if complexity_level == "complex":
            # Data gathering
            subtasks.append(
                SubTask(
                    id=f"{parent_id}_sub_3_gather",
                    title="Gather Data",
                    description="Collect required data or information",
                    parent_task_id=parent_id,
                    priority=TaskPriority.HIGH,
                    depends_on=[
                        subtasks[-1].id if len(subtasks) > 2 else subtasks[0].id
                    ],
                    estimated_effort=4,
                )
            )

        # Main execution
        prev_id = subtasks[-1].id if subtasks else None
        subtasks.append(
            SubTask(
                id=f"{parent_id}_sub_exec_main",
                title="Execute Main Task",
                description=f"Execute: {description}",
                parent_task_id=parent_id,
                priority=TaskPriority.NORMAL,
                depends_on=[prev_id] if prev_id else [],
                estimated_effort=5 if complexity_level == "complex" else 3,
            )
        )

        # Validation
        subtasks.append(
            SubTask(
                id=f"{parent_id}_sub_validate",
                title="Validate Results",
                description="Validate task completion and results",
                parent_task_id=parent_id,
                priority=TaskPriority.NORMAL,
                depends_on=[subtasks[-1].id],
                estimated_effort=2,
            )
        )

        # Final merge/reporting
        subtasks.append(
            SubTask(
                id=f"{parent_id}_sub_merge",
                title="Merge Results",
                description="Merge and report final results",
                parent_task_id=parent_id,
                priority=TaskPriority.NORMAL,
                depends_on=[subtasks[-1].id],
                estimated_effort=1,
            )
        )

        return subtasks

    def _establish_dependencies(self, task: DecomposedTask) -> None:
        """Establish dependencies between subtasks."""
        # Dependencies are established during subtask generation
        # This method can add cross-task dependencies if needed
        pass

    def update_subtask(
        self,
        subtask_id: str,
        status: TaskStatus,
        output_data: Optional[Dict[str, Any]] = None,
        error_message: Optional[str] = None,
    ) -> bool:
        """Update a subtask's status."""
        if not self.current_task:
            return False

        subtask = self.current_task.get_subtask(subtask_id)
        if not subtask:
            return False

        subtask.status = status
        if status == TaskStatus.IN_PROGRESS and not subtask.started_at:
            subtask.started_at = datetime.now().isoformat()
        if status == TaskStatus.COMPLETED:
            subtask.completed_at = datetime.now().isoformat()
        if output_data:
            subtask.output_data = output_data
        if error_message:
            subtask.error_message = error_message

        log.info(f"Updated subtask {subtask_id} → {status.value}")
        return True

    def merge_results(self) -> Dict[str, Any]:
        """Merge results from all completed subtasks."""
        if not self.current_task:
            return {}

        merged = {}
        for subtask in self.current_task.subtasks:
            if subtask.status == TaskStatus.COMPLETED:
                merged[subtask.id] = subtask.output_data

        self.current_task.merged_result = merged
        log.info(f"Merged results from {len(merged)} completed subtasks")
        return merged

    def get_task_graph(self, task_id: Optional[str] = None) -> Dict[str, Any]:
        """Get task dependency graph."""
        task = self.current_task if task_id is None else None
        if not task:
            return {}

        graph = {"nodes": [st.to_dict() for st in task.subtasks], "edges": []}

        # Add edges for dependencies
        for st in task.subtasks:
            for dep_id in st.depends_on:
                graph["edges"].append({"from": dep_id, "to": st.id})

        return graph

    def _save_task(self, task: DecomposedTask) -> None:
        """Save task to history."""
        try:
            with open(self.tasks_file, "a") as f:
                f.write(json.dumps(task.to_dict()) + "\n")
        except Exception as e:
            log.warning(f"Failed to save task: {e}")
