#!/usr/bin/env bash
# =============================================================================
# Aladdin Voice Core – Model Download Script
# =============================================================================
# Downloads and arranges all required model files into the expected locations.
#
# Usage:
#   chmod +x scripts/download_models.sh
#   ./scripts/download_models.sh [--dest /path/to/app/files] [--arch arm64-v8a]
#
# The default --dest is ./models (push to device with adb push models/ /data/...)
# =============================================================================

set -euo pipefail

DEST="${1:-./models}"
ARCH="${2:-arm64-v8a}"   # arm64-v8a | armeabi-v7a | x86_64
PIPER_VERSION="1.2.0"
VOSK_MODEL="vosk-model-small-en-us-0.15"
PIPER_VOICE="en_US-lessac-medium"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()    { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || error "'$1' is required but not installed."; }

require curl
require unzip
require tar

mkdir -p "$DEST/vosk-model" "$DEST/piper"

# ─── Vosk Small English Model (40 MB) ─────────────────────────────────────────
VOSK_URL="https://alphacephei.com/vosk/models/${VOSK_MODEL}.zip"
VOSK_ZIP="$DEST/${VOSK_MODEL}.zip"

if [ -f "$DEST/vosk-model/am/final.mdl" ]; then
    info "Vosk model already present – skipping"
else
    info "Downloading Vosk Small English model (~40 MB)…"
    curl -L --progress-bar -o "$VOSK_ZIP" "$VOSK_URL"
    info "Extracting Vosk model…"
    unzip -q "$VOSK_ZIP" -d "$DEST/vosk-model-tmp"
    # Flatten: move the inner directory up
    mv "$DEST/vosk-model-tmp/${VOSK_MODEL}/"* "$DEST/vosk-model/"
    rm -rf "$DEST/vosk-model-tmp" "$VOSK_ZIP"
    info "Vosk model ready at $DEST/vosk-model"
fi

# ─── Piper TTS Binary ─────────────────────────────────────────────────────────
case "$ARCH" in
    arm64-v8a)   PIPER_ARCH="aarch64" ;;
    armeabi-v7a) PIPER_ARCH="armv7l"  ;;
    x86_64)      PIPER_ARCH="x86_64"  ;;
    *)            error "Unknown arch: $ARCH" ;;
esac

PIPER_URL="https://github.com/rhasspy/piper/releases/download/v${PIPER_VERSION}/piper_linux_${PIPER_ARCH}.tar.gz"
PIPER_TAR="$DEST/piper_linux_${PIPER_ARCH}.tar.gz"

if [ -x "$DEST/piper/piper" ]; then
    info "Piper binary already present – skipping"
else
    info "Downloading Piper TTS binary (${ARCH}) (~30 MB)…"
    curl -L --progress-bar -o "$PIPER_TAR" "$PIPER_URL"
    info "Extracting Piper binary…"
    tar -xzf "$PIPER_TAR" -C "$DEST/piper" --strip-components=1
    chmod +x "$DEST/piper/piper"
    rm "$PIPER_TAR"
    info "Piper binary ready at $DEST/piper/piper"
fi

# ─── Piper Voice – en_US Lessac Medium (63 MB) ────────────────────────────────
VOICE_BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"
ONNX_FILE="$DEST/piper/${PIPER_VOICE}.onnx"
JSON_FILE="$DEST/piper/${PIPER_VOICE}.onnx.json"

if [ -f "$ONNX_FILE" ]; then
    info "Piper voice already present – skipping"
else
    info "Downloading Piper voice model (~63 MB)…"
    curl -L --progress-bar -o "$ONNX_FILE" "${VOICE_BASE}/${PIPER_VOICE}.onnx"
    info "Downloading Piper voice config…"
    curl -L --progress-bar -o "$JSON_FILE" "${VOICE_BASE}/${PIPER_VOICE}.onnx.json"
    info "Piper voice ready at $DEST/piper/"
fi

# ─── Optional: Porcupine keyword files ────────────────────────────────────────
warn "Porcupine keyword files (.ppn) must be trained via Picovoice Console:"
warn "  https://console.picovoice.ai/"
warn "Train keywords: 'Aladdin', 'Jarvis', 'Computer'"
warn "Download *_android.ppn files → place in: $DEST/porcupine/"

mkdir -p "$DEST/porcupine"

# ─── ADB push helper ──────────────────────────────────────────────────────────
info "All models downloaded to: $DEST"
info ""
info "Push to device (replace YOUR_PACKAGE):"
echo "  adb push $DEST/ /data/data/YOUR_PACKAGE/files/models/"
echo ""
info "Or programmatically via ModelDownloader.kt (in-app download)."

echo ""
info "Model summary:"
echo "  Vosk STT:  $DEST/vosk-model/"
echo "  Piper bin: $DEST/piper/piper"
echo "  Piper voice: $DEST/piper/${PIPER_VOICE}.onnx"
echo "  Porcupine: $DEST/porcupine/<keyword>_android.ppn  (manual)"
echo ""
info "Done!"
