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

    /**
     * Resolve the scope's overrides from {@code cookieHeader}: signed {@code se_ov}
     * (needs the served key) merged over the opt-in dev-only {@code se_ov_dev}
     * (per name, signed wins). {@code null} when nothing applies.
     */
    static OverrideCookie.Overrides verify(String cookieHeader, String overridesHeader) {
        try {
            Engine e = Shipeasy.engine();
            if (e == null) return null;
            OverrideCookie.Overrides signed = null;
            String key = e.overrideKey();
            String keyId = e.overrideKeyId();
            if (key != null && keyId != null) {
                // Cookie header wins; X-Shipeasy-Overrides (cookie syntax) fills in when absent.
                String raw = OverrideCookie.extract(cookieHeader);
                if (raw == null) raw = OverrideCookie.extract(overridesHeader);
                if (raw != null) {
                    signed = OverrideCookie.verify(raw, key, keyId, System.currentTimeMillis() / 1000L);
                }
            }
            // Not honored => don't even look for the dev cookie.
            OverrideCookie.Overrides dev = e.devOverridesHonored() ? OverrideCookie.parseDev(cookieHeader, overridesHeader) : null;
            OverrideCookie.Overrides ov = merge(signed, dev);
            return ov == null || ov.isEmpty() ? null : ov;
        } catch (Throwable t) {
            return null;
        }
    }

    private static OverrideCookie.Overrides merge(OverrideCookie.Overrides signed, OverrideCookie.Overrides dev) {
        if (dev == null) return signed;
        if (signed == null) return dev;
        java.util.Map<String, Boolean> g = new java.util.HashMap<>(dev.gates);
        g.putAll(signed.gates);
        java.util.Map<String, Object> c = new java.util.HashMap<>(dev.configs);
        c.putAll(signed.configs);
        java.util.Map<String, String> x = new java.util.HashMap<>(dev.exps);
        x.putAll(signed.exps);
        return new OverrideCookie.Overrides(g, c, x);
    }

    /** Enter a scope; returns the previous value to hand back to {@link #restore}. */
    static OverrideCookie.Overrides enter(String cookieHeader, String overridesHeader) {
        OverrideCookie.Overrides prev = CURRENT.get();
        set(verify(cookieHeader, overridesHeader));
        return prev;
    }

    static void restore(OverrideCookie.Overrides prev) {
        set(prev);
    }

    private static void set(OverrideCookie.Overrides ov) {
        if (ov == null) CURRENT.remove(); else CURRENT.set(ov);
    }

    static <T> T with(String cookieHeader, String overridesHeader, Supplier<T> body) {
        OverrideCookie.Overrides prev = enter(cookieHeader, overridesHeader);
        try {
            return body.get();
        } finally {
            restore(prev);
        }
    }
}
