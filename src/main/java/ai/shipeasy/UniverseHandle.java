package ai.shipeasy;

import java.util.Map;

/**
 * A reusable handle bound to one universe, returned by {@code Engine.universe(name)}.
 * {@code assign(user)} picks the ≤1 experiment the unit is pooled into within the
 * universe and auto-logs a single exposure when enrolled. See
 * {@link Engine#assignUniverse(String, Map)}.
 */
public final class UniverseHandle {

    private final Engine engine;
    private final String name;

    UniverseHandle(Engine engine, String name) {
        this.engine = engine;
        this.name = name;
    }

    /**
     * Assign {@code user} within this universe. Returns an {@link Assignment}: the
     * variant + resolved params, auto-logging a single (deduped) exposure when
     * enrolled. Never throws — an un-enrolled unit still resolves the universe
     * defaults.
     */
    public Assignment assign(Map<String, Object> user) {
        return engine.assignUniverse(name, user);
    }
}
