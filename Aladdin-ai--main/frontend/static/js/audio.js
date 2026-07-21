/**
 * Aladdin AI — Audio / TTS Frontend Module
 * Handles TTS playback, auto-play, streaming, and Browser SpeechSynthesis fallback.
 */

const AladdinAudio = (() => {
  let _audioCtx = null;
  let _currentAudio = null;
  let _userInteracted = false;
  let _baseUrl = '';

  // Track user interaction to unlock autoplay
  document.addEventListener('click', () => { _userInteracted = true; }, { once: false });
  document.addEventListener('keydown', () => { _userInteracted = true; }, { once: false });

  function _getAudioContext() {
    if (!_audioCtx) _audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (_audioCtx.state === 'suspended') _audioCtx.resume();
    return _audioCtx;
  }

  /** Update status indicator in the UI (if element exists). */
  function _setStatus(msg) {
    const el = document.getElementById('aladdin-audio-status');
    if (el) el.textContent = msg;
  }

  /** Stop any currently playing audio. */
  function stop() {
    if (_currentAudio) {
      _currentAudio.pause();
      _currentAudio.currentTime = 0;
      _currentAudio = null;
    }
    _setStatus('stopped');
  }

  /**
   * Speak text using the /tts API.
   * Falls back to Browser SpeechSynthesis if server TTS fails.
   * @param {string} text
   * @param {object} opts  { provider, voice_id, format, autoplay, onStart, onEnd }
   * @returns {Promise<HTMLAudioElement|null>}
   */
  async function speak(text, opts = {}) {
    if (!text) return null;
    const format   = opts.format    || 'mp3';
    const provider = opts.provider  || null;
    const voice_id = opts.voice_id  || null;
    const autoplay = opts.autoplay !== false;
    _setStatus('generating…');

    try {
      const res = await fetch(`${_baseUrl}/tts`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, provider, voice_id, format }),
      });

      if (!res.ok) throw new Error(`TTS HTTP ${res.status}`);

      const contentType = res.headers.get('content-type') || '';

      // Browser fallback instruction
      if (contentType.includes('application/json')) {
        const data = await res.json();
        if (data.provider === 'browser') {
          return _browserSpeech(data.text, opts);
        }
      }

      const blob = await res.blob();
      return _playBlob(blob, autoplay, opts.onStart, opts.onEnd);

    } catch (err) {
      console.warn('[AladdinAudio] Server TTS failed, falling back to browser speech:', err);
      _setStatus('using browser speech…');
      return _browserSpeech(text, opts);
    }
  }

  /** Stream TTS from /tts/stream using Media Source Extensions. */
  async function streamSpeak(text, opts = {}) {
    const format   = opts.format   || 'mp3';
    const provider = opts.provider || null;
    const voice_id = opts.voice_id || null;
    _setStatus('streaming…');

    try {
      const res = await fetch(`${_baseUrl}/tts/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, provider, voice_id, format }),
      });
      if (!res.ok) throw new Error(`TTS stream HTTP ${res.status}`);

      // Collect all chunks then play — simpler than MSE for broad compatibility
      const chunks = [];
      const reader = res.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
      }
      const blob = new Blob(chunks, { type: `audio/${format}` });
      return _playBlob(blob, true, opts.onStart, opts.onEnd);
    } catch (err) {
      console.warn('[AladdinAudio] Stream TTS failed:', err);
      return _browserSpeech(text, opts);
    }
  }

  function _playBlob(blob, autoplay, onStart, onEnd) {
    stop();
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    _currentAudio = audio;

    audio.onplay  = () => { _setStatus('playing…'); if (onStart) onStart(); };
    audio.onended = () => { _setStatus('idle'); URL.revokeObjectURL(url); if (onEnd) onEnd(); };
    audio.onerror = (e) => { _setStatus('error'); console.error('[AladdinAudio] Playback error', e); };

    if (autoplay) {
      const playPromise = audio.play();
      if (playPromise) {
        playPromise.catch(err => {
          console.warn('[AladdinAudio] Autoplay blocked:', err);
          _setStatus('click to play');
          // Show a play button so user can trigger manually
          _showPlayButton(() => audio.play());
        });
      }
    }
    return audio;
  }

  function _browserSpeech(text, opts = {}) {
    if (!('speechSynthesis' in window)) {
      console.error('[AladdinAudio] SpeechSynthesis not available');
      _setStatus('TTS unavailable');
      return null;
    }
    const utter = new SpeechSynthesisUtterance(text);
    utter.lang  = opts.language || navigator.language || 'en-US';
    utter.rate  = opts.rate  || 1.0;
    utter.pitch = opts.pitch || 1.0;

    if (opts.voice_id) {
      const voices = window.speechSynthesis.getVoices();
      const match = voices.find(v => v.name === opts.voice_id || v.voiceURI === opts.voice_id);
      if (match) utter.voice = match;
    }

    utter.onstart = () => { _setStatus('speaking…'); if (opts.onStart) opts.onStart(); };
    utter.onend   = () => { _setStatus('idle');      if (opts.onEnd)   opts.onEnd();   };
    utter.onerror = (e) => console.error('[AladdinAudio] SpeechSynthesis error', e);

    window.speechSynthesis.speak(utter);
    _setStatus('speaking (browser)…');
    return utter;
  }

  function _showPlayButton(onClick) {
    const existing = document.getElementById('aladdin-play-btn');
    if (existing) existing.remove();
    const btn = document.createElement('button');
    btn.id = 'aladdin-play-btn';
    btn.textContent = '▶ Play Response';
    btn.style.cssText = 'position:fixed;bottom:20px;right:20px;padding:10px 20px;font-size:16px;z-index:9999;cursor:pointer;border-radius:8px;background:#4CAF50;color:#fff;border:none;';
    btn.onclick = () => { onClick(); btn.remove(); };
    document.body.appendChild(btn);
  }

  /** Fetch and list available voices from the server. */
  async function getVoices() {
    try {
      const res = await fetch(`${_baseUrl}/tts/voices`);
      return await res.json();
    } catch (e) {
      console.warn('[AladdinAudio] getVoices failed:', e);
      return { voices: [], providers: [] };
    }
  }

  function setBaseUrl(url) { _baseUrl = url.replace(/\/$/, ''); }
  function pause()  { if (_currentAudio) _currentAudio.pause(); }
  function resume() { if (_currentAudio) _currentAudio.play();  }

  return { speak, streamSpeak, stop, pause, resume, getVoices, setBaseUrl };
})();

window.AladdinAudio = AladdinAudio;
