package ai.shipeasy;

import java.util.Map;

/**
 * The result of {@code universe(name).assign(user)} — a unit's standing in a
 * universe. A universe is a mutual-exclusion pool, so a unit lands in
 * <strong>at most one</strong> experiment. Never throws: an un-enrolled unit
 * still resolves {@link #get} to the universe defaults (or your fallback).
 *
 * <p>Exposure is logged <strong>on read</strong> (spec step 7): the single
 * exposure fires the first time an enrolled unit's param is actually read via
 * {@link #get}, not at {@code assign()} time — so an assignment that is computed
 * but never read logs nothing. Deduped per process; the durable
 * per-(unit, experiment, group) dedup lives server-side. Use {@link #peek} to
 * read without logging. Mirrors the {@code Assignment} type in the canonical TS
 * SDK.
 */
public final class Assignment {

    private final String name;
    private final String group;
    // Already merged (universeDefaults ⊕ variantOverride) when enrolled;
    // defaults-only (or empty) when not.
    private final Map<String, Object> params;
    // Fires the single exposure the first time an enrolled param is read; null
    // when not enrolled (nothing to expose). Deduped downstream.
    private final Runnable onExpose;
    private boolean exposed;

    Assignment(String name, String group, Map<String, Object> params) {
        this(name, group, params, null);
    }

    Assignment(String name, String group, Map<String, Object> params, Runnable onExpose) {
        this.name = name;
        this.group = group;
        this.params = params == null ? Map.of() : params;
        this.onExpose = onExpose;
    }

    /** The experiment the unit landed in, or {@code null} when not enrolled. */
    public String name() {
        return name;
    }

    /** The assigned variant/group name, or {@code null} when not enrolled. */
    public String group() {
        return group;
    }

    /**
     * True iff the unit is enrolled in an experiment in this universe. Reading it
     * does NOT log an exposure (only {@link #get} of a param does).
     */
    public boolean enrolled() {
        return group != null;
    }

    // On-read exposure: the first param read of an enrolled assignment logs one
    // exposure. Called by the get() overloads, not by peek().
    private void maybeExpose() {
        if (onExpose != null && !exposed) {
            exposed = true;
            onExpose.run();
        }
    }

    /**
     * Read a resolved param: the assigned variant's override, else the universe
     * default, else {@code fallback}. Works even when not enrolled (the variant
     * layer is absent, so you get {@code universeDefault ?? fallback}). The first
     * enrolled read logs the single exposure; use {@link #peek} to suppress it.
     */
    public Object get(String field, Object fallback) {
        maybeExpose();
        return lookup(field, fallback);
    }

    /**
     * As {@link #get(String, Object)} but casts the resolved value to {@code T}.
     * On a type mismatch (a {@link ClassCastException}) falls back to
     * {@code fallback} rather than throwing — the read stays fail-safe.
     */
    public <T> T get(String field, Class<T> type, T fallback) {
        maybeExpose();
        return lookupTyped(field, type, fallback);
    }

    /**
     * Read a resolved param WITHOUT logging an exposure — the read-only
     * counterpart to {@link #get(String, Object)} (spec step 7 opt-out).
     */
    public Object peek(String field, Object fallback) {
        return lookup(field, fallback);
    }

    /**
     * Typed {@link #peek(String, Object)}: reads without logging an exposure.
     */
    public <T> T peek(String field, Class<T> type, T fallback) {
        return lookupTyped(field, type, fallback);
    }

    private Object lookup(String field, Object fallback) {
        Object v = params.get(field);
        return v == null ? fallback : v;
    }

    private <T> T lookupTyped(String field, Class<T> type, T fallback) {
        Object v = params.get(field);
        if (v == null) return fallback;
        try {
            return type.cast(v);
        } catch (ClassCastException e) {
            return fallback;
        }
    }
}
