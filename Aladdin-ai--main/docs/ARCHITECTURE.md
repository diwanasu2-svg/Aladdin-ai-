# Architecture Details

```text
┌─────────────────────────────────────────────────────────────┐
│                    Aladdin AI System                         │
├──────────────┬──────────────┬──────────────┬────────────────┤
│  Android App │  Voice Core  │  AI Engine   │  Smart Memory  │
│  (Kotlin)    │  Whisper/STT │  LLM Router  │  SQLite DBs    │
│              │  Piper/TTS   │  OpenAI      │  Embeddings    │
│              │  VAD/Barge-in│  Gemini      │  Vector Store  │
├──────────────┴──────────────┴──────────────┴────────────────┤
│              FastAPI Backend (Python 3.11)                   │
│  Memory · Calendar · Reminders · Browser · Vision · Search  │
└─────────────────────────────────────────────────────────────┘
```

## Android Client
Built in Kotlin with Jetpack Compose. Handles VAD (Voice Activity Detection), wake word processing, and audio streaming.

## AI Engine
Local LLM router using Ollama. Can fallback to cloud providers (OpenAI, Gemini) if configured.

## Voice Core
Whisper for fast local speech-to-text. Piper for high-quality, low-latency local text-to-speech.

## Smart Memory
Layered SQLite databases for structured facts, conversational history, and vector embeddings.
