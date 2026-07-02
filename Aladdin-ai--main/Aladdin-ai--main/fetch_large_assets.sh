#!/usr/bin/env bash
# Re-download the large binary files that were stripped from the repo
# because they exceeded GitHub's 10 MB per-file commit limit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "→ Piper test voice"
mkdir -p "$ROOT/vendor/piper/etc"
curl -L -o "$ROOT/vendor/piper/etc/test_voice.onnx" \
  https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx

echo "→ Noto Sans SC fonts (Open WebUI)"
FONT_DIR="$ROOT/vendor/open_webui/backend/open_webui/static/fonts"
mkdir -p "$FONT_DIR"
curl -L -o "$FONT_DIR/NotoSansSC-Regular.ttf" \
  https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf
curl -L -o "$FONT_DIR/NotoSansSC-Variable.ttf" \
  https://github.com/notofonts/noto-cjk/raw/main/Sans/Variable/TTF/NotoSansSC-VF.ttf

echo "✓ Done"
