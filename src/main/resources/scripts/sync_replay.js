(function () {
  if (window.__worldSyncReplayInstalled) return;
  window.__worldSyncReplayInstalled = true;

  var TOUCH_ID = 2000;
  var POLL_MS = 8;
  var gestureTarget = null;
  var useMouseFallback = false;
  var generation = 0;
  var since = 0;
  var inflight = false;

  function gameBox() {
    var el = document.querySelector('.egret-player');
    if (el) {
      var r = el.getBoundingClientRect();
      if (r && r.width > 2 && r.height > 2) return r;
    }
    return { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight };
  }

  function isGameReady() {
    try {
      return !!document.querySelector('.egret-player') || typeof xself !== 'undefined';
    } catch (e) {
      return false;
    }
  }

  function dispatchTouch(type, p, target) {
    try {
      var touch = new Touch({
        identifier: TOUCH_ID, target: target,
        clientX: p.x, clientY: p.y,
        pageX: p.x + (window.scrollX || 0),
        pageY: p.y + (window.scrollY || 0),
        screenX: p.x, screenY: p.y,
        radiusX: 1, radiusY: 1, rotationAngle: 0, force: 1
      });
      var list = type === 'end' ? [] : [touch];
      var ev = new TouchEvent('touch' + type, {
        touches: list, targetTouches: list, changedTouches: [touch],
        bubbles: true, cancelable: true, composed: true
      });
      target.dispatchEvent(ev);
      return ev;
    } catch (e) {
      return null;
    }
  }

  function dispatchMouse(type, p, target) {
    try {
      var mouseType = type === 'start' ? 'mousedown' : type === 'move' ? 'mousemove' : 'mouseup';
      var buttons = type === 'end' ? 0 : 1;
      var ev = new MouseEvent(mouseType, {
        bubbles: true, cancelable: true, composed: true, view: window,
        detail: type === 'start' ? 1 : 0,
        screenX: p.x, screenY: p.y, clientX: p.x, clientY: p.y,
        button: 0, buttons: buttons, relatedTarget: null
      });
      var old = window.__worldSyncReplaying;
      window.__worldSyncReplaying = true;
      try { target.dispatchEvent(ev); } finally { window.__worldSyncReplaying = old; }
      return ev;
    } catch (e) {
      return null;
    }
  }

  function dispatch(type, nx, ny) {
    try {
      if (!Number.isFinite(nx) || !Number.isFinite(ny)) return;
      nx = Math.max(0, Math.min(1, nx));
      ny = Math.max(0, Math.min(1, ny));
      var r = gameBox();
      var p = { x: r.left + nx * r.width, y: r.top + ny * r.height };
      if (type === 'start' || !gestureTarget) {
        gestureTarget = document.elementFromPoint(p.x, p.y) || document.documentElement;
        useMouseFallback = false;
      }
      var target = gestureTarget || document.documentElement;
      var touchEvent = dispatchTouch(type, p, target);
      if (!touchEvent || !touchEvent.defaultPrevented) useMouseFallback = true;
      if (useMouseFallback) dispatchMouse(type, p, target);
      if (type === 'end') {
        gestureTarget = null;
        useMouseFallback = false;
      }
    } catch (e) {}
  }

  window.__worldSyncRecv = function (payload) {
    try {
      var d = typeof payload === 'string' ? JSON.parse(payload) : payload;
      if (d && typeof d.t === 'string') dispatch(d.t, d.x, d.y);
    } catch (e) {}
  };

  window.__worldSyncClear = function () {
    gestureTarget = null;
    useMouseFallback = false;
  };

  function endpoint() {
    return window.location.origin + '/__world_sync__?since=' + since;
  }

  function applyGeneration(gen) {
    var nextGen = Number(gen);
    if (!Number.isFinite(nextGen) || nextGen === generation) return;
    window.__worldSyncClear();
    generation = nextGen;
    since = 0;
  }

  function loop() {
    if (!isGameReady()) {
      setTimeout(loop, 120);
      return;
    }
    if (inflight) {
      setTimeout(loop, POLL_MS);
      return;
    }
    inflight = true;
    fetch(endpoint(), { cache: 'no-store', credentials: 'same-origin' })
      .then(function (response) { return response.json(); })
      .then(function (data) {
        if (!data || !data.ok) return;
        applyGeneration(data.gen);
        if (!data.on) return;
        var events = Array.isArray(data.events) ? data.events : [];
        for (var i = 0; i < events.length; i++) {
          var item = events[i];
          var seq = Number(item && item.seq);
          var d = item && item.d;
          if (!Number.isFinite(seq) || seq <= since || !d || typeof d.t !== 'string') continue;
          dispatch(d.t, d.x, d.y);
          since = seq;
        }
        var latest = Number(data.seq);
        if (Number.isFinite(latest)) since = Math.max(since, latest);
      })
      .catch(function () {})
      .finally(function () {
        inflight = false;
        setTimeout(loop, POLL_MS);
      });
  }

  loop();
})();
