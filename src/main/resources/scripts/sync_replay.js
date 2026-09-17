(function () {
  // 被控号：接收主控归一化坐标，在本号游戏画面盒对应位置重放 Touch 事件。
  if (window.__worldSyncReplayInstalled) return;
  window.__worldSyncReplayInstalled = true;

  var TOUCH_ID = 2000;
  var gestureTarget = null;

  function gameBox() {
    var el = document.querySelector('.egret-player');
    if (el) {
      var r = el.getBoundingClientRect();
      if (r && r.width > 2 && r.height > 2) return r;
    }
    return { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight };
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

  // Java 侧 evaluate 调用；入参可能是 JSON 字符串，也可能直接是对象。
  window.__worldSyncRecv = function (payload) {
    try {
      var d = typeof payload === 'string' ? JSON.parse(payload) : payload;
      if (!d || typeof d.t !== 'string') return;
      if (typeof d.x !== 'number' || typeof d.y !== 'number') return;
      dispatch(d.t, d.x, d.y);
    } catch (e) {}
  };

  window.__worldSyncClear = function () {
    gestureTarget = null;
  };
})();