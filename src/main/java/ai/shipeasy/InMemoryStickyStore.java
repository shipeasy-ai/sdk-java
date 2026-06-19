package ai.shipeasy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local, Map-backed {@link StickyBucketStore}. Handy for tests and
 * single-process servers. Thread-safe. Mirrors {@code createInMemoryStickyStore}
 * in the canonical TS SDK.
 */
public final class InMemoryStickyStore implements StickyBucketStore {
    private final Map<String, Map<String, StickyEntry>> store = new ConcurrentHashMap<>();

    @Override
    public Map<String, StickyEntry> get(String unit) {
        return store.get(unit);
    }

    @Override
    public void set(String unit, String exp, StickyEntry entry) {
        store.computeIfAbsent(unit, k -> new ConcurrentHashMap<>()).put(exp, entry);
    }
}
