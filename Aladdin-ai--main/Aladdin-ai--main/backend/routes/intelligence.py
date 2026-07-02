"""
backend/routes/intelligence.py — Phase 11 intelligence routes.

Exposes proactive AI intelligence features:
  GET  /intelligence/briefing   — Daily briefing
  GET  /intelligence/habits     — Predicted habits
  POST /intelligence/context    — Update user context
  GET  /intelligence/recommendations — Context-aware recommendations
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/intelligence", tags=["Intelligence"])


class ContextUpdate(BaseModel):
    activity: str = ""
    location: str = ""
    mood: str = ""
    time_of_day: str = ""
    extra: Dict[str, Any] = {}


@router.get("/briefing")
async def get_daily_briefing():
    """Generate a personalized daily briefing."""
    try:
        from backend.intelligence import BriefingGenerator
        gen = BriefingGenerator()
        briefing = await gen.generate()
        return {"success": True, "briefing": str(briefing), "generated_at": datetime.now().isoformat()}
    except ImportError:
        return {"success": False, "error": "Intelligence module not available", "briefing": "Intelligence features require additional setup."}
    except Exception as exc:
        log.error("Briefing error: %s", exc)
        return {"success": False, "error": str(exc)}


@router.get("/habits")
async def get_habit_predictions():
    """Get predicted user habits and routines."""
    try:
        from backend.intelligence import HabitPredictor
        predictor = HabitPredictor()
        predictions = predictor.predict_next_actions()
        return {"success": True, "predictions": [str(p) for p in predictions]}
    except ImportError:
        return {"success": False, "error": "HabitPredictor not available", "predictions": []}
    except Exception as exc:
        log.error("Habit prediction error: %s", exc)
        return {"success": False, "error": str(exc)}


@router.post("/context")
async def update_context(update: ContextUpdate):
    """Update user context for proactive assistance."""
    try:
        from backend.intelligence import ContextManager
        ctx_mgr = ContextManager()
        await ctx_mgr.update(
            activity=update.activity,
            location=update.location,
            mood=update.mood,
        )
        return {"success": True, "message": "Context updated"}
    except ImportError:
        return {"success": False, "error": "ContextManager not available"}
    except Exception as exc:
        log.error("Context update error: %s", exc)
        return {"success": False, "error": str(exc)}


@router.get("/recommendations")
async def get_recommendations():
    """Get context-aware recommendations."""
    try:
        from backend.intelligence import RecommendationEngine
        engine = RecommendationEngine()
        recs = engine.get_recommendations()
        return {"success": True, "recommendations": [str(r) for r in recs]}
    except ImportError:
        return {"success": False, "error": "RecommendationEngine not available", "recommendations": []}
    except Exception as exc:
        log.error("Recommendations error: %s", exc)
        return {"success": False, "error": str(exc)}
