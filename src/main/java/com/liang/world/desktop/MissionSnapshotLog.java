package com.liang.world.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务快照日志：托管运行期间每 3 分钟记录可接/可交任务数量和明细。
 * 该文件只写本机 data 目录，已被 .gitignore 忽略。
 */
public final class MissionSnapshotLog {
    private static final Path FILE = Path.of("data", "任务快照.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private MissionSnapshotLog() {
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
                // 日志文件失败不影响游戏托管。
            }
        }
    }
}