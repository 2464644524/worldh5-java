/*
 * 登录/进游戏过程追踪：只在游戏核心对象就绪前记录页面跳转、阶段和手动点击。
 * 不输出 URL query/hash（天宇 token 可能在 query 中），不输出输入框值/密码。
 * 通过 Playwright 暴露的 __worldTrace(text) 回传 Java 控制台。
 */
(function () {
  if (window.__worldLoginTraceInstalled) return;
  window.__worldLoginTraceInstalled = true;

  var lastUrl = safeUrl();
  var lastStage = "";
  var gameReady = false;
  var timer = null;

  function emit(msg) {
    try {
      if (typeof window.__worldTrace === "function") {
        window.__worldTrace(String(msg || ""));
      }
    } catch (e) {}
  }

  function safeUrl() {
    try {
      return location.origin + location.pathname;
    } catch (e) {
      return "";
    }
  }

  function visiblePanel(panel) {
    try {
      return !!(panel && panel.stage && panel.parent && panel.visible !== false);
    } catch (e) {
      return false;
    }
  }

  function readStage() {
    try {
      if (gameReady) return "游戏核心已就绪";

      // 登录/选角也是游戏内核加载后的场景，必须先于 xself 判断。
      if (typeof ImgCheckPanel !== "undefined"
          && typeof PanelManager !== "undefined"
          && PanelManager.isPanelShow
          && PanelManager.isPanelShow(ImgCheckPanel)) {
        return "图形验证码";
      }

      if (typeof Login !== "undefined" && Login.instance) {
        var login = Login.instance;
        if (login.createRoleScene && login.createRoleScene.stage
            && login.createRoleScene.visible !== false) {
          return "创建角色界面";
        }
        if (login.selectRoleScene && login.selectRoleScene.stage
            && login.selectRoleScene.visible !== false) {
          return "选择角色界面";
        }
      }

      if (typeof LoginPanel !== "undefined" && typeof PanelManager !== "undefined") {
        var panel = PanelManager.getPanel(LoginPanel);
        if (visiblePanel(panel)) {
          return "账号密码登录面板";
        }
      }

      if (typeof xself !== "undefined" && !!xself
          && typeof Control !== "undefined" && typeof nato !== "undefined") {
        gameReady = true;
        return "游戏核心已就绪（不在登录/选角界面）";
      }

      return "页面加载中";
    } catch (e) {
      return "页面加载中";
    }
  }

  function cleanText(value) {
    return String(value == null ? "" : value)
      .replace(/\s+/g, " ")
      .trim()
      .substring(0, 24);
  }

  function describeTarget(target, event) {
    if (!target || target === document || target === window) return "";

    var tag = target.tagName || "UNKNOWN";
    var parts = [tag];
    try {
      var type = target.type ? String(target.type) : "";
      if (type) parts.push("type=" + type.substring(0, 16));

      if (/^input$/i.test(tag) && /password/i.test(type)) {
        parts.push("密码框=***");
      } else if (!/^(input|textarea|select|canvas)$/i.test(tag)) {
        var text = cleanText(target.innerText || target.textContent);
        if (text) parts.push("text=" + text);
      }

      if (!/^input$/i.test(tag) || !/password/i.test(type)) {
        if (target.id) parts.push("id=" + cleanText(target.id));
        var role = target.getAttribute && target.getAttribute("role");
        if (role) parts.push("role=" + cleanText(role));
      }

      if (/^canvas$/i.test(tag) && target.getBoundingClientRect) {
        var rect = target.getBoundingClientRect();
        if (rect && rect.width > 0 && rect.height > 0) {
          var nx = (event.clientX - rect.left) / rect.width;
          var ny = (event.clientY - rect.top) / rect.height;
          parts.push("xy=" + nx.toFixed(3) + "," + ny.toFixed(3));
        }
      }
    } catch (e) {}
    return parts.join(" ");
  }

  document.addEventListener("click", function (event) {
    try {
      if (gameReady || !event.target || event.target.tagName === "CANVAS") return;
      var desc = describeTarget(event.target, event);
      if (desc) emit("手动点击 " + desc);
    } catch (e) {}
  }, true);

  document.addEventListener("pointerdown", function (event) {
    try {
      if (gameReady || !event.target || event.target.tagName !== "CANVAS") return;
      var desc = describeTarget(event.target, event);
      if (desc) emit("画布按下 " + desc);
    } catch (e) {}
  }, true);

  function poll() {
    try {
      var url = safeUrl();
      if (url !== lastUrl) {
        emit("页面跳转 " + lastUrl + " -> " + url);
        lastUrl = url;
      }

      var stage = readStage();
      if (stage !== lastStage) {
        emit("阶段：" + stage + " @ " + url);
        lastStage = stage;
      }

      if (gameReady && timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    } catch (e) {}
  }

  emit("页面加载 " + lastUrl);
  poll();
  timer = setInterval(poll, 500);
})();
