package ai.shipeasy;

import java.util.Map;

/**
 * Pluggable sticky-bucketing store for the server (doc 20 §2). Keyed by the
 * bucketing unit; the value is that unit's per-experiment assignments (keyed by
 * experiment name). Absent from the client options ⇒ today's deterministic
 * behaviour. Use {@link InMemoryStickyStore} for a process-local Map-backed
 * store, or implement a cookie-bridge over request cookies.
 *
 * <p>When supplied, {@link Client#getExperiment} locks a unit to its
 * first-assigned variant — changing allocation % or group weights won't
 * re-bucket enrolled units (changing the experiment salt is the reshuffle
 * lever). Mirrors {@code StickyBucketStore} in the canonical TS SDK.
 */
public interface StickyBucketStore {
    /** This unit's per-experiment assignments (experiment name -> entry), or {@code null}. */
    Map<String, StickyEntry> get(String unit);

    /** Persist {@code entry} as this unit's assignment for experiment {@code exp}. */
    void set(String unit, String exp, StickyEntry entry);
}
