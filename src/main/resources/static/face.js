'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let latest = null, inFlight = false, closed = false, retryAt = 0;
  const number = value => (Number(value) || 0).toFixed(1);
  const video = new VideoPreview.Player($('camera'), {
    unauthorized: () => { stop(); location.replace('/login'); },
    error: error => { showNotice(error.message); retryAt = Date.now() + 1500; },
    ended: () => { retryAt = Math.max(retryAt, Date.now() + 250); },
    frame: () => { $('cameraNotice').hidden = true; drawBox(); }
  });

  function showNotice(message) {
    $('cameraNotice').textContent = message;
    $('cameraNotice').hidden = false;
  }

  function drawBox() {
    const canvas = $('boxes'), camera = $('camera');
    if (canvas.width !== camera.width) canvas.width = camera.width;
    if (canvas.height !== camera.height) canvas.height = camera.height;
    const context = canvas.getContext('2d');
    context.clearRect(0, 0, canvas.width, canvas.height);
    const state = latest?.recognition, result = state?.recognition;
    if (!result?.box || Date.now() - state.frameAt > 2000 ||
        !latest.camera.lastFrame || Date.now() - latest.camera.lastFrame >= 1500) return;
    // The latest camera image and inference result can briefly have different sizes.
    const width = Number(latest.camera.width), height = Number(latest.camera.height);
    if (width !== canvas.width || height !== canvas.height) return;
    context.strokeStyle = result.accepted ? '#32deb2' : '#f0c268';
    context.lineWidth = Math.max(3, canvas.width / 400);
    context.strokeRect(...result.box);
  }

  async function update() {
    if (closed || document.hidden || inFlight) return;
    inFlight = true;
    try {
      const response = await fetch('/api/face/live', {credentials:'same-origin', cache:'no-store'});
      if (response.status === 401 || (response.redirected && new URL(response.url).pathname === '/login')) {
        stop(); location.replace('/login'); return;
      }
      const type = response.headers.get('Content-Type') || '';
      const status = type.includes('application/json') ? await response.json() : null;
      if (!response.ok || !status) throw Error(status?.message || `识别暂不可用（HTTP ${response.status}）`);
      latest = status;
      const camera = status.camera, recognition = status.recognition, result = recognition.recognition || {};
      const fresh = camera.lastFrame > 0 && Date.now() - camera.lastFrame < 1500;
      const resultFresh = fresh && recognition.frameAt > 0 && Date.now() - recognition.frameAt < 2000;
      $('message').textContent = recognition.message || '等待识别…';
      $('message').dataset.state = resultFresh && result.accepted ? 'recognized' : 'unknown';
      $('identity').textContent = resultFresh && result.accepted && result.employeeId
        ? `${result.employeeName} · ${result.employeeId} · 相似度 ${Number(result.similarity).toFixed(3)}` : '';
      $('cameraState').textContent = `${camera.state} · 采集 ${number(camera.captureFps)} FPS · 预览编码 ${number(camera.previewFps)} FPS · 识别 ${number(recognition.inferenceMs)} ms`;
      if (fresh) {
        if (!video.active && Date.now() >= retryAt) video.start('/api/camera/stream.mjpg?t=' + Date.now());
      } else {
        video.stop(); showNotice(camera.state || '等待摄像头画面');
      }
      drawBox();
    } catch (error) {
      latest = null; video.stop(); drawBox();
      $('identity').textContent = '';
      $('message').textContent = error.message;
      showNotice(error.message);
    } finally { inFlight = false; }
  }


  const statusTimer = setInterval(update, 400);
  function stop() { closed = true; clearInterval(statusTimer); video.stop(); }
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) video.stop(); else update();
  });
  window.addEventListener('pagehide', stop);
  window.addEventListener('pageshow', event => {
    if (event.persisted) location.reload();
  });
  update();
})();
