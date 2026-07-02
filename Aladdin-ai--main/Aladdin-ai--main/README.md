# Aladdin — AI Personal Voice Assistant

Aladdin is a fully **local, offline-first** AI voice assistant built on top of:

| Component  | Role                         | Source                           |
|------------|------------------------------|----------------------------------|
| Whisper    | Speech-to-text (STT)         | `vendor/whisper/` (OpenAI)       |
| Ollama     | Language model (LLM)         | External server (`ollama serve`) |
| Piper      | Neural text-to-speech (TTS)  | `vendor/piper/`                  |
| SQLite     | Persistent memory            | `data/aladdin_memory.sqlite`     |

## Pipeline

```
Microphone
    │
    ▼
Whisper (STT)
    │
    ▼
Smart Memory ←──── User profile, facts, semantic ranking, vector search
    │
    ▼
Internet Search (auto, when query needs it)
    │
    ▼
Ollama LLM  ←───── Tool calling (calendar, reminders, search, etc.)
    │
    ▼
Piper TTS
    │
    ▼
Speakers
```

## Quick Start

### 1. Install dependencies

```bash
cd aladdin
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

### 2. Install Ollama and pull a model

```bash
# Install Ollama: https://ollama.com
ollama pull llama3
ollama serve &
```

### 3. Download a Piper voice

```bash
mkdir -p voices
curl -L -o voices/en_US-amy-medium.onnx \
  https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx
curl -L -o voices/en_US-amy-medium.onnx.json \
  https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json
```

### 4. Run the setup wizard (optional)

```bash
python main.py --setup
```

### 5. Start Aladdin

```bash
python main.py                # full voice mode
python main.py --text         # text-only (no mic/speakers needed)
python main.py --text --once  # single Q&A
python main.py --daemon       # background service mode
```

## Voice Commands

| Say                           | Action                        |
|-------------------------------|-------------------------------|
| "Aladdin" (wake word)         | Activate from idle            |
| "What time is it?"            | Returns current time via tool |
| "Remind me to call John in 30 minutes" | Sets a reminder      |
| "What's on my calendar?"      | Lists upcoming events         |
| "My name is [name]"           | Aladdin remembers your name   |
| "What do you know about me?"  | Recall stored facts           |
| "Goodbye" / "Bye Aladdin"     | Graceful shutdown             |

## Configuration

Edit `config.yaml` to customise everything. Key settings:

```yaml
audio:
  wake_word: aladdin       # word to say to activate
  wake_word_enabled: true  # false = always listening

ollama:
  model: llama3            # any model in your Ollama install

piper:
  model_path: voices/en_US-amy-medium.onnx

search:
  enabled: true            # auto web search on factual queries
  provider: duckduckgo     # no API key needed
```

## Telegram Integration

1. Create a bot via [@BotFather](https://t.me/botfather)
2. Add your token to `config.yaml`:
   ```yaml
   telegram:
     enabled: true
     bot_token: "YOUR_TOKEN_HERE"
   ```
3. Install the library: `pip install python-telegram-bot>=20.0`

## Smart Memory

Aladdin remembers across sessions:
- **Your name** — says "My name is Alice" once, always remembered
- **Preferences** — "I like jazz", "I don't like spicy food"
- **Projects** — mentioned context is stored as facts
- **Conversation history** — rolling window of past turns

## Plugin System

Drop a `.py` file into `aladdin_core/plugins/` that contains a class
extending `Plugin`:

```python
from aladdin_core.plugin_system import Plugin

class MyPlugin(Plugin):
    name = "my_plugin"

    def on_user_input(self, text: str):
        if "weather" in text.lower():
            return "I'll check the weather for you!"
        return None  # let normal LLM handle it
```

Enable it in `config.yaml`:
```yaml
plugins:
  enabled: [my_plugin]
```

## Project Layout

```
aladdin/
├── main.py                   # single entry point
├── config.yaml               # runtime configuration
├── requirements.txt
├── data/                     # SQLite databases (auto-created)
├── logs/                     # Log files (auto-created)
├── voices/                   # Piper .onnx voice files
├── aladdin_core/             # integration layer
│   ├── config.py             # dataclass config loading
│   ├── audio.py              # mic capture + playback + wake word
│   ├── stt.py                # Whisper wrapper
│   ├── tts.py                # Piper wrapper + pyttsx3 fallback
│   ├── llm.py                # Ollama client + tool calling
│   ├── memory.py             # smart SQLite memory
│   ├── search.py             # internet search (DuckDuckGo)
│   ├── calendar_manager.py   # calendar + reminders
│   ├── tools.py              # built-in tool registrations
│   ├── plugin_system.py      # plugin loader
│   ├── telegram_bot.py       # Telegram integration
│   ├── logger.py             # logging setup
│   └── plugins/              # user plugins go here
└── vendor/
    ├── whisper/              # OpenAI Whisper (unchanged)
    ├── piper/                # Piper TTS (unchanged)
    └── open_webui/           # Open WebUI (optional web UI)
```

## Android / Termux

Aladdin works on Android via Termux:

```bash
pkg install python ffmpeg portaudio
pip install -r requirements.txt
# For TTS without Piper, set piper.enabled: false in config.yaml
# pyttsx3 will use Android's built-in TTS engine
python main.py --text   # text mode works without mic permission in Termux
```

For full voice on Android, grant microphone permission to Termux in Settings.

## Smoke Tests

```bash
# Test LLM connection
python -c "
from aladdin_core.config import AladdinConfig
from aladdin_core.llm import OllamaClient
cfg = AladdinConfig.load('config.yaml')
llm = OllamaClient(cfg.ollama, 'you are a test bot')
print(llm.chat('say hello in one word'))
"

# Test memory
python -c "
from aladdin_core.config import MemoryCfg
from aladdin_core.memory import ConversationMemory
m = ConversationMemory(MemoryCfg(db_path='/tmp/test_mem.sqlite'))
m.append('hello', 'hi there')
m.remember('test_key', 'test_value')
print(m.recent(5))
print(m.recall('test_key'))
"

# Full text-mode end-to-end
python main.py --text --once
```

## Credits

Built on three open-source projects, each under their own license:
- **OpenAI Whisper** — MIT
- **Piper TTS** — MIT
- **Open WebUI** — see `vendor/open_webui/LICENSE`

## Smart Memory (Phase 3)

Aladdin ships with a layered Smart Memory subsystem:

* **Part 1** — User profile, preferences, facts, contacts.
  See [`SMART_MEMORY_PHASE3_README.md`](SMART_MEMORY_PHASE3_README.md).
* **Part 2** — Projects, Locations, Reminders, Calendar and automatic
  Conversation Summaries.
  See [`SMART_MEMORY_PHASE3_PART2_README.md`](SMART_MEMORY_PHASE3_PART2_README.md).
* **Part 3** — Memory Importance Scoring, Memory Ranking, Semantic Search,
  Embedding Manager and a SQLite-backed Vector Database.
  See [`SMART_MEMORY_PHASE3_PART3_README.md`](SMART_MEMORY_PHASE3_PART3_README.md).

All Part 2 + Part 3 layers are config-gated and fully backward-compatible
with Part 1. Part 3 uses dependency-free hashed embeddings, importance-aware
ranking and persistent vector indexing in `data/memory_vectors.sqlite`.

## Architecture Overview
```
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

## Deployment Guide
- Quick start with Docker
- Environment variables reference
- Production checklist
*See full guide in [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)*

## Docker Guide
- docker-compose up instructions
- Building the image manually
- Environment configuration
*See full guide in [docs/DOCKER.md](../docs/DOCKER.md)*

## API Documentation
- Link to `/docs` (Swagger UI)
- Key endpoints overview: `/health`, `/api/chat`, `/api/memory`, `/api/voice`
*See full guide in [docs/API.md](../docs/API.md)*

## Developer Setup
### Prerequisites
- Python 3.11+
- Android Studio (for client development)
- Docker (optional, for local infrastructure)

### Installation steps
1. Clone the repository
2. Set up a virtual environment: `python -m venv .venv && source .venv/bin/activate`
3. Install requirements: `pip install -r requirements.txt`

### Running tests
Run the test suite using pytest:
```bash
pytest tests/
```

