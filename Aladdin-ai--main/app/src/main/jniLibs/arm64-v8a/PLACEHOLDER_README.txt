# Native Libraries for arm64-v8a

Place compiled .so files here:
  libwhisper.so  — Whisper.cpp speech-to-text
  librnnoise.so  — RNNoise noise suppression
  libwebrtc_vad.so — WebRTC Voice Activity Detection
  libllama.so    — llama.cpp on-device LLM
  libmlc_llm.so  — MLC LLM on-device inference
  libpiper.so    — Piper TTS binary wrapper

Build instructions:
  Whisper: https://github.com/ggerganov/whisper.cpp (Android build)
  RNNoise: https://github.com/xiph/rnnoise
  WebRTC VAD: https://chromium.googlesource.com/external/webrtc
  llama.cpp: https://github.com/ggerganov/llama.cpp (Android build)
  MLC LLM: https://github.com/mlc-ai/mlc-llm
