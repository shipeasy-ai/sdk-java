package ai.shipeasy;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight, <strong>user-bound</strong> handle to the Shipeasy SDK.
 *
 * <p>Construct one per user (typically per request) with your own user object;
 * the configured {@link Shipeasy.Options#attributes attributes} transform is run
 * <em>once</em> at construction and the resulting attribute map is bound to this
 * instance. Every method then takes <strong>no user argument</strong>:
 *
 * <pre>{@code
 * Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));   // once
 * boolean on = new Client(user).getFlag("new_checkout");      // per user
 * }</pre>
 *
 * <p>This class is <strong>cheap</strong>: it owns no HTTP connection, no blob
 * cache and no poll timer — it forwards every call to the single global
 * {@link Engine} built by {@link Shipeasy#configure}, passing the bound
 * attribute map. The request-scoped {@code anonymous_id} (from
 * {@link AnonIdFilter}/{@link AnonId}) is merged by the engine at evaluation
 * time exactly as the per-call {@code Engine.getFlag(name, user)} path does, so
 * anonymous bucketing keeps working with no per-call wiring.
 *
 * <p>Constructing a {@code Client} before {@link Shipeasy#configure} has been
 * called throws {@link IllegalStateException}.
 */
public final class Client {

    private final Engine engine;
    private final Map<String, Object> attributes;

    /**
     * Bind a user to the global engine. Runs the configured {@code attributes}
     * transform on {@code user} once and stores the resulting attribute map.
     *
     * @param user your own user object (any shape the configured transform
     *             accepts); with the default identity transform this must
     *             already be the Shipeasy attribute map (a {@code Map<String,
     *             Object>}).
     * @throws IllegalStateException if {@link Shipeasy#configure} was not called
     */
    public Client(Object user) {
        Engine e = Shipeasy.engine();
        if (e == null) {
            throw new IllegalStateException(
                "new Client(user) called before Shipeasy.configure({ apiKey })");
        }
        this.engine = e;
        Map<String, Object> mapped = Shipeasy.attributesFn().apply(user);
        // Defensive copy into a mutable map: callers may pass an immutable
        // Map.of(...) and the engine merges anon-id by copying anyway, but a
        // private copy isolates this bound instance from later caller mutations.
        this.attributes = mapped == null ? new HashMap<>() : new HashMap<>(mapped);
    }

    /** Bound user attribute map (the transform's output). */
    public Map<String, Object> attributes() {
        return attributes;
    }

    /** Evaluate gate {@code name} for the bound user. */
    public boolean getFlag(String name) {
        return engine.getFlag(name, attributes);
    }

    /**
     * Evaluate gate {@code name} for the bound user, returning {@code defaultValue}
     * only when the flag <em>cannot</em> be evaluated (engine not ready or the
     * flag is absent) — never when it evaluates to {@code false}.
     */
    public boolean getFlag(String name, boolean defaultValue) {
        return engine.getFlag(name, attributes, defaultValue);
    }

    /** Evaluate gate {@code name} for the bound user, returning value + reason. */
    public FlagDetail getFlagDetail(String name) {
        return engine.getFlagDetail(name, attributes);
    }

    /** Resolve dynamic config {@code name} (configs are not user-scoped). */
    public Object getConfig(String name) {
        return engine.getConfig(name);
    }

    /** Resolve dynamic config {@code name}, or {@code defaultValue} when absent. */
    public Object getConfig(String name, Object defaultValue) {
        return engine.getConfig(name, defaultValue);
    }

    /**
     * Evaluate experiment {@code name} for the bound user, filling in
     * {@code defaultParams} when the user is not enrolled / has no params.
     */
    public ExperimentResult getExperiment(String name, Object defaultParams) {
        return engine.getExperiment(name, attributes, defaultParams);
    }

    /**
     * Read killswitch {@code name} — {@code true} when the whole killswitch is
     * killed. (Killswitches are not user-scoped; the bound user is irrelevant,
     * but it is exposed here for one-stop ergonomics.)
     */
    public boolean getKillswitch(String name) {
        return engine.getKillswitch(name);
    }

    /**
     * Read killswitch {@code name}'s named per-key switch {@code switchKey} —
     * {@code true} when that switch is on. A {@code null} {@code switchKey}
     * reads the whole-killswitch killed state.
     */
    public boolean getKillswitch(String name, String switchKey) {
        return engine.getKillswitch(name, switchKey);
    }
}
