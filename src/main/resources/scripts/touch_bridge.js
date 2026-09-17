(function () {
  if (window.__worldTouchBridgeInstalled) return;
  window.__worldTouchBridgeInstalled = true;

  try {
    var noopTouchHandler = { value: null, writable: true, configurable: true };
    if (!('ontouchstart' in window)) {
      Object.defineProperty(window, 'ontouchstart', noopTouchHandler);
    }
    if (!('ontouchstart' in document)) {
      Object.defineProperty(document, 'ontouchstart', noopTouchHandler);
    }
    if (!('ontouchstart' in document.documentElement)) {
      Object.defineProperty(document.documentElement, 'ontouchstart', noopTouchHandler);
    }
    Object.defineProperty(Navigator.prototype, 'maxTouchPoints', {
      get: function () { return 5; }, configurable: true
    });
  } catch (e) {}

  var TOUCH_ID = 1000;
  var touching = false;

  function createTouch(type, x, y, target) {
    try {
      var touch = new Touch({
        identifier: TOUCH_ID,
        target: target,
        clientX: x,
        clientY: y,
        pageX: x + (window.scrollX || 0),
        pageY: y + (window.scrollY || 0),
        screenX: x,
        screenY: y,
        radiusX: 1,
        radiusY: 1,
        rotationAngle: 0,
        force: 1
      });
      var active = type === 'touchend' ? [] : [touch];
      return new TouchEvent(type, {
        touches: active,
        targetTouches: active,
        changedTouches: [touch],
        bubbles: true,
        cancelable: true,
        composed: true
      });
    } catch (e) {
      return null;
    }
  }

  function locate(e) {
    var target = e.target;
    try {
      var hit = document.elementFromPoint(e.clientX, e.clientY);
      if (hit) target = hit;
    } catch (err) {}
    return { x: e.clientX, y: e.clientY, target: target };
  }

  function dispatch(type, e) {
    var p = locate(e);
    if (!p.target) return;
    var touchEvent = createTouch(type, p.x, p.y, p.target);
    if (!touchEvent) return;
    p.target.dispatchEvent(touchEvent);
    if (touchEvent.defaultPrevented) {
      e.preventDefault();
    }
  }

  document.addEventListener('mousedown', function (e) {
    if (e.button !== 0) return;
    touching = true;
    dispatch('touchstart', e);
  }, { capture: true, passive: false });

  document.addEventListener('mousemove', function (e) {
    if (!touching) return;
    dispatch('touchmove', e);
  }, { capture: true, passive: false });

  function endTouch(e) {
    if (!touching) return;
    touching = false;
    dispatch('touchend', e);
  }

  document.addEventListener('mouseup', endTouch, { capture: true, passive: false });
  document.addEventListener('mousecancel', function (e) {
    if (!touching) return;
    touching = false;
    dispatch('touchcancel', e);
  }, { capture: true, passive: false });

  window.addEventListener('blur', function () { touching = false; });
})();