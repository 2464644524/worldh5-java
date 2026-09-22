(function () {
  // 主控号：捕获本机物理鼠标手势，归一化到 Egret 游戏画面盒坐标后，
  // 通过 Playwright 暴露的 __worldSyncSend 回传 Java 并发布到同步总线。
  if (window.__worldSyncCaptureInstalled) return;
  window.__worldSyncCaptureInstalled = true;

  var down = false;
  var lastNX = 0;
  var lastNY = 0;
  var throttleUntil = 0;

  function normalize(e) {
    var el = document.querySelector('.egret-player');
    if (el) {
      var r = el.getBoundingClientRect();
      if (r && r.width > 2 && r.height > 2) {
        return { x: (e.clientX - r.left) / r.width, y: (e.clientY - r.top) / r.height };
      }
    }
    return { x: e.clientX / window.innerWidth, y: e.clientY / window.innerHeight };
  }

  function emit(type, nx, ny) {
    try {
      if (typeof window.__worldSyncSend === 'function') {
        window.__worldSyncSend(JSON.stringify({ t: type, x: nx, y: ny }));
      }
    } catch (e) {}
  }

  function onDown(e) {
    if (!e.isTrusted || e.button !== 0) return;
    var p = normalize(e);
    down = true;
    lastNX = p.x;
    lastNY = p.y;
    emit('start', p.x, p.y);
  }

  function onMove(e) {
    if (!e.isTrusted || !down) return;
    var now = Date.now();
    if (now < throttleUntil) return;
    throttleUntil = now + 16;
    var p = normalize(e);
    lastNX = p.x;
    lastNY = p.y;
    emit('move', p.x, p.y);
  }

  function onUp(e) {
    if (!e.isTrusted || !down) return;
    down = false;
    var p = normalize(e);
    emit('end', p.x, p.y);
  }

  function onCancel() {
    if (!down) return;
    down = false;
    emit('end', lastNX, lastNY);
  }

  // 捕获阶段监听，游戏内部 stopPropagation 也挡不住；只接收物理事件，避免合成事件回环。
  document.addEventListener('mousedown', onDown, { capture: true, passive: true });
  window.addEventListener('mousemove', onMove, { capture: true, passive: true });
  window.addEventListener('mouseup', onUp, { capture: true, passive: true });
  window.addEventListener('blur', onCancel, { capture: true, passive: true });
  document.addEventListener('mousecancel', onCancel, { capture: true, passive: true });
})();
