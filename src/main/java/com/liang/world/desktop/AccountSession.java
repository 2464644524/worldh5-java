package com.liang.world.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ViewportSize;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;

import java.awt.Rectangle;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AccountSession implements AutoCloseable {
    public static final String GAME_URL_MARKER =
            "worldh5.gamehz.cn/version/world/publish/channel/res/index.html";

    private static final Gson GSON = new Gson();
    private static final int MOBILE_WIDTH = 510;
    private static final int MOBILE_HEIGHT = 760;
    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/(\\d+)");

    private final AccountConfig config;
    private final Path profileDir;
    private final Consumer<String> logger;
    private Rectangle gameBounds;

    private BrowserContext context;
    private Page page;
    private CDPSession cdpSession;
    private Long browserWindowId;
    private int windowX;
    private int windowY;
    private int windowOuterWidth;
    private int windowOuterHeight;
    private boolean bootstrapped;
    private String officialUsername = "";
    private String officialPassword = "";
    private boolean officialLoginSubmitted;
    private boolean roleEnterSubmitted;
    private long loadingSceneFirstSeenAt;
    private long loadingActionAt;
    private int loadingClickCount;
    private long roleSceneFirstSeenAt;
    private long roleFallbackActionAt;
    private int roleFallbackStep;
    private boolean roleObjectEnterRequested;
    private boolean createRoleNotified;
    private boolean captchaNotified;
    private boolean loginTipNotified;
    private String lastLoginTraceStage = "";
    // 仅“导号换号”这一次打开需要清 Cookie/本地存储；平时打开沿用已登录会话。
    private boolean forceFreshLogin;

    private Page activePage;
    private String mobileUserAgent;
    private int mobileWidth = MOBILE_WIDTH;
    private int mobileHeight = MOBILE_HEIGHT;
    private int chromeWidth = 16;
    private int chromeHeight = 88;
    private volatile double gameScale = 1.0;
    private String chromeVersion;
    private boolean popupNotified;
    private final java.util.Set<Page> adoptedPages =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 主控号采集到的归一化触摸事件，交给 SessionManager 广播（可能由 Playwright 网络回调线程触发）。
    private volatile java.util.function.Consumer<String> syncEventListener;

    // 自动/刷怪/跟随在游戏内的真实运行状态（读 TestXxx._isStarting），每秒轮询刷新。
    public static final int FLAG_AUTO = 1;
    public static final int FLAG_REFRESH = 2;
    public static final int FLAG_LOOP = 4;
    private volatile int scriptFlags;

    public AccountSession(AccountConfig config, Path profileDir, Rectangle gameBounds,
                          Consumer<String> logger) {
        this.config = config;
        this.profileDir = profileDir;
        this.gameBounds = gameBounds;
        this.logger = logger;
    }

    public boolean isOpen() {
        return context != null && page != null && !page.isClosed();
    }

    public synchronized boolean isBootstrapped() {
        return bootstrapped;
    }

    // 调整游戏区/辅助栏比例后，实时把已打开的窗口重新居中到新游戏区。
    public synchronized void updateGameBounds(Rectangle bounds) {
        this.gameBounds = bounds;
        if (isOpen() && windowOuterWidth > 0 && windowOuterHeight > 0) {
            windowX = gameBounds.x + Math.max(0, (gameBounds.width - windowOuterWidth) / 2);
            windowY = gameBounds.y + Math.max(0, (gameBounds.height - windowOuterHeight) / 2);
            try {
                forceWindowBounds();
            } catch (Exception ignored) {
            }
        }
    }

    public String currentUrl() {
        if (!isOpen()) {
            return "";
        }
        try {
            return page.url();
        } catch (Exception e) {
            return "";
        }
    }

    // 实时调整 Edge 手机仿真分辨率：改 viewport、外框窗口尺寸并重新居中。
    public synchronized void updateEmulationSize(int width, int height) {
        mobileWidth = Math.max(240, Math.min(1400, width));
        mobileHeight = Math.max(320, Math.min(2000, height));
        if (!isOpen()) {
            return;
        }
        windowOuterWidth = mobileWidth + chromeWidth;
        windowOuterHeight = mobileHeight + chromeHeight;
        if (gameBounds != null) {
            windowX = gameBounds.x + Math.max(0, (gameBounds.width - windowOuterWidth) / 2);
            windowY = gameBounds.y + Math.max(0, (gameBounds.height - windowOuterHeight) / 2);
        }
        for (Page candidate : activePages()) {
            try {
                if (!candidate.isClosed()) {
                    // 视口跟随窗口，只需改外框窗口大小，内容区会自动变成 mobileWidth x mobileHeight。
                    resizePageWindow(candidate);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public double getGameScale() {
        return gameScale;
    }

    // 实时缩放游戏画面（绕开 Edge 窗口最小宽度限制）。k 为相对视口的比例，如 0.7。
    public synchronized void setGameScale(double k) {
        double value = Math.max(0.35, Math.min(1.2, k));
        this.gameScale = value;
        if (!isOpen()) {
            return;
        }
        String js = "(k) => { window.__worldGameScale = k; "
                + "if (typeof window.__worldGameSetScale === 'function') window.__worldGameSetScale(k); }";
        for (Page candidate : activePages()) {
            try {
                if (candidate.isClosed()) {
                    continue;
                }
                for (Frame frame : candidate.frames()) {
                    try {
                        frame.evaluate(js, value);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void resizePageWindow(Page target) {
        if (target == null || target.isClosed() || browserWindowId == null) {
            return;
        }
        try {
            CDPSession perCdp = context.newCDPSession(target);
            JsonObject info = perCdp.send("Browser.getWindowForTarget");
            long windowId = info.get("windowId").getAsLong();
            JsonObject bounds = new JsonObject();
            bounds.addProperty("left", windowX);
            bounds.addProperty("top", windowY);
            bounds.addProperty("width", windowOuterWidth);
            bounds.addProperty("height", windowOuterHeight);
            bounds.addProperty("windowState", "normal");
            JsonObject params = new JsonObject();
            params.addProperty("windowId", windowId);
            params.add("bounds", bounds);
            perCdp.send("Browser.setWindowBounds", params);
        } catch (Exception ignored) {
        }
    }

    public synchronized void prepareOfficialLogin(String username, String password) {
        this.officialUsername = username == null ? "" : username.trim();
        this.officialPassword = password == null ? "" : password.trim();
        this.forceFreshLogin = true;
        resetOfficialAutomationState();
    }

    public synchronized void clearOfficialLogin() {
        this.officialUsername = "";
        this.officialPassword = "";
        this.forceFreshLogin = false;
        resetOfficialAutomationState();
    }

    /**
     * 天宇/小七等 URL 渠道接力换号：强制下一次 open() 使用干净登录态。
     * 正常打开不调用，避免破坏已登录会话。
     */
    public synchronized void prepareFreshChannelLogin() {
        this.officialUsername = "";
        this.officialPassword = "";
        this.forceFreshLogin = true;
        resetOfficialAutomationState();
    }

    public synchronized String getOfficialUsername() {
        return officialUsername == null ? "" : officialUsername;
    }

    public synchronized String getOfficialPassword() {
        return officialPassword == null ? "" : officialPassword;
    }

    private void resetOfficialAutomationState() {
        officialLoginSubmitted = false;
        roleEnterSubmitted = false;
        loadingSceneFirstSeenAt = 0L;
        loadingActionAt = 0L;
        loadingClickCount = 0;
        roleSceneFirstSeenAt = 0L;
        roleFallbackActionAt = 0L;
        roleFallbackStep = 0;
        roleObjectEnterRequested = false;
        createRoleNotified = false;
        captchaNotified = false;
        loginTipNotified = false;
        lastLoginTraceStage = "";
    }



    public synchronized void open(Playwright playwright) {
        if (isOpen()) {
            bringToFront();
            return;
        }
        if (context != null) {
            // 用户手动关掉了页面，但旧的 context 对象还残留，先清理再重开。
            try {
                context.close();
            } catch (Exception ignored) {
            }
            context = null;
            page = null;
            activePage = null;
            cdpSession = null;
            browserWindowId = null;
            bootstrapped = false; scriptFlags = 0;
            popupNotified = false;
        }

        try {
            Files.createDirectories(profileDir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建浏览器 Profile 目录: " + profileDir, e);
        }

        var options = new BrowserType.LaunchPersistentContextOptions()
                .setChannel("msedge")
                .setHeadless(false)
                .setBypassCSP(true)
                .setIgnoreHTTPSErrors(true)
                // 视口不锁定：传 null 让页面视口跟随真实窗口大小，
                // 这样手动放大/缩小 Edge 窗口时，游戏画面会按比例自适应。
                // 不启用 Chromium 触摸设备模式（hasTouch/isMobile），桌面鼠标点击更可靠，
                // 触摸事件由 touch_bridge.js 桥接。
                .setViewportSize((ViewportSize) null)
                .setIsMobile(false)
                .setHasTouch(false)
                .setArgs(List.of(
                        "--window-position=" + Math.max(0, gameBounds.x) + ","
                                + Math.max(0, gameBounds.y),
                        "--no-first-run",
                        "--no-default-browser-check",
                        "--disable-popup-blocking",
                        "--disable-background-timer-throttling",
                        "--disable-renderer-backgrounding",
                        "--disable-backgrounding-occluded-windows",
                        "--disable-features=Translate,msEdgeSidebar"
                ));

        context = playwright.chromium().launchPersistentContext(profileDir, options);
        page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
        activePage = page;
        popupNotified = false;
        resetOfficialAutomationState();
        adoptedPages.clear();
        adoptedPages.add(page);
        // 游戏选角后可能 window.open 弹出真正的游戏标签页，统一接管 context 里所有页面。
        // 同步操作：主控游戏帧把鼠标手势经该绑定回传，Java 再广播给其它号重放。
        context.exposeFunction("__worldSyncSend", args -> {
            try {
                java.util.function.Consumer<String> listener = syncEventListener;
                if (listener != null && args != null && args.length > 0 && args[0] != null) {
                    listener.accept(String.valueOf(args[0]));
                }
            } catch (Exception ignored) {
            }
            return null;
        });
        // 任务进度日志：mission_log.js 通过该绑定把“提交/交接任务”文本回传控制台。
        context.exposeFunction("__worldLog", args -> {
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    String text = String.valueOf(args[0]);
                    if (!text.isBlank()) {
                        log("[任务] " + config.displayName() + " " + text);
                        MissionLog.append(config.displayName(), text);
                        ActionLog.append(config.displayName(), "任务交互 " + text);
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        });
        context.exposeFunction("__worldTrace", args -> {
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    String text = String.valueOf(args[0]);
                    if (!text.isBlank()) {
                        traceLoginEvent(text);
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        });
        // 游戏内操作记录：只落盘到 data/操作记录.txt，不刷控制台，避免高频点击刷屏。
        context.exposeFunction("__worldAction", args -> {
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    String text = String.valueOf(args[0]);
                    if (!text.isBlank()) {
                        ActionLog.append(config.displayName(), text);
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        });
        context.onPage(this::onContextPage);
        onContextPage(page);
        hydrateOfficialCredentials();
        if (forceFreshLogin) {
            // 仅导号换号这一次清掉旧 Cookie，避免和上一个账号串号；平时打开保留登录态。
            context.clearCookies();
        }

        configureMobileWindow();
        installRedirectScripts();
        // 换号清理只对本次打开生效（init 脚本已装配），之后普通“打开/刷新”沿用已登录会话。
        boolean freshLogin = forceFreshLogin;
        forceFreshLogin = false;
        traceLoginEvent("开始打开" + config.getChannel().displayName()
                + "渠道地址：" + safeUrlForLog(config.startupUrl()));
        page.navigate(config.startupUrl());
        bringToFront();
        if (freshLogin && config.getChannel() == Channel.GUANFANG) {
            log("已按导号账号重新登录 " + config.displayName());
        }
        log("已打开 " + config.displayName());
    }

    public synchronized void login() {
        ensureOpen();
        bootstrapped = false; scriptFlags = 0;
        resetOfficialAutomationState();
        traceLoginEvent("进入渠道登录页：" + safeUrlForLog(config.getChannel().loginUrl()));
        page.navigate(config.getChannel().loginUrl());
        bringToFront();
        log("进入登录页 " + config.displayName());
    }

    public synchronized void reload() {
        ensureOpen();
        bootstrapped = false; scriptFlags = 0;
        resetOfficialAutomationState();
        page.reload();
        log("刷新 " + config.displayName());
    }

    public synchronized void bringToFront() {
        ensureOpen();
        // 不再强制恢复窗口尺寸/位置，保留用户手动缩放/拖动 Edge 后的状态，
        // 游戏视口跟随窗口，画面会随窗口大小自适应。
        try {
            Page front = activePage != null && !activePage.isClosed() ? activePage : page;
            front.bringToFront();
        } catch (Exception ignored) {
        }
        forceWindowsForeground();
    }

    public synchronized void pollBootstrap() {
        if (!isOpen()) {
            return;
        }
        adoptOtherPages();

        // 稳态快车道：已注入且游戏仍在已跟踪主帧时，只用 1 次合并往返同时拿到
        // “是否仍已注入 / 自动刷怪跟随运行位”，避免每秒全量遍历所有标签页和
        // iframe（那会独占 worker 线程近 1 秒，卡住同步、加速、存号等所有操作）。
        if (bootstrapped) {
            Frame cached = activeMainGameFrame();
            if (cached != null && pollSteadyState(cached)) {
                return;
            }
        }

        // 慢车道：登录页 / 加载中 / 刚跳转。扫描所有页面和 Frame，
        // 因为天宇加载页的第一次“进入游戏”点击可能发生在选角场景对象就绪前。
        Optional<Frame> gameFrame = pollLoginAutomationAcrossFrames();
        if (gameFrame.isEmpty()) {
            return;
        }
        Frame frame = gameFrame.get();
        try {
            Object ready = frame.evaluate(
                    "() => typeof xself !== 'undefined' && typeof Control !== 'undefined' "
                            + "&& typeof nato !== 'undefined' && !!xself");
            if (!Boolean.TRUE.equals(ready)) {
                return;
            }
            Object booted = frame.evaluate("() => !!window.__worldDesktopBooted");
            if (Boolean.TRUE.equals(booted)) {
                bootstrapped = true;
                return;
            }
            injectBootstrap(frame);
        } catch (Exception ignored) {
            // 页面尚在加载或跨域 Frame 尚未就绪，下一轮继续。
        }
    }

    // 单次往返读取稳态信息并刷新功能灯位；返回 false 表示页面已跳转，需走慢车道重新注入。
    private boolean pollSteadyState(Frame frame) {
        try {
            Object result = frame.evaluate("""
                    () => {
                        try {
                            const ready = typeof xself !== 'undefined' && !!xself
                                && typeof Control !== 'undefined'
                                && typeof nato !== 'undefined';
                            if (!ready) return JSON.stringify({ ready: false });
                            return JSON.stringify({
                                ready: true,
                                booted: !!window.__worldDesktopBooted,
                                a: !!(typeof TestAutoGame !== 'undefined' && TestAutoGame._isStarting),
                                r: !!(typeof TestRefreshGame !== 'undefined' && TestRefreshGame._isStarting),
                                l: !!(typeof TestLoopGame !== 'undefined' && TestLoopGame._isStarting)
                            });
                        } catch (e) {
                            return null;
                        }
                    }
                    """);
            if (!(result instanceof String json) || json.isBlank()) {
                return true; // 瞬时异常：留在快车道，下一秒再试
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.get("ready") == null || !obj.get("ready").getAsBoolean()) {
                return true;
            }
            if (obj.get("booted") == null || !obj.get("booted").getAsBoolean()) {
                return false; // 游戏发生跳转、注入标记消失，走慢车道重新注入
            }
            int flags = 0;
            if (obj.get("a") != null && obj.get("a").getAsBoolean()) flags |= FLAG_AUTO;
            if (obj.get("r") != null && obj.get("r").getAsBoolean()) flags |= FLAG_REFRESH;
            if (obj.get("l") != null && obj.get("l").getAsBoolean()) flags |= FLAG_LOOP;
            scriptFlags = flags;
            return true;
        } catch (Exception e) {
            return true;
        }
    }

        public synchronized void startAuto() {
        startAuto(true);
    }

    public synchronized void startAuto(boolean activateWindow) {
        evaluateGameFrame("TestAutoGame.start()", activateWindow);
        scriptFlags |= FLAG_AUTO;
        log("自动已开启 " + config.displayName());
    }

    public synchronized void startRefresh() {
        startRefresh(true);
    }

    public synchronized void startRefresh(boolean activateWindow) {
        evaluateGameFrame("TestRefreshGame.start(4000)", activateWindow);
        scriptFlags |= FLAG_REFRESH;
        log("刷怪已开启 " + config.displayName());
    }

    public synchronized void startLoop() {
        startLoop(true);
    }

    public synchronized void startLoop(boolean activateWindow) {
        evaluateGameFrame("TestLoopGame.start()", activateWindow);
        scriptFlags |= FLAG_LOOP;
        log("跟随已开启 " + config.displayName());
    }

    public synchronized void stopScripts() {
        stopScripts(true);
    }

    public synchronized void stopScripts(boolean activateWindow) {
        evaluateGameFrame("""
                try { TestAutoGame.stop(); } catch (e) {}
                try { TestRefreshGame.stop(); } catch (e) {}
                try { TestLoopGame.stop(); } catch (e) {}
                """, activateWindow);
        scriptFlags = 0;
        log("脚本已停止 " + config.displayName());
    }

    // 托管停止时可能正处在登录页/加载页，不能因为找不到游戏帧而打断状态机收尾。
    public synchronized void stopScriptsIfInGame(boolean activateWindow) {
        if (!isOpen()) {
            scriptFlags = 0;
            return;
        }
        Optional<Frame> gameFrame = locateGameFrame(true);
        if (gameFrame.isEmpty()) {
            scriptFlags = 0;
            return;
        }
        try {
            evalGlobal(gameFrame.get(), """
                    try { TestAutoGame.stop(); } catch (e) {}
                    try { TestRefreshGame.stop(); } catch (e) {}
                    try { TestLoopGame.stop(); } catch (e) {}
                    """);
            if (activateWindow) {
                bringToFront();
            }
        } catch (Exception ignored) {
        }
        scriptFlags = 0;
    }

    public int scriptFlags() {
        return scriptFlags;
    }

    public synchronized void stopAuto() {
        evaluateGameFrame("try { TestAutoGame.stop(); } catch (e) {}", true);
        scriptFlags &= ~FLAG_AUTO;
        log("自动已关闭 " + config.displayName());
    }

    public synchronized void stopRefresh() {
        evaluateGameFrame("try { TestRefreshGame.stop(); } catch (e) {}", true);
        scriptFlags &= ~FLAG_REFRESH;
        log("刷怪已关闭 " + config.displayName());
    }

    public synchronized void stopLoop() {
        evaluateGameFrame("try { TestLoopGame.stop(); } catch (e) {}", true);
        scriptFlags &= ~FLAG_LOOP;
        log("跟随已关闭 " + config.displayName());
    }

    // 设置里勾选后实时生效：不用重开窗口，直接在游戏帧启动/停止定时清背包。
    public synchronized void applyAutoClearBagLive() {
        boolean on = config.isAutoClearBag();
        if (!isOpen()) {
            return;
        }
        Optional<Frame> gameFrame = locateGameFrame(true);
        if (gameFrame.isEmpty()) {
            log("自动清背包将在进入游戏后生效 " + config.displayName());
            return;
        }
        try {
            Frame frame = gameFrame.get();
            evalGlobal(frame, Scripts.load(Scripts.AUTO_CLEAR_BAG));
            String js = on
                    ? "() => { try { window.WorldBagClear && WorldBagClear.start(); } catch (e) {} }"
                    : "() => { try { window.WorldBagClear && WorldBagClear.stop(); } catch (e) {} }";
            frame.evaluate(js);
            log((on ? "自动清背包已开启（约8秒后首次清理，之后每60秒一次）"
                    : "自动清背包已关闭") + " " + config.displayName());
        } catch (Exception e) {
            log("切换自动清背包失败 " + config.displayName() + ": " + e.getMessage());
        }
    }

    // 手动立即清一次背包，返回本次出售件数并写日志。
    public synchronized void clearBagNow() {
        ensureOpen();
        Optional<Frame> gameFrame = locateGameFrame(true);
        if (gameFrame.isEmpty()) {
            throw new IllegalStateException("还没进入游戏，无法清背包: " + config.displayName());
        }
        Frame frame = gameFrame.get();
        evalGlobal(frame, Scripts.load(Scripts.AUTO_CLEAR_BAG));
        Object result = frame.evaluate(
                "() => { try { if (!window.WorldBagClear) return -1; return WorldBagClear.runNow(); } catch (e) { return -1; } }");
        int sold = result instanceof Number number ? number.intValue() : -1;
        if (sold < 0) {
            log("清背包执行失败（游戏接口未就绪）" + config.displayName());
        } else {
            log("清背包完成，本次出售 " + sold + " 件 " + config.displayName());
        }
    }

    // 只读获取当前任务快照。必须在 playwright-worker 内调用；不点击、不接取、不切换游戏状态。
    public synchronized JsonObject readMissionSnapshot() {
        ensureOpen();
        Optional<Frame> gameFrame = locateGameFrame(true);
        if (gameFrame.isEmpty()) {
            throw new IllegalStateException("还没进入游戏，无法读取任务快照: " + config.displayName());
        }
        Frame frame = gameFrame.get();
        // 兼容注入后新增快照模块的情况：这里只重新定义只读 API，不改变游戏状态。
        evalGlobal(frame, Scripts.load(Scripts.MISSION_SNAPSHOT));
        Object raw = frame.evaluate("""
                () => {
                    try {
                        if (!window.WorldMissionSnapshot
                                || typeof window.WorldMissionSnapshot.snapshot !== 'function') {
                            return JSON.stringify({ ok: false, error: '任务快照脚本未就绪' });
                        }
                        return JSON.stringify(window.WorldMissionSnapshot.snapshot());
                    } catch (e) {
                        return JSON.stringify({
                            ok: false,
                            error: e && e.message ? String(e.message) : String(e)
                        });
                    }
                }
                """);
        if (raw == null) {
            throw new IllegalStateException("任务快照返回为空: " + config.displayName());
        }
        return JsonParser.parseString(String.valueOf(raw)).getAsJsonObject();
    }

    public synchronized void enterCity() {
        enterCity(true);
    }

    public synchronized boolean isInCity() {
        if (!isOpen()) {
            return false;
        }
        Optional<Frame> gameFrame = locateGameFrame(true);
        if (gameFrame.isEmpty()) {
            return false;
        }
        Object result = gameFrame.get().evaluate("""
                () => {
                    try {
                        return typeof xworld !== 'undefined' && !!xworld
                                && typeof xworld.isInCityNow === 'function'
                                && !!xworld.isInCityNow();
                    } catch (e) {
                        return false;
                    }
                }
                """);
        return Boolean.TRUE.equals(result);
    }

    public synchronized boolean enterCityIfNeeded(boolean activateWindow) {
        ensureOpen();
        Optional<Frame> gameFrame = locateGameFrame(true);
        if (gameFrame.isEmpty()) {
            throw new IllegalStateException("还没进入游戏，无法进城: " + config.displayName());
        }
        Object result = gameFrame.get().evaluate("""
                () => {
                    try {
                        const alreadyInCity = typeof xworld !== 'undefined' && !!xworld
                                && typeof xworld.isInCityNow === 'function'
                                && !!xworld.isInCityNow();
                        if (alreadyInCity) return true;
                        City.doEnterCity(xself.getId());
                        return false;
                    } catch (e) {
                        return null;
                    }
                }
                """);
        if (result == null) {
            throw new IllegalStateException("进城接口暂未就绪: " + config.displayName());
        }
        boolean alreadyInCity = Boolean.TRUE.equals(result);
        if (activateWindow) {
            bringToFront();
        }
        if (!alreadyInCity) {
            log("已执行进城 " + config.displayName());
        }
        return alreadyInCity;
    }

    public synchronized void enterCity(boolean activateWindow) {
        evaluateGameFrame("City.doEnterCity(xself.getId())", activateWindow);
        log("已执行进城 " + config.displayName());
    }

    public synchronized void drawMicroReward() {
        drawMicroReward(true);
    }

    public synchronized void drawMicroReward(boolean activateWindow) {
        evaluateGameFrame("nato.Network.sendCmd(MsgHandler.createDrawMicroReward())",
                activateWindow);
        log("已领取微端奖励 " + config.displayName());
    }

    public void setSyncEventListener(java.util.function.Consumer<String> listener) {
        this.syncEventListener = listener;
    }

    // 接收主控广播来的手势（归一化坐标 JSON），在本号游戏帧对应位置重放触摸事件。
    // 只能在 playwright-worker 线程调用。
    public synchronized void replaySyncEvent(String payload) {
        if (!isOpen() || payload == null || payload.isBlank()) {
            return;
        }
        Frame target = preferredReplayFrame();
        if (target == null) {
            return;
        }
        try {
            target.evaluate(
                    "(p) => { try { if (window.__worldSyncRecv) window.__worldSyncRecv(p); } catch (e) {} }",
                    payload);
        } catch (Exception ignored) {
        }
    }

    // 同步重放是高频调用（拖动时节流到约 24ms 一次），优先复用轮询已跟踪到的游戏主页帧，
    // 避免每次都遍历全部页面/iframe；定位不到时再全量兜底。
    // 已跟踪游戏主页的主帧：纯本地对象访问，不产生任何跨进程往返。
    private Frame activeMainGameFrame() {
        try {
            if (activePage != null && !activePage.isClosed()) {
                Frame main = activePage.mainFrame();
                if (main != null && isLikelyGameUrl(main.url())) {
                    return main;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Frame preferredReplayFrame() {
        Frame cached = activeMainGameFrame();
        if (cached != null) {
            return cached;
        }
        return locateGameFrame(false).orElse(null);
    }

    // 同步关闭或异常时，松开本号可能残留的按下状态。
    public synchronized void clearSyncGesture() {
        if (!isOpen()) {
            return;
        }
        for (Page candidate : activePages()) {
            try {
                if (candidate.isClosed()) {
                    continue;
                }
                for (Frame frame : candidate.frames()) {
                    try {
                        frame.evaluate(
                                "() => { try { window.__worldSyncClear && window.__worldSyncClear(); } catch (e) {} }");
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized String captureCurrentGameUrl() {
        ensureOpen();
        Optional<Frame> gameFrame = locateGameFrame(false);
        if (gameFrame.isEmpty()) {
            log("存号定位失败，当前各页面地址: " + describePagesForLog());
            throw new IllegalStateException("当前页面里没有找到游戏地址，请先登录并进入游戏");
        }
        String url = gameFrame.get().url();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("游戏地址为空，请稍后重试");
        }
        return url;
    }

    private String describePagesForLog() {
        StringBuilder sb = new StringBuilder();
        try {
            for (Page p : activePages()) {
                try {
                    if (p.isClosed()) {
                        continue;
                    }
                    sb.append("[page:").append(abridgeUrl(p.url()));
                    for (Frame f : p.frames()) {
                        try {
                            sb.append(" | frame:").append(abridgeUrl(f.url()));
                        } catch (Exception ignored) {
                        }
                    }
                    sb.append("] ");
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "无打开的页面" : sb.toString();
    }

    private static String abridgeUrl(String url) {
        if (url == null) {
            return "null";
        }
        return url.length() > 160 ? url.substring(0, 160) + "..." : url;
    }

    @Override
    public synchronized void close() {
        bootstrapped = false; scriptFlags = 0;
        if (context != null) {
            try {
                context.close();
            } catch (Exception ignored) {
            }
        }
        context = null;
        page = null;
        activePage = null;
        adoptedPages.clear();
        cdpSession = null;
        browserWindowId = null;
        bootstrapped = false; scriptFlags = 0;
        popupNotified = false;
        log("已关闭 " + config.displayName());
    }

    private void onContextPage(Page attachedPage) {
        attachedPage.onConsoleMessage(message -> {
            try {
                String type = message.type();
                if ("error".equalsIgnoreCase(type) || "warning".equalsIgnoreCase(type)) {
                    String text = message.text();
                    if (text != null && !text.isBlank()) {
                        log("页面" + ("error".equalsIgnoreCase(type) ? "错误" : "警告")
                                + " " + config.displayName() + ": " + text);
                    }
                }
            } catch (Exception ignored) {
            }
        });
        attachedPage.onPageError(error -> log(
                "页面异常 " + config.displayName() + ": " + error));
        attachedPage.onDialog(dialog -> {
            try {
                log("页面弹窗 " + config.displayName() + ": " + dialog.message());
                dialog.accept();
            } catch (Exception e) {
                log("处理页面弹窗失败: " + e.getMessage());
            }
        });
    }

    // 标签页一出现就接管（不等脚本就绪），保证手机 UA/尺寸/位置尽早生效。
    private void adoptOtherPages() {
        try {
            for (Page candidate : activePages()) {
                if (candidate == page || candidate.isClosed()) {
                    continue;
                }
                if (adoptedPages.add(candidate)) {
                    adoptGamePage(candidate);
                    if (!popupNotified) {
                        popupNotified = true;
                        log("检测到游戏在新窗口运行，已接管 " + config.displayName());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    // 游戏在新标签页里运行时，给新窗口同样的手机 UA、外框尺寸和位置。
    private void adoptGamePage(Page gamePage) {
        if (gamePage == null || gamePage == page) {
            return;
        }
        try {
            CDPSession popupCdp = context.newCDPSession(gamePage);
            JsonObject windowInfo = popupCdp.send("Browser.getWindowForTarget");
            long popupWindowId = windowInfo.get("windowId").getAsLong();
            JsonObject bounds = new JsonObject();
            bounds.addProperty("left", windowX);
            bounds.addProperty("top", windowY);
            bounds.addProperty("width", windowOuterWidth);
            bounds.addProperty("height", windowOuterHeight);
            bounds.addProperty("windowState", "normal");
            JsonObject boundsParams = new JsonObject();
            boundsParams.addProperty("windowId", popupWindowId);
            boundsParams.add("bounds", bounds);
            popupCdp.send("Browser.setWindowBounds", boundsParams);

            if (mobileUserAgent != null) {
                JsonObject uaParams = new JsonObject();
                uaParams.addProperty("userAgent", mobileUserAgent);
                uaParams.add("userAgentMetadata", buildUserAgentMetadata(chromeVersion));
                popupCdp.send("Network.enable", new JsonObject());
                popupCdp.send("Network.setUserAgentOverride", uaParams);
            }
        } catch (Exception e) {
            log("校正游戏新窗口失败 " + config.displayName() + ": " + e.getMessage());
        }
    }

    private void configureMobileWindow() {
        cdpSession = context.newCDPSession(page);
        JsonObject windowInfo = cdpSession.send("Browser.getWindowForTarget");
        browserWindowId = windowInfo.get("windowId").getAsLong();

        String dimensionsJson = (String) page.evaluate("""
                () => JSON.stringify({
                    outerWidth: window.outerWidth,
                    outerHeight: window.outerHeight,
                    innerWidth: window.innerWidth,
                    innerHeight: window.innerHeight
                })
                """);
        JsonObject dimensions = JsonParser.parseString(dimensionsJson).getAsJsonObject();
        int outerWidth = dimensions.get("outerWidth").getAsInt();
        int outerHeight = dimensions.get("outerHeight").getAsInt();
        int innerWidth = dimensions.get("innerWidth").getAsInt();
        int innerHeight = dimensions.get("innerHeight").getAsInt();

        this.chromeWidth = outerWidth >= innerWidth ? outerWidth - innerWidth : 16;
        this.chromeHeight = outerHeight >= innerHeight ? outerHeight - innerHeight : 88;
        windowOuterWidth = mobileWidth + this.chromeWidth;
        windowOuterHeight = mobileHeight + this.chromeHeight;
        windowX = gameBounds.x + Math.max(0, (gameBounds.width - windowOuterWidth) / 2);
        windowY = gameBounds.y + Math.max(0, (gameBounds.height - windowOuterHeight) / 2);

        String currentUserAgent = Objects.toString(page.evaluate("() => navigator.userAgent"), "");
        Matcher matcher = CHROME_VERSION.matcher(currentUserAgent);
        String chromeVersion = matcher.find() ? matcher.group(1) : "120";
        this.chromeVersion = chromeVersion;
        this.mobileUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/" + chromeVersion + ".0.0.0 Mobile Safari/537.36";

        JsonObject uaParams = new JsonObject();
        uaParams.addProperty("userAgent", mobileUserAgent);
        uaParams.add("userAgentMetadata", buildUserAgentMetadata(chromeVersion));
        cdpSession.send("Network.enable", new JsonObject());
        cdpSession.send("Network.setUserAgentOverride", uaParams);

        forceWindowBounds();
    }

    private JsonObject buildUserAgentMetadata(String chromeVersion) {
        JsonArray brands = new JsonArray();
        brands.add(brand("Chromium", chromeVersion));
        brands.add(brand("Google Chrome", chromeVersion));
        brands.add(brand("Not/A)Brand", "99"));

        JsonObject metadata = new JsonObject();
        metadata.add("brands", brands);
        metadata.add("fullVersionList", brands);
        metadata.addProperty("platform", "Android");
        metadata.addProperty("platformVersion", "13.0.0");
        metadata.addProperty("architecture", "");
        metadata.addProperty("model", "Pixel 7");
        metadata.addProperty("mobile", true);
        metadata.addProperty("bitness", "");
        metadata.addProperty("wow64", false);
        return metadata;
    }

    private JsonObject brand(String name, String version) {
        JsonObject item = new JsonObject();
        item.addProperty("brand", name);
        item.addProperty("version", version);
        return item;
    }

    private void forceWindowBounds() {
        if (cdpSession == null || browserWindowId == null) {
            return;
        }
        JsonObject bounds = new JsonObject();
        bounds.addProperty("left", windowX);
        bounds.addProperty("top", windowY);
        bounds.addProperty("width", windowOuterWidth);
        bounds.addProperty("height", windowOuterHeight);
        bounds.addProperty("windowState", "normal");

        JsonObject params = new JsonObject();
        params.addProperty("windowId", browserWindowId);
        params.add("bounds", bounds);
        cdpSession.send("Browser.setWindowBounds", params);
    }

    private void forceWindowsForeground() {
        Path scriptFile = null;
        try {
            scriptFile = Files.createTempFile("world-force-front-", ".ps1");
            try (InputStream input = getClass().getClassLoader()
                    .getResourceAsStream("scripts/force_front.ps1")) {
                if (input == null) {
                    throw new IllegalStateException("缺少强制前置脚本");
                }
                Files.write(scriptFile, input.readAllBytes());
            }

            Process process = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", scriptFile.toString(),
                    "-ProfileDir", profileDir.toString())
                    .redirectErrorStream(true)
                    .start();
            try (InputStream input = process.getInputStream()) {
                input.readAllBytes();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log("Windows 强制前置失败 " + config.displayName() + ": " + e.getMessage());
        } finally {
            if (scriptFile != null) {
                try {
                    Files.deleteIfExists(scriptFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void ensureOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("账号尚未打开: " + config.displayName());
        }
    }

    private boolean hasOfficialCredentials() {
        return config.getChannel() == Channel.GUANFANG
                && officialUsername != null && !officialUsername.isBlank()
                && officialPassword != null && !officialPassword.isBlank();
    }

    private void traceLoginStage(String stage) {
        if (stage == null || stage.isBlank() || stage.equals(lastLoginTraceStage)) {
            return;
        }
        lastLoginTraceStage = stage;
        traceLoginEvent(stage);
    }

    private void traceLoginEvent(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String message = "[登录] " + config.displayName() + " " + text;
        log(message);
        LoginTraceLog.append(config.displayName(), text);
    }

    private static String safeUrlForLog(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) {
                sb.append(uri.getScheme()).append("://");
            }
            if (uri.getHost() != null) {
                sb.append(uri.getHost());
            }
            if (uri.getPath() != null) {
                sb.append(uri.getPath());
            }
            String result = sb.toString();
            return result.isBlank() ? abridgeUrl(url) : result;
        } catch (Exception e) {
            int cut = url.length();
            int q = url.indexOf('?');
            int h = url.indexOf('#');
            if (q >= 0) cut = Math.min(cut, q);
            if (h >= 0) cut = Math.min(cut, h);
            return url.substring(0, Math.min(160, cut));
        }
    }
    // 重启后没有走“导号”时，用存号保存的官服账号密码回填，实现同样的自动登录。
    private void hydrateOfficialCredentials() {
        if (config.getChannel() != Channel.GUANFANG) {
            return;
        }
        boolean sessionEmpty = officialUsername == null || officialUsername.isBlank();
        if (sessionEmpty && config.getUsername() != null && !config.getUsername().isBlank()) {
            officialUsername = config.getUsername().trim();
            officialPassword = config.getPassword() == null ? "" : config.getPassword().trim();
            resetOfficialAutomationState();
        }
    }

    private String officialAccountStorageScript() {
        JsonObject savedAccount = new JsonObject();
        savedAccount.addProperty("account", officialUsername);
        savedAccount.addProperty("passwd", officialPassword);
        JsonArray savedAccounts = new JsonArray();
        savedAccounts.add(savedAccount);

        String accountKey = "world-1000-121-accountList";
        String accountValue = GSON.toJson(GSON.toJson(savedAccounts));
        return "(() => {"
                + "  try {"
                + "    if (location.hostname.indexOf('gamehz.cn') >= 0) {"
                + "      sessionStorage.clear();"
                + "      for (let i = localStorage.length - 1; i >= 0; i--) {"
                + "        const key = localStorage.key(i);"
                + "        if (key && key !== " + GSON.toJson(accountKey) + ") {"
                + "          localStorage.removeItem(key);"
                + "        }"
                + "      }"
                + "    }"
                + "    localStorage.setItem(" + GSON.toJson(accountKey) + ", "
                + GSON.toJson(accountValue) + ");"
                + "  } catch (e) {}"
                + "})();";
    }

    private void navigateOfficialLoginPage() {
        context.clearCookies();
        page.navigate(config.getChannel().loginUrl());
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.evaluate(officialAccountStorageScript());
            page.reload();
        } catch (Exception e) {
            log("\u9884\u7f6e\u5b98\u670d\u8d26\u53f7\u7f13\u5b58\u5931\u8d25 " + config.displayName() + ": " + e.getMessage());
        }
    }



    private static final String LOGIN_PHASE_SCRIPT = """
            (() => {
                try {
                    if (typeof ImgCheckPanel !== 'undefined'
                            && typeof PanelManager !== 'undefined'
                            && PanelManager.isPanelShow
                            && PanelManager.isPanelShow(ImgCheckPanel)) {
                        return 'captcha';
                    }
                    if (typeof Login !== 'undefined' && Login.instance) {
                        const login = Login.instance;
                        const visibleScene = (scene) => !!scene && !!scene.stage
                            && scene.visible !== false && !!scene.parent;
                        if (visibleScene(login.createRoleScene)) return 'create-role';
                        if (visibleScene(login.selectRoleScene)) return 'role';
                    }
                    if (typeof LoginPanel !== 'undefined' && typeof PanelManager !== 'undefined') {
                        const panel = PanelManager.getPanel(LoginPanel);
                        if (panel && panel.stage && panel.parent
                                && panel.visible !== false) {
                            return 'login';
                        }
                    }
                    if (typeof xself !== 'undefined' && !!xself
                            && typeof Control !== 'undefined'
                            && typeof nato !== 'undefined') {
                        return 'game';
                    }
                    return 'loading';
                } catch (e) {
                    return 'loading';
                }
            })()
            """;

    private static final String ROLE_ENTER_SCRIPT = """
            (() => {
                try {
                    if (typeof Login === 'undefined' || !Login.instance) {
                        return JSON.stringify({ stage: 'wait' });
                    }
                    const login = Login.instance;
                    const visibleScene = (scene) => !!scene && !!scene.stage
                        && scene.visible !== false && !!scene.parent;
                    if (visibleScene(login.createRoleScene)) {
                        return JSON.stringify({ stage: 'create-role' });
                    }
                    const scene = login.selectRoleScene;
                    if (!visibleScene(scene)) {
                        return JSON.stringify({ stage: 'wait' });
                    }

                    const players = login.allPlayerList || [];
                    let player = players[0] || null;
                    try {
                        const normal = ModelConst && ModelConst.STATUS_NORMAL;
                        if (normal !== undefined) {
                            player = players.find(p => p
                                && typeof p.getStatus === 'function'
                                && p.getStatus() === normal) || player;
                        }
                    } catch (ignored) {}

                    let entered = false;
                    if (player) {
                        scene.selectedPlayer = player;
                        scene.updatePlayerInfo && scene.updatePlayerInfo();
                        scene.onEnterGame && scene.onEnterGame(null);
                        entered = true;
                    }
                    try {
                        if (typeof AlertPanel !== 'undefined'
                                && AlertPanel.instance && AlertPanel.instance.stage) {
                            AlertPanel.instance.onBtnOkTouch
                                && AlertPanel.instance.onBtnOkTouch();
                        }
                    } catch (ignored) {}
                    return JSON.stringify({
                        stage: 'role',
                        players: players.length,
                        entered: entered
                    });
                } catch (e) {
                    return JSON.stringify({
                        stage: 'role',
                        entered: false,
                        error: e && e.message ? String(e.message) : String(e)
                    });
                }
            })()
            """;

    // 实测天宇直链进游戏：
    // 加载页先点 (0.436,0.870) 的“进入游戏”，选角页出现后点 (0.557,0.929) 进入游戏。
    private static final double LOADING_ENTER_X = 0.436D;
    private static final double LOADING_ENTER_Y = 0.870D;
    private static final double ROLE_ENTER_X = 0.557D;
    private static final double ROLE_ENTER_Y = 0.929D;

    private Optional<Frame> pollLoginAutomationAcrossFrames() {
        Frame bestFrame = null;
        Page bestPage = null;
        String bestPhase = "loading";
        int bestScore = -1;

        for (Page candidate : activePages()) {
            List<Frame> frames;
            try {
                if (candidate.isClosed()) {
                    continue;
                }
                frames = new ArrayList<>(candidate.frames());
            } catch (Exception e) {
                continue;
            }

            for (Frame frame : frames) {
                try {
                    String frameUrl = frame.url();
                    if (!isLikelyGameUrl(frameUrl)) {
                        continue;
                    }
                    String phase = String.valueOf(frame.evaluate(LOGIN_PHASE_SCRIPT));
                    int score = switch (phase) {
                        case "game" -> 6;
                        case "role" -> 5;
                        case "captcha" -> 4;
                        case "create-role" -> 3;
                        case "login" -> 2;
                        default -> 0;
                    };
                    if (score > bestScore) {
                        bestScore = score;
                        bestPhase = phase;
                        bestFrame = frame;
                        bestPage = candidate;
                    }
                } catch (Exception ignored) {
                    // 跨域或正在加载的 Frame 下一轮继续。
                }
            }
        }

        if (bestFrame == null) {
            return Optional.empty();
        }
        trackGamePage(bestPage);
        String resultingPhase = pollLoginAutomation(bestFrame, bestPhase);
        return "game".equals(resultingPhase) ? Optional.of(bestFrame) : Optional.empty();
    }

    private String pollLoginAutomation(Frame frame, String phase) {
        try {
            switch (phase) {
                case "game" -> {
                    if (!roleEnterSubmitted) {
                        traceLoginEvent("游戏核心对象已就绪，准备注入辅助环境");
                    }
                    officialLoginSubmitted = true;
                    roleEnterSubmitted = true;
                    resetRoleFallbackState();
                    return "game";
                }
                case "role" -> {
                    traceLoginStage("选择角色界面");
                    officialLoginSubmitted = true;
                    resetLoadingClickState();
                    pollAutoEnterRole(frame);
                    return "role";
                }
                case "create-role" -> {
                    if (!createRoleNotified) {
                        createRoleNotified = true;
                        traceLoginEvent("账号尚未创建角色，请手动创建后继续");
                    }
                    return "create-role";
                }
                case "captcha" -> {
                    if (!captchaNotified) {
                        captchaNotified = true;
                        traceLoginEvent("账号触发图形验证码，请在窗口中手动处理");
                    }
                    return "captcha";
                }
                case "login" -> {
                    traceLoginStage(hasOfficialCredentials()
                            ? "官服账号密码登录面板"
                            : "渠道账号密码登录面板");
                    pollOfficialLogin(frame);
                    return "login";
                }
                default -> {
                    traceLoginStage("登录资源加载中");
                    dismissLoginAlert(frame);
                    pollTianyuLoadingEnter(frame);
                    return "loading";
                }
            }
        } catch (Exception e) {
            return "loading";
        }
    }

    private void pollOfficialLogin(Frame frame) {
        if (!hasOfficialCredentials()) {
            return;
        }
        try {
            if (!officialLoginSubmitted) {
                String loginScript = """
                        (() => {
                            try {
                                if (typeof LoginPanel === 'undefined'
                                        || typeof PanelManager === 'undefined') {
                                    return 'wait';
                                }
                                const panel = PanelManager.getPanel(LoginPanel);
                                if (!panel || !panel.input_account || !panel.input_password) {
                                    return 'wait';
                                }
                                const shown = (PanelManager.isPanelShow
                                        && PanelManager.isPanelShow(LoginPanel))
                                        || (panel.stage && panel.parent && panel.visible !== false);
                                if (!shown) {
                                    return 'wait';
                                }
                                panel.updateTips && panel.updateTips('');
                                panel.input_account.text = __WORLD_USERNAME__;
                                panel.input_password.text = __WORLD_PASSWORD__;
                                if (typeof GameWorld !== 'undefined') {
                                    GameWorld.username = __WORLD_USERNAME__;
                                    GameWorld.password = __WORLD_PASSWORD__;
                                }
                                panel.login();
                                return 'submitted';
                            } catch (e) {
                                return 'error:' + (e && e.message ? e.message : e);
                            }
                        })()
                        """
                        .replace("__WORLD_USERNAME__", GSON.toJson(officialUsername))
                        .replace("__WORLD_PASSWORD__", GSON.toJson(officialPassword));
                Object result = frame.evaluate(loginScript);
                if ("submitted".equals(String.valueOf(result))) {
                    officialLoginSubmitted = true;
                    traceLoginEvent("官服账号密码已提交（仅记录动作，不记录密码）");
                } else if (String.valueOf(result).startsWith("error:")) {
                    log("自动登录暂未触发 " + config.displayName() + ": " + result);
                }
                return;
            }

            String tipScript = """
                    (() => {
                        try {
                            if (typeof LoginPanel === 'undefined'
                                    || typeof PanelManager === 'undefined') {
                                return 'gone';
                            }
                            const panel = PanelManager.getPanel(LoginPanel);
                            if (!panel || !panel.stage || !panel.parent
                                    || panel.visible === false) {
                                return 'gone';
                            }
                            const tip = panel.tips && panel.tips.text
                                ? String(panel.tips.text) : '';
                            return tip ? 'tip:' + tip : 'login';
                        } catch (e) {
                            return 'gone';
                        }
                    })()
                    """;
            String state = String.valueOf(frame.evaluate(tipScript));
            if (state.startsWith("tip:")) {
                if (!loginTipNotified) {
                    loginTipNotified = true;
                    traceLoginEvent("登录未成功：" + state.substring(4));
                }
            }
        } catch (Exception e) {
            // 登录资源仍在加载，下一秒继续。
        }
    }

    private void dismissLoginAlert(Frame frame) {
        try {
            frame.evaluate("""
                    (() => {
                        try {
                            if (typeof AlertPanel !== 'undefined'
                                    && AlertPanel.instance && AlertPanel.instance.stage) {
                                AlertPanel.instance.onBtnOkTouch
                                    && AlertPanel.instance.onBtnOkTouch();
                            }
                        } catch (e) {}
                    })()
                    """);
        } catch (Exception ignored) {
        }
    }

    private void pollTianyuLoadingEnter(Frame frame) {
        if (config.getChannel() != Channel.TIANYU) {
            return;
        }
        try {
            if (!hasVisibleCanvas(frame)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (loadingSceneFirstSeenAt == 0L) {
                loadingSceneFirstSeenAt = now;
                loadingActionAt = now;
                return;
            }

            long sinceScene = now - loadingSceneFirstSeenAt;
            long sinceClick = now - loadingActionAt;
            boolean shouldClick = sinceScene >= 2_500L && sinceClick >= 1_500L;
            if (!shouldClick) {
                return;
            }

            String currentPhase = String.valueOf(frame.evaluate(LOGIN_PHASE_SCRIPT));
            if (!"loading".equals(currentPhase)) {
                return;
            }
            clickCanvasNormalized(frame, LOADING_ENTER_X, LOADING_ENTER_Y);
            loadingClickCount++;
            loadingActionAt = now;
            traceLoginEvent(loadingClickCount == 1
                    ? "画布点击加载页“进入游戏”按钮"
                    : "仍在加载页，再次点击“进入游戏”按钮（第" + loadingClickCount + "次）");
        } catch (Exception e) {
            traceLoginEvent("加载页进入游戏点击暂未触发：" + e.getMessage());
        }
    }

    private boolean hasVisibleCanvas(Frame frame) {
        try {
            Object result = frame.evaluate("""
                    () => {
                        const canvas = document.querySelector('canvas');
                        if (!canvas || !canvas.getBoundingClientRect) return false;
                        const r = canvas.getBoundingClientRect();
                        return !!(r && r.width > 2 && r.height > 2);
                    }
                    """);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    private void pollAutoEnterRole(Frame frame) {
        dismissLoginAlert(frame);
        try {
            if (!roleObjectEnterRequested) {
                Object result = frame.evaluate(ROLE_ENTER_SCRIPT);
                if (result instanceof String json && !json.isBlank()) {
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    boolean entered = obj.has("entered") && obj.get("entered").getAsBoolean();
                    if (entered) {
                        roleObjectEnterRequested = true;
                        traceLoginEvent("已通过游戏对象请求进入游戏");
                    } else if (obj.has("error") && !obj.get("error").isJsonNull()) {
                        traceLoginEvent("游戏对象进入未触发，准备使用坐标兜底："
                                + obj.get("error").getAsString());
                    }
                }
            }

            long now = System.currentTimeMillis();
            if (roleSceneFirstSeenAt == 0L) {
                roleSceneFirstSeenAt = now;
                roleFallbackActionAt = now;
                return;
            }
            long elapsed = now - roleSceneFirstSeenAt;
            if (roleFallbackStep == 0 && elapsed >= 600L) {
                // 真正点击前再确认一次仍在选角页，避免页面已经切换后误点游戏内界面。
                String currentPhase = String.valueOf(frame.evaluate(LOGIN_PHASE_SCRIPT));
                if (!"role".equals(currentPhase)) {
                    return;
                }
                clickCanvasNormalized(frame, ROLE_ENTER_X, ROLE_ENTER_Y);
                roleFallbackStep = 1;
                roleFallbackActionAt = now;
                traceLoginEvent("画布兜底点击选角页“进入游戏”按钮");
            } else if (roleFallbackStep == 1 && elapsed >= 8_000L) {
                traceLoginEvent("选角界面仍在，重新尝试进入游戏");
                resetRoleFallbackState();
            }
        } catch (Exception e) {
            traceLoginEvent("选角页进入游戏坐标兜底暂未触发：" + e.getMessage());
        }
    }

    private void clickCanvasNormalized(Frame frame, double nx, double ny) {
        ElementHandle canvas = frame.querySelector("canvas");
        if (canvas == null) {
            throw new IllegalStateException("暂未找到游戏画布");
        }
        com.microsoft.playwright.options.BoundingBox box = canvas.boundingBox();
        if (box == null || box.width <= 1 || box.height <= 1) {
            throw new IllegalStateException("游戏画布尺寸无效");
        }
        Page target = frame.page();
        if (target == null || target.isClosed()) {
            throw new IllegalStateException("游戏页面已关闭");
        }
        double safeX = Math.max(0.02D, Math.min(0.98D, nx));
        double safeY = Math.max(0.02D, Math.min(0.98D, ny));
        target.mouse().click(box.x + safeX * box.width, box.y + safeY * box.height);
    }

    private void resetLoadingClickState() {
        loadingSceneFirstSeenAt = 0L;
        loadingActionAt = 0L;
        loadingClickCount = 0;
    }

    private void resetRoleFallbackState() {
        roleSceneFirstSeenAt = 0L;
        roleFallbackActionAt = 0L;
        roleFallbackStep = 0;
        roleObjectEnterRequested = false;
    }

    private List<Page> activePages() {
        List<Page> result = new ArrayList<>();
        try {
            if (context != null) {
                List<Page> contextPages = context.pages();
                if (contextPages != null) {
                    result.addAll(contextPages);
                }
            }
        } catch (Exception ignored) {
        }
        if (page != null && !result.contains(page)) {
            result.add(page);
        }
        result.removeIf(Objects::isNull);
        return result;
    }

    private boolean isLikelyGameUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("about:") || lower.startsWith("chrome:")
                || lower.startsWith("devtools:") || lower.startsWith("edge:")
                || lower.startsWith("data:")) {
            return false;
        }
        return lower.contains(GAME_URL_MARKER)
                || lower.contains("worldh5.gamehz.cn")
                || lower.contains("gamehz.cn/version/world")
                || lower.contains("/version/world/publish/channel/res/index.html")
                || (lower.contains("xameid=") && lower.contains("xhannel="));
    }

    private boolean frameHasGameGlobals(Frame frame) {
        try {
            Object ready = frame.evaluate(
                    "() => typeof xself !== 'undefined' && typeof Control !== 'undefined' "
                            + "&& typeof nato !== 'undefined' && !!xself");
            return Boolean.TRUE.equals(ready);
        } catch (Exception e) {
            return false;
        }
    }

    // requireReady=true 只返回 xself/Control/nato 已就绪的帧（用于注入与脚本操作）；
    // false 时只要 URL 命中游戏地址即可（用于存号）。顶层游戏页优先级高于嵌套 iframe。
    private Optional<Frame> locateGameFrame(boolean requireReady) {
        Frame best = null;
        Page bestPage = null;
        int bestScore = -1;
        for (Page candidate : activePages()) {
            List<Frame> frames;
            try {
                if (candidate.isClosed()) {
                    continue;
                }
                frames = new ArrayList<>(candidate.frames());
            } catch (Exception e) {
                continue;
            }
            Frame mainFrame;
            try {
                mainFrame = candidate.mainFrame();
            } catch (Exception e) {
                mainFrame = null;
            }
            for (Frame frame : frames) {
                String frameUrl;
                try {
                    frameUrl = frame.url();
                } catch (Exception e) {
                    continue;
                }
                if (!isLikelyGameUrl(frameUrl)) {
                    continue;
                }
                boolean globalsReady = frameHasGameGlobals(frame);
                if (requireReady && !globalsReady) {
                    continue;
                }
                int score = (globalsReady ? 2 : 0) + (frame == mainFrame ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = frame;
                    bestPage = candidate;
                }
            }
        }
        if (best != null && bestScore >= 3) {
            trackGamePage(bestPage);
        }
        return Optional.ofNullable(best);
    }

    private void trackGamePage(Page gamePage) {
        if (gamePage != null && gamePage != activePage) {
            activePage = gamePage;
        }
    }

    private Optional<Frame> findGameFrame() {
        return locateGameFrame(false);
    }

    private void evaluateGameFrame(String script, boolean activateWindow) {
        ensureOpen();
        Optional<Frame> frame = locateGameFrame(false);
        if (frame.isEmpty()) {
            throw new IllegalStateException(
                    "还没进入游戏页面，请先登录进入游戏: " + config.displayName());
        }
        evalGlobal(frame.get(), script);
        if (activateWindow) {
            bringToFront();
        }
    }

    private void injectBootstrap(Frame frame) {
        frame.waitForLoadState(LoadState.DOMCONTENTLOADED);

        // 与原 APK WorldActivity 中的注入顺序保持一致。
        evalGlobal(frame, Scripts.load(Scripts.SPEED));
        evalGlobal(frame, Scripts.load(Scripts.LOTTERY_CIRCLE));
        // 清背包库始终注入（仅定义 window.WorldBagClear，不 start 不会出售任何物品），
        // 再按账号配置决定是否启动定时清理。
        evalGlobal(frame, Scripts.load(Scripts.AUTO_CLEAR_BAG));
        if (config.isAutoClearBag()) {
            evalGlobal(frame, "try{window.WorldBagClear&&WorldBagClear.start();}catch(e){}");
        }
        evalGlobal(frame, Scripts.load(Scripts.ONLINE_REWARD_1));
        evalGlobal(frame, Scripts.load(Scripts.ONLINE_REWARD_2));
        evalGlobal(frame, Scripts.load(Scripts.LOGIN_LOTTERY_DRAW));
        evalGlobal(frame, Scripts.load(Scripts.REFRESH_GAME));
        evalGlobal(frame, Scripts.load(Scripts.LOOP_GAME));
        evalGlobal(frame, Scripts.load(Scripts.AUTO_GAME));
        evalGlobal(frame, Scripts.load(Scripts.MISSION_LOG));
        evalGlobal(frame, Scripts.load(Scripts.MISSION_SNAPSHOT));
        evalGlobal(frame, Scripts.load(Scripts.ACTION_LOG));

        frame.evaluate("() => { window.__worldDesktopBooted = true; }");
        bootstrapped = true;
        traceLoginEvent("脚本环境已注入，登录/进游戏流程完成");
    }

    // Playwright 会把字符串当作 JS 表达式求值，像 "var TestAutoGame = ..." 这样的语句
    // 直接 evaluate 会报语法错误；间接 eval 在全局作用域执行，var/function 会挂到 window，
    // 行为与 Android WebView 的 evaluateJavascript 一致。
    private static void evalGlobal(Frame frame, String script) {
        frame.evaluate("(0,eval)(" + GSON.toJson(script) + ")");
    }

    private void installRedirectScripts() {
        // 登录追踪必须最早注入，用于记录渠道跳转、登录/选角阶段和用户手动点击位置。
        context.addInitScript(Scripts.load(Scripts.LOGIN_TRACE));
        // addInitScript 会自动注入当前 BrowserContext 的每个 Frame，包括跨域游戏 Frame。
        // 手机 UA 下游戏只认 touch 事件；这里把桌面鼠标事件桥接成 touchstart/touchmove/touchend。
        context.addInitScript("window.__worldGameScale=" + gameScale + ";");
        context.addInitScript(Scripts.load(Scripts.GAME_SCALE));
        context.addInitScript(Scripts.load(Scripts.TOUCH_BRIDGE));
        // 同步操作：每个帧都具备“采集自己的手势”和“重放别人手势”的能力，
        // 是否真正广播由 SessionManager 的总开关控制（物理鼠标只会落到最前窗口）。
        context.addInitScript(Scripts.load(Scripts.SYNC_REPLAY));
        context.addInitScript(Scripts.load(Scripts.SYNC_CAPTURE));

        if (forceFreshLogin) {
            if (config.getChannel() == Channel.GUANFANG) {
                context.addInitScript(officialAccountStorageScript());
            } else {
                // URL 渠道接力换号时清掉上一个号在本机 Profile 里的站点存储，避免串到上一个角色。
                context.addInitScript("""
                        try {
                            window.sessionStorage && sessionStorage.clear();
                            window.localStorage && localStorage.clear();
                        } catch (e) {}
                        """);
            }
        }

        if (config.getChannel() == Channel.TIANYU) {
            context.addInitScript(Scripts.load(Scripts.TIANYU_IFRAME));
        } else if (config.getChannel() == Channel.XIAOQI) {
            context.addInitScript(Scripts.load(Scripts.XIAOQI_IFRAME));
        }
    }

    private void log(String message) {
        logger.accept(message);
    }
}