package ai.shipeasy;

import java.util.Map;

/**
 * The result of {@code universe(name).assign(user)} — a unit's standing in a
 * universe. A universe is a mutual-exclusion pool, so a unit lands in
 * <strong>at most one</strong> experiment. Never throws: an un-enrolled unit
 * still resolves {@link #get} to the universe defaults (or your fallback).
 *
 * <p>Reading is side-effect free — the single exposure is logged once by
 * {@code assign()} when the unit is enrolled. Mirrors the {@code Assignment}
 * type in the canonical TS SDK.
 */
public final class Assignment {

    private final String name;
    private final String group;
    // Already merged (universeDefaults ⊕ variantOverride) when enrolled;
    // defaults-only (or empty) when not.
    private final Map<String, Object> params;

    Assignment(String name, String group, Map<String, Object> params) {
        this.name = name;
        this.group = group;
        this.params = params == null ? Map.of() : params;
    }

    /** The experiment the unit landed in, or {@code null} when not enrolled. */
    public String name() {
        return name;
    }

    /** The assigned variant/group name, or {@code null} when not enrolled. */
    public String group() {
        return group;
    }

    /** True iff the unit is enrolled in an experiment in this universe. */
    public boolean enrolled() {
        return group != null;
    }

    /**
     * Read a resolved param: the assigned variant's override, else the universe
     * default, else {@code fallback}. Works even when not enrolled (the variant
     * layer is absent, so you get {@code universeDefault ?? fallback}).
     */
    public Object get(String field, Object fallback) {
        Object v = params.get(field);
        return v == null ? fallback : v;
    }

    /**
     * As {@link #get(String, Object)} but casts the resolved value to {@code T}.
     * On a type mismatch (a {@link ClassCastException}) falls back to
     * {@code fallback} rather than throwing — the read stays fail-safe.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String field, Class<T> type, T fallback) {
        Object v = params.get(field);
        if (v == null) return fallback;
        try {
            return (T) type.cast(v);
        } catch (ClassCastException e) {
            return fallback;
        }
    }
}
