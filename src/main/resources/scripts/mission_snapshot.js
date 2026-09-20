/*
 * 只读任务快照：判断账号当前是否真的有任务可推进。
 * xworld.npcList 是全局缓存，可能包含其他地图/不可达 NPC，不能全部当作可执行任务。
 * 可执行任务来源：
 *   1. xself.missionList：已经接在身上的任务（含打怪/寻路等进行中状态）
 *   2. 当前打开的 NPC 对话框
 *   3. 全局 NPC 中明确属于当前地图、可见或任务目标地图就是当前地图的任务
 * 另输出玩家坐标/战斗状态签名，Java 侧用它识别“一直发寻路但人不动、任务不推进”的卡死。
 */
(function () {
  if (window.WorldMissionSnapshot && window.WorldMissionSnapshot.__installed) return;

  var MAX_ITEMS = 30;
  var MAX_SCAN = 1000;

  function isFunction(v) { return typeof v === "function"; }

  function safeCall(obj, method) {
    try {
      if (obj && isFunction(obj[method])) return obj[method]();
    } catch (e) {}
    return null;
  }

  function constName(obj, val) {
    try {
      for (var k in obj) if (obj[k] === val) return k;
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
      try { if (isChineseText(m[fields[j]])) return String(m[fields[j]]).trim(); } catch (e) {}
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

  function isMission(v) {
    return !!v && typeof v === "object" && isFunction(v.getMissionStatus);
  }

  function addUnique(arr, value, limit) {
    if (value === null || value === undefined || value === "") return;
    value = String(value);
    if (arr.indexOf(value) < 0 && arr.length < limit) arr.push(value);
  }

  function currentMapId() {
    try {
      var id = safeCall(xworld, "getCurMapID");
      if (id === null || id === undefined) id = xworld.mapId;
      var n = Number(id);
      return Number.isFinite(n) ? n : 0;
    } catch (e) {
      return 0;
    }
  }

  function isInCityContext() {
    try {
      if (isFunction(xworld.isInCityNow) && xworld.isInCityNow()) return true;
      return Number(currentMapId()) === 14748;
    } catch (e) {
      return false;
    }
  }

  function blockedByCityRule(m) {
    try {
      var id = Number(m.getId());
      return isInCityContext()
        && Number.isFinite(id)
        && !(id >= 3060 && id <= 3075);
    } catch (e) {
      return false;
    }
  }

  function SnapshotState() {
    this.canAccept = [];
    this.canSubmit = [];
    this.activeAccepted = [];
    this.autoCanAccept = [];
    this.autoCanSubmit = [];
    this.globalCanAccept = [];
    this.globalCanSubmit = [];
    this.sources = [];
    this.keys = {};
    this.scanned = 0;
  }

  function sourceSeen(state, source) {
    addUnique(state.sources, source, 30);
  }

  function makeItem(m, status, source, npc) {
    return {
      id: missionId(m),
      name: missionName(m),
      status: status,
      sources: [source],
      npcNames: npc ? [actorName(npc)].filter(Boolean) : []
    };
  }

  function pushUnique(list, item, kind) {
    var key = kind + "|" + item.id + "|" + item.status;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === item.id && list[i].status === item.status) {
        addUnique(list[i].sources, item.sources[0], 8);
        return;
      }
    }
    if (list.length < MAX_ITEMS) list.push(item);
  }

  function missionStatus(m, npc) {
    var statusValue;
    try {
      statusValue = m.getMissionStatus(xself);
      if (npc) {
        var npcStatusValue = m.getMissionStatus(xself, npc);
        var npcStatus = constName(typeof MissionConst !== "undefined" ? MissionConst : null, npcStatusValue);
        if (npcStatus === "CAN_ACCEPT" || npcStatus === "CAN_SUBMIT") statusValue = npcStatusValue;
      }
    } catch (e) {
      return "";
    }
    return constName(typeof MissionConst !== "undefined" ? MissionConst : null, statusValue);
  }

  function addMission(state, m, source, npc, trusted, reachable) {
    if (!m || state.scanned >= MAX_SCAN || !isMission(m)) return;
    state.scanned++;
    sourceSeen(state, source);

    var status = missionStatus(m, npc);
    if (!status) return;
    var item = makeItem(m, status, source, npc);
    var isAcceptedSource = source.indexOf("xself.missionList") === 0;

    if (status === "CAN_SUBMIT") {
      pushUnique(state.canSubmit, item, "rawSubmit");
      pushUnique(state.globalCanSubmit, item, "globalSubmit");
      if (isAcceptedSource) pushUnique(state.activeAccepted, item, "active");
      if (trusted && (isAcceptedSource || reachable || source.indexOf("PanelManager") === 0) && !blockedByCityRule(m)) {
        pushUnique(state.autoCanSubmit, item, "autoSubmit");
      }
      return;
    }

    if (status === "CAN_ACCEPT") {
      pushUnique(state.canAccept, item, "rawAccept");
      pushUnique(state.globalCanAccept, item, "globalAccept");
      if (trusted && (reachable || source.indexOf("PanelManager") === 0) && !blockedByCityRule(m)) {
        pushUnique(state.autoCanAccept, item, "autoAccept");
      }
      return;
    }

    // NOT_CAN_ACCEPT / NOT_CAN_SUBMIT：身上任务代表已接取，可能正在打怪/采集/寻路。
    if (isAcceptedSource) pushUnique(state.activeAccepted, item, "active");
  }

  function scanAccepted(state) {
    try {
      var list = xself && xself.missionList;
      if (!list || !list.length) return;
      sourceSeen(state, "xself.missionList");
      for (var i = 0; i < list.length && state.scanned < MAX_SCAN; i++) {
        addMission(state, list[i], "xself.missionList[" + i + "]", null, true, true);
      }
    } catch (e) {}
  }

  function eachMission(list, npc, cb) {
    if (!list) return;
    if (isMission(list)) {
      cb(list, npc);
      return;
    }
    if ((typeof list.length !== "number" || !list.length) && list.missionList) {
      eachMission(list.missionList, npc, cb);
      return;
    }
    if (typeof list.length !== "number" || !list.length) return;
    for (var i = 0; i < list.length; i++) cb(list[i], npc);
  }

  function scanOpenDialogue(state) {
    try {
      var dlg = PanelManager && PanelManager.npcDialogue;
      if (!dlg || !dlg.stage || !dlg.visible || !dlg._actionList) return;
      var npc = isFunction(dlg.getNpc) ? dlg.getNpc() : dlg._npc;
      sourceSeen(state, "PanelManager.npcDialogue");
      for (var i = 0; i < dlg._actionList.length && state.scanned < MAX_SCAN; i++) {
        var action = dlg._actionList[i];
        var mission = action && action.data && action.data.mission;
        if (mission) addMission(state, mission, "PanelManager.npcDialogue[" + i + "]", npc, true, true);
      }
    } catch (e) {}
  }

  function numberFromMethods(obj, methods) {
    for (var i = 0; i < methods.length; i++) {
      var v = safeCall(obj, methods[i]);
      var n = Number(v);
      if (Number.isFinite(n)) return n;
    }
    return null;
  }

  function numberFromFields(obj, fields) {
    for (var i = 0; i < fields.length; i++) {
      try {
        var n = Number(obj[fields[i]]);
        if (Number.isFinite(n)) return n;
      } catch (e) {}
    }
    return null;
  }

  function npcBelongsToMap(npc, mapId) {
    if (!npc || !mapId) return false;
    var id = numberFromMethods(npc, ["getMapID", "getMapId", "getCurMapID", "getMapIdKey"]);
    if (id === null) id = numberFromFields(npc, ["mapId", "mapID", "mapIDKey", "_mapId", "curMapId", "mapid"]);
    return id === mapId;
  }

  function npcVisible(npc) {
    try {
      if (isFunction(npc.isVisible) && npc.isVisible()) return true;
    } catch (e) {}
    return false;
  }

  function missionMentionsMap(mission, mapId) {
    if (!mission || !mapId) return false;
    var id = numberFromMethods(mission, [
      "getAcceptJumpMapID", "getSubmitJumpMapID", "getTargetMapID",
      "getMapID", "getMapId", "getFinishMapID"
    ]);
    if (id === mapId) return true;
    id = numberFromFields(mission, [
      "acceptJumpMapID", "submitJumpMapID", "targetMapID", "finishMapID",
      "mapId", "mapID", "mapid", "_mapId"
    ]);
    return id === mapId;
  }

  function scanNpcs(state) {
    try {
      var npcs = typeof xworld !== "undefined" && xworld ? xworld.npcList : null;
      if (!npcs || typeof npcs.length !== "number") return;
      var mapId = currentMapId();
      sourceSeen(state, "xworld.npcList");
      for (var i = 0; i < npcs.length && state.scanned < MAX_SCAN; i++) {
        var npc = npcs[i];
        if (!npc) continue;
        var npcReachable = npcBelongsToMap(npc, mapId) || npcVisible(npc);
        ["missionList", "missionData", "missions"].forEach(function (field) {
          eachMission(npc[field], npc, function (mission, owner) {
            var reachable = npcReachable || missionMentionsMap(mission, mapId);
            addMission(state, mission, "xworld.npcList[" + i + "]." + field, owner, reachable, reachable);
          });
        });
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

  function ids(list) {
    return list.map(function (x) { return x.id; }).join(",");
  }

  function playerPosition() {
    var x = null, y = null;
    try {
      x = numberFromMethods(xself, ["getXKey", "getX", "getXPos"]);
      y = numberFromMethods(xself, ["getYKey", "getY", "getYPos"]);
      if ((x === null || y === null) && isFunction(xself.getPosition)) {
        var p = xself.getPosition();
        if (p) { x = Number(p.x); y = Number(p.y); }
      }
      if (x === null) x = Number(xself.x);
      if (y === null) y = Number(xself.y);
    } catch (e) {}
    return { x: Number.isFinite(x) ? x : null, y: Number.isFinite(y) ? y : null };
  }

  function snapshot() {
    var empty = function (error) {
      return {
        ok: !error, canAcceptCount: 0, canSubmitCount: 0,
        activeAcceptedCount: 0, autoCanAcceptCount: 0, autoCanSubmitCount: 0,
        canAccept: [], canSubmit: [], activeAccepted: [],
        autoCanAccept: [], autoCanSubmit: [],
        globalCanAcceptCount: 0, globalCanSubmitCount: 0, globalCanAccept: [], globalCanSubmit: [],
        scanned: 0, sources: [], mapId: 0, inCity: false, inBattle: false,
        playerX: null, playerY: null, signature: "", activitySignature: "", error: error || ""
      };
    };

    try {
      if (typeof xself === "undefined" || !xself || typeof MissionConst === "undefined") {
        return empty("游戏核心对象未就绪");
      }

      var state = new SnapshotState();
      scanAccepted(state);
      scanOpenDialogue(state);
      scanNpcs(state);

      ["canAccept", "canSubmit", "activeAccepted", "autoCanAccept", "autoCanSubmit",
       "globalCanAccept", "globalCanSubmit"].forEach(function (name) { sortItems(state[name]); });

      var mapId = currentMapId();
      var inCity = false;
      var inBattle = false;
      try { inCity = !!(isFunction(xworld.isInCityNow) && xworld.isInCityNow()); } catch (e) {}
      try { inBattle = !!xworld.inBattle; } catch (e) {}
      var pos = playerPosition();
      var dialogueVisible = false;
      try {
        dialogueVisible = !!(PanelManager && PanelManager.npcDialogue
          && PanelManager.npcDialogue.stage && PanelManager.npcDialogue.visible);
      } catch (e) {}

      var signature = "accept:" + ids(state.autoCanAccept)
        + ";submit:" + ids(state.autoCanSubmit)
        + ";active:" + ids(state.activeAccepted);

      // 坐标按 20 单位取整，避免待机轻微抖动导致误判；地图/战斗/对话框变化都视为仍在推进。
      var activitySignature = "map:" + mapId
        + ";battle:" + inBattle
        + ";dlg:" + dialogueVisible
        + ";xy:" + (pos.x === null ? "?" : Math.round(pos.x / 20))
        + "," + (pos.y === null ? "?" : Math.round(pos.y / 20));

      return {
        ok: true,
        canAcceptCount: state.canAccept.length,
        canSubmitCount: state.canSubmit.length,
        activeAcceptedCount: state.activeAccepted.length,
        activeAccepted: state.activeAccepted,
        canAccept: state.canAccept,
        canSubmit: state.canSubmit,
        autoCanAcceptCount: state.autoCanAccept.length,
        autoCanSubmitCount: state.autoCanSubmit.length,
        autoCanAccept: state.autoCanAccept,
        autoCanSubmit: state.autoCanSubmit,
        globalCanAcceptCount: state.globalCanAccept.length,
        globalCanSubmitCount: state.globalCanSubmit.length,
        globalCanAccept: state.globalCanAccept,
        globalCanSubmit: state.globalCanSubmit,
        scanned: state.scanned,
        sources: state.sources,
        inCity: inCity,
        mapId: mapId,
        inBattle: inBattle,
        playerX: pos.x,
        playerY: pos.y,
        signature: signature,
        activitySignature: activitySignature,
        error: ""
      };
    } catch (e) {
      return empty(e && e.message ? String(e.message) : String(e));
    }
  }

  window.WorldMissionSnapshot = { __installed: true, snapshot: snapshot };
})();
