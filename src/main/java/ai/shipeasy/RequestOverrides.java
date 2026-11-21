package ai.shipeasy;

import java.util.function.Supplier;

/**
 * Request-scoped holder for the verified DevTools override map (the signed
 * {@code se_ov} cookie). Thread-local: set at scope entry (verified ONCE per
 * request), always restored afterwards. Reads outside a scope, or with no
 * override key served by Shipeasy, are unchanged.
 */
final class RequestOverrides {

    private static final ThreadLocal<OverrideCookie.Overrides> CURRENT = new ThreadLocal<>();

    private RequestOverrides() {}

    /** The verified overrides for the current request, or {@code null}. */
    static OverrideCookie.Overrides current() {
        return CURRENT.get();
    }

    /** Verify {@code cookieHeader} against the global engine; {@code null} when none apply. */
    static OverrideCookie.Overrides verify(String cookieHeader) {
        try {
            Engine e = Shipeasy.engine();
            if (e == null) return null;
            String key = e.overrideKey();
            String keyId = e.overrideKeyId();
            if (key == null || keyId == null) return null; // fail closed
            String raw = OverrideCookie.extract(cookieHeader);
            if (raw == null) return null;
            OverrideCookie.Overrides ov =
                OverrideCookie.verify(raw, key, keyId, System.currentTimeMillis() / 1000L);
            return ov == null || ov.isEmpty() ? null : ov;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Enter a scope; returns the previous value to hand back to {@link #restore}. */
    static OverrideCookie.Overrides enter(String cookieHeader) {
        OverrideCookie.Overrides prev = CURRENT.get();
        set(verify(cookieHeader));
        return prev;
    }

    static void restore(OverrideCookie.Overrides prev) {
        set(prev);
    }

    private static void set(OverrideCookie.Overrides ov) {
        if (ov == null) CURRENT.remove(); else CURRENT.set(ov);
    }

    static <T> T with(String cookieHeader, Supplier<T> body) {
        OverrideCookie.Overrides prev = enter(cookieHeader);
        try {
            return body.get();
        } finally {
            restore(prev);
        }
    }
}
