package ai.shipeasy;

import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Process-wide entry point for the Shipeasy server SDK — the one-time
 * {@code configure()} step that builds the single global {@link Engine} and
 * stores the optional {@code attributes} transform consumed by the lightweight,
 * user-bound {@link Client}.
 *
 * <p>The intended end-state usage is two calls:
 *
 * <pre>{@code
 * // Once, at startup (e.g. an @PostConstruct bean or main()):
 * Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
 *
 * // Per request / per user, anywhere downstream:
 * boolean on = new Client(user).getFlag("new_checkout");
 * }</pre>
 *
 * <p>With an {@code attributes} transform mapping your own user object to the
 * Shipeasy attribute map:
 *
 * <pre>{@code
 * Shipeasy.configure(Shipeasy.options(apiKey)
 *     .attributes((Object u) -> {
 *         MyUser my = (MyUser) u;
 *         return Map.of("user_id", my.id(), "plan", my.plan());
 *     }));
 *
 * boolean on = new Client(myUser).getFlag("new_checkout");
 * }</pre>
 *
 * <p>{@code configure} is <strong>first-config-wins</strong> idempotent: the
 * first call builds the global engine and triggers its one-shot fetch
 * (fire-and-forget, like {@link Engine#initOnce()}); subsequent calls return the
 * already-built engine and do not rebuild it. The returned {@link Engine} is the
 * full heavyweight handle — long-running servers may call {@link Engine#init()}
 * on it to also start the background poll.
 */
public final class Shipeasy {
    private static final Logger log = Logger.getLogger("shipeasy");

    /** Identity transform: the user object IS already the attribute map. */
    @SuppressWarnings("unchecked")
    static final Function<Object, Map<String, Object>> IDENTITY =
        u -> (Map<String, Object>) u;

    private static final Object LOCK = new Object();
    private static volatile Engine engine;
    private static volatile Function<Object, Map<String, Object>> attributes = IDENTITY;

    private Shipeasy() {}

    /**
     * Options for {@link #configure(Options)}. Mirrors the {@link Engine}
     * constructor knobs plus the {@code attributes} transform. Build with
     * {@link Shipeasy#options(String)} and chain the setters.
     */
    public static final class Options {
        final String apiKey;
        String baseUrl;
        String env = "prod";
        boolean disableTelemetry;
        Function<Object, Map<String, Object>> attributes = IDENTITY;

        private Options(String apiKey) {
            this.apiKey = apiKey;
        }

        /** Override the edge base URL (default {@code https://edge.shipeasy.dev}). */
        public Options baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Set the deployment env reported in usage telemetry and {@code see()} events. */
        public Options env(String env) {
            this.env = env;
            return this;
        }

        /** Turn off per-evaluation usage beacons (ON by default). */
        public Options disableTelemetry(boolean disableTelemetry) {
            this.disableTelemetry = disableTelemetry;
            return this;
        }

        /**
         * Transform from your own user object (any shape) to the Shipeasy
         * attribute map ({@code {"user_id": ..., "anonymous_id": ..., <attrs>}}).
         * Runs once, in the {@link Client} constructor. Default = identity (the
         * user object is assumed to already be the attribute map).
         */
        public Options attributes(Function<Object, Map<String, Object>> attributes) {
            this.attributes = attributes == null ? IDENTITY : attributes;
            return this;
        }
    }

    /** Start building {@link Options} for {@code apiKey}. */
    public static Options options(String apiKey) {
        return new Options(apiKey);
    }

    /**
     * Configure the global engine with {@code apiKey} and the identity attributes
     * transform. Convenience for {@code configure(options(apiKey))}.
     */
    public static Engine configure(String apiKey) {
        return configure(options(apiKey));
    }

    /**
     * Configure the global engine and {@code attributes} transform from
     * {@code opts}. First-config-wins: builds {@link Engine} once, stores it (and
     * registers it as the default {@code see()} engine via the engine
     * constructor), kicks off the one-shot fetch fire-and-forget, and returns it.
     * Subsequent calls return the existing engine without rebuilding.
     */
    public static Engine configure(Options opts) {
        Engine existing = engine;
        if (existing != null) return existing;
        synchronized (LOCK) {
            if (engine != null) return engine;
            Engine e = new Engine(opts.apiKey, opts.baseUrl, opts.env, opts.disableTelemetry);
            attributes = opts.attributes;
            engine = e;
            // One-shot fetch, fire-and-forget, so new Client(user).getFlag(...)
            // resolves against real rules without an explicit init call.
            Thread t = new Thread(() -> {
                try {
                    e.initOnce();
                } catch (Exception ex) {
                    log.warning("Shipeasy.configure initial fetch failed: " + ex.getMessage());
                }
            }, "shipeasy-configure-init");
            t.setDaemon(true);
            t.start();
            return e;
        }
    }

    /**
     * The global engine built by {@link #configure}, or {@code null} if configure
     * has not been called. {@link Client} uses this; it throws when {@code null}.
     */
    public static Engine engine() {
        return engine;
    }

    /** The configured attributes transform (identity until {@code configure} sets one). */
    static Function<Object, Map<String, Object>> attributesFn() {
        return attributes;
    }

    /**
     * Reset the global engine and attributes transform. Test-only seam — closes
     * the existing engine if present. Package-private.
     */
    static void resetForTest() {
        synchronized (LOCK) {
            if (engine != null) engine.close();
            engine = null;
            attributes = IDENTITY;
        }
    }

    /**
     * Install a pre-built engine as the global engine (e.g. an
     * {@link Engine#forTesting()} / {@link Engine#fromSnapshot} engine) with an
     * optional attributes transform, without performing any network fetch.
     * Test-only seam. Package-private.
     */
    static void useEngineForTest(Engine e, Function<Object, Map<String, Object>> attrs) {
        synchronized (LOCK) {
            engine = e;
            attributes = attrs == null ? IDENTITY : attrs;
        }
    }
}
