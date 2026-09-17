# 世界H5 十开桌面控制台 — 交接 / 维护文档

> 交接时间：2026-09-15
> 工程根目录：`<USER_PROFILE>Jing\Desktop\h5\world-desktop`
> 原始逆向参考：`<USER_PROFILE>Jing\Desktop\h5`（含 `世界H5-有道共存版.apk`、`analysis/` 反编译结果）

---

## 1. 项目是做什么的

把原安卓 APK（多开 + 注入 JS 辅助脚本的 H5 游戏《世界H5》）重写成一个
Windows 桌面控制台：

- 用 **Playwright（Java 1.62.0）驱动系统自带 Microsoft Edge**；
- 最多 **10 个账号**，每个账号一个**独立持久化浏览器 Profile**（Cookie/缓存隔离）；
- 检测到游戏 Frame 加载完成后，自动注入从原 APK 抽取的辅助 JS；
- 提供单账号和批量（10 个）操作：自动、刷怪、跟随、停止、进城、微端奖励等。

技术栈：Java 17+（本机实际 JDK 25）、Maven（工程内自带 `tools/apache-maven-3.9.11`）、
Playwright Java、Swing 控制台。

---

## 2. 怎么运行 / 编译 / 环境要求

- Windows 10/11，已安装 **Microsoft Edge**（Playwright 用 `setChannel("msedge")`）。
- JDK 17 或更高。
- 启动：双击 `run.cmd`（内容是 `mvn -q compile exec:java`），首次会下载 Playwright 驱动，需联网。
- 只编译验证：`.\tools\apache-maven-3.9.11\bin\mvn.cmd -q compile`（当前 **BUILD SUCCESS**）。
- 入口类：`com.liang.world.desktop.WorldDesktop`（main 在其中）。
- 改完 Java/JS 后必须**关闭控制台和所有 Edge 游戏窗口再重启**；
  `addInitScript` 只在页面/Frame 新建时注入，已打开的旧窗口不会热更新。

---

## 3. 当前界面与交互形态（重要，用户反复调整过）

最终布局是 **90% 游戏 + 10% 辅助栏**，不是大表单：

- 屏幕左侧约 90%：游戏 Edge 窗口区域（多个账号窗口共用这块区域、互相重叠）。
- 屏幕右侧约 10%（最少 120px）：一个**常驻最前的窄竖排 Swing 辅助栏**。
- 辅助栏上半是 `01`–`10` 账号切换按钮：
  - 灰色=未打开；蓝色=已打开；绿色=脚本已注入；橙色边框=当前选中。
  - 点账号按钮 = 选中并把对应 Edge 窗口强制前置。
- “当前账号”区按钮：打开 / 登录 / 刷新 / 前置 / 存号 / 设置 /
  自动 / 刷怪 / 跟随 / 停止 / 进城 / 微端 / 关闭。
- “批量”区按钮：全开 / 全前置 / 全自动 / 全刷怪 / 全跟随 / 全停止 / 全进城 / 全微端。
- “设置”弹窗里可改单个账号的：备注、渠道、存号地址、是否自动清背包。
- 底部是一小块日志区。

> 用户明确否决过“一个大窗口里堆 10 行表单”的方案，不要再改回那种布局。
> 需求演进：单一下拉框统一操作 → 10 行表单（被否）→ 90/10 窄边栏（当前）。

---

## 4. 源码结构

`src/main/java/com/liang/world/desktop/`

| 文件 | 职责 |
|---|---|
| `WorldDesktop.java` | Swing 窄辅助栏 UI、账号按钮、单账号/批量按钮、设置弹窗、日志；计算 90/10 分屏 `Rectangle`。 |
| `SessionManager.java` | 10 个 `AccountSession` 的总管；**所有 Playwright 调用串行在单线程 `playwright-worker`**；维护每账号状态（0 关 / 1 开 / 2 已注入，用 `AtomicIntegerArray`）；批量操作只对“已注入”账号执行。 |
| `AccountSession.java` | 单账号浏览器生命周期：启动 Edge、手机仿真、CDP 校正窗口、渠道 iframe 跳转、触摸桥接、轮询注入游戏脚本、单账号各项操作、Windows 强制前置、页面错误/弹窗日志。核心文件。 |
| `AccountConfig.java` / `ConfigStore.java` | 账号配置模型与 `data/accounts.json` 读写（Gson，固定 10 条）。Profile 目录 `data/profiles/account-01..10`。 |
| `Channel.java` | 渠道枚举：天宇 TIANYU / 官版 GUANFANG / 小七 XIAOQI，含登录 URL。 |
| `Scripts.java` | 从 classpath `scripts/` 读取 JS 文本的工具类（常量注册表）。 |

`src/main/resources/scripts/`（JS 会打进 classpath）

- `tianyu_iframe.js` / `xiaoqi_iframe.js`：渠道外层页 → 游戏 iframe 的自动跳转。
- `touch_bridge.js`：**鼠标事件 → 手机 touch 事件桥接**（后加，见第 7 节）。
- `force_front.ps1`：Windows `user32.dll` 强制前置 Edge 窗口的 PowerShell。
- `speed.js`、`refresh_game.js`、`loop_game.js`、`auto_game.js`、
  `online_reward_part_1/2.js`、`login_lottery_draw.js`、`auto_clear_bag.js`、
  `lottery_circle.js`：从原 APK 抽取的游戏内辅助脚本（基本保持原样）。
- `official_auto_input_*.js`：官版自动填账号密码的分片（当前 Java 流程里**未被调用**，属于保留资源）。

---

## 5. 关键实现细节（维护必读）

### 5.1 单线程 Playwright 模型
`SessionManager` 用一个 `newSingleThreadScheduledExecutor`：
- Playwright.create、打开/关闭页面、**每秒一次的 `pollBootstrap` 轮询**全部在这一个线程；
- UI 线程只读 `AtomicIntegerArray` 状态，绝不直接碰 Playwright 对象；
- 否则 Playwright 跨线程调用会抛错。改动时务必沿用 `submit(...)`。

### 5.2 脚本注入判定
`AccountSession.pollBootstrap()` 每秒查找 URL 含
`worldh5.gamehz.cn/version/world/publish/channel/res/index.html` 的 Frame，
在该 Frame 里判断
`typeof xself !== 'undefined' && typeof Control !== 'undefined' && typeof nato !== 'undefined' && !!xself`
成立后只注入一次（`bootstrapped` 标志）。
注入用间接 eval：`frame.evaluate("(0,eval)(" + gson.toJson(script) + ")")`，
让脚本里的 `var TestAutoGame=...` 挂到全局 window（模拟安卓 evaluateJavascript）。

注入顺序见 `injectBootstrap()`：speed → lottery_circle →（可选 auto_clear_bag）
→ online_reward_1 → online_reward_2 → login_lottery_draw → refresh_game → loop_game → auto_game。

### 5.3 手机仿真（固定 510×760）
在 `AccountSession.configureMobileWindow()`：
- 启动参数 `setViewportSize(510,760)`、`setIsMobile(false)`、`setHasTouch(false)`、`deviceScaleFactor=1`；
- 通过 CDP（`context.newCDPSession(page)`）：
  - `Browser.getWindowForTarget` 拿 windowId；
  - 读取 `window.outerWidth/outerHeight/innerWidth/innerHeight` 算出 Edge 边框/工具栏尺寸；
  - `Browser.setWindowBounds` 把**外层窗口**设成 `510+chromeWidth × 760+chromeHeight`，保证网页客户区精确 510×760，且窗口在游戏区域内居中；
  - `Network.setUserAgentOverride` 设置 Android 手机 UA（Pixel 7，Chrome 主版本号取自当前 UA）。
- 常量 `MOBILE_WIDTH=510`、`MOBILE_HEIGHT=760`。

### 5.4 为什么 isMobile/hasTouch 最终设成 false
设置成 true 时，Windows 物理鼠标点击被 Chromium 转成触摸事件，
游戏按钮“看得到点不动”。改成 false 后，靠 `touch_bridge.js`（addInitScript 注入到每个 Frame）
手动把 `mousedown/move/up` 派发生成 `TouchEvent(touchstart/touchmove/touchend)`，
并伪造 `navigator.maxTouchPoints=5`、`ontouchstart` 等能力检测。
**这是当前“能点进游戏”的关键组合：手机 UA + 510×760 + 关闭原生触摸 + JS 触摸桥接。**

### 5.5 渠道 iframe 跳转（曾导致“频繁访问”死循环）
原 `tianyu_iframe.js/xiaoqi_iframe.js` 是每 100ms 无条件
`window.open(iframe.src,'_self')` / `location.href=iframe.src`，登录进游戏后
页面里残留同地址 iframe → 无限自我刷新 → 服务器报“频繁访问”。
已修：包 IIFE 防重复、间隔改 500ms、加跳转锁，
并在“顶层已是游戏页 / 已是 release 页”时跳过同名 iframe 跳转。

### 5.6 Windows 强制前置
Playwright `page.bringToFront()` 在多个独立 Edge 窗口重叠时经常无效。
`bringToFront()` 现在会：先 CDP `forceWindowBounds()` 恢复 510×760，
再 `page.bringToFront()`，再调用临时落地的 `force_front.ps1`，
按**该账号 Profile 目录路径**匹配 `msedge.exe` 进程命令行，
用 `ShowWindowAsync` + `AttachThreadInput` + `BringWindowToTop` + `SetForegroundWindow` 前置。
批量操作调用各 session 的 `xxx(false)`（不抢焦点），最后只前置当前选中账号。

### 5.7 诊断
`attachPageDiagnostics()` 监听 `onConsoleMessage`（error/warning）、
`onPageError`、`onDialog`（弹窗默认 accept），全部写进辅助栏日志。
后续“点不动/进不去”优先看日志里的“页面错误/页面异常/页面弹窗”。

### 5.8 反调试注意
**游戏疑似带反调试：手动打开 F12 DevTools 会命中 `debugger` 暂停。**
这不是 Playwright 的 CDP 导致的；排查问题时不要开 F12，用第 5.7 的日志，
或让维护者用 Playwright 自己抓控制台。

---

## 6. 配置与数据

- `data/accounts.json`：10 条，字段
  `index,title,channel,autoRepair,autoReward,autoClearBag`。
  - `customUrl`：留空=打开渠道登录页；非空=直接打开该直链。
  - “存号”按钮 = 抓到当前游戏 Frame 的完整 URL（含 token）写入 `customUrl` 并回填 UI。
  - 注意：token 直链会过期；失效后要清空 `customUrl` 走渠道重新登录。
- `data/profiles/account-XX/`：每账号独立 Edge 用户目录；删除该目录=让该账号重新登录。
- 当前（交接时）账号 1、2 已存入带 `mbUserId/mbToken` 的 https 游戏直链，3–10 为空。
  这些是敏感登录凭据，外发文档/仓库时注意脱敏。

---

## 7. 已完成功能清单

- [x] 10 开独立 Edge Profile、Cookie/缓存隔离
- [x] 90% 游戏区 + 10% 常驻窄辅助栏 UI
- [x] 账号 01–10 切换按钮 + 三色状态 + 橙色选中态
- [x] 单账号：打开/登录/刷新/前置/存号/设置/关闭
- [x] 单账号脚本：自动/刷怪/跟随/停止/进城/微端奖励
- [x] 批量：全开/全前置/全自动/全刷怪/全跟随/全停止/全进城/全微端
      （仅对“已注入”账号生效，日志报告成功/跳过数量）
- [x] 固定 510×760 手机视口 + 手机 UA + 外框尺寸 CDP 校正
- [x] 鼠标→触摸 JS 桥接（解决手机 UA 下点不动按钮）
- [x] 修复渠道 iframe 无限刷新导致的“频繁访问”
- [x] Windows user32 强制前置 + 前置时恢复窗口尺寸
- [x] 页面 console 错误/异常/弹窗接入日志
- [x] **同步操作（复刻 APK 长按同步触摸）**：辅助栏“当前账号”区新增“同步”开关，
      开启后操作当前号（主控），其它已进入游戏的号同步执行同样的点击/拖动；
      主控跟随当前选中账号，开关文案显示“同步中·XX”。
- [x] **辅助功能运行状态可视化（2026-09-16）**：“自动/刷怪/跟随”改成开关按钮，
      每秒读取游戏内真实标志 `TestAutoGame/TestRefreshGame/TestLoopGame._isStarting`，
      正在运行=绿色高亮、未运行=灰、未进游戏=禁用置灰；再点一次即开/关。
      顺带修复原 APK `loop_game.js` 的 stop 只清 interval、不复位
      `_isStarting` / 不 unregister ticker 的 bug。

---

## 8. 已知问题 / 待后续处理（重点）

1. **“进入游戏”点击问题虽已多轮修复，用户在最后一版（加 touch_bridge + 诊断）之后
   尚未确认是否彻底解决。** 若仍点不进：
   - 先让用户重启（必须重开窗口）并提供辅助栏里“页面错误/异常/弹窗”日志；
   - 怀疑方向：游戏可能不是标准 `Touch` 构造，或用了指针事件/坐标缩放，
     可在 `touch_bridge.js` 里再补 `pointerdown/pointerup`（`pointerType:'touch'`）
     和 canvas 直接派发；也可临时把手机 UA 去掉做 A/B 对照，确认是不是 UA 触发触摸分支。
2. 网络环境：工程内曾出现对游戏域名直连失败（`127.0.0.1:9 积极拒绝`，疑似本机代理/DNS）。
   若打不开游戏，先排查系统代理/host。
3 `official_auto_input_*.js` 自动填账号密码尚未接入 Java 流程。
4. 官版/小七是 HTTP 明文（已 `setIgnoreHTTPSErrors`），天宇为 HTTPS。
5. `force_front.ps1` 每次前置会落地临时 ps1 并起一个 powershell 进程（5s 超时），
   频繁批量前置时有一定进程开销，后续可考虑常驻或改用 JNA。
6. 10 开内存占用高；辅助脚本改变游戏行为，有封号风险（用户自担）。

---

## 9. 对下一位维护者的建议

- 改动遵守第 5.1 的单线程模型；任何新浏览器操作走 `SessionManager.submit`。
- 改 JS 后用 `node --check <file>` 验证语法；改完跑一次 `mvn -q compile`。
- 不要开 F12 调试游戏（反调试），优先用已接好的 console/pageerror 日志。
- 不要把 UI 改回大表单；保持 90/10。
- 新增游戏功能时：单账号方法放 `AccountSession`（参考 startAuto 的
  `activateWindow` 重载），批量封装放 `SessionManager.runOnBootstrapped(...)`，
  按钮加到 `WorldDesktop` 批量区。
- 逆向原始逻辑可查 `<USER_PROFILE>Jing\Desktop\h5\analysis\java\com\liang\world\`
  （`WorldActivity`、`WorldAccount$3` 等）及 `analysis/REPORT.md`。

---

## 10. 一键信息速查

- 编译：`.\tools\apache-maven-3.9.11\bin\mvn.cmd -q compile`
- 运行：双击 `run.cmd`
- 游戏 URL 特征：`worldh5.gamehz.cn/version/world/publish/channel/res/index.html`
- 就绪判定全局对象：`xself` / `Control` / `nato`
- 视口：510 × 760
- 账号数：10

---

## 11. 同步操作实现说明（2026-09-16 新增）

需求来源：原 APK 长按账号标签可“同步触摸”。APK 原理见
`analysis/java/com/liang/world/WorldAccount$4.java`：当前显示的 WebView 收到
`MotionEvent` 时，在 `OnTouchListener` 里把同一个事件用
`webView.dispatchTouchEvent(event)` 原样分发给其它“开启同步且可见”的 WebView
（5 个 WebView 同尺寸叠放，坐标直接通用）。

桌面端是 10 个**独立 Edge 进程**，无法直接派发系统触摸事件，因此改为
“归一化坐标 + JS 采集/重放”：

- `scripts/sync_capture.js`（每个帧都注入）：在主控窗口捕获原生 `mousedown /
  mousemove / mouseup`，把坐标按 `.egret-player` 游戏画面盒归一化成 0~1
  （窗口/画面缩放比例不同也能对齐），经 Playwright `exposeFunction` 绑定
  `__worldSyncSend` 回传 Java。move 节流 24ms。
- `scripts/sync_replay.js`（每个帧都注入）：`window.__worldSyncRecv(json)`
  接收归一化坐标，在本号游戏盒对应位置合成 `TouchEvent`（identifier 2000，
  start 时 `elementFromPoint` 锁定目标，move/end 沿用），游戏 Egret canvas 直接响应。
- `AccountSession`：`open()` 里 `context.exposeFunction("__worldSyncSend",…)`
  注册回传；`replaySyncEvent()` 优先用已跟踪的 `activePage` 主帧
  （`preferredReplayFrame`，避免高频遍历所有 iframe）；`clearSyncGesture()` 松键。
- `SessionManager`：`syncEnabled/syncMaster` 总开关与主控；回传只在
  “已开启 + 主控号”时入槽。**防积压**：start/end 边界事件入
  `syncBoundaryQueue` 必达，move 只保留 `latestSyncMove` 最新一帧；
  worker 单线程每 33ms `flushSyncEvents()` 广播给所有 isBootstrapped 的号。
- UI：`WorldDesktop` “前置”旁新增“同步”按钮，开启后橙色显示
  “同步中·XX”；点账号按钮切换选中时 `setSyncMaster()` 跟随并清理残留按下态。

注意：
- 同步只对“已注入（已进入游戏）”的号生效；主控必须是最前窗口（物理鼠标只落在最前窗口）。
- 这是游戏级触摸同步，不依赖按钮 DOM 是否一致，各号画面布局一致即可对齐。
- 与 APK 一样属于同时操作多号，有误触/封号风险，用完及时关“同步”。

### 11.1 辅助功能开关与状态灯（自动/刷怪/跟随）

- 游戏脚本本身有真实运行位 `TestXxx._isStarting`（auto/refresh 的 start、stop
  都会置位/复位；**原 loop 的 stop 不会复位，已在 `loop_game.js` 修复**）。
- `AccountSession`：`FLAG_AUTO=1 / FLAG_REFRESH=2 / FLAG_LOOP=4` 位掩码 +
  `volatile int scriptFlags`；startXxx 乐观置位；新增 `stopAuto/stopRefresh/stopLoop`
  单独停止；`pollScriptStates()` 每秒在 worker 线程用
  `preferredReplayFrame()`（复用已跟踪游戏主帧，不全量遍历 iframe）读一次三个
  `_isStarting` 校正真实状态。
- `SessionManager`：`isScriptOn(index,flagBit)` 查询；`toggleAuto/toggleRefresh/
  toggleLoop` 按当前状态 start/stop；`pollAllSafely()` 每秒调用
  `pollScriptStates()`。
- `WorldDesktop`：三个按钮改走 `toggleButton(...)`，`paintToggle()` 在每秒
  刷新里着色——运行=绿 `COLOR_RUNNING`、空闲=灰、未注入(未进游戏)=`setEnabled(false)`。
  “停止”按钮仍为一键停全部（会把三个灯都清掉）。

### 11.2 同步延迟 ~1s 的根因与修复（2026-09-16 晚）

现象：开同步后，主控号点完，其它号约 1 秒后才动；同时“加速/存号”等按钮也偶发卡顿。

根因：所有 Playwright 调用都串行在单线程 `playwright-worker`。每秒巡检
`pollAllSafely` 对最多 10 个号逐个 `locateGameFrame`（遍历所有 page/frame，
每个 frame 还发一次 evaluate 探测就绪/注入），加上后来每秒再读一次功能状态，
稳态下一轮要几十次跨进程往返、耗时接近 1 秒；同步重放和按钮指令只能排队，尾延迟≈整轮。

修复：
- `AccountSession` 增加稳态快车道：已注入且游戏仍在 `activePage` 主帧时，
  `pollBootstrap` 每轮只发 **1 次合并 evaluate**（`pollSteadyState` 同时返回
  booted 与 auto/refresh/loop 运行位），不再全量遍历 iframe；检测到跳转
  （booted=false）才回慢车道重新定位+注入。新增零探测的 `activeMainGameFrame()`，
  `preferredReplayFrame()` 优先用它。原独立 `pollScriptStates()` 已删除并入快车道。
- `SessionManager.pollAllSafely` 每巡检完**一个号立即 `flushSyncEvents()`**，
  同步尾延迟上限≈单个号一次往返，而不是等完整整轮；同步冲刷定时从 33ms 提到 20ms。
- 结果：稳态每号每秒从 4~5 次往返降到 1 次，worker 队列基本不再积压。

注意：加速脚本 `speed.js` 内容未被破坏（仍是 `Control.DEFAULT_MOVE_SPEED = 12`）；
之前“加速/存号像没反应”主要是 worker 被巡检占满导致指令排队，本次一并缓解。
若仍异常，先确认是否已重开窗口（init/注入脚本只对新窗口生效）。

### 11.3 自动清背包排查与重写（2026-09-17）

原实现：注入一段从 APK 抽出的**混淆脚本** `auto_clear_bag.js`，内部
`setInterval(_bag_clean_func, 0x2bf20=180000ms=3分钟)`，且只在 `injectBootstrap`
里 **if (config.isAutoClearBag()) 才注入一次**。扫描 `xself.bag.store.slice(30, bagEnd)`
（只看第30格后），按 NEED_CLEAN 垃圾名单 / 限时道具“天）”“天)”且 reqLv<55 /
grade<3 reqLv<50 装备 / grade<4 reqLv<40 装备 收集 slotPos；白名单 WHITE、名字含
“无限潜能/魔染幽纹”、star>0 跳过；逐格 `xself.bag.removeItem` +
`MsgHandler.createItemShopSell(Define.SHOP_PET_USEITEM_ID,id,slotPos,quantity)` +
`nato.Network.sendCmd`，最后 `ItemManager.doBagRefresh()`。

“感觉没生效”的原因：①首次要等 **3 分钟**，之后每 3 分钟才一次；②设置里勾选只改
配置，**必须重开窗口/重进游戏重新注入**才生效，无任何反馈；③无状态/无日志，无法确认。

重写（规则与两个名单 1:1 保留，已用桩对象自测白名单/升星/高等级不误卖）：
- 新 `auto_clear_bag.js` 暴露 `window.WorldBagClear={start,stop,runNow,isRunning,ready,status}`，
  注入幂等（`__WorldBagClearInstalled`），含 ready() 依赖保护；**启动 8 秒后首次清理，
  之后每 60 秒一次**。
- `AccountSession.injectBootstrap` 现在**始终加载该库**（不 start 不卖任何东西），
  再按 `config.isAutoClearBag()` 调 start；新增 `applyAutoClearBagLive()`（设置勾选
  即时开/关，无需重开）与 `clearBagNow()`（立即清一次并日志回报出售件数）。
- `SessionManager` 暴露 `applyAutoClearBagLive(index)`、`clearBagNow(index)`。
- UI：设置弹窗勾选保存后实时生效；“当前账号”区新增 **清包** 按钮（立即清理+日志件数）。

### 11.4 任务进度日志（提交/接取任务自动记录，2026-09-17）

需求：游戏里一提交（或接取）任务，就在控制台打出任务，记录做到哪个任务。

实现：
- 新脚本 `src/main/resources/scripts/mission_log.js`，在 `injectBootstrap` 里
  随其它辅助脚本一起 `evalGlobal` 注入（仅游戏帧）。旁路 hook，**透传 this/参数/返回值，
  try/catch 全包裹，绝不影响游戏**；Mission 未就绪时每 500ms 重试（最多 30s）。
- hook 两个统一入口（覆盖自动挂机与手动点 NPC）：
  - `Mission.doMenuButton(npc, arg2, subType, mission)`：TestAutoGame 交/接任务走这里；
  - `NPC.handlerMissionNPCAction(npc, action)`：手动 NPC 对话走这里，mission 在 `action.data.mission`。
- 任务名用多种 getter 兜底（getName/getTitle/getMissionName…及 name/title 字段，再遍历找中文短串），
  任务 id 用 `getId()`；状态用 `mission.getMissionStatus(xself)` 反查 MissionConst 名字。
  游戏源码确认：已接任务且 `submitCondition` 满足时为 `CAN_SUBMIT`（点了就是提交），
  未接且满足接取条件时为 `CAN_ACCEPT`，`NOT_CAN_*` 表示进行中/条件不足。因此文案规则为
  `CAN_SUBMIT`→“提交任务”、`CAN_ACCEPT`→“接取任务”、其余→“任务推进”；必须精确比较，
  不能用 indexOf（`NOT_CAN_SUBMIT` 包含 `CAN_SUBMIT` 子串）。
  3 秒去重 key 为 `id|name|status`（不含 subType），用于合并 `Mission.doMenuButton` 与
  `NPC.handlerMissionNPCAction` 两个入口的同一次点击；接取/提交状态不同，不会互相吞掉。
  2026-09-17 21:39 已通过 `mvn -q clean compile`，并用桩对象验证两个入口同时触发时只记录一条、
  `CAN_SUBMIT` 正确显示“提交任务”（改动需完全重启控制台和所有 Edge 窗口后生效）。
- 回传：JS 调 Playwright 绑定 `__worldLog(text)`（每个 context 在 open() 注册）。
  Java 侧同时 ①写辅助栏日志“[任务] 账号 文本”；②追加到文件
  `data/任务记录.txt`（新类 `MissionLog.java`，线程安全、带 yyyy-MM-dd HH:mm:ss）。

注意：任务名/状态依赖游戏运行时对象，真实字段以游戏为准；若日志只打出 id 没名字，
说明该 mission 对象 getter 命名不同，按实际 `onConsoleMessage` 或补 getter 名单即可。
hook 只在注入后的游戏帧生效，需重开窗口。

---

## 12. 自动托管 / 自动切号目标定稿（2026-09-17 21:49）

用户确认的完成判定：

1. **A 方案：目标任务优先。**
   - 用户后续会模拟走完整游戏任务，并提供最终目标任务名称（必要时再补任务 ID）。
   - Excel/配置中支持填写“目标任务”；多个候选名可用逗号/顿号分隔，命中任一“提交任务”事件即判定该号完成。
   - 任务名匹配需 trim，忽略全角/半角空格差异；若游戏能稳定取到 id，优先用 id，名称只作展示和兜底。
2. **B 方案：60 分钟后的无任务计数保底（用户 2026-09-17 21:55:37（星期四，CST）最终确认）。**
   - 单号开始自动任务后，前 60 分钟正常挂机，不启用“无任务切号”判定。
   - 满 60 分钟后开始保底检查：**每 3 分钟检查一次**当前是否存在 `CAN_ACCEPT`（可接）或 `CAN_SUBMIT`（可交）任务。
   - 当次检查没有可接/可交任务：无任务计数 `+1`。
   - 当次检查仍有可接或可交任务：无任务计数立即清零，继续挂机。
   - 无任务计数累计满 **20 次**才执行收尾并切号；20 次 × 3 分钟 = 连续 60 分钟检查点都没任务。
   - 不是“满 60 分钟后当次没任务就切”，也不是连续监控 3 分钟。

3. **最大风险保护：** B 方案最坏在 120 分钟时才切号（前 60 分钟不检查 + 20 次 × 3 分钟），所以硬性最大运行时长必须大于 120 分钟；默认建议 **150 分钟**（后续 UI 可调），仅用于异常兜底，不能早于 B 方案正常触发。
4. 收尾动作：停止自动/刷怪/跟随 → 可选最后清包 → 写入任务/托管结果 → 关闭当前 Edge → 打开下一个 Excel 账号。
5. 实施顺序：先做“单号一键托管”（登录等待→注入→进城→自动→状态显示/停止），再做任务监控与 A/B 完成判定，最后接入 Excel 队列自动切号；不要一开始直接做 10 开并发。

待用户提供：完整任务链末端的目标任务名称/截图/日志；拿到前可先开发状态机、任务快照、计时和队列框架，A 命中逻辑预留配置字段。

---

## 13. 单号一键托管状态机（2026-09-17 22:24:14 CST）

第一阶段只做单号闭环，**不做自动切号/关窗**，避免登录、进城、任务完成判定混在一起难以排查。

入口：右侧“当前账号”区新增 **托管 / 停管·XX** 按钮。点击“托管”后对当前选中账号执行：

1. 若窗口未打开则自动打开；已打开则复用当前窗口。
2. 等待官服自动登录/选角，或天宇 URL 加载进入游戏。
3. 等待既有 `pollBootstrap()` 定位游戏 Frame、确认 `xself/Control/nato` 并完成脚本注入。
4. 注入后调用 `AccountSession.enterCityIfNeeded(false)`：先用 `xworld.isInCityNow()` 判断，未进城才执行 `City.doEnterCity(xself.getId())`，避免重复进城。
5. 启动 `TestAutoGame.start()`，通过稳态轮询读取真实 `_isStarting` 位确认自动任务运行。
6. 持续守护：若自动状态丢失，间隔重试最多 3 次；窗口关闭、登录超时、注入丢失、进城/启动超时都会输出明确失败阶段。
7. 点击“停管”会尽量停止自动/刷怪/跟随（登录页找不到游戏帧也不会报错），**保留 Edge 窗口**。

实现要点：
- `SessionManager` 新增 `PILOT_*` 阶段、`startAutoPilot/stopAutoPilot/pollAutoPilot`；所有 Playwright 操作仍在单线程 `playwright-worker`，每秒 `pollAllSafely()` 末尾推进一次状态机，不使用阻塞 sleep。
- `AccountSession` 新增 `isInCity()`、`enterCityIfNeeded(boolean)`、`stopScriptsIfInGame(boolean)`。
- UI 顶部当前账号行会显示托管阶段和已运行时长；按钮运行中变橙色并显示“停管·槽位”。
- 登录/选角等待超时 10 分钟；进城超时 60 秒；自动启动确认 30 秒。后续 A/B 判定和自动切号在此状态机上继续扩展。