package com.liang.world.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务进度记录：把游戏内“提交/接取任务”事件长期追加到 data/任务记录.txt。
 * 控制台日志区会滚动覆盖，文件可长期留存、按时间回溯做到哪一步。
 * 所有方法线程安全（可能从 Playwright 回调线程调用）。
 */
public final class MissionLog {
    private static final Path FILE = Path.of("data", "任务记录.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private MissionLog() {
    }

    public static void append(String account, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String line = LocalDateTime.now().format(TS) + "  "
                + (account == null || account.isBlank() ? "" : "[" + account + "] ")
                + text.trim() + System.lineSeparator();
        synchronized (LOCK) {
            try {
                Files.createDirectories(FILE.getParent());
                Files.write(FILE, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // 记录文件失败不影响游戏与控制台主流程
            }
        }
    }
}