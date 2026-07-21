"""Abstract base tool and result types."""
from __future__ import annotations
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class ToolResult:
    success: bool
    tool_name: str
    data: Any = None
    error: Optional[str] = None
    duration_ms: float = 0.0
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {"success": self.success, "tool": self.tool_name,
                "data": self.data, "error": self.error,
                "duration_ms": self.duration_ms, "metadata": self.metadata}


class BaseTool(ABC):
    name: str = "base"
    description: str = ""
    parameters: Dict[str, Any] = {}

    @abstractmethod
    async def execute(self, **kwargs) -> ToolResult:
        ...

    def schema(self) -> Dict:
        return {"type": "function", "function": {
            "name": self.name, "description": self.description,
            "parameters": self.parameters}}
