package com.liang.world.desktop;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 严重错误日志：保证 Playwright/调度线程异常不会静默消失。
 */
public final class ErrorLog {
    private static final Path FILE = Path.of("data", "错误记录.txt");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private ErrorLog() {
    }

    public static void append(String location, Throwable throwable) {
        StringWriter sw = new StringWriter(4096);
        try (PrintWriter pw = new PrintWriter(sw)) {
            pw.println(LocalDateTime.now().format(TS) + "  " + location);
            throwable.printStackTrace(pw);
        }
        String body = sw.toString();
        if (body.length() > 8000) {
            body = body.substring(0, 8000) + System.lineSeparator() + "... 堆栈过长已截断 ...";
        }
        String line = body + System.lineSeparator();
        synchronized (LOCK) {
            try {
                Files.createDirectories(FILE.getParent());
                Files.write(FILE, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {
            }
        }
    }
}
