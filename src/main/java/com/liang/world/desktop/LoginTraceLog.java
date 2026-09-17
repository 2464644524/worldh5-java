package com.liang.world.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 登录/选角/进游戏过程追踪：长期追加到 data/登录流程.txt。
 * 右侧控制台日志区域较短，文件用于完整复盘用户是如何进入游戏的。
 */
public final class LoginTraceLog {
    private static final Path FILE = Path.of("data", "登录流程.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private LoginTraceLog() {
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
                // 追踪日志失败不能影响登录和游戏主流程。
            }
        }
    }
}
