#!/usr/bin/env bash
# =============================================================================
# Aladdin Voice Core — Multilingual Model Download Script
# Downloads Hindi, Gujarati, and English STT + TTS models
#
# Features: 1, 5, 6, 13 (model download + setup)
#
# Usage:
#   chmod +x scripts/download_models_multilingual.sh
#   ./scripts/download_models_multilingual.sh [--dest /path/to/models] [--arch arm64-v8a]
#   ./scripts/download_models_multilingual.sh --lang hi     # Hindi only
#   ./scripts/download_models_multilingual.sh --lang gu     # Gujarati only
#   ./scripts/download_models_multilingual.sh --lang en     # English only
#   ./scripts/download_models_multilingual.sh --lang all    # All languages (default)
# =============================================================================

set -euo pipefail

DEST="${1:-./models}"
ARCH="${2:-arm64-v8a}"
LANG="${LANG:-all}"    # can be overridden: hi | gu | en | all
PIPER_VERSION="1.2.0"

# Parse named args
for arg in "$@"; do
  case $arg in
    --dest=*)   DEST="${arg#*=}" ;;
    --arch=*)   ARCH="${arg#*=}" ;;
    --lang=*)   LANG="${arg#*=}" ;;
    --dest)     shift; DEST="$1" ;;
    --arch)     shift; ARCH="$1" ;;
    --lang)     shift; LANG="$1" ;;
  esac
done

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || error "'$1' is required but not installed."; }
require curl
require unzip
require tar

mkdir -p "$DEST/vosk" "$DEST/piper"

# =============================================================================
# Piper Binary
# =============================================================================
case "$ARCH" in
  arm64-v8a)   PIPER_ARCH="aarch64" ;;
  armeabi-v7a) PIPER_ARCH="armv7l"  ;;
  x86_64)      PIPER_ARCH="x86_64"  ;;
  *)            error "Unknown arch: $ARCH" ;;
esac

PIPER_BIN="$DEST/piper/piper"
if [ -x "$PIPER_BIN" ]; then
  info "Piper binary already present — skipping"
else
  PIPER_URL="https://github.com/rhasspy/piper/releases/download/v${PIPER_VERSION}/piper_linux_${PIPER_ARCH}.tar.gz"
  PIPER_TAR="$DEST/piper_linux_${PIPER_ARCH}.tar.gz"
  info "Downloading Piper TTS binary (${ARCH})…"
  curl -L --progress-bar -o "$PIPER_TAR" "$PIPER_URL"
  tar -xzf "$PIPER_TAR" -C "$DEST/piper" --strip-components=1
  chmod +x "$PIPER_BIN"
  rm "$PIPER_TAR"
  info "Piper binary ready"
fi

# =============================================================================
# Helper: download a Vosk model
# =============================================================================
download_vosk() {
  local lang="$1" url="$2" dir="$3"
  if [ -f "$DEST/vosk/$dir/am/final.mdl" ]; then
    info "Vosk $lang model already present — skipping"
    return
  fi
  local zip="$DEST/vosk/${dir}.zip"
  info "Downloading Vosk $lang model…"
  curl -L --progress-bar -o "$zip" "$url"
  mkdir -p "$DEST/vosk/$dir-tmp"
  unzip -q "$zip" -d "$DEST/vosk/$dir-tmp"
  mv "$DEST/vosk/$dir-tmp/$dir/"* "$DEST/vosk/$dir/" 2>/dev/null || \
    mv "$DEST/vosk/$dir-tmp/"*/* "$DEST/vosk/$dir/" 2>/dev/null || true
  rm -rf "$DEST/vosk/$dir-tmp" "$zip"
  info "Vosk $lang model ready: $DEST/vosk/$dir"
}

# =============================================================================
# Helper: download a Piper voice
# =============================================================================
download_piper_voice() {
  local lang="$1" voice="$2" base_url="$3"
  local onnx="$DEST/piper/${voice}.onnx"
  local json="$DEST/piper/${voice}.onnx.json"
  if [ -f "$onnx" ]; then
    info "Piper $lang voice already present — skipping"
    return
  fi
  info "Downloading Piper $lang voice: $voice (~40–80 MB)…"
  curl -L --progress-bar -o "$onnx" "${base_url}/${voice}.onnx"
  info "Downloading Piper $lang voice config…"
  curl -L --progress-bar -o "$json" "${base_url}/${voice}.onnx.json"
  info "Piper $lang voice ready: $DEST/piper/$voice"
}

HF_BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main"
VOSK_BASE="https://alphacephei.com/vosk/models"

# =============================================================================
# English — STT + TTS (Feature 1 base)
# =============================================================================
if [[ "$LANG" == "en" || "$LANG" == "all" ]]; then
  info "── English Models ──"
  mkdir -p "$DEST/vosk/vosk-model-small-en-us-0.15"
  download_vosk "English" \
    "${VOSK_BASE}/vosk-model-small-en-us-0.15.zip" \
    "vosk-model-small-en-us-0.15"
  download_piper_voice "English" "en_US-lessac-medium" \
    "${HF_BASE}/en/en_US/lessac/medium"
fi

# =============================================================================
# Hindi — STT + TTS (Feature 1, 5)
# =============================================================================
if [[ "$LANG" == "hi" || "$LANG" == "all" ]]; then
  info "── Hindi Models (Feature 1, 5) ──"
  mkdir -p "$DEST/vosk/vosk-model-hi-0.22"
  download_vosk "Hindi" \
    "${VOSK_BASE}/vosk-model-hi-0.22.zip" \
    "vosk-model-hi-0.22"
  # Hindi Piper TTS voice
  download_piper_voice "Hindi" "hi_IN-hindi_male-medium" \
    "${HF_BASE}/hi/hi_IN/hindi_male/medium"
fi

# =============================================================================
# Gujarati — STT + TTS (Feature 1, 6)
# =============================================================================
if [[ "$LANG" == "gu" || "$LANG" == "all" ]]; then
  info "── Gujarati Models (Feature 1, 6) ──"
  mkdir -p "$DEST/vosk/vosk-model-gu-0.42"
  download_vosk "Gujarati" \
    "${VOSK_BASE}/vosk-model-gu-0.42.zip" \
    "vosk-model-gu-0.42"
  # Gujarati Piper TTS voice (CMU Indic)
  if download_piper_voice "Gujarati" "gu_IN-cmu_indic-medium" \
       "${HF_BASE}/gu/gu_IN/cmu_indic/medium" 2>/dev/null; then
    info "Gujarati Piper voice downloaded successfully"
  else
    warn "Piper Gujarati voice unavailable — gTTS fallback will be used automatically"
    warn "Install: pip install gtts  (requires internet on first use)"
    warn "Alternative: configure GOOGLE_APPLICATION_CREDENTIALS in .env for Google Cloud TTS"
  fi
fi

# =============================================================================
# Porcupine wake word (optional)
# =============================================================================
warn "Porcupine keyword files (.ppn) must be trained via Picovoice Console:"
warn "  https://console.picovoice.ai/"
warn "Train keywords: 'Aladdin' in English, Hindi, Gujarati"
warn "Download *_android.ppn → $DEST/porcupine/"
mkdir -p "$DEST/porcupine"

# =============================================================================
# Summary
# =============================================================================
echo ""
info "Model download complete. Summary:"
echo ""
echo "  STT Models:"
[ -d "$DEST/vosk/vosk-model-small-en-us-0.15" ] && echo "    ✅ English (Vosk)"  || echo "    ⏭  English (skipped)"
[ -d "$DEST/vosk/vosk-model-hi-0.22"           ] && echo "    ✅ Hindi (Vosk)"    || echo "    ⏭  Hindi (skipped)"
[ -d "$DEST/vosk/vosk-model-gu-0.42"           ] && echo "    ✅ Gujarati (Vosk)" || echo "    ⏭  Gujarati (skipped)"
echo ""
echo "  TTS Models (Piper):"
[ -f "$DEST/piper/en_US-lessac-medium.onnx"     ] && echo "    ✅ English"         || echo "    ❌ English (missing)"
[ -f "$DEST/piper/hi_IN-hindi_male-medium.onnx" ] && echo "    ✅ Hindi"            || echo "    ❌ Hindi (missing — run with --lang hi)"
[ -f "$DEST/piper/gu_IN-cmu_indic-medium.onnx"  ] && echo "    ✅ Gujarati (Piper)" || echo "    ⚠  Gujarati (will use gTTS fallback)"
echo ""
echo "  Piper binary: $PIPER_BIN"
echo ""
info "Push to Android device (replace YOUR_PACKAGE):"
echo "  adb push $DEST/ /data/data/YOUR_PACKAGE/files/models/"
echo ""
info "Done! Multilingual support ready for: English, Hindi, Gujarati"
