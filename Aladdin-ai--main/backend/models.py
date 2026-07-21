"""All Pydantic BaseModel classes for Aladdin AI Backend."""

from __future__ import annotations

from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field
import time


# ── Chat Models ──────────────────────────────────────────────────────────────

class Message(BaseModel):
    role: str = Field(..., description="'user' | 'assistant' | 'system'")
    content: str
    timestamp: Optional[float] = Field(default_factory=time.time)


class ChatRequest(BaseModel):
    message: str
    session_id: Optional[str] = None
    model: Optional[str] = None
    provider: Optional[str] = None  # openai | gemini | anthropic | ollama
    stream: bool = False
    system_prompt: Optional[str] = None
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    tools: Optional[List[Dict[str, Any]]] = None


class ChatResponse(BaseModel):
    response: str
    session_id: str
    model: str
    provider: str
    tokens_used: Optional[int] = None
    finish_reason: Optional[str] = None


class StreamChunk(BaseModel):
    text: str
    session_id: str
    done: bool = False


class SessionInfo(BaseModel):
    session_id: str
    created_at: float
    last_active: float
    message_count: int
    model: Optional[str] = None
    provider: Optional[str] = None


# ── Memory Models ─────────────────────────────────────────────────────────────

class ShortTermMemoryItem(BaseModel):
    id: Optional[str] = None
    session_id: str
    role: str
    content: str
    timestamp: Optional[float] = Field(default_factory=time.time)
    language: Optional[str] = None


class LongTermMemoryItem(BaseModel):
    id: Optional[str] = None
    key: str
    value: str
    category: Optional[str] = "general"
    timestamp: Optional[float] = Field(default_factory=time.time)
    tags: Optional[List[str]] = []


class SemanticSearchRequest(BaseModel):
    query: str
    top_k: int = 5
    min_similarity: float = 0.12
    category: Optional[str] = None


class SemanticSearchResult(BaseModel):
    id: str
    content: str
    similarity: float
    metadata: Optional[Dict[str, Any]] = {}


class Contact(BaseModel):
    id: Optional[str] = None
    name: str
    phone: Optional[str] = None
    email: Optional[str] = None
    relation: Optional[str] = None
    notes: Optional[str] = None
    created_at: Optional[float] = Field(default_factory=time.time)
    updated_at: Optional[float] = Field(default_factory=time.time)


class UserProfile(BaseModel):
    name: Optional[str] = None
    language: Optional[str] = "en"
    timezone: Optional[str] = "UTC"
    preferences: Optional[Dict[str, Any]] = {}
    created_at: Optional[float] = Field(default_factory=time.time)
    updated_at: Optional[float] = Field(default_factory=time.time)


class Preferences(BaseModel):
    key: str
    value: Any
    category: Optional[str] = "general"


class Project(BaseModel):
    id: Optional[str] = None
    name: str
    description: Optional[str] = None
    status: Optional[str] = "active"
    notes: Optional[str] = None
    created_at: Optional[float] = Field(default_factory=time.time)
    updated_at: Optional[float] = Field(default_factory=time.time)


class Location(BaseModel):
    id: Optional[str] = None
    name: str
    address: Optional[str] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    notes: Optional[str] = None
    category: Optional[str] = None
    created_at: Optional[float] = Field(default_factory=time.time)


# ── Voice Models ──────────────────────────────────────────────────────────────

class TranscriptionResponse(BaseModel):
    text: str
    language: Optional[str] = None
    confidence: Optional[float] = None
    duration: Optional[float] = None


class LiveTranscriptionChunk(BaseModel):
    text: str
    is_final: bool = False
    language: Optional[str] = None


# ── Health Model ──────────────────────────────────────────────────────────────

class HealthStatus(BaseModel):
    status: str
    version: str = "2.0.0"
    providers: Dict[str, bool] = {}
    memory: Dict[str, bool] = {}
    voice: Dict[str, bool] = {}
