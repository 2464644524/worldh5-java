/*
 * 自动清背包（由原 APK 混淆脚本 1:1 还原，名单与出售规则保持一致）。
 * 暴露 window.WorldBagClear = { start, stop, runNow, isRunning, status }
 * 只扫描背包第 30 格之后（slice(30, bagEnd)），按规则出售垃圾/限时/低级装备。
 */
(function () {
  if (window.__WorldBagClearInstalled) {
    // 已注入过（页面未刷新）：保留原实例，避免重复启动定时器。
    return;
  }
  window.__WorldBagClearInstalled = true;

  // 命中即卖（垃圾/材料/低级盒子等）
  var NEED_CLEAN = [
    "额外生命药水(1小时)", "木制武器箱子", "法力药水", "体质药水(1小时)", "敏捷药水(1小时)",
    "20级防具盒", "钻石(兑换铜币)", "炎之元素", "地之元素", "木之元素", "冰之元素",
    "20级轻型武器箱(锁)", "20级重型武器箱(锁)", "20级服饰箱(锁)", "20级防具箱(锁)", "20级秘宝礼包(锁)",
    "20级轻型武器盒", "20级重型武器盒", "20级防具盒", "20级服饰盒",
    "30级轻型武器箱(锁)", "30级重型武器箱(锁)", "30级服饰箱(锁)", "30级防具箱(锁)", "30级秘宝礼包(锁)",
    "30级轻型武器盒", "30级重型武器盒", "30级服饰盒", "30级防具盒",
    "40级轻型武器箱(锁)", "40级重型武器箱(锁)", "40级服饰箱(锁)", "40级防具箱(锁)", "40级秘宝礼包(锁)",
    "加1感知宝石(绑）", "加1体质宝石(绑）", "加1力量宝石(绑）", "加1智力宝石(绑）", "加1敏捷宝石(绑）",
    "1级魔力结晶", "2级魔力结晶", "3级魔力结晶",
    "七星龙渊", "夜啸", "无为守护", "魔剑美神时装", "龙藻古剑", "风语者",
    "雷斯林战斗护符", "雷斯林祝福护符", "星雪项链", "上古守护者长弓", "死灵携载者",
    "雪蝶(1天）", "星月幻化项链(绑)",
    "一级碎矿", "二级碎矿", "三级碎矿", "四级碎矿", "五级碎矿",
    "一级碎木", "二级碎木", "三级碎木", "四级碎木", "五级碎木",
    "一级碎石", "二级碎石", "三级碎石", "四级碎石", "五级碎石",
    "黑暗圣衣时装", "30级秘宝盒", "秘宝盒"
  ];

  // 白名单：即使命中其它规则也绝不卖
  var WHITE = [
    "速度增幅项链", "法力精华护符", "生命精华护符", "极限法力护符", "极限生命护符",
    "驯鹿", "火焰法球", "周年稀有凤凰", "死灵马", "死灵马（无敌）", "天灾弩", "巫月神刀",
    "死亡之翼", "银月虚化护符", "还魂法球", "结婚戒指", "法师指环", "暗影指环",
    "中元护符", "圣诞护符", "等级先锋护符", "星月幻化项链", "幻影刺客",
    "壕（7天）", "土豪时装（7天）", "力量指环", "敏捷指环", "感知指环", "智力指环", "体质指环"
  ];

  // 装备类 type（原脚本的数字表）
  var EQUIP_TYPES = [0,1,2,3,4,5,6,7,8,9,13,14,15,16,17,18,19,20,21,22,23];

  var SCAN_FROM = 30;      // 只清理第 30 格之后（与原脚本一致）
  var INTERVAL = 60000;    // 每 60 秒扫描一次（原脚本为 180 秒）
  var FIRST_DELAY = 8000;  // 启动后 8 秒先清一次，尽快看到效果
  var timer = null;

  var state = {
    running: false,
    scans: 0,
    totalSold: 0,
    lastSold: 0,
    lastRun: 0,
    lastError: ""
  };

  function ready() {
    try {
      return !!(window.xself && xself.bag && xself.bag.store && xself.bag.bagEnd != null
        && window.MsgHandler && typeof MsgHandler.createItemShopSell === "function"
        && window.nato && nato.Network && typeof nato.Network.sendCmd === "function"
        && window.ItemManager && typeof ItemManager.doBagRefresh === "function"
        && window.Define && Define.SHOP_PET_USEITEM_ID != null);
    } catch (e) {
      return false;
    }
  }

  // 返回本次要出售的 slotPos 列表（规则与原脚本逐条对应）
  function collectSlots() {
    var sell = [];
    var store = xself.bag.store;
    var end = xself.bag.bagEnd;
    store.slice(SCAN_FROM, end).forEach(function (item) {
      if (!item) return;
      var name = item.name || "";
      if (WHITE.indexOf(name) !== -1) return;
      if (name.indexOf("无限潜能") !== -1) return;
      if (name.indexOf("魔染幽纹") !== -1) return;
      if (item.star > 0) return; // 升星装备不卖

      if (NEED_CLEAN.indexOf(name) !== -1) { sell.push(item.slotPos); return; }
      if (name.indexOf("天）") !== -1 && item.reqLv < 55) { sell.push(item.slotPos); return; }
      if (name.indexOf("天)") !== -1 && item.reqLv < 55) { sell.push(item.slotPos); return; }
      if (item.grade < 3 && item.reqLv < 50 && EQUIP_TYPES.indexOf(item.type) !== -1) { sell.push(item.slotPos); return; }
      if (item.grade < 4 && item.reqLv < 40 && EQUIP_TYPES.indexOf(item.type) !== -1) { sell.push(item.slotPos); return; }
    });
    return sell;
  }

  function sellSlots(slots) {
    var count = 0;
    slots.forEach(function (pos) {
      var item = xself.bag.store[pos];
      if (!item) return;
      xself.bag.removeItem(pos);
      var msg = MsgHandler.createItemShopSell(
        Define.SHOP_PET_USEITEM_ID, item.id, item.slotPos, item.quantity);
      nato.Network.sendCmd(msg, xself);
      count++;
    });
    if (count > 0) {
      ItemManager.doBagRefresh();
    }
    return count;
  }

  function runOnce() {
    if (!state.running) return 0;
    try {
      if (!ready()) return 0;
      var slots = collectSlots();
      var sold = sellSlots(slots);
      state.scans++;
      state.lastSold = sold;
      state.totalSold += sold;
      state.lastRun = Date.now();
      state.lastError = "";
      return sold;
    } catch (e) {
      state.lastError = String(e && e.message ? e.message : e);
      return 0;
    }
  }

  function schedule(delay) {
    timer = setTimeout(function () {
      runOnce();
      if (state.running) schedule(INTERVAL);
    }, delay);
  }

  function start() {
    if (state.running) return false;
    state.running = true;
    if (timer) { clearTimeout(timer); timer = null; }
    schedule(FIRST_DELAY);
    return true;
  }

  function stop() {
    state.running = false;
    if (timer) { clearTimeout(timer); timer = null; }
  }

  // 手动立即清一次（不等定时器），返回本次出售件数
  function runNow() {
    var wasRunning = state.running;
    state.running = true;
    var sold = runOnce();
    state.running = wasRunning;
    return sold;
  }

  window.WorldBagClear = {
    start: start,
    stop: stop,
    runNow: runNow,
    isRunning: function () { return state.running; },
    ready: ready,
    status: function () {
      return {
        running: state.running,
        ready: ready(),
        scans: state.scans,
        totalSold: state.totalSold,
        lastSold: state.lastSold,
        lastRun: state.lastRun,
        lastError: state.lastError
      };
    }
  };
})();