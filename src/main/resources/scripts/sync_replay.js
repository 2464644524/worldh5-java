(function () {
  if (window.__worldSyncReplayInstalled) return;
  window.__worldSyncReplayInstalled = true;

  var TOUCH_ID = 2000;
  var gestureTarget = null;
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

  function dispatch(type, nx, ny) {
    try {
      var r = gameBox();
      var cx = r.left + nx * r.width;
      var cy = r.top + ny * r.height;
      if (type === 'start' || !gestureTarget) {
        gestureTarget = document.elementFromPoint(cx, cy) || document.documentElement;
      }
      var target = gestureTarget || document.documentElement;
      var touch = new Touch({
        identifier: TOUCH_ID,
        target: target,
        clientX: cx,
        clientY: cy,
        pageX: cx + (window.scrollX || 0),
        pageY: cy + (window.scrollY || 0),
        screenX: cx,
        screenY: cy,
        radiusX: 1,
        radiusY: 1,
        rotationAngle: 0,
        force: 1
      });
      var list = type === 'end' ? [] : [touch];
      var ev = new TouchEvent('touch' + type, {
        touches: list,
        targetTouches: list,
        changedTouches: [touch],
        bubbles: true,
        cancelable: true,
        composed: true
      });
      target.dispatchEvent(ev);
      if (type === 'end') gestureTarget = null;
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
  };

  function endpoint() {
    return window.location.origin + '/__world_sync__?since=' + since;
  }

  function loop() {
    if (!isGameReady()) {
      setTimeout(loop, 200);
      return;
    }
    if (inflight) {
      setTimeout(loop, 24);
      return;
    }
    inflight = true;
    fetch(endpoint(), { cache: 'no-store', credentials: 'same-origin' })
      .then(function (response) { return response.json(); })
      .then(function (data) {
        if (!data || !data.ok) return;
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
        setTimeout(loop, 24);
      });
  }

  loop();
})();
