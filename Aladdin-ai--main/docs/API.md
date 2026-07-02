# API Documentation

## Overview
Aladdin AI provides a RESTful API powered by FastAPI.

## Swagger UI
You can access the interactive API documentation at:
- `http://localhost:8000/docs` (Swagger UI)
- `http://localhost:8000/redoc` (ReDoc)

## Key Endpoints

### Health
`GET /health`
Returns the system status, version, and component health.

### Chat
`POST /api/chat`
Send a text query to the LLM and get a response. Supports streaming.

### Memory
`GET /api/memory`
Retrieve stored memories, facts, and user profile data.

`POST /api/memory`
Manually add a memory or fact to the system.

### Voice
`POST /api/voice/transcribe`
Upload an audio file to get a text transcription (Whisper).

`POST /api/voice/synthesize`
Send text to get an audio stream back (Piper TTS).
