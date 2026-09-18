package com.liang.world.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 游戏内操作记录：点击/拖动、面板、任务、网络指令等旁路日志。
 * 只写本机 data 目录，该目录已被 .gitignore 忽略，不提交账号数据。
 */
public final class ActionLog {
    private static final Path FILE = Path.of("data", "操作记录.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private ActionLog() {
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
                // 记录失败不能影响游戏主流程。
            }
        }
    }
}
