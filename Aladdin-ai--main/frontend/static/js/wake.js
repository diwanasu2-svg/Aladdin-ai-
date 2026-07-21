/**
 * Aladdin AI — Wake Word & Continuous Listening Frontend Module
 * Handles browser-side wake word, WebSocket streaming, and mic animation.
 */

const AladdinWake = (() => {
  let _ws         = null;
  let _mediaStream = null;
  let _recorder   = null;
  let _running    = false;
  let _wakeWords  = ['aladdin', 'hey aladdin'];
  let _sensitivity = 0.7;
  let _baseUrl    = '';
  let _wsUrl      = '';
  let _onWakeWord = null;
  let _onTranscript = null;
  let _onStatus   = null;
  let _recognition = null;  // Web Speech API for wake word

  // ── Mic animation helpers ──────────────────────────────────────────────────
  function _setMicState(state) {
    const mic = document.getElementById('aladdin-mic');
    if (!mic) return;
    mic.className = mic.className.replace(/\baladdin-mic-\S+/g, '');
    mic.classList.add(`aladdin-mic-${state}`);
    const label = document.getElementById('aladdin-mic-label');
    const labels = { idle: 'Idle', listening: '● Listening', wake: '🎉 Wake word!', processing: '⚙ Processing', error: '✕ Error' };
    if (label) label.textContent = labels[state] || state;
  }

  function _emit(type, data) {
    if (type === 'wake' && _onWakeWord) _onWakeWord(data);
    if (type === 'transcript' && _onTranscript) _onTranscript(data);
    if (type === 'status' && _onStatus) _onStatus(data);
    // Dispatch DOM event too
    document.dispatchEvent(new CustomEvent(`aladdin:${type}`, { detail: data }));
  }

  // ── WebSocket management ───────────────────────────────────────────────────
  function _connectWS() {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const host = _wsUrl || `${protocol}://${location.host}`;
    _ws = new WebSocket(`${host}/ws/listen`);
    _ws.binaryType = 'arraybuffer';

    _ws.onopen = () => {
      console.log('[AladdinWake] WS connected');
      _setMicState('listening');
      _emit('status', { state: 'connected' });
    };
    _ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data);
        if (msg.type === 'transcript') {
          _emit('transcript', msg);
          _setMicState('idle');
        } else if (msg.type === 'wake') {
          _emit('wake', msg);
          _setMicState('wake');
          setTimeout(() => _setMicState('listening'), 1000);
        } else if (msg.type === 'status') {
          _setMicState(msg.state || 'listening');
          _emit('status', msg);
        } else if (msg.type === 'error') {
          console.error('[AladdinWake] Server error:', msg.message);
          _setMicState('error');
        }
      } catch (e) {}
    };
    _ws.onclose = () => {
      console.log('[AladdinWake] WS closed');
      _setMicState('idle');
      // Auto-reconnect after 3s if still running
      if (_running) setTimeout(_connectWS, 3000);
    };
    _ws.onerror = (e) => { console.error('[AladdinWake] WS error', e); };
  }

  // ── MediaRecorder audio streaming ─────────────────────────────────────────
  async function _startMic() {
    if (_mediaStream) return;
    try {
      _mediaStream = await navigator.mediaDevices.getUserMedia({ audio: {
        sampleRate: 16000, channelCount: 1, echoCancellation: true, noiseSuppression: true,
      }});
    } catch (err) {
      console.error('[AladdinWake] Mic access denied:', err);
      _setMicState('error');
      throw err;
    }

    const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus' : 'audio/webm';

    _recorder = new MediaRecorder(_mediaStream, { mimeType, audioBitsPerSecond: 16000 });
    _recorder.ondataavailable = (ev) => {
      if (ev.data.size > 0 && _ws && _ws.readyState === WebSocket.OPEN) {
        ev.data.arrayBuffer().then(buf => _ws.send(buf));
      }
    };
    _recorder.start(100); // send every 100ms
  }

  function _stopMic() {
    if (_recorder) { try { _recorder.stop(); } catch(e){} _recorder = null; }
    if (_mediaStream) {
      _mediaStream.getTracks().forEach(t => t.stop());
      _mediaStream = null;
    }
  }

  // ── Browser SpeechRecognition for wake word (client-side) ─────────────────
  function _startSpeechRecognition() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;
    _recognition = new SR();
    _recognition.continuous = true;
    _recognition.interimResults = true;
    _recognition.lang = navigator.language || 'en-US';

    _recognition.onresult = (ev) => {
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        const transcript = ev.results[i][0].transcript.toLowerCase().trim();
        for (const ww of _wakeWords) {
          if (transcript.includes(ww)) {
            console.log('[AladdinWake] Browser wake word detected:', ww);
            _setMicState('wake');
            _emit('wake', { word: ww, source: 'browser_speech' });
            // Notify server
            if (_ws && _ws.readyState === WebSocket.OPEN) {
              _ws.send(JSON.stringify({ type: 'wake_detected', word: ww }));
            }
            break;
          }
        }
        if (ev.results[i].isFinal) {
          _emit('transcript', { text: ev.results[i][0].transcript, is_final: true, source: 'browser_speech' });
        }
      }
    };
    _recognition.onerror = (e) => { if (e.error !== 'no-speech') console.warn('[AladdinWake] SR error', e.error); };
    _recognition.onend = () => { if (_running) setTimeout(() => _recognition.start(), 500); };
    _recognition.start();
  }

  // ── Public API ─────────────────────────────────────────────────────────────
  async function start(opts = {}) {
    if (_running) return;
    _running = true;
    _wakeWords   = opts.wakeWords   || _wakeWords;
    _sensitivity = opts.sensitivity || _sensitivity;
    _onWakeWord  = opts.onWakeWord  || null;
    _onTranscript= opts.onTranscript|| null;
    _onStatus    = opts.onStatus    || null;

    _connectWS();
    await _startMic();
    _startSpeechRecognition();
    _setMicState('listening');
    console.log('[AladdinWake] Continuous listening started');
  }

  function stop() {
    _running = false;
    _stopMic();
    if (_ws) { _ws.close(); _ws = null; }
    if (_recognition) { try { _recognition.stop(); } catch(e){} _recognition = null; }
    _setMicState('idle');
    console.log('[AladdinWake] Listening stopped');
  }

  function isRunning() { return _running; }
  function setBaseUrl(url) { _baseUrl = url.replace(/\/$/, ''); _wsUrl = url.replace(/^http/, 'ws').replace(/\/$/, ''); }

  /**
   * Inject the mic animation HTML into a container element.
   * @param {string} containerId  The ID of a DOM element to inject into.
   */
  function injectMicUI(containerId) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = `
      <style>
        #aladdin-mic { display:flex;flex-direction:column;align-items:center;gap:8px;cursor:pointer; }
        .aladdin-mic-btn { width:64px;height:64px;border-radius:50%;border:3px solid #888;display:flex;align-items:center;justify-content:center;font-size:28px;transition:all .3s; }
        .aladdin-mic-idle .aladdin-mic-btn { background:#f5f5f5;border-color:#888; }
        .aladdin-mic-listening .aladdin-mic-btn { background:#e3f2fd;border-color:#2196F3;animation:pulse 1.2s infinite; }
        .aladdin-mic-wake .aladdin-mic-btn { background:#e8f5e9;border-color:#4CAF50;animation:wake-pulse .4s 3; }
        .aladdin-mic-processing .aladdin-mic-btn { background:#fff3e0;border-color:#FF9800;animation:spin 1s linear infinite; }
        .aladdin-mic-error .aladdin-mic-btn { background:#ffebee;border-color:#f44336; }
        @keyframes pulse { 0%,100%{box-shadow:0 0 0 0 rgba(33,150,243,.4)} 50%{box-shadow:0 0 0 12px rgba(33,150,243,0)} }
        @keyframes wake-pulse { 0%,100%{transform:scale(1)} 50%{transform:scale(1.15)} }
        @keyframes spin { to{transform:rotate(360deg)} }
        #aladdin-mic-label { font-size:13px;color:#555; }
      </style>
      <div id="aladdin-mic" class="aladdin-mic-idle" onclick="AladdinWake.isRunning() ? AladdinWake.stop() : AladdinWake.start()">
        <div class="aladdin-mic-btn">🎤</div>
        <span id="aladdin-mic-label">Idle</span>
      </div>`;
  }

  return { start, stop, isRunning, setBaseUrl, injectMicUI };
})();

window.AladdinWake = AladdinWake;
