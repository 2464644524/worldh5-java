(function () {
  if (window.__worldDesktopIframeRedirectInstalled) return;
  window.__worldDesktopIframeRedirectInstalled = true;

  var GAME_MARKER = 'worldh5.gamehz.cn/version/world/publish/channel/res/index.html';
  var redirecting = false;

  function hasMarker(url) {
    return !!url && url.indexOf(GAME_MARKER) !== -1;
  }

  function redirectOnce(url) {
    if (!url || redirecting) return;

    // 顶层已经是游戏页时，绝不再跳到页面内部的同名游戏 iframe。
    if (hasMarker(window.location.href)) return;

    redirecting = true;
    try {
      window.location.href = url;
    } finally {
      setTimeout(function () { redirecting = false; }, 1000);
    }
  }

  setInterval(function () {
    var frames = document.getElementsByTagName('iframe');
    for (var i = 0; i < frames.length; i++) {
      var src = frames[i] && frames[i].src;
      if (hasMarker(src)) {
        redirectOnce(src);
        return;
      }
    }
  }, 500);
})();