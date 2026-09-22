package com.liang.world.desktop;

import java.util.ArrayList;
import java.util.List;

/**
 * 主控手势总线：主控窗口发布一次，其它窗口通过虚拟同源地址独立轮询。
 * 不再由 Playwright 单线程逐个 evaluate 到每个窗口，避免事件积压。
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
        sequence = 0;
        events.clear();
    }

    public synchronized long sequence() {
        return sequence;
    }

    public synchronized List<SyncEvent> eventsAfter(long after) {
        List<SyncEvent> result = new ArrayList<>();
        if (sequence == 0 || after >= sequence) {
            return result;
        }
        // 总线被重置时从头读取，防止被控号停在旧的 sequence。
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
