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

    // ---- Fail-safe runtime reads ----
    //
    // Every read below routes through the engine's own fail-safe read methods.
    // The extra try/catch here is belt-and-suspenders: a read on the bound
    // Client MUST NEVER throw into the caller, so any unexpected error (e.g. a
    // corrupt bound attribute map) is caught, logged at error level via the
    // leveled logger, and the documented safe default is returned.

    /** Evaluate gate {@code name} for the bound user. Never throws → default {@code false}. */
    public boolean getFlag(String name) {
        try {
            return engine.getFlag(name, attributes);
        } catch (Throwable t) {
            Log.error("Client.getFlag threw: " + t);
            return false;
        }
    }

    /**
     * Evaluate gate {@code name} for the bound user, returning {@code defaultValue}
     * only when the flag <em>cannot</em> be evaluated (engine not ready or the
     * flag is absent) — never when it evaluates to {@code false}. Never throws →
     * {@code defaultValue}.
     */
    public boolean getFlag(String name, boolean defaultValue) {
        try {
            return engine.getFlag(name, attributes, defaultValue);
        } catch (Throwable t) {
            Log.error("Client.getFlag(default) threw: " + t);
            return defaultValue;
        }
    }

    /** Evaluate gate {@code name} for the bound user, returning value + reason. Never throws. */
    public FlagDetail getFlagDetail(String name) {
        try {
            return engine.getFlagDetail(name, attributes);
        } catch (Throwable t) {
            Log.error("Client.getFlagDetail threw: " + t);
            return new FlagDetail(false, FlagDetail.CLIENT_NOT_READY);
        }
    }

    /** Resolve dynamic config {@code name} (configs are not user-scoped). Never throws → {@code null}. */
    public Object getConfig(String name) {
        try {
            return engine.getConfig(name);
        } catch (Throwable t) {
            Log.error("Client.getConfig threw: " + t);
            return null;
        }
    }

    /** Resolve dynamic config {@code name}, or {@code defaultValue} when absent. Never throws → {@code defaultValue}. */
    public Object getConfig(String name, Object defaultValue) {
        try {
            return engine.getConfig(name, defaultValue);
        } catch (Throwable t) {
            Log.error("Client.getConfig(default) threw: " + t);
            return defaultValue;
        }
    }

    /**
     * Evaluate experiment {@code name} for the bound user, filling in
     * {@code defaultParams} when the user is not enrolled / has no params.
     * Never throws → not-enrolled {@code control}/{@code defaultParams}.
     */
    public ExperimentResult getExperiment(String name, Object defaultParams) {
        try {
            return engine.getExperiment(name, attributes, defaultParams);
        } catch (Throwable t) {
            Log.error("Client.getExperiment threw: " + t);
            return new ExperimentResult(false, "control", defaultParams);
        }
    }

    /**
     * Read killswitch {@code name} — {@code true} when the whole killswitch is
     * killed. (Killswitches are not user-scoped; the bound user is irrelevant,
     * but it is exposed here for one-stop ergonomics.) Never throws → {@code false}.
     */
    public boolean getKillswitch(String name) {
        try {
            return engine.getKillswitch(name);
        } catch (Throwable t) {
            Log.error("Client.getKillswitch threw: " + t);
            return false;
        }
    }

    /**
     * Read killswitch {@code name}'s named per-key switch {@code switchKey} —
     * {@code true} when that switch is on. A {@code null} {@code switchKey}
     * reads the whole-killswitch killed state. Never throws → {@code false}.
     */
    public boolean getKillswitch(String name, String switchKey) {
        try {
            return engine.getKillswitch(name, switchKey);
        } catch (Throwable t) {
            Log.error("Client.getKillswitch threw: " + t);
            return false;
        }
    }

    /**
     * Record a conversion event {@code eventName} for the bound user. The unit
     * id is derived from the bound attribute map ({@code user_id}, else
     * {@code anonymous_id}) — no user argument is needed. Fire-and-forget;
     * delegates to {@link Engine#track(String, String, Map)}.
     */
    public void track(String eventName) {
        track(eventName, null);
    }

    /**
     * Record a conversion event {@code eventName} with {@code properties} for the
     * bound user. The unit id is derived from the bound attribute map
     * ({@code user_id}, else {@code anonymous_id}). Fire-and-forget; delegates to
     * {@link Engine#track(String, String, Map)}.
     */
    public void track(String eventName, Map<String, Object> properties) {
        try {
            engine.track(unitId(), eventName, properties);
        } catch (Throwable t) {
            Log.error("Client.track threw: " + t);
        }
    }

    /**
     * Emit an exposure event for experiment {@code experimentName} for the bound
     * user. Re-evaluates the experiment with the bound attribute map (so
     * targeting gates and {@code bucketBy} resolve correctly) and POSTs one
     * exposure only when the user is enrolled. Delegates to
     * {@link Engine#logExposure(Map, String)}.
     */
    public void logExposure(String experimentName) {
        try {
            engine.logExposure(attributes, experimentName);
        } catch (Throwable t) {
            Log.error("Client.logExposure threw: " + t);
        }
    }

    /** Derive the unit id from the bound attributes: {@code user_id} else {@code anonymous_id}. */
    private String unitId() {
        Object uid = attributes.get("user_id");
        if (uid != null) return uid.toString();
        Object anon = attributes.get("anonymous_id");
        return anon == null ? null : anon.toString();
    }
}
