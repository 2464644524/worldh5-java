(function () {
  // 只在每个页面的顶层窗口运行，嵌套 iframe 不自行跳转，避免循环。
  if (window.self !== window.top) return;
  if (window.__worldDesktopTianyuTopInstalled) return;
  window.__worldDesktopTianyuTopInstalled = true;

  var GAME_MARKER = 'worldh5.gamehz.cn/version/world/publish/channel/res/index.html';
  var RELEASE_MARKER = 'release.tianyuyou.cn/h5game/';

  function has(url, marker) {
    return !!url && url.indexOf(marker) !== -1;
  }

  function sameOriginSrc(iframe) {
    try {
      var loc = iframe.contentWindow && iframe.contentWindow.location;
      if (loc && loc.href && loc.href !== 'about:blank') return loc.href;
    } catch (e) { /* 跨域，忽略 */ }
    return '';
  }

  function findTarget() {
    var gameUrl = '';
    var releaseUrl = '';
    var frames = document.getElementsByTagName('iframe');
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      var src = (f.getAttribute && f.getAttribute('src')) || f.src || '';
      var dyn = sameOriginSrc(f);
      if (dyn && dyn !== src) src = dyn;
      if (!src) continue;
      if (has(src, GAME_MARKER)) { gameUrl = src; }
      else if (has(src, RELEASE_MARKER) && !releaseUrl) { releaseUrl = src; }
    }
    // 优先直接进游戏；还没拿到游戏地址时先跳到 release 中转页。
    return gameUrl || releaseUrl || '';
  }

  var redirecting = false;
  setInterval(function () {
    if (redirecting) return;
    var here = window.location.href;
    // 顶层已经是游戏直链（含手动存号后重开的直链），不要再跳，避免刷新循环。
    if (has(here, GAME_MARKER)) return;

    var target = findTarget();
    if (!target) return;
    // 目标和当前地址等价时不跳。
    if (target === here) return;

    redirecting = true;
    try {
      window.open(target, '_self');
    } finally {
      setTimeout(function () { redirecting = false; }, 1000);
    }
  }, 300);
})();