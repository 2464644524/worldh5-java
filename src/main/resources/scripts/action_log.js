/*
 * 游戏内操作记录：只做旁路采集，不点击、不改游戏状态、不发网络包。
 * 记录手动点击/拖动、面板打开关闭、任务交互和游戏网络指令，写入本机 data/操作记录.txt。
 */
(function () {
  if (window.__worldActionLogInstalled) return;
  window.__worldActionLogInstalled = true;

  var MAX_TEXT_LEN = 80;
  var pendingFactory = new WeakMap();
  var lastRawNetworkKey = "";
  var lastRawNetworkAt = 0;

  function emit(msg) {
    try {
      if (typeof window.__worldAction === "function" && msg) {
        window.__worldAction(String(msg));
      }
    } catch (e) {}
  }

  function safe(v) {
    try {
      if (v == null) return "";
      if (typeof v === "number" || typeof v === "boolean") return String(v);
      if (typeof v === "string") {
        var t = v.replace(/\s+/g, " ").trim();
        return t.length > MAX_TEXT_LEN ? t.slice(0, MAX_TEXT_LEN) + "…" : t;
      }
      if (typeof v.getId === "function") {
        var id = v.getId();
        var name = readChinese(v);
        return name ? name + "#" + id : "#" + id;
      }
      var ctor = v.constructor && v.constructor.name;
      return ctor && ctor !== "Object" ? ctor : Object.prototype.toString.call(v).slice(8, -1);
    } catch (e) {
      return "";
    }
  }

  function readChinese(obj) {
    if (!obj) return "";
    var names = ["getName", "getTitle", "getPanelName", "getText"];
    for (var i = 0; i < names.length; i++) {
      try {
        if (typeof obj[names[i]] === "function") {
          var v = obj[names[i]]();
          if (typeof v === "string" && /[\u4e00-\u9fa5]/.test(v)) {
            return v.replace(/\s+/g, " ").trim().slice(0, 24);
          }
        }
      } catch (e) {}
    }
    var fields = ["name", "title", "panelName", "_title", "_name"];
    for (var j = 0; j < fields.length; j++) {
      try {
        var f = obj[fields[j]];
        if (typeof f === "string" && /[\u4e00-\u9fa5]/.test(f)) {
          return f.replace(/\s+/g, " ").trim().slice(0, 24);
        }
      } catch (e) {}
    }
    return "";
  }

  function argsText(args) {
    var parts = [];
    for (var i = 0; i < args.length && i < 5; i++) {
      var t = safe(args[i]);
      if (t) parts.push(t);
    }
    return parts.length ? " 参数=" + parts.join(",") : "";
  }

  function num(v, digits) {
    var n = Number(v);
    return Number.isFinite(n) ? Number(n.toFixed(digits || 3)) : 0;
  }

  function gamePoint(event) {
    try {
      var host = document.querySelector(".egret-player") ||
        (event.target && event.target.tagName === "CANVAS" ? event.target : null);
      if (host && host.getBoundingClientRect) {
        var r = host.getBoundingClientRect();
        if (r.width > 2 && r.height > 2) {
          return {
            x: (event.clientX - r.left) / r.width,
            y: (event.clientY - r.top) / r.height
          };
        }
      }
      return { x: event.clientX / Math.max(1, window.innerWidth), y: event.clientY / Math.max(1, window.innerHeight) };
    } catch (e) {
      return { x: 0, y: 0 };
    }
  }

  function playerContext() {
    var parts = [];
    try {
      if (typeof xworld !== "undefined" && xworld) {
        if (typeof xworld.getCurMapID === "function") parts.push("地图=" + xworld.getCurMapID());
        if (typeof xworld.isInCityNow === "function") {
          try { if (xworld.isInCityNow()) parts.push("城内"); } catch (e) {}
        }
      }
    } catch (e) {}
    try {
      if (typeof xself !== "undefined" && xself) {
        var x = null, y = null;
        if (x == null && typeof xself.getXKey === "function") x = xself.getXKey();
        if (y == null && typeof xself.getYKey === "function") y = xself.getYKey();
        if (x == null && typeof xself.getX === "function") x = xself.getX();
        if (y == null && typeof xself.getY === "function") y = xself.getY();
        if ((x == null || y == null) && typeof xself.getPosition === "function") {
          var p = xself.getPosition();
          if (p) { x = p.x; y = p.y; }
        }
        if (x == null) x = xself.x;
        if (y == null) y = xself.y;
        if (Number.isFinite(Number(x)) && Number.isFinite(Number(y))) {
          parts.push("坐标=" + num(Number(x), 1) + "," + num(Number(y), 1));
        }
      }
    } catch (e) {}
    return parts.length ? "（" + parts.join("，") + "）" : "";
  }

  function objectClassName(obj) {
    try {
      if (typeof obj === "function" && obj.name) return obj.name;
      if (!obj || !obj.constructor) return "";
      var n = obj.constructor.name;
      if (n && n !== "Object" && n !== "Function") return n;
      for (var k in window) {
        try {
          if (window[k] === obj.constructor && /^[A-Za-z_$][\w$]*$/.test(k)) return k;
        } catch (e) {}
      }
    } catch (e) {}
    return "";
  }

  function panelName(panel) {
    var title = readChinese(panel);
    var cls = objectClassName(panel);
    if (title && cls) return title + "(" + cls + ")";
    return title || cls || "未知面板";
  }

  function installInput() {
    var down = false;
    var start = null;
    var startAt = 0;

    window.addEventListener("mousedown", function (e) {
      if (e.button !== 0) return;
      down = true;
      start = gamePoint(e);
      startAt = Date.now();
    }, { capture: true, passive: true });

    window.addEventListener("mouseup", function (e) {
      if (!down || e.button !== 0) return;
      down = false;
      var end = gamePoint(e);
      var dx = end.x - (start ? start.x : end.x);
      var dy = end.y - (start ? start.y : end.y);
      var dist = Math.sqrt(dx * dx + dy * dy);
      var duration = Date.now() - startAt;
      if (dist < 0.018 || duration > 600 && dist < 0.035) {
        emit("手动点击 xy=" + num(end.x) + "," + num(end.y) + playerContext());
      } else {
        emit("手动拖动 " + num(start.x) + "," + num(start.y) + " -> " + num(end.x) + "," + num(end.y)
          + " 距离=" + num(dist) + " 用时=" + duration + "ms" + playerContext());
      }
    }, { capture: true, passive: true });

    window.addEventListener("blur", function () { down = false; }, { capture: true, passive: true });
  }

  function installPanels() {
    function wrap(manager, method, verb) {
      try {
        if (!manager || typeof manager[method] !== "function" || manager["__world" + method + "Hooked"]) return;
        var orig = manager[method];
        manager[method] = function (panel) {
          try { emit(verb + " " + panelName(panel) + playerContext()); } catch (e) {}
          return orig.apply(this, arguments);
        };
        manager["__world" + method + "Hooked"] = true;
      } catch (e) {}
    }
    var timer = setInterval(function () {
      try {
        if (typeof PopUpManager !== "undefined" && PopUpManager) {
          wrap(PopUpManager, "addPopUp", "打开面板");
          wrap(PopUpManager, "removePopUp", "关闭面板");
          if (PopUpManager.__worldaddPopUpHooked) clearInterval(timer);
        }
      } catch (e) {}
    }, 500);
    setTimeout(function () { clearInterval(timer); }, 30000);
  }

  function installMissions() {
    try {
      if (typeof Mission === "undefined" || !Mission || Mission.__worldActionHooked) return;
      if (typeof Mission.doMenuButton === "function") {
        var origMission = Mission.doMenuButton;
        Mission.doMenuButton = function (npc, arg2, subType, mission) {
          try { emit("任务菜单 " + missionText(npc, mission) + playerContext()); } catch (e) {}
          return origMission.apply(this, arguments);
        };
      }
      if (typeof NPC !== "undefined" && NPC && typeof NPC.handlerMissionNPCAction === "function") {
        var origNpc = NPC.handlerMissionNPCAction;
        NPC.handlerMissionNPCAction = function (npc, action) {
          try {
            var mission = action && action.data ? action.data.mission : null;
            if (mission) emit("任务对话 " + missionText(npc, mission) + playerContext());
          } catch (e) {}
          return origNpc.apply(this, arguments);
        };
      }
      Mission.__worldActionHooked = true;
    } catch (e) {}
  }

  function missionText(npc, mission) {
    try {
      var name = mission ? readChinese(mission) : "";
      var id = "";
      try { id = typeof mission.getId === "function" ? mission.getId() : mission.id; } catch (e) {}
      var status = "";
      try {
        if (mission && typeof mission.getMissionStatus === "function" && typeof MissionConst !== "undefined") {
          status = constName(MissionConst, mission.getMissionStatus(xself)) || "";
        }
      } catch (e) {}
      var npcName = npc ? readChinese(npc) : "";
      var label = name || ("任务#" + (id == null ? "?" : id));
      var text = label + "(id=" + (id == null ? "-" : id);
      if (status) text += ",状态=" + status;
      text += ")";
      if (npcName) text += " NPC=" + npcName;
      return text;
    } catch (e) {
      return "未知任务";
    }
  }

  function constName(obj, value) {
    try {
      for (var k in obj) if (obj[k] === value) return k;
    } catch (e) {}
    return "";
  }

  function messageName(msg) {
    try {
      if (!msg) return "未知消息";
      var mapped = pendingFactory.get(msg);
      if (mapped) return mapped;
      var ctor = objectClassName(msg);
      var candidates = ["cmd", "msgCmd", "msgId", "messageId", "opcode", "id"];
      for (var i = 0; i < candidates.length; i++) {
        if (msg[candidates[i]] != null) return (ctor || "网络消息") + ":" + candidates[i] + "=" + safe(msg[candidates[i]]);
      }
      return ctor || "网络消息";
    } catch (e) {
      return "网络消息";
    }
  }

  function installNetwork() {
    try {
      if (typeof MsgHandler !== "undefined" && MsgHandler) {
        Object.getOwnPropertyNames(MsgHandler).forEach(function (key) {
          try {
            if (!/^create/i.test(key)) return;
            var fn = MsgHandler[key];
            if (typeof fn !== "function" || fn.__worldHooked) return;
            var wrapped = function () {
              var ret = fn.apply(this, arguments);
              try {
                if (ret && typeof ret === "object") {
                  pendingFactory.set(ret, key + argsText(arguments));
                }
              } catch (e) {}
              return ret;
            };
            wrapped.__worldHooked = true;
            MsgHandler[key] = wrapped;
          } catch (e) {}
        });
      }

      var networks = [];
      if (typeof nato !== "undefined" && nato && nato.Network) networks.push(nato.Network);
      networks.forEach(function (net) {
        try {
          if (typeof net.sendCmd !== "function" || net.__worldSendHooked) return;
          var orig = net.sendCmd;
          net.sendCmd = function (msg) {
            try {
              var mapped = pendingFactory.get(msg);
              if (mapped) {
                emit("网络指令 " + mapped + playerContext());
                pendingFactory.delete(msg);
              } else {
                var name = messageName(msg);
                var now = Date.now();
                if (name !== lastRawNetworkKey || now - lastRawNetworkAt > 1000) {
                  emit("网络指令 " + name + playerContext());
                  lastRawNetworkKey = name;
                  lastRawNetworkAt = now;
                }
              }
            } catch (e) {}
            return orig.apply(this, arguments);
          };
          net.__worldSendHooked = true;
        } catch (e) {}
      });
    } catch (e) {}
  }

  installInput();
  installPanels();
  installMissions();
  installNetwork();
  emit("操作记录已开启" + playerContext());
})();
