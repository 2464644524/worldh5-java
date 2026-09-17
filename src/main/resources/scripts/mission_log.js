/*
 * 任务进度日志：包住游戏统一的任务交互入口 Mission.doMenuButton
 * （手动点击 NPC 任务菜单、自动脚本 TestAutoGame 交/接任务都会走这里）。
 * 仅做旁路记录，透传 this/参数/返回值，任何异常都不影响游戏本身。
 * 通过 Playwright 暴露的 __worldLog(text) 回传控制台。
 */
(function () {
  if (window.__worldMissionLogInstalled) return;
  window.__worldMissionLogInstalled = true;

  function emit(msg) {
    try { if (typeof window.__worldLog === "function") window.__worldLog(msg); } catch (e) {}
  }

  function isChineseText(v) {
    return typeof v === "string" && /[\u4e00-\u9fa5]/.test(v) && v.trim().length >= 2 && v.trim().length <= 16;
  }

  // 尽量从任务对象上取一个可读名字
  function missionName(m) {
    if (!m) return "";
    var getters = ["getName", "getTitle", "getMissionName", "getTaskName", "getShowName"];
    for (var i = 0; i < getters.length; i++) {
      try {
        if (typeof m[getters[i]] === "function") {
          var v = m[getters[i]]();
          if (isChineseText(v)) return v.trim();
        }
      } catch (e) {}
    }
    var fields = ["name", "title", "missionName", "taskName", "_name"];
    for (var j = 0; j < fields.length; j++) {
      try { if (isChineseText(m[fields[j]])) return m[fields[j]].trim(); } catch (e) {}
    }
    // 最后兜底：遍历自身属性找一个中文短串（任务名通常是中文）
    try {
      for (var k in m) {
        var v2;
        try { v2 = m[k]; } catch (e) { continue; }
        if (isChineseText(v2)) return v2.trim();
      }
    } catch (e) {}
    return "";
  }

  function missionId(m) {
    try {
      if (m == null) return "";
      if (typeof m.getId === "function") return m.getId();
      if (m.id != null) return m.id;
    } catch (e) {}
    return "";
  }

  function constName(obj, val) {
    try {
      for (var k in obj) {
        if (obj[k] === val) return k;
      }
    } catch (e) {}
    return null;
  }

  function npcName(npc) {
    try {
      if (!npc) return "";
      if (typeof npc.getName === "function") return npc.getName() || "";
      return npc.name || "";
    } catch (e) { return ""; }
  }

  var lastKey = "";
  var lastTime = 0;

  function report(npc, subType, mission) {
    var id = missionId(mission);
    var name = missionName(mission);
    var statusName = "";
    try {
      if (mission && typeof mission.getMissionStatus === "function"
          && typeof MissionConst !== "undefined" && typeof xself !== "undefined") {
        var st = mission.getMissionStatus(xself);
        statusName = constName(MissionConst, st) || ("" + st);
      }
    } catch (e) {}

    var key = id + "|" + name + "|" + statusName;
    var now = Date.now();
    // 同一任务同一动作 3 秒内只记一次，避免自动挂机连交刷屏
    if (key === lastKey && now - lastTime < 3000) return;
    lastKey = key;
    lastTime = now;

    var label = name || ("任务#" + (id === "" ? "?" : id));
    // CAN_SUBMIT=已完成可交付(点了就是提交)；CAN_ACCEPT=可接取；NOT_CAN_*=进行中/不可操作
    var verb = "任务推进";
    // 注意必须精确匹配：NOT_CAN_SUBMIT 字符串里也包含 "CAN_SUBMIT"，不能用 indexOf
    if (statusName === "CAN_SUBMIT") {
      verb = "提交任务";
    } else if (statusName === "CAN_ACCEPT") {
      verb = "接取任务";
    }
    var msg = verb + "：" + label
      + "（id=" + (id === "" ? "-" : id)
      + (statusName ? "，状态=" + statusName : "")
      + "）";
    var nn = npcName(npc);
    if (nn) msg += " NPC=" + nn;
    emit(msg);
  }

  function install() {
    try {
      if (typeof Mission === "undefined" || !Mission.doMenuButton || Mission.__worldHooked) {
        return false;
      }
      var orig = Mission.doMenuButton;
      Mission.doMenuButton = function (npc, arg2, subType, mission) {
        try { report(npc, subType, mission); } catch (e) {}
        return orig.apply(this, arguments);
      };
      Mission.__worldHooked = true;

      // 手动点 NPC 对话交/接任务走这里：handlerMissionNPCAction(npc, action)，
      // mission 类动作的任务对象在 action.data.mission。
      try {
        if (typeof NPC !== "undefined" && NPC.handlerMissionNPCAction && !NPC.__worldHooked) {
          var origNpc = NPC.handlerMissionNPCAction;
          NPC.handlerMissionNPCAction = function (npc, action) {
            try {
              var mission = action && action.data ? action.data.mission : null;
              if (mission) {
                var sub = action.subType != null ? action.subType
                          : (action.data.subType != null ? action.data.subType : null);
                report(npc, sub, mission);
              }
            } catch (e) {}
            return origNpc.apply(this, arguments);
          };
          NPC.__worldHooked = true;
        }
      } catch (e) {}

      emit("任务进度记录已开启（提交/接取任务会自动记录）");
      return true;
    } catch (e) {
      return false;
    }
  }

  // 注入时 Mission 一般已就绪；若还没好就短时重试
  if (!install()) {
    var n = 0;
    var timer = setInterval(function () {
      if (install() || ++n > 60) clearInterval(timer);
    }, 500);
  }
})();