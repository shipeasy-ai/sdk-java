package ai.shipeasy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code see()} — <em>shipeasy error</em>. Structured error reporting for the
 * server SDK, mirroring {@code @shipeasy/sdk} (TS) and the Python reference.
 *
 * <p>Every handled exception documents its product <em>consequence</em>, not
 * just its stack:
 *
 * <pre>{@code
 * import static ai.shipeasy.See.see;
 *
 * try {
 *     chargeCard(order);
 * } catch (Exception e) {
 *     see(e).causesThe("checkout").extras(Map.of("order_id", order.id))
 *           .to("use the backup processor");
 * }
 * }</pre>
 *
 * <p><b>Dispatch model</b> (differs from TS, which uses a microtask): {@code .to(outcome)}
 * is the terminal — it builds the wire event and fire-and-forgets the POST to
 * {@code /collect}. {@code causesThe()} and {@code extras()} are chainable
 * setters that may be called in any order <em>before</em> {@code .to()}. Calling
 * {@code .to()} twice is a no-op; a chain that never calls {@code .to()} sends
 * nothing.
 *
 * <p>This class is also the package-level facade backing the global
 * {@link #see(Object)}, {@link #violation(String)} and
 * {@link #controlFlowException(Throwable)} functions, dispatched against the
 * last-constructed {@link Engine} (registered automatically in its constructor,
 * and by {@link Shipeasy#configure}). A global call before any engine exists logs
 * a warning and returns a no-op chain — it never throws.
 */
public final class See {

    // ---- Limits (mirror core.ts; kept in sync with the worker's /collect) ----
    static final int SEE_MAX_MESSAGE = 500;
    static final int SEE_MAX_STACK = 8000;
    static final int SEE_MAX_SUBJECT = 200; // subject, outcome, error_type
    static final int SEE_MAX_EXTRA_VALUE = 200;
    static final int SEE_MAX_EXTRA_KEYS = 20;
    static final long SEE_DEDUP_WINDOW_MS = 30_000L;
    static final int SEE_MAX_PER_PROCESS = 25;

    static final String DEFAULT_SUBJECT = "app";
    static final String DEFAULT_OUTCOME = "hit an error";

    private static final Object DEFAULT_LOCK = new Object();
    private static volatile Engine defaultEngine;

    private See() {}

    /**
     * Register the engine backing the package-level {@code See.see()} functions.
     * Called automatically when an {@link Engine} is constructed (last wins) and
     * by {@link Shipeasy#configure}.
     */
    public static void setDefaultEngine(Engine engine) {
        synchronized (DEFAULT_LOCK) {
            defaultEngine = engine;
        }
    }

    private static Engine resolveDefault() {
        return defaultEngine;
    }

    /**
     * Report a caught throwable (or thrown non-throwable problem) via the default
     * engine. Use {@link Engine#see(Object)} to target a specific engine.
     */
    public static SeeChain see(Object problem) {
        Engine c = resolveDefault();
        if (c == null) {
            Log.warn("see() called before an engine was created — error dropped");
            return SeeChain.noop(problem);
        }
        return c.see(problem);
    }

    /** Report a non-throwable problem (a {@link Violation}) via the default engine. */
    public static SeeChain violation(String name) {
        Engine c = resolveDefault();
        if (c == null) {
            Log.warn("seeViolation() called before an engine was created — error dropped");
            return SeeChain.noop(new Violation(name));
        }
        return c.seeViolation(name);
    }

    /** Alias for {@link #violation(String)} for cross-SDK muscle memory. */
    public static SeeChain seeViolation(String name) {
        return violation(name);
    }

    /**
     * Mark a throwable as expected control flow (reports nothing). Works without
     * a client — it only stamps the throwable object.
     */
    public static ControlFlowChain controlFlowException(Throwable e) {
        return new ControlFlowChain(e);
    }

    // ---- Ambient per-request extras -------------------------------------

    /**
     * Buffer extras that merge into EVERY {@code see()} report firing later in
     * this thread — attach context (order id, tenant, route) from anywhere
     * without threading it into the catch block:
     *
     * <pre>{@code
     * See.addExtras(Map.of("order_id", order.id(), "tenant", tenant.slug()));
     * // ...later, deep in a service...
     * } catch (Exception e) {
     *     See.see(e).causesThe("checkout").to("use cached prices");
     *     // ^ report carries order_id + tenant automatically
     * }
     * }</pre>
     *
     * <p>The buffer is <b>thread-local</b>, so concurrent requests never bleed,
     * and it merges into <em>every</em> report in scope. A chained
     * {@code .extras} / {@code .to} extra of the same key overrides an ambient
     * one. {@link AnonIdFilter} clears it per request; outside a servlet request
     * call {@link #clearExtras()} when a unit of work ends. Works with no engine
     * configured (it only writes the buffer); never throws.
     */
    public static void addExtras(Map<String, Object> extras) {
        SeeExtras.add(extras);
    }

    /** Drop the ambient extras buffer for the current thread. Never throws. */
    public static void clearExtras() {
        SeeExtras.clear();
    }

    // ---- Shared helpers (package-private) ----

    static String truncate(String s, int limit) {
        if (s == null) return null;
        return s.length() <= limit ? s : s.substring(0, limit);
    }

    /**
     * Drop null values, keep only String / finite Number / Boolean, truncate
     * string values to 200 chars, cap at 20 keys (insertion order). Returns
     * {@code null} if nothing kept.
     */
    static Map<String, Object> sanitizeExtras(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        int n = 0;
        for (Map.Entry<String, Object> e : extras.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (n >= SEE_MAX_EXTRA_KEYS) break;
            String k = String.valueOf(e.getKey());
            if (v instanceof Boolean) {
                out.put(k, v);
            } else if (v instanceof String) {
                out.put(k, truncate((String) v, SEE_MAX_EXTRA_VALUE));
            } else if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                out.put(k, v);
            } else {
                continue;
            }
            n++;
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Build the {@code type:"error"} event accepted by POST /collect. Mirrors the
     * Python {@code build_see_event}.
     */
    static Map<String, Object> buildSeeEvent(
            Object problem,
            String subject,
            String outcome,
            Map<String, Object> extras,
            String side,
            String sdkVersion,
            String env) {
        String errorType;
        String message;
        String stack = null;
        String kind;

        if (problem instanceof Violation) {
            String name = ((Violation) problem).name();
            errorType = name;
            message = name;
            kind = "violation";
        } else if (problem instanceof Throwable) {
            Throwable t = (Throwable) problem;
            String simple = t.getClass().getSimpleName();
            errorType = (simple == null || simple.isEmpty()) ? "Error" : simple;
            String msg = t.getMessage();
            message = (msg == null || msg.isEmpty()) ? errorType : msg;
            stack = stackToString(t);
            kind = "caught";
        } else {
            errorType = "Error";
            message = String.valueOf(problem);
            kind = "caught";
        }

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "error");
        ev.put("kind", kind);
        ev.put("error_type", truncate(errorType, SEE_MAX_SUBJECT));
        ev.put("message", truncate(message, SEE_MAX_MESSAGE));
        ev.put("subject", truncate(subject, SEE_MAX_SUBJECT));
        ev.put("outcome", truncate(outcome, SEE_MAX_SUBJECT));
        ev.put("side", side);
        ev.put("sdk_version", sdkVersion);
        ev.put("ts", System.currentTimeMillis());
        if (stack != null && !stack.isEmpty()) {
            ev.put("stack", truncate(stack, SEE_MAX_STACK));
        }
        Map<String, Object> clean = sanitizeExtras(extras);
        if (clean != null) ev.put("extras", clean);
        if (env != null && !env.isEmpty()) ev.put("env", env);
        return ev;
    }

    private static String stackToString(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            try (PrintWriter pw = new PrintWriter(sw)) {
                t.printStackTrace(pw);
            }
            String s = sw.toString().strip();
            return s.isEmpty() ? null : s;
        } catch (Throwable ignore) {
            return null;
        }
    }

    static String topStackLine(String stack) {
        if (stack == null || stack.isEmpty()) return "";
        for (String line : stack.split("\n")) {
            String s = line.strip();
            if (s.startsWith("at ") || s.startsWith("File ") || s.contains("line ")) {
                return s.length() > 200 ? s.substring(0, 200) : s;
            }
        }
        return "";
    }
}
