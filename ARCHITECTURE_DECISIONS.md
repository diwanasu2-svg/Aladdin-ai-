# Architecture Decision: On-Device vs Server LLM

**Status: SETTLED (2026-07-08)**

## Decision

Aladdin uses an **external server (Ollama, or any OpenAI-compatible HTTP
endpoint) as the default AI backend.** A fully on-device (offline,
no-server) engine using `llama.cpp` exists in the codebase and works, but is
**opt-in only** — never the default.

## Why

Three options were evaluated on real target hardware:

1. **On-device `llama.cpp` (fully offline, default)** — tried on 2026-07-07.
   Result: response generation was too slow and occasionally hung the app
   on this device's hardware. Rejected as the default — a Jarvis-like
   assistant that freezes is worse than one that needs a server.
2. **Cloud APIs only (Gemini/OpenAI/Anthropic)** — reliable and fast, but
   requires internet + an API key + ongoing cost, and doesn't fit the
   "always available, private" goal by itself.
3. **Server (Ollama / OpenAI-compatible endpoint) — CHOSEN DEFAULT.**
   Works great when:
   - Ollama runs locally on the phone itself (e.g. via Termux/PRoot
     `ollama serve`, pointed at `127.0.0.1`), or
   - Ollama/any compatible server runs on a PC/laptop on the same WiFi
     (point the app at that machine's LAN IP), or
   - A remote/cloud Ollama-compatible server is reachable over the internet.

   This gives near-instant, non-hanging responses because the heavy
   inference runs on hardware suited for it, while the phone just streams
   tokens over HTTP.

## What "settled" means in the code

- `ProviderConfig.kt` (`com.aladdin.app.provider`) — `preferredProvider`
  defaults to `"ollama"`. Users can still set it to `"local"` to explicitly
  opt into the fully offline on-device engine.
- `StreamingLLM.kt` (`com.aladdin.assistant.llm`) — provider priority is:
  1. Gemini, only if the user pastes an API key in Settings.
  2. **Ollama / OpenAI-compatible HTTP endpoint — the default.**
  3. On-device `llama.cpp` — only used if `preferredProvider == "local"`.
- Settings screen (`com.aladdin.app.ui.screens.SettingsScreen.kt`) exposes:
  - Host / Port / Model fields for the server, persisted via `ProviderConfig`.
  - A **"Test Connection"** button that pings the configured server and
    reports a clear ✅/⚠️/❌ result — so connectivity problems are caught in
    Settings, not as a silent failed chat message.
  - A toggle to explicitly opt into the on-device fallback, with an
    explanation of the tradeoff, so the choice is informed and reversible.

## Revisiting this decision

If a future device/model combination is fast enough to run on-device
inference smoothly, flip the default in `ProviderConfig.kt`
(`preferredProvider = "local"`) and re-run the same test used before:
send 5+ real chat turns and confirm no turn takes more than ~3s to start
streaming tokens, with no UI hang.
