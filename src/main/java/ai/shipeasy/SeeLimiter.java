package ai.shipeasy;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-process spam guard for {@code see()}: identical events within a 30s window
 * collapse to one send, and a hard cap bounds total sends over the process
 * lifetime. Thread-safe. The worker dedupes by fingerprint anyway — this only
 * bounds network chatter from a hot loop.
 */
final class SeeLimiter {
    private final int max;
    private final long windowMs;
    private final Map<String, Long> last = new HashMap<>();
    private int sent = 0;

    SeeLimiter() {
        this(See.SEE_MAX_PER_PROCESS, See.SEE_DEDUP_WINDOW_MS);
    }

    SeeLimiter(int maxPerProcess, long dedupWindowMs) {
        this.max = maxPerProcess;
        this.windowMs = dedupWindowMs;
    }

    synchronized boolean shouldSend(Map<String, Object> ev) {
        if (sent >= max) return false;
        String key = String.valueOf(ev.get("kind"))
            + "|" + String.valueOf(ev.get("error_type"))
            + "|" + See.truncate(String.valueOf(ev.getOrDefault("message", "")), 200)
            + "|" + See.topStackLine((String) ev.get("stack"));
        long now = System.currentTimeMillis();
        Long prev = last.get(key);
        if (prev != null && now - prev < windowMs) return false;
        last.put(key, now);
        sent++;
        return true;
    }
}
