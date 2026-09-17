(function () {
  // 直接控制游戏画面盒子的大小（Egret showAll 会把 640x960 设计画面等比缩放到该盒子），
  // 从而绕开 Edge 窗口最小宽度无法拖窄的限制。盒子居中，周围黑边。
  if (window.__worldGameScaleInstalled) return;
  window.__worldGameScaleInstalled = true;

  var scale = Number(window.__worldGameScale);
  if (!(scale > 0)) scale = 1;
  var lastKey = '';

  function clamp(v) {
    v = Number(v);
    if (!(v > 0)) v = 1;
    return Math.max(0.35, Math.min(1.2, v));
  }

  function apply(force) {
    var k = clamp(window.__worldGameScale);
    var el = document.querySelector('.egret-player');
    if (!el) return;
    var w = Math.round(window.innerWidth * k);
    var h = Math.round(window.innerHeight * k);
    var key = w + 'x' + h;
    el.style.setProperty('width', w + 'px', 'important');
    el.style.setProperty('height', h + 'px', 'important');
    el.style.setProperty('position', 'fixed', 'important');
    el.style.setProperty('left', '0', 'important');
    el.style.setProperty('right', '0', 'important');
    el.style.setProperty('top', '0', 'important');
    el.style.setProperty('bottom', '0', 'important');
    el.style.setProperty('margin', 'auto', 'important');
    el.style.setProperty('z-index', '1', 'important');
    document.documentElement.style.background = '#000';
    document.body.style.background = '#000';
    // 仅在尺寸真正变化时通知 Egret 重算，避免定时刷 resize 干扰游戏。
    if (force || key !== lastKey) {
      lastKey = key;
      try { window.dispatchEvent(new Event('resize')); } catch (e) {}
    }
  }

  window.__worldGameSetScale = function (k) {
    window.__worldGameScale = clamp(k);
    try { localStorage.setItem('worldGameScale', String(window.__worldGameScale)); } catch (e) {}
    lastKey = '';
    apply(true);
  };

  try {
    var saved = parseFloat(localStorage.getItem('worldGameScale'));
    if (saved > 0) { scale = clamp(saved); window.__worldGameScale = scale; }
  } catch (e) {}

  window.addEventListener('resize', function () { apply(false); });
  window.addEventListener('DOMContentLoaded', function () { apply(true); });
  setInterval(function () { apply(false); }, 500);
  apply(true);
})();