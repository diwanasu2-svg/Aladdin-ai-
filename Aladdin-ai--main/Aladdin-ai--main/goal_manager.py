"""Goal Manager Module for Aladdin - AI Brain Part 1.

Implements a goal manager for:
- Active goals
- Completed goals
- Failed goals
- Goal status updates
- Nested goals
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta
from enum import Enum
from typing import Optional, List, Dict, Set, Any
from pathlib import Path

log = logging.getLogger(__name__)


class GoalStatus(Enum):
    """Status of a goal."""

    PENDING = "pending"
    ACTIVE = "active"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    ABANDONED = "abandoned"


class GoalCategory(Enum):
    """Categories of goals."""

    TASK = "task"  # One-time task
    HABIT = "habit"  # Recurring habit
    PROJECT = "project"  # Long-term project
    MILESTONE = "milestone"  # Significant achievement
    REMINDER = "reminder"  # Reminder-based
    CUSTOM = "custom"  # Custom category


@dataclass
class Goal:
    """Represents a goal."""

    id: str
    title: str
    description: str
    category: GoalCategory
    status: GoalStatus = GoalStatus.PENDING
    priority: int = 0  # 0-100, higher = more important
    parent_goal_id: Optional[str] = None  # For nested goals
    child_goal_ids: List[str] = field(default_factory=list)
    progress: float = 0.0  # 0.0 to 100.0
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    activated_at: Optional[str] = None
    due_date: Optional[str] = None
    completed_at: Optional[str] = None
    failed_at: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    metrics: Dict[str, Any] = field(default_factory=dict)  # Custom metrics
    associated_tasks: List[str] = field(default_factory=list)  # Plan/task IDs
    notes: str = ""

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        d = asdict(self)
        d["category"] = self.category.value
        d["status"] = self.status.value
        return d

    def is_active(self) -> bool:
        """Check if goal is active."""
        return self.status == GoalStatus.ACTIVE

    def is_completed(self) -> bool:
        """Check if goal is completed."""
        return self.status == GoalStatus.COMPLETED

    def is_overdue(self) -> bool:
        """Check if goal is overdue."""
        if not self.due_date or not self.is_active():
            return False
        due = datetime.fromisoformat(self.due_date)
        return datetime.now() > due


@dataclass
class GoalProgress:
    """Tracks goal progress."""

    goal_id: str
    timestamp: str = field(default_factory=lambda: datetime.now().isoformat())
    progress_value: float = 0.0
    status_change: Optional[str] = None
    notes: str = ""

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary."""
        return asdict(self)


class GoalManager:
    """Manages goals, their status, and progress."""

    def __init__(self, data_dir: str = "data"):
        """Initialize goal manager.

        Args:
            data_dir: Directory for storing goals
        """
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(exist_ok=True)
        self.goals_file = self.data_dir / "goals.jsonl"
        self.progress_file = self.data_dir / "goal_progress.jsonl"

        self.goals: Dict[str, Goal] = {}
        self.progress_history: List[GoalProgress] = []
        self._load_goals()
        log.info("Goal manager initialized.")

    def _load_goals(self) -> None:
        """Load goals from disk."""
        if not self.goals_file.exists():
            return
        try:
            with open(self.goals_file, "r") as f:
                for line in f:
                    if line.strip():
                        data = json.loads(line)
                        # Convert enums
                        goal_id = data["id"]
                        self.goals[goal_id] = data
                        log.debug(f"Loaded goal: {goal_id}")
        except Exception as e:
            log.warning(f"Failed to load goals: {e}")

    def _save_goal(self, goal: Goal) -> None:
        """Save a goal to disk."""
        try:
            with open(self.goals_file, "a") as f:
                f.write(json.dumps(goal.to_dict()) + "\n")
        except Exception as e:
            log.warning(f"Failed to save goal: {e}")

    def _save_progress(self, progress: GoalProgress) -> None:
        """Save progress record."""
        try:
            with open(self.progress_file, "a") as f:
                f.write(json.dumps(progress.to_dict()) + "\n")
        except Exception as e:
            log.warning(f"Failed to save progress: {e}")

    def create_goal(
        self,
        title: str,
        description: str,
        category: GoalCategory,
        priority: int = 0,
        due_date: Optional[str] = None,
        parent_goal_id: Optional[str] = None,
        goal_id: Optional[str] = None,
    ) -> Goal:
        """Create a new goal.

        Args:
            title: Goal title
            description: Goal description
            category: Goal category
            priority: Priority level (0-100)
            due_date: Optional due date (ISO format)
            parent_goal_id: Optional parent goal ID for nested goals
            goal_id: Optional custom goal ID

        Returns:
            Created Goal
        """
        if goal_id is None:
            goal_id = f"goal_{int(datetime.now().timestamp() * 1000)}"

        goal = Goal(
            id=goal_id,
            title=title,
            description=description,
            category=category,
            priority=priority,
            due_date=due_date,
            parent_goal_id=parent_goal_id,
        )

        # Add to parent's children if applicable
        if parent_goal_id and parent_goal_id in self.goals:
            parent = self.goals[parent_goal_id]
            if isinstance(parent, Goal):
                parent.child_goal_ids.append(goal_id)
            elif isinstance(parent, dict):
                parent["child_goal_ids"].append(goal_id)

        self.goals[goal_id] = goal
        self._save_goal(goal)
        log.info(f"Created goal: {title} (id: {goal_id})")

        return goal

    def activate_goal(self, goal_id: str) -> bool:
        """Activate a goal."""
        if goal_id not in self.goals:
            return False

        goal = self.goals[goal_id]
        if isinstance(goal, dict):
            goal["status"] = GoalStatus.ACTIVE.value
            goal["activated_at"] = datetime.now().isoformat()
        else:
            goal.status = GoalStatus.ACTIVE
            goal.activated_at = datetime.now().isoformat()

        progress = GoalProgress(goal_id=goal_id, status_change="activated")
        self._save_progress(progress)
        self.progress_history.append(progress)
        log.info(f"Activated goal: {goal_id}")

        return True

    def update_progress(
        self,
        goal_id: str,
        progress_value: float,
        notes: str = "",
    ) -> bool:
        """Update goal progress.

        Args:
            goal_id: ID of the goal
            progress_value: Progress value (0.0-100.0)
            notes: Optional progress notes

        Returns:
            True if successful
        """
        if goal_id not in self.goals:
            return False

        goal = self.goals[goal_id]
        if isinstance(goal, dict):
            goal["progress"] = min(100.0, progress_value)
        else:
            goal.progress = min(100.0, progress_value)

        progress = GoalProgress(
            goal_id=goal_id, progress_value=progress_value, notes=notes
        )
        self._save_progress(progress)
        self.progress_history.append(progress)
        log.info(f"Updated progress for {goal_id}: {progress_value:.1f}%")

        # Auto-complete if progress reaches 100%
        if progress_value >= 100.0:
            self.complete_goal(goal_id)

        return True

    def complete_goal(self, goal_id: str, notes: str = "") -> bool:
        """Mark goal as completed."""
        if goal_id not in self.goals:
            return False

        goal = self.goals[goal_id]
        if isinstance(goal, dict):
            goal["status"] = GoalStatus.COMPLETED.value
            goal["completed_at"] = datetime.now().isoformat()
            goal["progress"] = 100.0
        else:
            goal.status = GoalStatus.COMPLETED
            goal.completed_at = datetime.now().isoformat()
            goal.progress = 100.0
            goal.notes = notes

        progress = GoalProgress(
            goal_id=goal_id,
            progress_value=100.0,
            status_change="completed",
            notes=notes,
        )
        self._save_progress(progress)
        self.progress_history.append(progress)
        log.info(f"Completed goal: {goal_id}")

        return True

    def fail_goal(self, goal_id: str, reason: str = "") -> bool:
        """Mark goal as failed."""
        if goal_id not in self.goals:
            return False

        goal = self.goals[goal_id]
        if isinstance(goal, dict):
            goal["status"] = GoalStatus.FAILED.value
            goal["failed_at"] = datetime.now().isoformat()
            goal["notes"] = reason
        else:
            goal.status = GoalStatus.FAILED
            goal.failed_at = datetime.now().isoformat()
            goal.notes = reason

        progress = GoalProgress(goal_id=goal_id, status_change="failed", notes=reason)
        self._save_progress(progress)
        self.progress_history.append(progress)
        log.warning(f"Goal failed: {goal_id} - {reason}")

        return True

    def get_goal(self, goal_id: str) -> Optional[Goal]:
        """Get a goal by ID."""
        goal_data = self.goals.get(goal_id)
        if isinstance(goal_data, Goal):
            return goal_data
        return None

    def get_active_goals(self) -> List[Goal]:
        """Get all active goals."""
        active = []
        for goal_data in self.goals.values():
            if isinstance(goal_data, Goal):
                if goal_data.is_active():
                    active.append(goal_data)
            elif isinstance(goal_data, dict):
                if goal_data.get("status") == GoalStatus.ACTIVE.value:
                    active.append(goal_data)
        return sorted(
            active,
            key=lambda g: -(
                g.priority if isinstance(g, Goal) else g.get("priority", 0)
            ),
        )

    def get_completed_goals(self, limit: int = 10) -> List[Goal]:
        """Get completed goals."""
        completed = []
        for goal_data in self.goals.values():
            if isinstance(goal_data, Goal):
                if goal_data.is_completed():
                    completed.append(goal_data)
            elif isinstance(goal_data, dict):
                if goal_data.get("status") == GoalStatus.COMPLETED.value:
                    completed.append(goal_data)
        return completed[:limit]

    def get_overdue_goals(self) -> List[Goal]:
        """Get overdue goals."""
        overdue = []
        for goal_data in self.goals.values():
            if isinstance(goal_data, Goal):
                if goal_data.is_overdue():
                    overdue.append(goal_data)
        return sorted(overdue, key=lambda g: g.due_date or "")

    def get_goal_stats(self) -> Dict[str, Any]:
        """Get goal statistics."""
        total = len(self.goals)
        active = len(self.get_active_goals())
        completed = sum(
            1
            for g in self.goals.values()
            if (isinstance(g, Goal) and g.is_completed())
            or (isinstance(g, dict) and g.get("status") == GoalStatus.COMPLETED.value)
        )
        failed = sum(
            1
            for g in self.goals.values()
            if (isinstance(g, Goal) and g.status == GoalStatus.FAILED)
            or (isinstance(g, dict) and g.get("status") == GoalStatus.FAILED.value)
        )

        return {
            "total_goals": total,
            "active_goals": active,
            "completed_goals": completed,
            "failed_goals": failed,
            "completion_rate": (completed / total * 100) if total > 0 else 0,
        }
