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
        boolean poll;
        java.util.List<String> privateAttributes = java.util.List.of();
        StickyBucketStore stickyStore;
        Function<Object, Map<String, Object>> attributes = IDENTITY;

        private Options(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * Long-running server: start the background poll internally (initial fetch
         * + periodic refresh) so flags stay fresh without a redeploy. Default
         * {@code false} (a one-shot fire-and-forget fetch). Either way you never
         * call {@link Engine#init()} yourself — {@code configure} owns the lifecycle.
         */
        public Options poll(boolean poll) {
            this.poll = poll;
            return this;
        }

        /** Attribute keys usable for targeting but stripped from outbound events. */
        public Options privateAttributes(java.util.List<String> attrs) {
            this.privateAttributes = attrs == null ? java.util.List.of() : java.util.List.copyOf(attrs);
            return this;
        }

        /** Pluggable sticky-bucketing store (absent ⇒ deterministic). */
        public Options stickyStore(StickyBucketStore store) {
            this.stickyStore = store;
            return this;
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
            e.privateAttributes(opts.privateAttributes).stickyStore(opts.stickyStore);
            attributes = opts.attributes;
            engine = e;
            // Fetch lifecycle owned by configure (the docs never tell a user to
            // call init() themselves): poll → initial fetch + background refresh;
            // default → one-shot fire-and-forget fetch.
            final boolean poll = opts.poll;
            Thread t = new Thread(() -> {
                try {
                    if (poll) {
                        e.init();
                    } else {
                        e.initOnce();
                    }
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

    // ---- Doc-23 configure() family + package-level helpers ----
    //
    // The documented surface is exactly configure() (+ these test/offline
    // siblings) and the bound Client(user); the heavyweight Engine stays public
    // but undocumented. These statics let users avoid naming the Engine in tests,
    // overrides, change listeners, and SSR tags.

    /** A forced experiment enrolment seed: {@code Variant.of(group, params)}. */
    public static final class Variant {
        final String group;
        final Object params;

        private Variant(String group, Object params) {
            this.group = group;
            this.params = params;
        }

        public static Variant of(String group, Object params) {
            return new Variant(group, params);
        }
    }

    /** Seeds for {@link #configureForTesting(TestOptions)} (and its offline sibling). */
    public static class TestOptions {
        Function<Object, Map<String, Object>> attributes = IDENTITY;
        Map<String, Boolean> flags = Map.of();
        Map<String, Object> configs = Map.of();
        Map<String, Variant> experiments = Map.of();

        /** Same transform as {@link Options#attributes} (default identity). */
        public TestOptions attributes(Function<Object, Map<String, Object>> a) {
            this.attributes = a == null ? IDENTITY : a;
            return this;
        }

        /** Forced {@code getFlag} results: {@code name -> bool}. */
        public TestOptions flags(Map<String, Boolean> f) {
            this.flags = f == null ? Map.of() : f;
            return this;
        }

        /** Forced {@code getConfig} results: {@code name -> value}. */
        public TestOptions configs(Map<String, Object> c) {
            this.configs = c == null ? Map.of() : c;
            return this;
        }

        /** Forced enrolments: {@code name -> Variant.of(group, params)}. */
        public TestOptions experiments(Map<String, Variant> e) {
            this.experiments = e == null ? Map.of() : e;
            return this;
        }
    }

    public static TestOptions testOptions() {
        return new TestOptions();
    }

    /** Offline source + seeds. Provide exactly one of {@code path} / {@code snapshot}. */
    public static final class OfflineOptions extends TestOptions {
        String path;
        Map<String, Object> snapshot;

        /** A JSON file {@code {"flags": ..., "experiments": ...}}. */
        public OfflineOptions path(String p) {
            this.path = p;
            return this;
        }

        /** In-memory {@code {"flags": <body>, "experiments": <body>}}. */
        public OfflineOptions snapshot(Map<String, Object> s) {
            this.snapshot = s;
            return this;
        }

        @Override public OfflineOptions attributes(Function<Object, Map<String, Object>> a) {
            super.attributes(a);
            return this;
        }

        @Override public OfflineOptions flags(Map<String, Boolean> f) {
            super.flags(f);
            return this;
        }

        @Override public OfflineOptions configs(Map<String, Object> c) {
            super.configs(c);
            return this;
        }

        @Override public OfflineOptions experiments(Map<String, Variant> e) {
            super.experiments(e);
            return this;
        }
    }

    public static OfflineOptions offlineOptions() {
        return new OfflineOptions();
    }

    /**
     * Configure Shipeasy in <strong>test mode</strong> — a drop-in sibling of
     * {@link #configure} with no network, ever (no api key needed). Seed the
     * values your code under test should see, then read them through the ordinary
     * {@code new Client(user)}. REPLACES any previously-configured engine, so tests
     * can reconfigure freely.
     */
    public static Engine configureForTesting() {
        return configureForTesting(new TestOptions());
    }

    public static Engine configureForTesting(TestOptions o) {
        Engine e = Engine.forTesting();
        applyOverrides(e, o);
        installReplace(e, o.attributes);
        return e;
    }

    /**
     * Configure Shipeasy <strong>offline</strong> — evaluate the REAL rules from an
     * in-memory snapshot or a JSON file, with no network. Optional flag/config/
     * experiment overrides layer on top. REPLACES any previously-configured engine.
     */
    @SuppressWarnings("unchecked")
    public static Engine configureForOffline(OfflineOptions o) throws java.io.IOException {
        Engine e;
        if (o.path != null) {
            e = Engine.fromFile(o.path);
        } else if (o.snapshot != null) {
            Object flags = o.snapshot.get("flags");
            Object exps = o.snapshot.get("experiments");
            e = Engine.fromSnapshot(
                flags instanceof Map ? (Map<String, Object>) flags : Map.of(),
                exps instanceof Map ? (Map<String, Object>) exps : Map.of());
        } else {
            throw new IllegalArgumentException(
                "configureForOffline requires either a path(...) or a snapshot(...) source");
        }
        applyOverrides(e, o);
        installReplace(e, o.attributes);
        return e;
    }

    private static void applyOverrides(Engine e, TestOptions o) {
        o.flags.forEach((name, value) -> e.overrideFlag(name, value));
        o.configs.forEach(e::overrideConfig);
        o.experiments.forEach((name, v) -> e.overrideExperiment(name, v.group, v.params));
    }

    private static void installReplace(Engine e, Function<Object, Map<String, Object>> attrs) {
        synchronized (LOCK) {
            if (engine != null && engine != e) engine.close();
            engine = e;
            attributes = attrs == null ? IDENTITY : attrs;
        }
    }

    private static Engine requireEngine(String fn) {
        Engine e = engine;
        if (e == null) {
            throw new IllegalStateException(
                "Shipeasy." + fn + "(...) called before Shipeasy.configure(...) (or a configureFor* sibling)");
        }
        return e;
    }

    /**
     * Force {@code getFlag(name)} to {@code value} on the spot, for the current
     * configuration — a quick in-test override layered on top of whatever
     * {@link #configureForTesting}/{@link #configureForOffline} (or {@link #configure})
     * set up. Wins over the blob until {@link #clearOverrides()}.
     */
    public static void overrideFlag(String name, boolean value) {
        requireEngine("overrideFlag").overrideFlag(name, value);
    }

    /** Force {@code getConfig(name)} to {@code value} on the spot (see {@link #overrideFlag}). */
    public static void overrideConfig(String name, Object value) {
        requireEngine("overrideConfig").overrideConfig(name, value);
    }

    /** Force {@code getExperiment(name)} to enrol in {@code group}/{@code params} (see {@link #overrideFlag}). */
    public static void overrideExperiment(String name, String group, Object params) {
        requireEngine("overrideExperiment").overrideExperiment(name, group, params);
    }

    /**
     * Drop every on-the-spot flag/config/experiment override — INCLUDING the seed
     * from {@link #configureForTesting} (test mode has no blob beneath). Under
     * {@link #configureForOffline} evaluations revert to the snapshot.
     */
    public static void clearOverrides() {
        requireEngine("clearOverrides").clearOverrides();
    }

    /**
     * Register a listener fired after a background poll fetches NEW data. Returns
     * an unsubscribe {@link Runnable}. Requires {@code configure(options(key).poll(true))}.
     */
    public static Runnable onChange(Runnable listener) {
        return requireEngine("onChange").onChange(listener);
    }

    /** SSR bootstrap {@code <script>} tag for a request, via the configured global engine. */
    public static String bootstrapScriptTag(Map<String, Object> user) {
        return requireEngine("bootstrapScriptTag").bootstrapScriptTag(user);
    }

    /** SSR bootstrap {@code <script>} tag (full form), via the configured global engine. */
    public static String bootstrapScriptTag(Map<String, Object> user, String anonId, String i18nProfile, String baseUrl) {
        return requireEngine("bootstrapScriptTag").bootstrapScriptTag(user, anonId, i18nProfile, baseUrl);
    }

    /** i18n loader {@code <script>} tag (public client key), via the configured global engine. */
    public static String i18nScriptTag(String clientKey, String profile) {
        return requireEngine("i18nScriptTag").i18nScriptTag(clientKey, profile);
    }

    /** i18n loader {@code <script>} tag (with base URL), via the configured global engine. */
    public static String i18nScriptTag(String clientKey, String profile, String baseUrl) {
        return requireEngine("i18nScriptTag").i18nScriptTag(clientKey, profile, baseUrl);
    }
}
