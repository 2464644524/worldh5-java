package com.liang.world.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Playwright;

import java.awt.Rectangle;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Properties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Consumer;

public class SessionManager implements AutoCloseable {
    private static final int STATE_CLOSED = 0;
    private static final int STATE_OPEN = 1;
    private static final int STATE_BOOTSTRAPPED = 2;

    private final ConfigStore store;
    private final Path dataDir;
    private Rectangle gameBounds;
    private int emuWidth;
    private int emuHeight;
    private double gameScale;
    private Consumer<String> logger;
    private final AccountSession[] sessions = new AccountSession[ConfigStore.ACCOUNT_COUNT];
    private final AtomicIntegerArray accountStates = new AtomicIntegerArray(ConfigStore.ACCOUNT_COUNT);

    private ScheduledExecutorService browserExecutor;
    private Playwright playwright;

    // 同步操作：开启后，主控号的游戏手势会广播给其它已进入游戏的号同步执行。
    private volatile boolean syncEnabled;
    private volatile int syncMaster = -1;
    // start/end 是边界事件，必须按序送达；move 是高频事件，只保留最新一帧，避免单线程队列积压。
    private final java.util.concurrent.ConcurrentLinkedQueue<String> syncBoundaryQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicReference<String> latestSyncMove =
            new java.util.concurrent.atomic.AtomicReference<>();

    // 单号一键托管：等待进游戏 → 注入 → 进城 → 开自动 → 持续守护 → 无主线任务自动切号。
    private static final int PILOT_IDLE = 0;
    private static final int PILOT_OPENING = 1;
    private static final int PILOT_WAIT_GAME = 2;
    private static final int PILOT_ENTER_CITY = 3;
    private static final int PILOT_START_AUTO = 4;
    private static final int PILOT_RUNNING = 5;
    private static final int PILOT_STOPPING = 6;
    private static final int PILOT_FAILED = 7;

    private static final long PILOT_OPEN_TIMEOUT_MS = 90_000L;
    private static final long PILOT_LOGIN_TIMEOUT_MS = 10 * 60_000L;
    private static final long PILOT_CITY_TIMEOUT_MS = 60_000L;
    private static final long PILOT_AUTO_TIMEOUT_MS = 30_000L;
    private static final long MISSION_SNAPSHOT_INTERVAL_MS = 3 * 60_000L;
    private static final long MISSION_SNAPSHOT_RETRY_MS = 60_000L;
    private static final int NO_MISSION_SWITCH_LIMIT = 20;

    private volatile int autoPilotIndex = -1;
    private volatile String autoPilotStatus = "";
    private volatile boolean autoPilotActive;
    private volatile boolean autoPilotStopRequested;
    private int autoPilotPhase = PILOT_IDLE;
    private long autoPilotStartedAt;
    private long autoPilotPhaseAt;
    private long autoPilotNextActionAt;
    private int autoPilotAttempts;
    private int autoPilotRestartCount;
    private boolean autoPilotCityRequested;
    private long autoPilotLastRestartAt;
    private long nextMissionSnapshotAt;
    private int noMissionStreak;
    private final List<Integer> pilotQueue = new ArrayList<>();
    private final java.util.Set<Integer> pilotHandledExcelRows = new java.util.HashSet<>();
    private int pilotQueuePosition;
    private List<Integer> lastLaunchedSlots = new ArrayList<>();

    public SessionManager(Path dataDir, Rectangle gameBounds, Consumer<String> logger) {
        this.dataDir = dataDir;
        this.store = new ConfigStore(dataDir);
        this.gameBounds = gameBounds;
        this.logger = logger;
        int[] size = loadEmulationSize(dataDir.resolve("ui.properties"));
        this.emuWidth = size[0];
        this.emuHeight = size[1];
        this.gameScale = loadGameScale(dataDir.resolve("ui.properties"));
    }

    public double getGameScale() {
        return gameScale;
    }

    // 调整游戏画面缩放（绕开 Edge 窗口最小宽度限制），实时应用并记住。
    public void setGameScale(double scale) {
        final double k = Math.max(0.5, Math.min(1.0, scale));
        this.gameScale = k;
        saveGameScale(dataDir.resolve("ui.properties"), k);
        if (browserExecutor == null) {
            return;
        }
        browserExecutor.submit(() -> {
            for (AccountSession session : sessions) {
                try {
                    if (session != null) {
                        session.setGameScale(k);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static double loadGameScale(Path file) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (Exception ignored) {
            return 1.0;
        }
        try {
            double k = Double.parseDouble(props.getProperty("gameScale", "1").trim());
            return Math.max(0.5, Math.min(1.0, k));
        } catch (Exception e) {
            return 1.0;
        }
    }

    private void saveGameScale(Path file, double k) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (Exception ignored) {
        }
        props.setProperty("gameScale", String.valueOf(k));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "world-desktop ui");
            }
        } catch (Exception e) {
            log("保存游戏缩放失败: " + e.getMessage());
        }
    }

    public int getEmuWidth() {
        return emuWidth;
    }

    public int getEmuHeight() {
        return emuHeight;
    }

    // 调整 Edge 手机仿真分辨率：实时应用到已打开窗口，并记住供新窗口使用。
    public void setEmulationSize(int width, int height) {
        final int w = Math.max(240, Math.min(1400, width));
        final int h = Math.max(320, Math.min(2000, height));
        this.emuWidth = w;
        this.emuHeight = h;
        saveEmulationSize(dataDir.resolve("ui.properties"), w, h);
        if (browserExecutor == null) {
            return;
        }
        browserExecutor.submit(() -> {
            for (AccountSession session : sessions) {
                try {
                    if (session != null) {
                        session.updateEmulationSize(w, h);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static int[] loadEmulationSize(Path file) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (Exception ignored) {
            return new int[]{510, 760};
        }
        try {
            int w = Integer.parseInt(props.getProperty("emuWidth", "510").trim());
            int h = Integer.parseInt(props.getProperty("emuHeight", "760").trim());
            return new int[]{
                    Math.max(240, Math.min(1400, w)),
                    Math.max(320, Math.min(2000, h))
            };
        } catch (Exception e) {
            return new int[]{510, 760};
        }
    }

    private void saveEmulationSize(Path file, int w, int h) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (Exception ignored) {
        }
        props.setProperty("emuWidth", String.valueOf(w));
        props.setProperty("emuHeight", String.valueOf(h));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "world-desktop ui");
            }
        } catch (Exception e) {
            log("保存仿真尺寸失败: " + e.getMessage());
        }
    }

    public ConfigStore store() {
        return store;
    }

    public synchronized void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    public synchronized void setGameBounds(Rectangle bounds) {
        this.gameBounds = bounds;
        if (browserExecutor == null) {
            return;
        }
        browserExecutor.submit(() -> {
            for (AccountSession session : sessions) {
                try {
                    if (session != null && session.isOpen()) {
                        session.updateGameBounds(bounds);
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    // 同步已开启时，切换主控号（一般跟随辅助栏当前选中/前置的号）。
    public void setSyncMaster(int index) {
        if (!syncEnabled) {
            return;
        }
        int next = Math.max(0, Math.min(index, sessions.length - 1));
        if (next == syncMaster) {
            return;
        }
        submit("清理同步手势失败", () -> {
            for (AccountSession session : sessions) {
                try {
                    if (session != null) {
                        session.clearSyncGesture();
                    }
                } catch (Exception ignored) {
                }
            }
        });
        syncMaster = next;
        log("同步主控已切换为 " + String.format("%02d", next + 1));
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public int getSyncMaster() {
        return syncMaster;
    }

    // 开启/关闭同步。开启时以 masterIndex（当前前置的号）为主控，
    // 主控游戏内的点击/拖动会按归一化坐标在其它已进入游戏的号上原样重放。
    public void setSyncEnabled(boolean enabled, int masterIndex) {
        if (enabled) {
            this.syncMaster = Math.max(0, Math.min(masterIndex, sessions.length - 1));
            syncBoundaryQueue.clear();
            latestSyncMove.set(null);
            this.syncEnabled = true;
            log("同步操作已开启：主控 " + String.format("%02d", this.syncMaster + 1)
                    + "，操作该号会同步到其它已进入游戏的号");
        } else {
            this.syncEnabled = false;
            syncBoundaryQueue.clear();
            latestSyncMove.set(null);
            log("同步操作已关闭");
            submit("清理同步手势失败", () -> {
                for (AccountSession session : sessions) {
                    try {
                        if (session != null) {
                            session.clearSyncGesture();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    // Playwright 回调线程触发：只接受“已开启 + 主控号”的事件。
    // 不在本线程碰 Playwright：start/end 入边界队列，move 只存最新值，交由 worker 定时冲刷。
    private void handleSyncEvent(int sourceIndex, String payload) {
        if (!syncEnabled || sourceIndex != syncMaster || payload == null || payload.isBlank()) {
            return;
        }
        String type = syncEventType(payload);
        if ("move".equals(type)) {
            latestSyncMove.set(payload);
        } else if ("start".equals(type) || "end".equals(type)) {
            // 新的按下/抬起到来后，尚未冲刷的旧 move 没有意义，直接丢弃，保证顺序不错乱。
            latestSyncMove.set(null);
            syncBoundaryQueue.add(payload);
        }
    }

    private static String syncEventType(String payload) {
        try {
            com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            return obj.has("t") ? obj.get("t").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // 固定在 playwright-worker 单线程执行：按序重放边界事件，最多再补一帧最新移动。
    // 坐标是绝对归一化值，丢帧只会让被控号直接跳到最新位置，不会错位。
    private void flushSyncEvents() {
        if (!syncEnabled) {
            return;
        }
        final int master = syncMaster;
        java.util.List<String> boundaries = new java.util.ArrayList<>();
        String boundary;
        while ((boundary = syncBoundaryQueue.poll()) != null) {
            boundaries.add(boundary);
            if (boundaries.size() >= 8) {
                break;
            }
        }
        String move = boundaries.isEmpty() ? latestSyncMove.getAndSet(null) : null;
        if (boundaries.isEmpty() && move == null) {
            return;
        }
        for (int i = 0; i < sessions.length; i++) {
            if (i == master) {
                continue;
            }
            AccountSession session = sessions[i];
            if (session == null || !session.isOpen() || !session.isBootstrapped()) {
                continue;
            }
            for (String payload : boundaries) {
                try {
                    session.replaySyncEvent(payload);
                } catch (Exception ignored) {
                }
            }
            if (move != null) {
                try {
                    session.replaySyncEvent(move);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void closeAccount(int index) {
        submit("关闭账号失败", () -> {
            sessions[index].close();
            refreshState(index);
        });
    }

    public synchronized void start() {
        if (playwright != null) {
            return;
        }

        browserExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "playwright-worker");
            thread.setDaemon(true);
            return thread;
        });

        for (int i = 0; i < ConfigStore.ACCOUNT_COUNT; i++) {
            sessions[i] = new AccountSession(
                    store.account(i), store.profileDir(i), gameBounds, logger);
            // 关闭状态下只记录尺寸，新窗口打开时按该尺寸创建 viewport。
            sessions[i].updateEmulationSize(emuWidth, emuHeight);
            sessions[i].setGameScale(gameScale);
            final int sessionIndex = i;
            sessions[i].setSyncEventListener(payload -> handleSyncEvent(sessionIndex, payload));
        }

        try {
            browserExecutor.submit(() -> {
                // 固定使用系统 Microsoft Edge（setChannel("msedge")），
                // 并禁止 Playwright 下载 Chrome for Testing。
                Map<String, String> env = new HashMap<>(System.getenv());
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                env.put("PLAYWRIGHT_SKIP_BROWSER_GC", "1");
                playwright = Playwright.create(
                        new Playwright.CreateOptions().setEnv(env));
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Playwright 初始化被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Playwright 初始化失败", e.getCause());
        }

        browserExecutor.scheduleWithFixedDelay(this::pollAllSafely, 1, 1, TimeUnit.SECONDS);
        // 同步手势冲刷：固定节奏把“边界事件 + 最新一帧移动”广播出去，多余 move 直接合并丢弃。
        browserExecutor.scheduleWithFixedDelay(this::flushSyncEvents, 1, 20, TimeUnit.MILLISECONDS);
        log("控制台已启动");
    }

    public void openAccount(int index) {
        submit("打开账号失败", () -> {
            sessions[index].open(playwright);
            refreshState(index);
        });
    }

    public void openAll(int frontIndex) {
        submit("批量打开失败", () -> {
            for (int i = 0; i < sessions.length; i++) {
                try {
                    sessions[i].open(playwright);
                } catch (Exception e) {
                    log("打开账号 " + (i + 1) + " 失败: " + e.getMessage());
                } finally {
                    refreshState(i);
                }
            }
            int safeIndex = Math.max(0, Math.min(frontIndex, sessions.length - 1));
            if (sessions[safeIndex] != null && sessions[safeIndex].isOpen()) {
                sessions[safeIndex].bringToFront();
            }
        });
    }

    public void launchExcelAccounts(List<ExcelAccount> excelAccounts) {
        if (excelAccounts == null || excelAccounts.isEmpty()) {
            log("没有可导入的 Excel 账号");
            return;
        }

        submit("Excel 导号失败", () -> {
            int count = Math.min(excelAccounts.size(), sessions.length);
            log("从 Excel 导入并打开 " + count + " 个账号");

            for (AccountSession session : sessions) {
                if (session != null) {
                    session.clearOfficialLogin();
                }
            }

            List<Integer> launchedSlots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                launchedSlots.add(i);
                ExcelAccount excelAccount = excelAccounts.get(i);
                AccountConfig config = store.account(i);
                Channel channel = excelAccount.getChannel();

                config.setTitle(excelAccount.getDisplayName());
                config.setChannel(channel);

                config.setExcelRow(excelAccount.getRowNumber());
                config.setFinishDate(excelAccount.getFinishDate());
                if (channel == Channel.GUANFANG) {
                    config.setCustomUrl("");
                    config.setUsername(excelAccount.getUsername());
                    config.setPassword(excelAccount.getPassword());
                    sessions[i].prepareOfficialLogin(
                            excelAccount.getUsername(), excelAccount.getPassword());
                } else {
                    config.setCustomUrl(excelAccount.getUrl());
                    config.setUsername("");
                    config.setPassword("");
                    sessions[i].clearOfficialLogin();
                }

                try {
                    sessions[i].close();
                    sessions[i].open(playwright);
                    log("正在打开槽位 "
                            + String.format("%02d", i + 1) + " "
                            + channel.displayName() + " " + excelAccount.getDisplayName());
                } catch (Exception e) {
                    log("打开槽位 "
                            + String.format("%02d", i + 1) + " "
                            + excelAccount.getDisplayName() + " 失败: " + e.getMessage());
                } finally {
                    refreshState(i);
                }
            }

            for (int i = count; i < sessions.length; i++) {
                AccountConfig unusedConfig = store.account(i);
                unusedConfig.setTitle(String.valueOf(i + 1));
                unusedConfig.setCustomUrl("");
                unusedConfig.setExcelRow(0);
                unusedConfig.setUsername("");
                unusedConfig.setPassword("");
                unusedConfig.setFinishDate("");
                try {
                    sessions[i].close();
                    log("已关闭未使用槽位 " + String.format("%02d", i + 1));
                } catch (Exception e) {
                    log("关闭未使用槽位 "
                            + String.format("%02d", i + 1) + " 失败: " + e.getMessage());
                } finally {
                    refreshState(i);
                }
            }

            lastLaunchedSlots = launchedSlots;
            store.save();
            PilotLog.append("", "导号建立自动上号队列（" + launchedSlots.size() + "个）：" + slotNames(launchedSlots));
            if (launchedSlots.size() <= 1) {
                log("本次打开 1 个账号；当前号完成后会重新读取 Excel，自动接力今日未完成账号");
                PilotLog.append("", "本次打开1个账号；托管完成后将重读Excel接力未完成账号");
            }
            if (excelAccounts.size() > ConfigStore.ACCOUNT_COUNT) {
                log("Excel 中有 " + excelAccounts.size()
                        + " 个账号，最多同时打开 10 个，多余的已忽略");
            }
            bringFrontIfOpen(0);
        });
    }

    public void login(int index) {
        submit("进入登录页失败", () -> {
            sessions[index].login();
            refreshState(index);
        });
    }

    public void reload(int index) {
        submit("刷新失败", () -> {
            sessions[index].reload();
            refreshState(index);
        });
    }

    public void bringToFront(int index) {
        submit("前置窗口失败", () -> sessions[index].bringToFront());
    }

    public void bringAllToFront(int frontIndex) {
        submit("批量前置失败", () -> {
            for (AccountSession session : sessions) {
                if (session != null && session.isOpen()) {
                    session.bringToFront();
                }
            }
            bringFrontIfOpen(frontIndex);
        });
    }

    public void startAuto(int index) {
        submit("开启自动失败", () -> sessions[index].startAuto());
    }

    public void startRefresh(int index) {
        submit("开启刷怪失败", () -> sessions[index].startRefresh());
    }

    public void startLoop(int index) {
        submit("开启跟随失败", () -> sessions[index].startLoop());
    }

    public void stopScripts(int index) {
        submit("停止脚本失败", () -> sessions[index].stopScripts());
    }

    // 某号的自动/刷怪/跟随是否真实运行（AccountSession.FLAG_* 位）。
    public boolean isScriptOn(int index, int flagBit) {
        AccountSession session = sessions[index];
        return session != null && (session.scriptFlags() & flagBit) != 0;
    }

    public void toggleAuto(int index) {
        submit("切换自动失败", () -> {
            if ((sessions[index].scriptFlags() & AccountSession.FLAG_AUTO) != 0) {
                sessions[index].stopAuto();
            } else {
                sessions[index].startAuto();
            }
        });
    }

    public void toggleRefresh(int index) {
        submit("切换刷怪失败", () -> {
            if ((sessions[index].scriptFlags() & AccountSession.FLAG_REFRESH) != 0) {
                sessions[index].stopRefresh();
            } else {
                sessions[index].startRefresh();
            }
        });
    }

    public void toggleLoop(int index) {
        submit("切换跟随失败", () -> {
            if ((sessions[index].scriptFlags() & AccountSession.FLAG_LOOP) != 0) {
                sessions[index].stopLoop();
            } else {
                sessions[index].startLoop();
            }
        });
    }

    public void startAutoAll(int frontIndex) {
        runOnBootstrapped("批量自动", session -> session.startAuto(false), frontIndex);
    }

    public void startRefreshAll(int frontIndex) {
        runOnBootstrapped("批量刷怪", session -> session.startRefresh(false), frontIndex);
    }

    public void startLoopAll(int frontIndex) {
        runOnBootstrapped("批量跟随", session -> session.startLoop(false), frontIndex);
    }

    public void stopAllScripts(int frontIndex) {
        runOnBootstrapped("批量停止脚本", session -> session.stopScripts(false), frontIndex);
    }

    public void enterCityAll(int frontIndex) {
        runOnBootstrapped("批量进城", session -> session.enterCity(false), frontIndex);
    }

    public void drawMicroRewardAll(int frontIndex) {
        runOnBootstrapped("批量领取微端奖励",
                session -> session.drawMicroReward(false), frontIndex);
    }

    public void enterCity(int index) {
        submit("进城失败", () -> sessions[index].enterCity());
    }

    public void drawMicroReward(int index) {
        submit("领取微端奖励失败", () -> sessions[index].drawMicroReward());
    }

    public boolean isAutoPilotActive() {
        return autoPilotActive;
    }

    public int getAutoPilotIndex() {
        return autoPilotIndex;
    }

    public String getAutoPilotStatus() {
        return autoPilotStatus == null ? "" : autoPilotStatus;
    }

    public int getAutoPilotQueuePosition() {
        return pilotQueue.isEmpty() ? 0 : pilotQueuePosition + 1;
    }

    public int getAutoPilotQueueSize() {
        return pilotQueue.size();
    }

    public void startAutoPilotAll() {
        submit("启动批量托管失败", () -> {
            if (autoPilotActive) {
                log("一键托管正在运行：" + pilotDisplayName());
                return;
            }

            int first = findNextUnfinishedPilotSlot();
            if (first < 0) {
                // 当前槽位可能已经跑完并写入了今天日期；此时不要直接结束，重新读取 Excel 接力下一个未完成账号。
                try {
                    ExcelAccount next = findNextUnfinishedExcelAccount();
                    if (next == null) {
                        String text = "今天可托管账号都已跑完（Excel 完成日期为 " + LocalDate.now() + "）";
                        log("[托管] " + text);
                        PilotLog.append("", text);
                        setAutoPilotStatus("今日账号已全部跑完");
                        return;
                    }
                    first = 0;
                    try {
                        if (sessions[first] != null && sessions[first].isOpen()) {
                            sessions[first].close();
                        }
                        refreshState(first);
                    } catch (Exception ignored) {
                    }
                    applyExcelAccountToPilotSlot(first, next);
                    PilotLog.append("", "全托启动时从 Excel 载入未完成账号：Excel第 " + next.getRowNumber() + " 行");
                    log("[托管] 全托启动时从 Excel 载入未完成账号：Excel第 " + next.getRowNumber() + " 行");
                } catch (Exception e) {
                    failAutoPilot("重新读取 Excel 寻找未完成账号失败: " + e.getMessage());
                    PilotLog.append("", "全托启动时读取 Excel 失败: " + e.getMessage());
                    return;
                }
            }
            beginAutoPilot(first);
        });
    }

    public void startAutoPilot(int index) {
        submit("启动一键托管失败", () -> beginAutoPilot(index));
    }

    private void beginAutoPilot(int index) {
        submit("启动一键托管失败", () -> {
            if (autoPilotActive) {
                log("一键托管正在运行：" + pilotDisplayName());
                return;
            }
            int safeIndex = Math.max(0, Math.min(index, sessions.length - 1));
            AccountSession session = sessions[safeIndex];
            if (session == null) {
                log("一键托管启动失败：账号槽位未初始化");
                return;
            }

            long now = System.currentTimeMillis();
            pilotQueue.clear();
            pilotHandledExcelRows.clear();
            pilotQueue.addAll(buildPilotQueue(safeIndex));
            pilotQueuePosition = 0;
            if (pilotQueue.isEmpty()) {
                autoPilotActive = false;
                autoPilotStopRequested = false;
                autoPilotPhase = PILOT_IDLE;
                String text = "今天可托管账号都已跑完（完成日期为 " + LocalDate.now() + "）";
                setAutoPilotStatus("今日账号已全部跑完");
                log("[托管] " + text);
                PilotLog.append(store.account(safeIndex).displayName(), text);
                return;
            }
            if (!pilotQueue.contains(safeIndex)) {
                safeIndex = pilotQueue.get(0);
            }
            session = sessions[safeIndex];
            if (session == null) {
                autoPilotActive = false;
                autoPilotStopRequested = false;
                autoPilotPhase = PILOT_IDLE;
                log("一键托管启动失败：账号槽位未初始化");
                return;
            }

            autoPilotIndex = safeIndex;
            autoPilotStopRequested = false;
            autoPilotActive = true;
            autoPilotStartedAt = now;
            autoPilotPhaseAt = now;
            autoPilotNextActionAt = now;
            autoPilotAttempts = 0;
            autoPilotRestartCount = 0;
            autoPilotCityRequested = false;
            nextMissionSnapshotAt = 0L;
            noMissionStreak = 0;
            autoPilotPhase = PILOT_OPENING;
            setAutoPilotStatus("正在打开浏览器");
            log("[托管] 开始处理 " + store.account(safeIndex).displayName());
            log("[托管] 自动切号队列（" + pilotQueue.size() + "个）：" + pilotQueueText());
            PilotLog.append("", "建立托管队列（" + pilotQueue.size() + "个）：" + pilotQueueText());
            if (pilotQueue.size() <= 1) {
                log("[托管] 当前先处理 1 个账号；该号保底完成后会重新读取 Excel，自动接力今日未完成账号");
                PilotLog.append(store.account(safeIndex).displayName(), "当前先处理1个账号；完成后将重读Excel接力未完成账号");
            } else {
                setAutoPilotStatus("等待处理队列 1/" + pilotQueue.size());
            }

            try {
                session.open(playwright);
                refreshState(safeIndex);
                autoPilotPhase = PILOT_WAIT_GAME;
                autoPilotPhaseAt = System.currentTimeMillis();
                autoPilotNextActionAt = autoPilotPhaseAt;
                setAutoPilotStatus("等待登录、选角并进入游戏");
            } catch (Exception e) {
                failAutoPilot("打开浏览器失败: " + e.getMessage());
            }
        });
    }

    public void stopAutoPilot() {
        submit("停止一键托管失败", () -> {
            if (!autoPilotActive || autoPilotIndex < 0) {
                autoPilotActive = false;
                autoPilotStopRequested = false;
                autoPilotPhase = PILOT_IDLE;
                setAutoPilotStatus("");
                return;
            }
            int index = autoPilotIndex;
            autoPilotStopRequested = true;
            autoPilotPhase = PILOT_STOPPING;
            setAutoPilotStatus("正在停止脚本");
            AccountSession session = sessions[index];
            if (session != null) {
                try {
                    session.stopScriptsIfInGame(false);
                } catch (Exception ignored) {
                }
            }
            autoPilotActive = false;
            autoPilotStopRequested = false;
            autoPilotPhase = PILOT_IDLE;
            setAutoPilotStatus("已停止（窗口保留）");
            log("[托管] 已停止 " + store.account(index).displayName() + "，窗口保留");
        });
    }

    private void pollAutoPilot() {
        if (!autoPilotActive || autoPilotStopRequested) {
            return;
        }
        int index = autoPilotIndex;
        if (index < 0 || index >= sessions.length || sessions[index] == null) {
            failAutoPilot("托管账号槽位无效");
            return;
        }

        AccountSession session = sessions[index];
        long now = System.currentTimeMillis();
        try {
            switch (autoPilotPhase) {
                case PILOT_OPENING -> setAutoPilotStatus("正在打开浏览器");

                case PILOT_WAIT_GAME -> {
                    if (!session.isOpen()) {
                        failAutoPilot("浏览器窗口已关闭");
                        return;
                    }
                    if (session.isBootstrapped()) {
                        log("[托管] 游戏环境已就绪 " + pilotDisplayName());
                        autoPilotPhase = PILOT_ENTER_CITY;
                        autoPilotPhaseAt = now;
                        autoPilotNextActionAt = now;
                        autoPilotAttempts = 0;
                        autoPilotCityRequested = false;
                        setAutoPilotStatus("准备进城");
                    } else if (now - autoPilotStartedAt > PILOT_LOGIN_TIMEOUT_MS) {
                        failAutoPilot("等待登录/选角/进入游戏超时，请检查账号密码、验证码或网络");
                    } else {
                        setAutoPilotStatus("等待登录/选角/进入游戏（"
                                + elapsedText(now - autoPilotStartedAt) + "）");
                    }
                }

                case PILOT_ENTER_CITY -> {
                    if (!session.isOpen()) {
                        failAutoPilot("浏览器窗口已关闭");
                        return;
                    }
                    if (!session.isBootstrapped()) {
                        if (now - autoPilotPhaseAt > 60_000L) {
                            failAutoPilot("游戏脚本注入失败或页面跳转异常");
                        } else {
                            setAutoPilotStatus("等待脚本注入完成");
                        }
                        return;
                    }

                    boolean inCity = false;
                    try {
                        inCity = session.isInCity();
                    } catch (Exception ignored) {
                    }
                    if (inCity) {
                        log("[托管] 已在城内 " + pilotDisplayName());
                        autoPilotPhase = PILOT_START_AUTO;
                        autoPilotPhaseAt = now;
                        autoPilotNextActionAt = now;
                        autoPilotAttempts = 0;
                        setAutoPilotStatus("准备开启自动任务");
                        return;
                    }

                    if (!autoPilotCityRequested) {
                        try {
                            session.enterCity(false);
                            autoPilotCityRequested = true;
                            autoPilotNextActionAt = now + 3_000L;
                            setAutoPilotStatus("已请求进城，等待加载");
                        } catch (Exception e) {
                            autoPilotAttempts++;
                            autoPilotNextActionAt = now + 3_000L;
                            if (now - autoPilotPhaseAt > PILOT_CITY_TIMEOUT_MS) {
                                failAutoPilot("进城失败: " + e.getMessage());
                            } else {
                                setAutoPilotStatus("进城接口未就绪，重试中（" + autoPilotAttempts + "）");
                            }
                        }
                        return;
                    }

                    if (now - autoPilotPhaseAt > PILOT_CITY_TIMEOUT_MS) {
                        failAutoPilot("进城超时，请确认角色状态或网络");
                    } else {
                        setAutoPilotStatus("等待进城加载（"
                                + elapsedText(now - autoPilotPhaseAt) + "）");
                    }
                }

                case PILOT_START_AUTO, PILOT_RUNNING -> {
                    if (!session.isOpen()) {
                        failAutoPilot("浏览器窗口已关闭");
                        return;
                    }
                    if (!session.isBootstrapped()) {
                        if (now - autoPilotPhaseAt > 60_000L) {
                            failAutoPilot("游戏脚本注入丢失，自动任务无法继续");
                        } else {
                            setAutoPilotStatus("等待页面重新注入脚本");
                        }
                        return;
                    }

                    boolean autoOn = (session.scriptFlags() & AccountSession.FLAG_AUTO) != 0;
                    if (autoPilotPhase == PILOT_START_AUTO) {
                        if (now >= autoPilotNextActionAt && !autoOn) {
                            try {
                                session.startAuto(false);
                                autoPilotAttempts++;
                                log("[托管] 已发送自动任务启动指令 " + pilotDisplayName());
                            } catch (Exception e) {
                                if (now - autoPilotPhaseAt > PILOT_AUTO_TIMEOUT_MS) {
                                    failAutoPilot("开启自动任务失败: " + e.getMessage());
                                    return;
                                }
                            }
                            autoPilotNextActionAt = now + 3_000L;
                        }

                        if (autoOn) {
                            autoPilotPhase = PILOT_RUNNING;
                            autoPilotPhaseAt = now;
                            autoPilotNextActionAt = now + 10_000L;
                            autoPilotRestartCount = 0;
                            // 严格按 3 分钟检查一次；连续 20 次无主线可接/可交，即约 60 分钟保底切号。
                            nextMissionSnapshotAt = now + MISSION_SNAPSHOT_INTERVAL_MS;
                            log("[托管] 自动任务已开启，进入持续守护 " + pilotDisplayName());
                        } else if (now - autoPilotPhaseAt > PILOT_AUTO_TIMEOUT_MS) {
                            failAutoPilot("自动任务开启后未检测到运行状态");
                        } else {
                            setAutoPilotStatus("确认自动任务状态（"
                                    + elapsedText(now - autoPilotPhaseAt) + "）");
                        }
                        return;
                    }

                    if (autoOn) {
                        autoPilotRestartCount = 0;
                        autoPilotNextActionAt = now + 10_000L;
                        boolean switched = pollMissionSnapshot(session, now);
                        if (switched) {
                            return;
                        }
                        String noTaskText = noMissionStreak > 0
                                ? "，无主线 " + noMissionStreak + "/" + NO_MISSION_SWITCH_LIMIT
                                : "";
                        setAutoPilotStatus("自动任务运行中（已托管 "
                                + elapsedText(now - autoPilotStartedAt) + noTaskText + "）");
                    } else if (now < autoPilotNextActionAt) {
                        setAutoPilotStatus("自动任务状态确认中");
                    } else if (autoPilotRestartCount >= 3) {
                        failAutoPilot("自动任务连续停止，守护失败");
                    } else {
                        autoPilotRestartCount++;
                        log("[托管] 检测到自动任务停止，第 "
                                + autoPilotRestartCount + " 次重新开启 " + pilotDisplayName());
                        session.startAuto(false);
                        autoPilotNextActionAt = now + 10_000L;
                        setAutoPilotStatus("自动任务断开，正在重启（"
                                + autoPilotRestartCount + "/3）");
                    }
                }

                case PILOT_STOPPING -> setAutoPilotStatus("正在停止脚本");
                case PILOT_FAILED -> { }
                default -> {
                    autoPilotActive = false;
                    autoPilotPhase = PILOT_IDLE;
                }
            }
        } catch (Exception e) {
            failAutoPilot("托管异常: " + e.getMessage());
        }
    }
    private boolean pollMissionSnapshot(AccountSession session, long now) {
        if (nextMissionSnapshotAt == 0L || now < nextMissionSnapshotAt) {
            return false;
        }
        String name = pilotDisplayName();
        try {
            JsonObject snapshot = session.readMissionSnapshot();
            if (!snapshot.has("ok") || !snapshot.get("ok").getAsBoolean()) {
                String error = snapshot.has("error") && !snapshot.get("error").isJsonNull()
                        ? snapshot.get("error").getAsString() : "未知错误";
                nextMissionSnapshotAt = now + MISSION_SNAPSHOT_RETRY_MS;
                String text = "[任务快照] 读取失败，60秒后重试：" + error;
                log(text + " " + name);
                MissionSnapshotLog.append(name, "读取失败：" + error);
                return false;
            }

            int rawAcceptCount = optionalInt(snapshot, "canAcceptCount");
            int rawSubmitCount = optionalInt(snapshot, "canSubmitCount");
            int autoAcceptCount = optionalInt(snapshot, "autoCanAcceptCount");
            int autoSubmitCount = optionalInt(snapshot, "autoCanSubmitCount");
            int ignoredCount = optionalInt(snapshot, "ignoredNonAutoCount");
            JsonArray autoAccept = snapshot.has("autoCanAccept") && snapshot.get("autoCanAccept").isJsonArray()
                    ? snapshot.getAsJsonArray("autoCanAccept") : new JsonArray();
            JsonArray autoSubmit = snapshot.has("autoCanSubmit") && snapshot.get("autoCanSubmit").isJsonArray()
                    ? snapshot.getAsJsonArray("autoCanSubmit") : new JsonArray();
            JsonArray ignored = snapshot.has("ignoredNonAuto") && snapshot.get("ignoredNonAuto").isJsonArray()
                    ? snapshot.getAsJsonArray("ignoredNonAuto") : new JsonArray();
            String acceptNames = missionNames(autoAccept);
            String submitNames = missionNames(autoSubmit);
            String ignoredNames = missionNames(ignored);
            String scanned = snapshot.has("scanned") && !snapshot.get("scanned").isJsonNull()
                    ? snapshot.get("scanned").getAsString() : "0";

            StringBuilder detail = new StringBuilder();
            detail.append("主线可接=").append(autoAcceptCount)
                    .append(" 主线可交=").append(autoSubmitCount);
            if (ignoredCount > 0) {
                detail.append("｜忽略城市/支线=").append(ignoredCount);
            }
            if (!acceptNames.isEmpty()) detail.append("｜主线可接：").append(acceptNames);
            if (!submitNames.isEmpty()) detail.append("｜主线可交：").append(submitNames);
            if (!ignoredNames.isEmpty()) detail.append("｜忽略：").append(ignoredNames);
            detail.append("｜原始可接=").append(rawAcceptCount)
                    .append(" 原始可交=").append(rawSubmitCount)
                    .append(" 扫描对象=").append(scanned);

            String text = "[任务快照] " + detail;
            log(text + " " + name);
            MissionSnapshotLog.append(name, detail.toString());

            if (autoAcceptCount > 0 || autoSubmitCount > 0) {
                if (noMissionStreak > 0) {
                    log("[托管] 检测到主线任务，无任务计数已清零 " + name);
                }
                noMissionStreak = 0;
                nextMissionSnapshotAt = now + MISSION_SNAPSHOT_INTERVAL_MS;
                return false;
            }

            noMissionStreak++;
            log("[托管] 无主线任务计数 " + noMissionStreak + "/" + NO_MISSION_SWITCH_LIMIT
                    + "（连续约60分钟无主线可接/可交后切号） " + name);
            MissionSnapshotLog.append(name, "无主线任务计数 " + noMissionStreak
                    + "/" + NO_MISSION_SWITCH_LIMIT);
            PilotLog.append(name, "无主线任务计数 " + noMissionStreak + "/" + NO_MISSION_SWITCH_LIMIT);
            nextMissionSnapshotAt = now + MISSION_SNAPSHOT_INTERVAL_MS;

            if (noMissionStreak >= NO_MISSION_SWITCH_LIMIT) {
                log("[托管] 连续 " + NO_MISSION_SWITCH_LIMIT
                        + " 次无主线可接/可交任务，开始切换下一个账号 " + name);
                PilotLog.append(name, "连续" + NO_MISSION_SWITCH_LIMIT
                        + "次无主线可接/可交任务，准备切换下一个账号");
                return switchToNextPilotAccount(now);
            }
            return false;
        } catch (Exception e) {
            // 读取异常不计数，避免网络/页面瞬时异常导致误切号；60 秒后重试。
            nextMissionSnapshotAt = now + MISSION_SNAPSHOT_RETRY_MS;
            String text = "[任务快照] 读取异常，60秒后重试：" + e.getMessage();
            log(text + " " + name);
            MissionSnapshotLog.append(name, "读取异常：" + e.getMessage());
            return false;
        }
    }

    private List<Integer> buildPilotQueue(int selected) {
        List<Integer> base = new ArrayList<>();
        if (!lastLaunchedSlots.isEmpty()) {
            for (Integer slot : lastLaunchedSlots) {
                if (slot != null && slot >= 0 && slot < sessions.length
                        && hasPilotLoginConfig(slot) && !isPilotFinishedToday(slot)
                        && !base.contains(slot)) {
                    base.add(slot);
                }
            }
        }

        if (base.isEmpty()) {
            for (int i = 0; i < sessions.length; i++) {
                if (hasPilotLoginConfig(i) && !isPilotFinishedToday(i)) {
                    base.add(i);
                }
            }
        }

        int start = base.indexOf(selected);
        if (start < 0) {
            start = 0;
        }
        List<Integer> queue = new ArrayList<>();
        if (base.isEmpty()) {
            return queue;
        }
        queue.addAll(base.subList(start, base.size()));
        queue.addAll(base.subList(0, start));
        return queue;
    }

    private int findNextUnfinishedPilotSlot() {
        if (!lastLaunchedSlots.isEmpty()) {
            for (Integer slot : lastLaunchedSlots) {
                if (slot != null && slot >= 0 && slot < sessions.length
                        && hasPilotLoginConfig(slot) && !isPilotFinishedToday(slot)) {
                    return slot;
                }
            }
        }
        for (int i = 0; i < sessions.length; i++) {
            if (hasPilotLoginConfig(i) && !isPilotFinishedToday(i)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isPilotFinishedToday(int index) {
        try {
            return ExcelAccount.isDateToday(store.account(index).getFinishDate());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasPilotLoginConfig(int index) {
        AccountConfig config = store.account(index);
        if (config.getCustomUrl() != null && !config.getCustomUrl().isBlank()) {
            return true;
        }
        return config.getChannel() == Channel.GUANFANG
                && config.getUsername() != null && !config.getUsername().isBlank()
                && config.getPassword() != null && !config.getPassword().isBlank();
    }

    private String pilotQueueText() {
        return slotNames(pilotQueue);
    }

    private String slotNames(List<Integer> slots) {
        List<String> names = new ArrayList<>();
        for (Integer slot : slots) {
            if (slot != null && slot >= 0 && slot < sessions.length) {
                names.add(store.account(slot).displayName());
            }
        }
        return String.join(" → ", names);
    }

    private boolean switchToNextPilotAccount(long now) {
        int oldIndex = autoPilotIndex;
        int nextPosition = pilotQueuePosition + 1;
        AccountSession oldSession = oldIndex >= 0 && oldIndex < sessions.length
                ? sessions[oldIndex] : null;
        AccountConfig oldConfig = oldIndex >= 0 && oldIndex < sessions.length
                ? store.account(oldIndex) : null;
        String oldName = oldConfig != null ? oldConfig.displayName() : "未知账号";
        int oldExcelRow = oldConfig != null ? oldConfig.getExcelRow() : 0;

        markPilotAccountFinished(oldIndex);
        if (oldExcelRow > 0) {
            pilotHandledExcelRows.add(oldExcelRow);
        }

        // 启动时已经导入并打开过的队列优先处理，保持原有 1→2→3... 槽位顺序。
        if (nextPosition < pilotQueue.size()) {
            if (oldSession != null) {
                PilotLog.append(oldName, "保底无任务，停止脚本并关闭当前账号");
                try {
                    oldSession.stopScriptsIfInGame(false);
                } catch (Exception ignored) {
                }
                try {
                    oldSession.close();
                } catch (Exception ignored) {
                }
                refreshState(oldIndex);
            }
            return openPilotSlot(pilotQueue.get(nextPosition), nextPosition, now, oldName);
        }

        // 固定队列表跑完后，动态重读 Excel：只要还有“完成日期不是今天”的账号，就覆盖当前槽位继续登录托管。
        ExcelAccount nextExcelAccount;
        try {
            nextExcelAccount = findNextUnfinishedExcelAccount();
        } catch (Exception e) {
            PilotLog.append(oldName, "重新读取 Excel 寻找下一个账号失败: " + e.getMessage());
            failAutoPilot("重新读取 Excel 寻找下一个账号失败: " + e.getMessage());
            return true;
        }

        if (nextExcelAccount == null) {
            if (oldSession != null) {
                PilotLog.append(oldName, "Excel 中今日账号已全部跑完，停止脚本并保留窗口");
                try {
                    oldSession.stopScriptsIfInGame(false);
                } catch (Exception ignored) {
                }
            }
            autoPilotActive = false;
            autoPilotStopRequested = false;
            autoPilotPhase = PILOT_IDLE;
            setAutoPilotStatus("Excel 今日账号已全部跑完");
            log("[托管] Excel 中今日账号已全部跑完");
            PilotLog.append(oldName, "Excel 中今日账号已全部跑完");
            return true;
        }

        int nextIndex = Math.max(0, Math.min(oldIndex, sessions.length - 1));
        if (sessions[nextIndex] == null) {
            failAutoPilot("Excel 接力目标槽位无效");
            return true;
        }

        PilotLog.append(oldName, "从 Excel 找到下一个未完成账号：Excel第 "
                + nextExcelAccount.getRowNumber() + " 行，准备关闭当前号并接力登录");
        log("[托管] 从 Excel 找到下一个未完成账号：Excel第 "
                + nextExcelAccount.getRowNumber() + " 行，准备接力");

        if (oldSession != null) {
            try {
                oldSession.stopScriptsIfInGame(false);
            } catch (Exception ignored) {
            }
            try {
                oldSession.close();
            } catch (Exception ignored) {
            }
            refreshState(oldIndex);
        }

        try {
            applyExcelAccountToPilotSlot(nextIndex, nextExcelAccount);
        } catch (Exception e) {
            PilotLog.append(oldName, "写入下一个 Excel 账号配置失败: " + e.getMessage());
            failAutoPilot("写入下一个 Excel 账号配置失败: " + e.getMessage());
            return true;
        }

        pilotQueue.add(nextIndex);
        return openPilotSlot(nextIndex, nextPosition, now, oldName);
    }

    private boolean openPilotSlot(int nextIndex, int nextPosition, long now, String oldName) {
        if (nextIndex < 0 || nextIndex >= sessions.length || sessions[nextIndex] == null) {
            PilotLog.append(oldName, "切号失败：目标槽位无效");
            failAutoPilot("切号目标槽位无效");
            return true;
        }

        autoPilotIndex = nextIndex;
        pilotQueuePosition = nextPosition;
        autoPilotStopRequested = false;
        autoPilotStartedAt = now;
        autoPilotPhaseAt = now;
        autoPilotNextActionAt = now;
        autoPilotAttempts = 0;
        autoPilotRestartCount = 0;
        autoPilotCityRequested = false;
        noMissionStreak = 0;
        nextMissionSnapshotAt = 0L;
        autoPilotPhase = PILOT_OPENING;
        setAutoPilotStatus("正在切换并打开浏览器");

        try {
            sessions[nextIndex].open(playwright);
            refreshState(nextIndex);
            autoPilotPhase = PILOT_WAIT_GAME;
            autoPilotPhaseAt = System.currentTimeMillis();
            autoPilotNextActionAt = autoPilotPhaseAt;
            setAutoPilotStatus("等待登录、选角并进入游戏");
            String nextName = pilotDisplayName();
            log("[托管] 已切换到下一个账号，开始处理 " + nextName);
            PilotLog.append(nextName, "已切换到队列第 " + (nextPosition + 1)
                    + " 个账号（Excel第 " + store.account(nextIndex).getExcelRow()
                    + " 行），等待登录、选角并进入游戏");
        } catch (Exception e) {
            PilotLog.append(oldName, "切换账号打开失败: " + e.getMessage());
            failAutoPilot("切换账号打开失败: " + e.getMessage());
        }
        return true;
    }

    private ExcelAccount findNextUnfinishedExcelAccount() throws Exception {
        Path excelFile = dataDir.resolve("账号.xlsx");
        if (!Files.isRegularFile(excelFile)) {
            throw new java.io.IOException("固定账号表不存在: " + excelFile);
        }

        Path tempFile = Files.createTempFile("world-pilot-accounts-", ".xlsx");
        try {
            Files.copy(excelFile, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            List<ExcelAccount> accounts = ExcelAccountReader.read(tempFile);
            for (ExcelAccount account : accounts) {
                if (account == null || account.isFinishedToday()) {
                    continue;
                }
                if (pilotHandledExcelRows.contains(account.getRowNumber())) {
                    continue;
                }
                Channel channel = account.getChannel();
                boolean valid = channel == Channel.GUANFANG
                        ? !account.getUsername().isBlank() && !account.getPassword().isBlank()
                        : !account.getUrl().isBlank();
                if (valid) {
                    return account;
                }
            }
            return null;
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
            }
        }
    }

    private void applyExcelAccountToPilotSlot(int slot, ExcelAccount excelAccount) {
        AccountConfig config = store.account(slot);
        Channel channel = excelAccount.getChannel();

        config.setTitle(excelAccount.getDisplayName());
        config.setChannel(channel);
        config.setExcelRow(excelAccount.getRowNumber());
        config.setFinishDate(excelAccount.getFinishDate());

        if (channel == Channel.GUANFANG) {
            config.setCustomUrl("");
            config.setUsername(excelAccount.getUsername());
            config.setPassword(excelAccount.getPassword());
            sessions[slot].prepareOfficialLogin(excelAccount.getUsername(), excelAccount.getPassword());
        } else {
            config.setCustomUrl(excelAccount.getUrl());
            config.setUsername("");
            config.setPassword("");
            sessions[slot].prepareFreshChannelLogin();
        }
        store.save();
    }

    private void markPilotAccountFinished(int index) {
        if (index < 0 || index >= sessions.length) {
            return;
        }
        AccountConfig config = store.account(index);
        String today = LocalDate.now().toString();
        String name = config.displayName();
        config.setFinishDate(today);
        try {
            store.save();
        } catch (Exception e) {
            log("[托管] 保存本地完成日期失败: " + e.getMessage());
        }
        try {
            Path excelFile = dataDir.resolve("账号.xlsx");
            ExcelAccountWriter.markAccountFinished(excelFile, config, today);
            log("[托管] 已写入今日完成日期 " + today + " " + name);
            PilotLog.append(name, "已写入今日完成日期 " + today);
        } catch (Exception e) {
            String text = "写入 Excel 完成日期失败（不影响切号）: " + e.getMessage();
            log("[托管] " + text + " " + name);
            PilotLog.append(name, text);
        }
    }

    private static int optionalInt(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String missionNames(JsonArray items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(items.size(), 10);
        for (int i = 0; i < limit; i++) {
            JsonElement element = items.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String title = "";
            if (item.has("name") && item.get("name").isJsonPrimitive()) {
                title = item.get("name").getAsString().trim();
            }
            String id = item.has("id") && !item.get("id").isJsonNull()
                    ? item.get("id").getAsString() : "";
            if (!sb.isEmpty()) {
                sb.append("、");
            }
            sb.append(title.isEmpty() ? "任务#" + id : title);
            if (!id.isEmpty()) {
                sb.append("#").append(id);
            }
        }
        if (items.size() > limit) {
            sb.append("等").append(items.size()).append("个");
        }
        return sb.toString();
    }

    private void failAutoPilot(String reason) {
        String name = pilotDisplayName();
        autoPilotActive = false;
        autoPilotStopRequested = false;
        autoPilotPhase = PILOT_FAILED;
        setAutoPilotStatus("失败：" + reason);
        log("[托管] " + name + " 失败: " + reason);
        PilotLog.append(name, "托管失败：" + reason);
    }

    private String pilotDisplayName() {
        if (autoPilotIndex < 0 || autoPilotIndex >= sessions.length) {
            return "未知账号";
        }
        return store.account(autoPilotIndex).displayName();
    }

    private void setAutoPilotStatus(String status) {
        autoPilotStatus = status == null ? "" : status;
    }

    private static String elapsedText(long ms) {
        long totalSeconds = Math.max(0, ms / 1000L);
        return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }
    public void saveCurrentUrl(int index, Path excelFile, Consumer<String> callback) {
        submit("存号失败", () -> {
            AccountConfig config = store.account(index);
            String url = "";
            if (config.getChannel() == Channel.GUANFANG) {
                // 官服存号 = 保存账号密码（不抓 URL），下次打开自动填号登录。
                String username = sessions[index].getOfficialUsername();
                String password = sessions[index].getOfficialPassword();
                if (username.isBlank()) {
                    username = config.getUsername();
                    password = config.getPassword();
                }
                if (username.isBlank()) {
                    throw new IllegalStateException("没有可保存的官服账号密码，请先用导号或在设置里填写");
                }
                config.setUsername(username);
                config.setPassword(password == null ? "" : password);
                config.setCustomUrl("");
                store.save();
                writeExcelRecord(excelFile, config);
                log("官服存号成功（账号密码已写入表格）" + config.displayName());
                if (callback != null) {
                    callback.accept("官服账号 " + username + " 的账号密码已保存，并写入账号表，下次点“打开”即可自动登录。");
                }
                return;
            }

            // 天宇等渠道存号 = 保存当前游戏直链（含登录 token）。
            url = sessions[index].captureCurrentGameUrl();
            config.setCustomUrl(url);
            store.save();
            writeExcelRecord(excelFile, config);
            log("已保存游戏直链并写入表格 " + config.displayName());
            if (callback != null) {
                callback.accept(url);
            }
        });
    }

    private void writeExcelRecord(Path excelFile, AccountConfig config) {
        if (excelFile == null) {
            return;
        }
        try {
            ExcelAccountWriter.saveAccount(excelFile, config);
            log("已写入账号表 " + excelFile.getFileName());
        } catch (Exception e) {
            log("写入账号表失败: " + e.getMessage());
        }
    }

    // 设置里勾选/取消“自动清背包”后实时应用到已在游戏中的号，不必重开窗口。
    public void applyAutoClearBagLive(int index) {
        submit("切换自动清背包失败", () -> sessions[index].applyAutoClearBagLive());
    }

    // 手动立即清一次当前号背包。
    public void clearBagNow(int index) {
        submit("立即清背包失败", () -> sessions[index].clearBagNow());
    }

    public void saveConfig() {
        submit("保存配置失败", store::save);
    }

    public boolean isAccountOpen(int index) {
        int state = accountStates.get(index);
        return state == STATE_OPEN || state == STATE_BOOTSTRAPPED;
    }

    public boolean isAccountBootstrapped(int index) {
        return accountStates.get(index) == STATE_BOOTSTRAPPED;
    }

    @Override
    public synchronized void close() {
        if (browserExecutor == null) {
            return;
        }

        browserExecutor.submit(() -> {
            for (AccountSession session : sessions) {
                if (session != null) {
                    try {
                        session.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (playwright != null) {
                playwright.close();
            }
        });
        browserExecutor.shutdown();
        try {
            browserExecutor.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        browserExecutor = null;
        playwright = null;
        log("控制台已退出");
    }

    private void runOnBootstrapped(String actionName, Consumer<AccountSession> action,
                                   int frontIndex) {
        submit(actionName + "失败", () -> {
            int success = 0;
            int skipped = 0;
            for (int i = 0; i < sessions.length; i++) {
                AccountSession session = sessions[i];
                if (session == null || !session.isOpen() || !session.isBootstrapped()) {
                    skipped++;
                    continue;
                }
                try {
                    action.accept(session);
                    success++;
                } catch (Exception e) {
                    log(actionName + "失败，账号 " + (i + 1) + ": " + e.getMessage());
                }
            }
            log(actionName + "完成：成功 " + success + " 个，跳过未进入/未注入 " + skipped + " 个");
            bringFrontIfOpen(frontIndex);
        });
    }

    private void bringFrontIfOpen(int index) {
        int safeIndex = Math.max(0, Math.min(index, sessions.length - 1));
        if (sessions[safeIndex] != null && sessions[safeIndex].isOpen()) {
            sessions[safeIndex].bringToFront();
        }
    }

    private void pollAllSafely() {
        for (int i = 0; i < sessions.length; i++) {
            try {
                AccountSession session = sessions[i];
                if (session != null) {
                    session.pollBootstrap();
                    // 每处理完一个号就立刻把待同步手势广播出去，
                    // 让同步延迟上限≈单个号的检测耗时（稳态仅 1 次往返），而不是整轮 10 个号。
                    flushSyncEvents();
                }
                refreshState(i);
            } catch (Exception e) {
                log("注入轮询异常: " + e.getMessage());
            }
        }
        pollAutoPilot();
    }

    private void refreshAllStates() {
        for (int i = 0; i < sessions.length; i++) {
            refreshState(i);
        }
    }

    private void refreshState(int index) {
        AccountSession session = sessions[index];
        int state;
        if (session == null || !session.isOpen()) {
            state = STATE_CLOSED;
        } else if (session.isBootstrapped()) {
            state = STATE_BOOTSTRAPPED;
        } else {
            state = STATE_OPEN;
        }
        accountStates.set(index, state);
    }

    private void submit(String errorPrefix, Runnable action) {
        if (browserExecutor == null) {
            log("控制台尚未启动");
            return;
        }
        browserExecutor.submit(() -> {
            try {
                action.run();
            } catch (Exception e) {
                log(errorPrefix + ": " + e.getMessage());
            }
        });
    }

    private void log(String message) {
        logger.accept(message);
    }
}
