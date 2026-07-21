"""
Compatibility shim: adds start_chain() and add_conclusion() to ReasoningEngine
if they're not already present (depends on the version of reasoning.py).
"""

from __future__ import annotations

import logging
from typing import Any

log = logging.getLogger(__name__)


def _patch_reasoning_engine() -> None:
    try:
        from reasoning import (
            ReasoningEngine,
            ReasoningChain,
            ReasoningType,
            ReasoningStatus,
        )
        import uuid

        if not hasattr(ReasoningEngine, "start_chain"):

            def start_chain(self, goal: str) -> "ReasoningChain":
                chain = ReasoningChain(
                    id=str(uuid.uuid4()),
                    goal=goal,
                    status=ReasoningStatus.IN_PROGRESS,
                )
                if not hasattr(self, "_chains"):
                    self._chains = {}
                self._chains[chain.id] = chain
                log.debug("Reasoning chain started: %s", chain.id)
                return chain

            ReasoningEngine.start_chain = start_chain

        if not hasattr(ReasoningEngine, "add_conclusion"):

            def add_conclusion(self, chain_id: str, conclusion: str) -> None:
                if hasattr(self, "_chains") and chain_id in self._chains:
                    chain = self._chains[chain_id]
                    chain.final_conclusion = conclusion
                    chain.status = ReasoningStatus.COMPLETED
                    log.debug("Reasoning chain concluded: %s", chain_id)

            ReasoningEngine.add_conclusion = add_conclusion

        log.debug("Reasoning engine patched with start_chain / add_conclusion")
    except Exception as exc:
        log.debug("Reasoning engine patch skipped: %s", exc)


_patch_reasoning_engine()
