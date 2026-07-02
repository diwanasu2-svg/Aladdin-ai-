#!/usr/bin/env bash
# ============================================================
# Aladdin Model Download Script
# Downloads all required AI models for local deployment
# Usage: bash scripts/download_models.sh [--whisper tiny|base|small|medium|large]
#                                        [--piper en_US-amy-medium]
#                                        [--all]
# ============================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── Default settings ──────────────────────────────────────────────────────────
WHISPER_MODEL="base"
PIPER_VOICE="en_US-amy-medium"
DOWNLOAD_OLLAMA=false
DOWNLOAD_WAKEWORD=true
MODELS_DIR="$(cd "$(dirname "$0")/.." && pwd)/Aladdin-ai--main/models"
VOICES_DIR="$(cd "$(dirname "$0")/.." && pwd)/Aladdin-ai--main/voices"

mkdir -p "$MODELS_DIR" "$VOICES_DIR"

# ── Args ──────────────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case $1 in
    --whisper) WHISPER_MODEL="$2"; shift 2 ;;
    --piper)   PIPER_VOICE="$2";   shift 2 ;;
    --ollama)  DOWNLOAD_OLLAMA=true; shift ;;
    --all)     DOWNLOAD_OLLAMA=true; shift ;;
    *) warn "Unknown arg: $1"; shift ;;
  esac
done

# ── Helper ────────────────────────────────────────────────────────────────────
download() {
  local url="$1" dest="$2"
  if [[ -f "$dest" ]]; then
    info "Already exists: $dest"
    return 0
  fi
  info "Downloading: $url"
  if command -v curl &>/dev/null; then
    curl -L --progress-bar -o "$dest" "$url"
  elif command -v wget &>/dev/null; then
    wget -q --show-progress -O "$dest" "$url"
  else
    error "Neither curl nor wget found. Please install one."; exit 1
  fi
  info "Saved: $dest"
}

# ── 1. Whisper model (via Python openai-whisper / faster-whisper) ─────────────
info "=== Whisper STT model: $WHISPER_MODEL ==="
if python3 -c "import faster_whisper" 2>/dev/null; then
  info "faster-whisper available — downloading $WHISPER_MODEL via Python"
  python3 - <<PYEOF
from faster_whisper import WhisperModel
print("Downloading faster-whisper model: $WHISPER_MODEL ...")
WhisperModel("$WHISPER_MODEL", device="cpu", compute_type="int8")
print("Done.")
PYEOF
elif python3 -c "import whisper" 2>/dev/null; then
  info "openai-whisper available — downloading $WHISPER_MODEL"
  python3 - <<PYEOF
import whisper, os
print("Downloading whisper model: $WHISPER_MODEL ...")
whisper.load_model("$WHISPER_MODEL")
print("Done.")
PYEOF
else
  warn "Neither faster-whisper nor openai-whisper installed."
  warn "Run: pip install faster-whisper  OR  pip install openai-whisper"
fi

# ── 2. Piper TTS model ────────────────────────────────────────────────────────
info "=== Piper TTS model: $PIPER_VOICE ==="
PIPER_BASE="https://github.com/rhasspy/piper/releases/download/v1.2.0"
ONNX_FILE="$VOICES_DIR/${PIPER_VOICE}.onnx"
JSON_FILE="$VOICES_DIR/${PIPER_VOICE}.onnx.json"

download "${PIPER_BASE}/${PIPER_VOICE}.onnx"      "$ONNX_FILE"
download "${PIPER_BASE}/${PIPER_VOICE}.onnx.json" "$JSON_FILE"

# Piper binary
PIPER_BIN_DIR="$MODELS_DIR/piper"
mkdir -p "$PIPER_BIN_DIR"
ARCH=$(uname -m)
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
PIPER_RELEASE="https://github.com/rhasspy/piper/releases/download/v1.2.0"
case "${OS}_${ARCH}" in
  linux_x86_64)  PIPER_TARBALL="piper_linux_x86_64.tar.gz" ;;
  linux_aarch64) PIPER_TARBALL="piper_linux_aarch64.tar.gz" ;;
  darwin_arm64)  PIPER_TARBALL="piper_macos_aarch64.tar.gz" ;;
  darwin_x86_64) PIPER_TARBALL="piper_macos_x86_64.tar.gz"  ;;
  *) warn "No pre-built Piper binary for ${OS}_${ARCH}. Build from source."; PIPER_TARBALL="" ;;
esac

if [[ -n "$PIPER_TARBALL" ]]; then
  TARBALL_DEST="$MODELS_DIR/$PIPER_TARBALL"
  download "${PIPER_RELEASE}/${PIPER_TARBALL}" "$TARBALL_DEST"
  if [[ -f "$TARBALL_DEST" ]]; then
    tar -xzf "$TARBALL_DEST" -C "$PIPER_BIN_DIR" --strip-components=1 2>/dev/null || true
    chmod +x "$PIPER_BIN_DIR/piper" 2>/dev/null || true
    info "Piper binary extracted to $PIPER_BIN_DIR"
  fi
fi

# ── 3. Wake word model (openWakeWord) ─────────────────────────────────────────
if [[ "$DOWNLOAD_WAKEWORD" == "true" ]]; then
  info "=== Wake word models ==="
  WAKEWORD_DIR="$MODELS_DIR/wakeword"
  mkdir -p "$WAKEWORD_DIR"
  # openWakeWord downloads its own models at first use
  if python3 -c "import openwakeword" 2>/dev/null; then
    python3 - <<PYEOF
import openwakeword
openwakeword.utils.download_models()
print("Wake word models downloaded.")
PYEOF
  else
    warn "openwakeword not installed: pip install openwakeword"
  fi
fi

# ── 4. Ollama (local LLM) ─────────────────────────────────────────────────────
if [[ "$DOWNLOAD_OLLAMA" == "true" ]]; then
  info "=== Ollama + LLM model ==="
  if ! command -v ollama &>/dev/null; then
    info "Installing Ollama..."
    curl -fsSL https://ollama.com/install.sh | sh
  else
    info "Ollama already installed: $(ollama --version)"
  fi

  info "Pulling llama3 model (this may take a while)..."
  ollama pull llama3 || warn "Could not pull llama3 — start Ollama first: ollama serve"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
info "=== Download complete ==="
echo ""
echo "Models directory : $MODELS_DIR"
echo "Voices directory : $VOICES_DIR"
echo ""
echo "Next steps:"
echo "  1. Install Python deps : pip install -r Aladdin-ai--main/requirements.txt"
echo "  2. Start Ollama        : ollama serve"
echo "  3. Run Aladdin         : cd Aladdin-ai--main && python main.py"
