package com.liang.world.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 托管队列持久日志：记录队列建立、切号、结束和失败。
 * 只记录槽位/备注和状态，不记录 URL token、Cookie、账号密码。
 */
public final class PilotLog {
    private static final Path FILE = Path.of("data", "托管记录.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private PilotLog() {
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
                // 日志失败不影响托管主流程。
            }
        }
    }
}
