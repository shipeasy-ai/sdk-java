package ai.shipeasy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

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

    /**
     * The browser's per-request correlation token ({@code X-SE-Correlation}),
     * held next to the ambient extras and cleared with them.
     */
    private static final ThreadLocal<String> CORRELATION = new ThreadLocal<>();

    /** Same shape the collector accepts; anything else is dropped, never sent. */
    private static final Pattern CORRELATION_RE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

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
     * Park the browser SDK's per-request correlation token for the current
     * thread. The browser mints one token per same-origin request and sends it
     * on {@code X-SE-Correlation}; every {@code see()} fired later on this
     * thread carries it as {@code correlation_id}, which is what lets the
     * platform link the browser's error to this server exception.
     * {@link AnonIdFilter} calls this for you. A malformed / blank value clears
     * it. Never throws.
     */
    public static void setCorrelationId(String value) {
        try {
            String clean = cleanCorrelation(value);
            if (clean == null) CORRELATION.remove();
            else CORRELATION.set(clean);
        } catch (Throwable ignore) {
            // Never raise out of context bookkeeping.
        }
    }

    /** The current thread's correlation token, or {@code null}. */
    static String correlationId() {
        return CORRELATION.get();
    }

    /** The trimmed token when it matches the collector's shape, else {@code null}. */
    static String cleanCorrelation(String value) {
        if (value == null) return null;
        String s = value.trim();
        return CORRELATION_RE.matcher(s).matches() ? s : null;
    }

    /**
     * Drop the current thread's buffer (and correlation token) so neither leaks
     * to the next request handled by this pooled thread.
     */
    public static void clear() {
        BUFFER.remove();
        CORRELATION.remove();
    }
}
