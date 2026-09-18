/*
 * 只读任务快照：供桌面控制台每 3 分钟判断当前是否存在 CAN_ACCEPT / CAN_SUBMIT。
 * 不点击、不接任务、不发网络请求，只读取游戏当前内存里的任务与 NPC 数据。
 */
(function () {
  if (window.WorldMissionSnapshot && window.WorldMissionSnapshot.__installed) return;

  var MAX_ITEMS = 30;
  var MAX_SCAN = 2000;

  function type(v) {
    return Object.prototype.toString.call(v).slice(8, -1);
  }

  function isFunction(v) {
    return typeof v === "function";
  }

  function safeCall(obj, method) {
    try {
      if (obj && isFunction(obj[method])) return obj[method]();
    } catch (e) {}
    return null;
  }

  function constName(obj, val) {
    try {
      for (var k in obj) {
        if (obj[k] === val) return k;
      }
    } catch (e) {}
    return "";
  }

  function isChineseText(v) {
    return typeof v === "string"
      && /[\u4e00-\u9fa5]/.test(v)
      && v.trim().length >= 2
      && v.trim().length <= 24;
  }

  function missionName(m) {
    if (!m) return "";
    var methods = ["getName", "getTitle", "getMissionName", "getTaskName", "getShowName"];
    for (var i = 0; i < methods.length; i++) {
      var v = safeCall(m, methods[i]);
      if (isChineseText(v)) return String(v).trim();
    }
    var fields = ["name", "title", "missionName", "taskName", "_name"];
    for (var j = 0; j < fields.length; j++) {
      try {
        if (isChineseText(m[fields[j]])) return String(m[fields[j]]).trim();
      } catch (e) {}
    }
    return "";
  }

  function missionId(m) {
    try {
      var v = safeCall(m, "getId");
      if (v === null || v === undefined || v === "") v = m.id;
      if (v === null || v === undefined || v === "") return "";
      var n = Number(v);
      return Number.isFinite(n) ? n : String(v);
    } catch (e) {
      return "";
    }
  }

  function actorName(a) {
    var v = safeCall(a, "getName");
    return isChineseText(v) ? String(v).trim() : "";
  }

  function actorId(a) {
    var v = safeCall(a, "getId");
    if (v === null || v === undefined) {
      try { v = a.id; } catch (e) { v = ""; }
    }
    return v === null || v === undefined ? "" : v;
  }

  function isMission(v) {
    return !!v && typeof v === "object" && isFunction(v.getMissionStatus);
  }

  function addUnique(arr, value, limit) {
    if (!value) return;
    value = String(value);
    if (arr.indexOf(value) < 0 && arr.length < limit) arr.push(value);
  }

  function missionKind(m) {
    var flags = [];
    var methods = [
      "isRandomMission", "isOneKeyMission", "isCityBulltinMission",
      "isCountryAssignTask", "isEscort", "isDirectSubmit",
      "isUnLimitSubmit", "isNestedMission"
    ];
    for (var i = 0; i < methods.length; i++) {
      try {
        if (isFunction(m[methods[i]]) && m[methods[i]]()) flags.push(methods[i]);
      } catch (e) {}
    }
    return flags;
  }

  function SnapshotState() {
    this.map = new Map();
    this.canAccept = [];
    this.canSubmit = [];
    this.sources = [];
    this.scanned = 0;
    this.ignored = 0;
  }

  function sourceSeen(state, source) {
    addUnique(state.sources, source, 30);
  }

  function addMission(state, m, source, npc) {
    if (!m || state.scanned >= MAX_SCAN) return;
    if (!isMission(m)) return;
    state.scanned++;
    sourceSeen(state, source);

    var statusValue;
    try {
      // 自动任务日志里对 NPC 对话任务使用 getMissionStatus(xself, npc)，
      // 普通已接任务只传 xself；两种都尝试，任一精确命中 CAN_ACCEPT/CAN_SUBMIT 即记录。
      statusValue = m.getMissionStatus(xself);
      if (npc) {
        var npcStatusValue = m.getMissionStatus(xself, npc);
        var npcStatus = constName(typeof MissionConst !== "undefined" ? MissionConst : null, npcStatusValue);
        if (npcStatus === "CAN_ACCEPT" || npcStatus === "CAN_SUBMIT") {
          statusValue = npcStatusValue;
        }
      }
    } catch (e) {
      return;
    }
    var status = constName(typeof MissionConst !== "undefined" ? MissionConst : null, statusValue);
    if (status !== "CAN_ACCEPT" && status !== "CAN_SUBMIT") return;

    var id = missionId(m);
    var key = String(id) + "|" + status;
    var item = state.map.get(key);
    if (!item) {
      item = {
        id: id,
        name: missionName(m),
        status: status,
        sources: [],
        npcIds: [],
        npcNames: [],
        kind: missionKind(m)
      };
      state.map.set(key, item);
      (status === "CAN_ACCEPT" ? state.canAccept : state.canSubmit).push(item);
    }
    addUnique(item.sources, source, 8);
    if (npc) {
      var nid = actorId(npc);
      if (nid !== "") addUnique(item.npcIds, String(nid), 5);
      addUnique(item.npcNames, actorName(npc), 5);
    }
  }

  function scanAccepted(state) {
    try {
      var list = xself && xself.missionList;
      if (!list || !list.length) return;
      sourceSeen(state, "xself.missionList");
      for (var i = 0; i < list.length && state.scanned < MAX_SCAN; i++) {
        addMission(state, list[i], "xself.missionList[" + i + "]", null);
      }
    } catch (e) {}
  }

  function scanNpcMissionArray(state, npc, field, npcIndex) {
    try {
      var list = npc[field];
      if (!list) return;
      if (isMission(list)) {
        addMission(state, list, "xworld.npcList[" + npcIndex + "]." + field, npc);
        return;
      }
      // 不同版本里 NPC 可能直接挂 missionData=[...]，也可能包一层 missionData.missionList。
      if ((typeof list.length !== "number" || !list.length) && list.missionList) {
        scanNpcMissionArray(state, list, "missionList", npcIndex);
        return;
      }
      if (typeof list.length !== "number" || !list.length) return;
      var source = "xworld.npcList[" + npcIndex + "]." + field;
      sourceSeen(state, "xworld.npcList.*." + field);
      for (var i = 0; i < list.length && state.scanned < MAX_SCAN; i++) {
        addMission(state, list[i], source + "[" + i + "]", npc);
      }
    } catch (e) {}
  }

  function scanNpcs(state) {
    try {
      var npcs = typeof xworld !== "undefined" && xworld ? xworld.npcList : null;
      if (!npcs || typeof npcs.length !== "number") return;
      sourceSeen(state, "xworld.npcList");
      for (var i = 0; i < npcs.length && state.scanned < MAX_SCAN; i++) {
        var npc = npcs[i];
        if (!npc) continue;
        scanNpcMissionArray(state, npc, "missionList", i);
        scanNpcMissionArray(state, npc, "missionData", i);
        scanNpcMissionArray(state, npc, "missions", i);
      }
    } catch (e) {}
  }

  function scanOpenDialogue(state) {
    try {
      var dlg = PanelManager && PanelManager.npcDialogue;
      if (!dlg || !dlg.stage || !dlg.visible || !dlg._actionList) return;
      var npc = isFunction(dlg.getNpc) ? dlg.getNpc() : dlg._npc;
      sourceSeen(state, "PanelManager.npcDialogue");
      for (var i = 0; i < dlg._actionList.length && state.scanned < MAX_SCAN; i++) {
        var action = dlg._actionList[i];
        var data = action && action.data;
        var mission = data && data.mission;
        if (mission) addMission(state, mission, "PanelManager.npcDialogue._actionList[" + i + "]", npc);
      }
    } catch (e) {}
  }

  function sortItems(list) {
    list.sort(function (a, b) {
      var ai = typeof a.id === "number" ? a.id : Number.MAX_SAFE_INTEGER;
      var bi = typeof b.id === "number" ? b.id : Number.MAX_SAFE_INTEGER;
      if (ai !== bi) return ai - bi;
      return String(a.id).localeCompare(String(b.id));
    });
    if (list.length > MAX_ITEMS) list.length = MAX_ITEMS;
  }

  function snapshot() {
    var state = new SnapshotState();
    try {
      if (typeof xself === "undefined" || !xself || typeof MissionConst === "undefined") {
        return {
          ok: false,
          canAcceptCount: 0,
          canSubmitCount: 0,
          canAccept: [],
          canSubmit: [],
          scanned: 0,
          sources: [],
          error: "游戏核心对象未就绪"
        };
      }
      scanAccepted(state);
      scanNpcs(state);
      scanOpenDialogue(state);
      var totalCanAccept = state.canAccept.length;
      var totalCanSubmit = state.canSubmit.length;
      sortItems(state.canAccept);
      sortItems(state.canSubmit);

      var mapId = 0;
      var inCity = false;
      try { mapId = xworld.getCurMapID ? xworld.getCurMapID() : (xworld.mapId || 0); } catch (e) {}
      try { inCity = !!(xworld.isInCityNow && xworld.isInCityNow()); } catch (e) {}

      return {
        ok: true,
        canAcceptCount: totalCanAccept,
        canSubmitCount: totalCanSubmit,
        canAccept: state.canAccept,
        canSubmit: state.canSubmit,
        scanned: state.scanned,
        sources: state.sources,
        inCity: inCity,
        mapId: mapId,
        error: ""
      };
    } catch (e) {
      return {
        ok: false,
        canAcceptCount: 0,
        canSubmitCount: 0,
        canAccept: [],
        canSubmit: [],
        scanned: state.scanned,
        sources: state.sources,
        error: e && e.message ? String(e.message) : String(e)
      };
    }
  }

  window.WorldMissionSnapshot = {
    __installed: true,
    snapshot: snapshot
  };
})();