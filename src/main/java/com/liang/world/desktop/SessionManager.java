package com.liang.world.desktop;

import com.microsoft.playwright.Playwright;

import java.awt.Rectangle;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;
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

            for (int i = 0; i < count; i++) {
                ExcelAccount excelAccount = excelAccounts.get(i);
                AccountConfig config = store.account(i);
                Channel channel = excelAccount.getChannel();

                config.setTitle(excelAccount.getDisplayName());
                config.setChannel(channel);

                config.setExcelRow(excelAccount.getRowNumber());
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

            store.save();
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
