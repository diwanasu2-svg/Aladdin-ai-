/**
 * Aladdin AI — Vision Frontend Module
 * Handles image upload, camera capture, drag-and-drop, and vision API calls.
 */

const AladdinVision = (() => {
  let _baseUrl = '';
  let _currentStream = null;

  // ── Helpers ────────────────────────────────────────────────────────────────
  function _setStatus(msg) {
    const el = document.getElementById('aladdin-vision-status');
    if (el) el.textContent = msg;
  }

  function _post(endpoint, formData) {
    return fetch(`${_baseUrl}${endpoint}`, { method: 'POST', body: formData });
  }

  // ── Core API calls ─────────────────────────────────────────────────────────

  /**
   * Analyze an image file with the vision API.
   * @param {File|Blob} file
   * @param {string} prompt
   * @param {string} provider  'gpt4'|'gemini'|null
   * @returns {Promise<object>}
   */
  async function analyze(file, prompt = 'Describe this image.', provider = null) {
    _setStatus('analyzing…');
    const fd = new FormData();
    fd.append('file', file);
    fd.append('prompt', prompt);
    if (provider) fd.append('provider', provider);
    try {
      const res = await _post('/vision', fd);
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
      const data = await res.json();
      _setStatus('done');
      return data;
    } catch (err) {
      _setStatus('error');
      throw err;
    }
  }

  /** Extract text via OCR. */
  async function ocr(file, language = null) {
    _setStatus('extracting text…');
    const fd = new FormData();
    fd.append('file', file);
    if (language) fd.append('language', language);
    const res = await _post('/vision/ocr', fd);
    if (!res.ok) throw new Error(`OCR HTTP ${res.status}`);
    _setStatus('done');
    return await res.json();
  }

  /** Detect objects in an image. */
  async function detectObjects(file) {
    _setStatus('detecting objects…');
    const fd = new FormData();
    fd.append('file', file);
    const res = await _post('/vision/detect', fd);
    if (!res.ok) throw new Error(`Detect HTTP ${res.status}`);
    _setStatus('done');
    return await res.json();
  }

  /** Analyze a screenshot. */
  async function analyzeScreenshot(file, context = null) {
    _setStatus('analyzing screenshot…');
    const fd = new FormData();
    fd.append('file', file);
    if (context) fd.append('context', context);
    const res = await _post('/vision/screenshot', fd);
    if (!res.ok) throw new Error(`Screenshot HTTP ${res.status}`);
    _setStatus('done');
    return await res.json();
  }

  /** Analyze a PDF file. */
  async function analyzePDF(file) {
    _setStatus('extracting PDF…');
    const fd = new FormData();
    fd.append('file', file);
    const res = await _post('/vision/pdf', fd);
    if (!res.ok) throw new Error(`PDF HTTP ${res.status}`);
    _setStatus('done');
    return await res.json();
  }

  /**
   * Stream vision analysis, calling onChunk for each token.
   */
  async function streamAnalyze(file, prompt = 'Describe this image.', onChunk, provider = null) {
    _setStatus('streaming analysis…');
    const fd = new FormData();
    fd.append('file', file);
    fd.append('prompt', prompt);
    if (provider) fd.append('provider', provider);
    const res = await _post('/vision/stream', fd);
    if (!res.ok) throw new Error(`Stream HTTP ${res.status}`);
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const lines = decoder.decode(value).split('\n');
      for (const line of lines) {
        if (line.startsWith('data: ')) {
          try {
            const d = JSON.parse(line.slice(6));
            if (d.text && onChunk) onChunk(d.text);
            if (d.done) { _setStatus('done'); return; }
          } catch (e) {}
        }
      }
    }
  }

  // ── Camera capture ─────────────────────────────────────────────────────────

  /** Open camera and return a video element with the live stream. */
  async function openCamera(videoElementId) {
    if (_currentStream) closeCamera();
    try {
      _currentStream = await navigator.mediaDevices.getUserMedia({ video: true });
      const video = document.getElementById(videoElementId);
      if (video) { video.srcObject = _currentStream; video.play(); }
      _setStatus('camera active');
      return _currentStream;
    } catch (err) {
      _setStatus('camera denied');
      throw err;
    }
  }

  /** Capture a photo from an active video element. Returns a Blob. */
  function capturePhoto(videoElementId, canvasElementId) {
    const video = document.getElementById(videoElementId);
    const canvas = document.getElementById(canvasElementId) || document.createElement('canvas');
    if (!video) throw new Error('Video element not found');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(video, 0, 0);
    return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.92));
  }

  function closeCamera() {
    if (_currentStream) {
      _currentStream.getTracks().forEach(t => t.stop());
      _currentStream = null;
      _setStatus('camera closed');
    }
  }

  // ── Drag & Drop ────────────────────────────────────────────────────────────

  /**
   * Enable drag-and-drop on a DOM element.
   * @param {string} dropZoneId
   * @param {function} onDrop  Called with (files: FileList)
   */
  function enableDragDrop(dropZoneId, onDrop) {
    const zone = document.getElementById(dropZoneId);
    if (!zone) return;

    const highlight  = () => zone.classList.add('aladdin-drop-active');
    const unhighlight = () => zone.classList.remove('aladdin-drop-active');

    zone.addEventListener('dragover', (e) => { e.preventDefault(); highlight(); });
    zone.addEventListener('dragenter', highlight);
    zone.addEventListener('dragleave', unhighlight);
    zone.addEventListener('drop', (e) => {
      e.preventDefault();
      unhighlight();
      const files = e.dataTransfer.files;
      if (!files.length) return;
      // Validate types
      const valid = Array.from(files).filter(f => f.type.startsWith('image/') || f.type === 'application/pdf');
      if (!valid.length) { _setStatus('invalid file type'); return; }
      if (onDrop) onDrop(valid);
    });

    // Inject minimal drop zone styles
    if (!document.getElementById('aladdin-drop-styles')) {
      const style = document.createElement('style');
      style.id = 'aladdin-drop-styles';
      style.textContent = `
        .aladdin-drop-zone { border:2px dashed #888;border-radius:12px;padding:30px;text-align:center;transition:all .2s;cursor:pointer; }
        .aladdin-drop-active { border-color:#2196F3;background:rgba(33,150,243,.08);transform:scale(1.01); }
      `;
      document.head.appendChild(style);
    }
    zone.classList.add('aladdin-drop-zone');
  }

  /**
   * Inject a complete vision UI panel into a container.
   */
  function injectVisionUI(containerId, opts = {}) {
    const container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = `
      <div style="display:flex;flex-direction:column;gap:12px;max-width:600px;">
        <div id="aladdin-vision-drop" style="min-height:120px;display:flex;align-items:center;justify-content:center;color:#888;">
          📁 Drop image / PDF here, or <label style="color:#2196F3;cursor:pointer;margin-left:4px;">
            browse<input type="file" id="aladdin-vision-file" accept="image/*,.pdf" style="display:none">
          </label>
        </div>
        <div style="display:flex;gap:8px;align-items:center;">
          <input id="aladdin-vision-prompt" type="text" value="Describe this image." style="flex:1;padding:8px;border:1px solid #ddd;border-radius:6px;">
          <button id="aladdin-vision-analyze-btn" style="padding:8px 16px;background:#2196F3;color:#fff;border:none;border-radius:6px;cursor:pointer;">Analyze</button>
          <button id="aladdin-vision-camera-btn" style="padding:8px 12px;background:#4CAF50;color:#fff;border:none;border-radius:6px;cursor:pointer;">📷</button>
        </div>
        <div id="aladdin-vision-status" style="font-size:12px;color:#888;">ready</div>
        <div id="aladdin-vision-preview" style="display:none;"><img id="aladdin-vision-img" style="max-width:100%;border-radius:8px;max-height:300px;object-fit:contain;"></div>
        <video id="aladdin-vision-video" style="display:none;max-width:100%;border-radius:8px;max-height:300px;" playsinline></video>
        <canvas id="aladdin-vision-canvas" style="display:none;"></canvas>
        <pre id="aladdin-vision-result" style="background:#f5f5f5;padding:12px;border-radius:8px;white-space:pre-wrap;display:none;max-height:300px;overflow:auto;font-size:13px;"></pre>
      </div>`;

    let _selectedFile = null;
    enableDragDrop('aladdin-vision-drop', (files) => {
      _selectedFile = files[0];
      const preview = document.getElementById('aladdin-vision-preview');
      const img = document.getElementById('aladdin-vision-img');
      if (_selectedFile.type.startsWith('image/')) {
        img.src = URL.createObjectURL(_selectedFile);
        preview.style.display = 'block';
      } else {
        preview.style.display = 'none';
      }
      _setStatus(`Selected: ${_selectedFile.name}`);
    });

    document.getElementById('aladdin-vision-file').onchange = (e) => {
      _selectedFile = e.target.files[0];
      if (_selectedFile) {
        const img = document.getElementById('aladdin-vision-img');
        img.src = URL.createObjectURL(_selectedFile);
        document.getElementById('aladdin-vision-preview').style.display = 'block';
        _setStatus(`Selected: ${_selectedFile.name}`);
      }
    };

    document.getElementById('aladdin-vision-analyze-btn').onclick = async () => {
      if (!_selectedFile) { _setStatus('Select an image first'); return; }
      const prompt = document.getElementById('aladdin-vision-prompt').value;
      const result = document.getElementById('aladdin-vision-result');
      result.style.display = 'block';
      result.textContent = '';
      try {
        if (_selectedFile.type === 'application/pdf') {
          const data = await analyzePDF(_selectedFile);
          result.textContent = JSON.stringify(data, null, 2);
        } else {
          await streamAnalyze(_selectedFile, prompt, (t) => { result.textContent += t; }, null);
        }
      } catch (err) {
        result.textContent = `Error: ${err.message}`;
      }
    };

    let _cameraOpen = false;
    document.getElementById('aladdin-vision-camera-btn').onclick = async () => {
      const video = document.getElementById('aladdin-vision-video');
      if (!_cameraOpen) {
        await openCamera('aladdin-vision-video');
        video.style.display = 'block';
        document.getElementById('aladdin-vision-camera-btn').textContent = '📸 Capture';
        _cameraOpen = true;
      } else {
        const blob = await capturePhoto('aladdin-vision-video', 'aladdin-vision-canvas');
        _selectedFile = new File([blob], 'capture.jpg', { type: 'image/jpeg' });
        const img = document.getElementById('aladdin-vision-img');
        img.src = URL.createObjectURL(_selectedFile);
        document.getElementById('aladdin-vision-preview').style.display = 'block';
        closeCamera();
        video.style.display = 'none';
        document.getElementById('aladdin-vision-camera-btn').textContent = '📷';
        _cameraOpen = false;
        _setStatus('Photo captured — click Analyze');
      }
    };
  }

  function setBaseUrl(url) { _baseUrl = url.replace(/\/$/, ''); }

  return { analyze, ocr, detectObjects, analyzeScreenshot, analyzePDF,
           streamAnalyze, openCamera, capturePhoto, closeCamera,
           enableDragDrop, injectVisionUI, setBaseUrl };
})();

window.AladdinVision = AladdinVision;
