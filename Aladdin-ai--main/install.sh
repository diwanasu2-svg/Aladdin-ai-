#!/usr/bin/env bash
# =============================================================
# Aladdin — Install & Run Script
# =============================================================
# Usage:
#   chmod +x install.sh
#   ./install.sh          # install dependencies
#   ./install.sh --run    # install + start voice assistant
#   ./install.sh --api    # install + start REST API server
#   ./install.sh --text   # install + start text-only chat
#   ./install.sh --test   # install + run tests

set -e

VENV=".venv"
PYTHON="python3"

echo ""
echo "  ╔══════════════════════════════════════════════╗"
echo "  ║       🪄 Aladdin AI Assistant Setup          ║"
echo "  ╚══════════════════════════════════════════════╝"
echo ""

# ── Python version check ──────────────────────────────────────────
PY_VERSION=$($PYTHON -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
echo "  Python version: $PY_VERSION"

# ── Virtual environment ───────────────────────────────────────────
if [ ! -d "$VENV" ]; then
  echo "  Creating virtual environment…"
  $PYTHON -m venv "$VENV"
fi

source "$VENV/bin/activate"
echo "  Virtualenv: $VENV (activated)"

# ── Core dependencies ─────────────────────────────────────────────
echo ""
echo "  Installing core dependencies…"
pip install --quiet --upgrade pip
pip install --quiet pyyaml requests numpy

# ── Whisper ───────────────────────────────────────────────────────
echo "  Installing Whisper (STT)…"
pip install --quiet openai-whisper tiktoken more-itertools tqdm

# ── Torch (CPU only unless CUDA env is set) ───────────────────────
if [ -z "$CUDA_HOME" ]; then
  echo "  Installing PyTorch (CPU)…"
  pip install --quiet torch --index-url https://download.pytorch.org/whl/cpu
else
  echo "  CUDA detected — installing PyTorch with CUDA support…"
  pip install --quiet torch
fi

# ── TTS ───────────────────────────────────────────────────────────
echo "  Installing Piper TTS…"
pip install --quiet piper-tts onnxruntime pyttsx3 || \
  echo "  ⚠️  Piper install failed — will use pyttsx3 fallback"

# ── Audio ─────────────────────────────────────────────────────────
echo "  Installing audio I/O…"
pip install --quiet sounddevice || \
  echo "  ⚠️  sounddevice install failed — voice mode disabled"

# ── API server (optional) ─────────────────────────────────────────
echo "  Installing REST API server…"
pip install --quiet fastapi uvicorn || \
  echo "  ℹ️  FastAPI not installed — --api mode unavailable"

# ── Voice model download ──────────────────────────────────────────
echo ""
if [ ! -f "voices/en_US-amy-medium.onnx" ]; then
  echo "  Downloading Piper voice model (en_US Amy)…"
  mkdir -p voices
  curl -sL -o voices/en_US-amy-medium.onnx \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx" \
    && echo "  ✅ Voice model downloaded" \
    || echo "  ⚠️  Voice model download failed — run scripts/fetch_large_assets.sh manually"
  curl -sL -o voices/en_US-amy-medium.onnx.json \
    "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json" \
    || true
else
  echo "  ✅ Voice model already present"
fi

# ── Ollama check ──────────────────────────────────────────────────
echo ""
if command -v ollama &> /dev/null; then
  echo "  ✅ Ollama installed"
else
  echo "  ⚠️  Ollama not found. Install from: https://ollama.com"
  echo "     Then run: ollama pull llama3 && ollama serve"
fi

echo ""
echo "  ✅ Installation complete!"
echo ""

# ── Run mode ──────────────────────────────────────────────────────
case "$1" in
  --run)
    echo "  Starting Aladdin (voice mode)…"
    python main.py
    ;;
  --api)
    echo "  Starting Aladdin API server on http://0.0.0.0:8000 …"
    uvicorn api.server:app --host 0.0.0.0 --port 8000
    ;;
  --text)
    echo "  Starting Aladdin (text mode)…"
    python main.py --text
    ;;
  --test)
    echo "  Running tests…"
    python tests/test_aladdin.py
    ;;
  --setup)
    python main.py --setup
    ;;
  *)
    echo "  Usage:"
    echo "    ./install.sh             — install only"
    echo "    ./install.sh --run       — install + start voice assistant"
    echo "    ./install.sh --text      — install + start text chat"
    echo "    ./install.sh --api       — install + start REST API"
    echo "    ./install.sh --setup     — run setup wizard"
    echo "    ./install.sh --test      — run test suite"
    echo ""
    echo "  Quick start (text mode, no mic/speakers needed):"
    echo "    ./install.sh --text"
    echo ""
    ;;
esac
