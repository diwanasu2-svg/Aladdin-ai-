"""Memory routes — short-term, long-term, semantic, contacts, profile."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException
from ..models import (
    ShortTermMemoryItem, LongTermMemoryItem,
    SemanticSearchRequest, SemanticSearchResult,
    Contact, UserProfile, Preferences,
)

log = logging.getLogger(__name__)
router = APIRouter(prefix="/memory", tags=["Memory"])


# ── Short-Term ────────────────────────────────────────────────────────────────

@router.post("/short", status_code=201)
async def save_short_term(item: ShortTermMemoryItem):
    from ..main import app_state
    mem = app_state.get("short_term")
    if not mem:
        raise HTTPException(503, "Short-term memory not available")
    rid = mem.save(item.session_id, item.role, item.content, item.language)
    return {"id": rid, "message": "Saved"}


@router.get("/short")
async def get_short_term(session_id: str, limit: int = 50):
    from ..main import app_state
    mem = app_state.get("short_term")
    if not mem:
        raise HTTPException(503, "Short-term memory not available")
    return mem.get(session_id, limit)


# ── Long-Term ─────────────────────────────────────────────────────────────────

@router.post("/long", status_code=201)
async def save_long_term(item: LongTermMemoryItem):
    from ..main import app_state
    mem = app_state.get("long_term")
    if not mem:
        raise HTTPException(503, "Long-term memory not available")
    rid = mem.save(item.key, item.value, item.category or "general", item.tags)
    return {"id": rid, "message": "Saved"}


@router.get("/long")
async def get_long_term(key: Optional[str] = None, category: Optional[str] = None):
    from ..main import app_state
    mem = app_state.get("long_term")
    if not mem:
        raise HTTPException(503, "Long-term memory not available")
    if key:
        result = mem.get(key)
        if not result:
            raise HTTPException(404, "Key not found")
        return result
    return mem.list_all(category)


# ── Semantic ──────────────────────────────────────────────────────────────────

@router.post("/semantic/search")
async def semantic_search(request: SemanticSearchRequest):
    from ..main import app_state
    mem = app_state.get("semantic")
    if not mem:
        raise HTTPException(503, "Semantic memory not available")
    results = mem.search(request.query, request.top_k, request.min_similarity, request.category)
    return results


# ── Contacts ──────────────────────────────────────────────────────────────────

@router.get("/contacts")
async def list_contacts():
    from ..main import app_state
    contacts = app_state.get("contacts")
    if not contacts:
        raise HTTPException(503, "Contacts memory not available")
    return contacts.list_all()


@router.post("/contacts", status_code=201)
async def add_contact(contact: Contact):
    from ..main import app_state
    contacts = app_state.get("contacts")
    if not contacts:
        raise HTTPException(503, "Contacts memory not available")
    cid = contacts.add(contact.name, contact.phone, contact.email, contact.relation, contact.notes)
    return {"id": cid, "message": "Contact added"}


@router.get("/contacts/{contact_id}")
async def get_contact(contact_id: str):
    from ..main import app_state
    contacts = app_state.get("contacts")
    if not contacts:
        raise HTTPException(503, "Contacts memory not available")
    result = contacts.get(contact_id)
    if not result:
        raise HTTPException(404, "Contact not found")
    return result


@router.put("/contacts/{contact_id}")
async def update_contact(contact_id: str, contact: Contact):
    from ..main import app_state
    contacts = app_state.get("contacts")
    if not contacts:
        raise HTTPException(503, "Contacts memory not available")
    ok = contacts.update(contact_id, name=contact.name, phone=contact.phone,
                         email=contact.email, relation=contact.relation, notes=contact.notes)
    if not ok:
        raise HTTPException(404, "Contact not found")
    return {"message": "Updated"}


@router.delete("/contacts/{contact_id}")
async def delete_contact(contact_id: str):
    from ..main import app_state
    contacts = app_state.get("contacts")
    if not contacts:
        raise HTTPException(503, "Contacts memory not available")
    ok = contacts.delete(contact_id)
    if not ok:
        raise HTTPException(404, "Contact not found")
    return {"message": "Deleted"}


# ── Profile ───────────────────────────────────────────────────────────────────

@router.get("/profile")
async def get_profile():
    from ..main import app_state
    profile = app_state.get("profile")
    if not profile:
        raise HTTPException(503, "Profile memory not available")
    return profile.get()


@router.put("/profile")
async def update_profile(data: Dict[str, Any]):
    from ..main import app_state
    profile = app_state.get("profile")
    if not profile:
        raise HTTPException(503, "Profile memory not available")
    profile.update(data)
    return {"message": "Profile updated"}


# ── Preferences ───────────────────────────────────────────────────────────────

@router.get("/preferences")
async def list_preferences(category: Optional[str] = None):
    from ..main import app_state
    prefs = app_state.get("preferences")
    if not prefs:
        raise HTTPException(503, "Preferences memory not available")
    return prefs.list_all(category)


@router.post("/preferences")
async def set_preference(pref: Preferences):
    from ..main import app_state
    prefs = app_state.get("preferences")
    if not prefs:
        raise HTTPException(503, "Preferences memory not available")
    prefs.set(pref.key, pref.value, pref.category or "general")
    return {"message": "Preference set"}
