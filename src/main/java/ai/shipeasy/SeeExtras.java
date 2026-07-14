package ai.shipeasy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ambient per-request {@code see()} extras — a thread-local buffer of context
 * that merges into EVERY {@code see()} report firing later in the same request.
 *
 * <p>Lets a request attach context (order id, route, tenant) from anywhere
 * without threading it into the catch block: call {@link #add(Map)} early in the
 * request, and any subsequent {@code see()} in this thread carries it. The
 * chain's own extras win on a key collision (the ambient buffer merges
 * <em>under</em> the chained extras).
 *
 * <p>This is thread-local (like {@link AnonId}), so concurrent requests never
 * bleed into each other. {@link AnonIdFilter} clears it at the end of each
 * request; outside a servlet request (jobs, scripts) call {@link #clear()}
 * yourself when a logical unit of work ends, or a pooled thread will carry the
 * buffer into the next task.
 *
 * <p>Values are stored raw and sanitized (scalar-only, truncated, 20-key cap,
 * private-attribute stripped) at build time, exactly like chained extras.
 *
 * <p>These primitives carry no servlet dependency; non-servlet stacks can call
 * them directly. The package-level {@link See#addExtras(Map)} /
 * {@link See#clearExtras()} statics forward here.
 */
public final class SeeExtras {

    private static final ThreadLocal<Map<String, Object>> BUFFER = new ThreadLocal<>();

    private SeeExtras() {}

    /**
     * Merge fields into the current thread's buffer (later wins). A {@code null}
     * or empty map is ignored. Never throws.
     */
    public static void add(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) return;
        try {
            Map<String, Object> buf = BUFFER.get();
            if (buf == null) {
                buf = new LinkedHashMap<>();
                BUFFER.set(buf);
            }
            for (Map.Entry<String, Object> e : extras.entrySet()) {
                buf.put(String.valueOf(e.getKey()), e.getValue());
            }
        } catch (Throwable ignore) {
            // Never raise out of context bookkeeping.
        }
    }

    /**
     * A copy of the current thread's buffer, or {@code null} when empty. Copied
     * so a caller can hold it without observing later mutations.
     */
    static Map<String, Object> current() {
        Map<String, Object> buf = BUFFER.get();
        if (buf == null || buf.isEmpty()) return null;
        return new LinkedHashMap<>(buf);
    }

    /**
     * Drop the current thread's buffer so extras never leak to the next request
     * handled by this pooled thread.
     */
    public static void clear() {
        BUFFER.remove();
    }
}
