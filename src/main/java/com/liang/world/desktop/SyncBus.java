package com.liang.world.desktop;

import java.util.ArrayList;
import java.util.List;

/**
 * 主控手势总线：主控窗口发布一次，其它窗口通过虚拟同源地址独立轮询。
 * 不再由 Playwright 单线程逐个 evaluate 到每个窗口，避免事件积压。
 * generation 在开启/关闭/切换主控时递增，用于隔离旧手势序号和按下状态。
 */
public final class SyncBus {
    public static final class SyncEvent {
        public final long sequence;
        public final String payload;

        SyncEvent(long sequence, String payload) {
            this.sequence = sequence;
            this.payload = payload;
        }
    }

    private static final int MAX_EVENTS = 1024;
    private static final int MAX_BATCH = 64;

    private final List<SyncEvent> events = new ArrayList<>();
    private long sequence;
    private long generation = 1;

    public synchronized void publish(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        sequence++;
        events.add(new SyncEvent(sequence, payload));
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    public synchronized void reset() {
        generation++;
        sequence = 0;
        events.clear();
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized long sequence() {
        return sequence;
    }

    public synchronized List<SyncEvent> eventsAfter(long after) {
        List<SyncEvent> result = new ArrayList<>();
        if (sequence == 0 || after >= sequence) {
            return result;
        }
        // 正常情况下客户端会按 generation 清零；这里仍保留越界保护。
        if (after < 0 || after > sequence) {
            after = 0;
        }
        for (SyncEvent event : events) {
            if (event.sequence > after) {
                result.add(event);
                if (result.size() >= MAX_BATCH) {
                    break;
                }
            }
        }
        return result;
    }
}
