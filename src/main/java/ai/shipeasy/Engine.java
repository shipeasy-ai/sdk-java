package ai.shipeasy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Engine implements AutoCloseable {
    private static final String DEFAULT_BASE_URL = "https://api.shipeasy.ai";
    /**
     * CDN origin serving the static loader scripts ({@code /sdk/bootstrap.js},
     * {@code /sdk/i18n/loader.js}) — distinct from the edge API the blobs are
     * fetched from.
     */
    private static final String DEFAULT_CDN_BASE = "https://cdn.shipeasy.ai";

    /** Single runtime source of the SDK version (used for {@code sdk_version}). */
    public static final String VERSION = "0.18.0";

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "shipeasy-poll"); t.setDaemon(true); return t; });
    private final Object lock = new Object();

    private volatile Map<String, Object> flagsBlob;
    private volatile Map<String, Object> expsBlob;
    private volatile String flagsEtag;
    private volatile String expsEtag;
    private volatile int pollIntervalSec = 30;
    private volatile boolean initialized = false;
    private ScheduledFuture<?> task;
    private final Telemetry telemetry;

    /**
     * Deployment env, tagged onto {@code see()} error events. Telemetry already
     * gets env via its own constructor; this stores it for the /collect error
     * path too.
     */
    private final String env;

    /**
     * Verbosity of the SDK's own diagnostic logging (network/decode/listener
     * failures, misuse warnings). Defaults to {@link LogLevel#WARN}. The resolved
     * level is also mirrored into the static {@link Log} facade so the
     * {@code See}/{@code Shipeasy} static log sites gate the same way.
     */
    private final LogLevel logLevel;

    /** Per-process spam guard for {@code see()} sends. */
    private final SeeLimiter seeLimiter = new SeeLimiter();

    /**
     * When {@code true} the client performs zero network I/O — {@link #init()},
     * {@link #initOnce()} and {@link #track(String, String, Map)} are no-ops and
     * telemetry is disabled. Set only by {@link #forTesting()}. Evaluation reads
     * the {@code override*} maps below and never touches a fetched blob.
     */
    private final boolean localMode;

    /**
     * The master network switch, resolved from {@code isNetworkEnabled} (explicit
     * value wins) else the environment-derived default (ON in production, OFF
     * everywhere else — see {@link Env#isProductionEnv}). {@code localMode} always
     * forces this off. When {@code true} the SDK performs zero network I/O exactly
     * like {@code localMode}: {@link #init()}/{@link #initOnce()}/{@link #track}/
     * {@code see()}/exposure are no-ops, telemetry is off, and evaluation reads the
     * {@code override*} maps and any seeded blob only. It is the convenience inverse
     * of "network enabled" that every egress gate keys on.
     */
    private final boolean offline;

    /**
     * Attribute names usable for targeting but never persisted in analytics
     * (LD/Statsig {@code privateAttributes}). The server evaluates locally, so
     * private attrs never leave for evaluation at all; the only egress is
     * {@code /collect}, and these keys are stripped from every outbound
     * {@link #track} payload before it is POSTed.
     */
    private volatile List<String> privateAttributes;

    /**
     * Optional sticky-bucketing store (doc 20 §2). When set, {@link #assignUniverse}
     * locks a unit to its first-assigned variant. {@code null} ⇒ deterministic
     * (fully backward compatible).
     */
    private volatile StickyBucketStore stickyStore;

    // Local overrides (Statsig-style). Thread-safe to match the volatile-blob
    // concurrency model; an override, when present, wins over any fetched value.
    private final Map<String, Boolean> flagOverrides = new ConcurrentHashMap<>();
    private final Map<String, Object> configOverrides = new ConcurrentHashMap<>();
    private final Map<String, ExperimentResult> experimentOverrides = new ConcurrentHashMap<>();

    // Change listeners fired after a background poll applies NEW (200, not 304)
    // data. Thread-safe to match the volatile-blob concurrency model.
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    // Per-process exposure dedup: `uid:experiment:group` keys already POSTed, so
    // repeated assign() calls in one server don't spam /collect. Bounded (cleared
    // at ~5000). Thread-safe to match the volatile-blob concurrency model.
    private final java.util.Set<String> exposureSeen =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public Engine(String apiKey) { this(apiKey, null, "prod", false); }

    public Engine(String apiKey, String baseUrl) { this(apiKey, baseUrl, "prod", false); }

    /**
     * @param env              published env reported in usage telemetry ("prod" default)
     * @param disableTelemetry turn off per-evaluation usage beacons. {@code true}
     *                         forces telemetry off; {@code false} defers to the
     *                         environment-derived default (ON in production, OFF
     *                         elsewhere — see {@link Env#isProductionEnv}).
     */
    public Engine(String apiKey, String baseUrl, String env, boolean disableTelemetry) {
        this(apiKey, baseUrl, env, disableTelemetry ? Boolean.FALSE : null, null, false, LogLevel.WARN);
    }

    /**
     * As {@link #Engine(String, String, String, boolean)} but with an explicit
     * SDK {@link LogLevel}. Package-private — used by {@link Shipeasy#configure}
     * to thread {@link Shipeasy.Options#logLevel} through.
     */
    Engine(String apiKey, String baseUrl, String env, boolean disableTelemetry, LogLevel logLevel) {
        this(apiKey, baseUrl, env, disableTelemetry ? Boolean.FALSE : null, null, false, logLevel);
    }

    /**
     * Full package-private constructor with the environment-derived egress knobs.
     * {@code isTrackingEnabled} and {@code isNetworkEnabled} are {@link Boolean}
     * objects so {@code null} means "unset ⇒ use the env-derived default"; a
     * non-null value always wins. Used by {@link Shipeasy#configure} to thread
     * {@link Shipeasy.Options#isTrackingEnabled}/{@code isNetworkEnabled} through.
     *
     * @param isTrackingEnabled per-evaluation usage telemetry; {@code null} ⇒ prod-on
     * @param isNetworkEnabled  master egress switch; {@code null} ⇒ prod-on
     */
    Engine(String apiKey, String baseUrl, String env,
           Boolean isTrackingEnabled, Boolean isNetworkEnabled, LogLevel logLevel) {
        this(apiKey, baseUrl, env, isTrackingEnabled, isNetworkEnabled, false, logLevel);
    }

    private Engine(String apiKey, String baseUrl, String env,
                   Boolean isTrackingEnabled, Boolean isNetworkEnabled,
                   boolean localMode, LogLevel logLevel) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl.replaceAll("/$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.localMode = localMode;
        this.env = env;
        this.logLevel = logLevel == null ? LogLevel.WARN : logLevel;
        // Store the resolved level statically so the See/Shipeasy static log
        // sites gate too (last constructed engine wins).
        Log.setLevel(this.logLevel);
        this.privateAttributes = List.of();

        // Environment-derived egress defaults. In production both switches default
        // ON; outside production both default OFF, so an app that embeds the SDK
        // never phones home from a dev machine or CI unless it opts in. An explicit
        // isNetworkEnabled/isTrackingEnabled always wins; localMode forces both off.
        boolean prod = Env.isProductionEnv(env);
        boolean networkEnabled = !localMode && (isNetworkEnabled != null ? isNetworkEnabled : prod);
        this.offline = !networkEnabled;
        // Telemetry: an explicit value wins, else default to prod-on; the master
        // network switch (offline) forces it off regardless (it IS network egress).
        boolean trackingEnabled = networkEnabled && (isTrackingEnabled != null ? isTrackingEnabled : prod);

        this.telemetry = new Telemetry(
            Telemetry.DEFAULT_TELEMETRY_URL, apiKey, "server", env, !trackingEnabled, this.http);
        // Both localMode and the offline (network-off) default make init a no-op,
        // so mark initialized so reads resolve from overrides / seeded blob / code
        // defaults instead of blocking on CLIENT_NOT_READY forever.
        if (localMode || this.offline) this.initialized = true;
        // Register as the default engine backing the package-level See.see()
        // functions (last constructed wins — the analog of TS's shipeasy({key})).
        See.setDefaultEngine(this);
        // Arm the internal self-monitoring channel (SDK-internal errors → Shipeasy's
        // OWN project). Default ON; forced OFF whenever the SDK is offline (test/
        // offline mode OR the env-derived network-off default — no network at all).
        // A later configure(...) with disableInternalErrorReporting(true) re-sets
        // this OFF via setInternalErrorReporting.
        InternalReport.setContext(VERSION, !this.offline);
    }

    /**
     * Opt out of (or back into) the internal self-monitoring channel that reports
     * SDK-internal errors to Shipeasy's own project. Wired from
     * {@link Shipeasy.Options#disableInternalErrorReporting}. Ignored (kept off)
     * in test/offline mode, which never touches the network. Returns {@code this}.
     */
    Engine setInternalErrorReporting(boolean enabled) {
        InternalReport.setContext(VERSION, enabled && !offline);
        return this;
    }

    /** A no-network (local/test-mode) engine: telemetry off, egress fully offline. */
    private static Engine newLocalMode() {
        return new Engine(null, null, "test", Boolean.FALSE, Boolean.FALSE, true, LogLevel.WARN);
    }

    /**
     * A no-network, immediately-usable client for tests. Telemetry is disabled,
     * {@link #init()}/{@link #initOnce()} never fetch, {@link #track} is a no-op,
     * and no API key is required. Seed values with {@link #overrideFlag},
     * {@link #overrideConfig} and {@link #overrideExperiment}.
     *
     * <pre>{@code
     * try (Engine c = Engine.forTesting()) {
     *     c.overrideFlag("new_checkout", true);
     *     assertTrue(c.getFlag("new_checkout", Map.of()));
     * }
     * }</pre>
     */
    public static Engine forTesting() {
        return newLocalMode();
    }

    /**
     * A no-network client backed by a JSON snapshot file. The file is the
     * shape {@code { "flags": <body of /sdk/flags>, "experiments": <body of
     * /sdk/experiments> }}. The client performs zero network I/O (like
     * {@link #forTesting()}): {@link #init()}/{@link #initOnce()}/{@link #track}
     * are no-ops and telemetry is off, but evaluations run the real eval against
     * the loaded blobs and {@code override*} values still apply on top.
     *
     * <pre>{@code
     * try (Engine c = Engine.fromFile("snapshot.json")) {
     *     boolean on = c.getFlag("new_checkout", Map.of("user_id", "u_123"));
     * }
     * }</pre>
     */
    public static Engine fromFile(String path) throws IOException {
        Engine c = newLocalMode();
        Map<String, Object> root = c.mapper.readValue(Files.readAllBytes(Path.of(path)), Map.class);
        c.loadSnapshot(root.get("flags"), root.get("experiments"));
        return c;
    }

    /**
     * A no-network client backed by in-memory snapshot blobs. {@code flags} is
     * the parsed body of {@code /sdk/flags} (with {@code gates}/{@code configs}
     * keys) and {@code experiments} the parsed body of {@code /sdk/experiments}
     * (with {@code experiments}/{@code universes} keys); either may be
     * {@code null}. Same no-network semantics as {@link #fromFile(String)}.
     */
    public static Engine fromSnapshot(Map<String, Object> flags, Map<String, Object> experiments) {
        Engine c = newLocalMode();
        c.loadSnapshot(flags, experiments);
        return c;
    }

    @SuppressWarnings("unchecked")
    private void loadSnapshot(Object flags, Object experiments) {
        synchronized (lock) {
            this.flagsBlob = flags instanceof Map ? (Map<String, Object>) flags : null;
            this.expsBlob = experiments instanceof Map ? (Map<String, Object>) experiments : null;
        }
        this.initialized = true;
    }

    /** Force {@code name} to {@code value} for {@link #getFlag}. */
    public void overrideFlag(String name, boolean value) {
        flagOverrides.put(name, value);
    }

    /** Force {@code name} to {@code value} for {@link #getConfig}. */
    public void overrideConfig(String name, Object value) {
        configOverrides.put(name, value);
    }

    /**
     * Force the experiment {@code name} to resolve to an in-experiment result
     * with the given {@code group} and {@code params}. Surfaces through
     * {@code universe(name).assign(user)} when {@code name} is an experiment in
     * that universe. An internal test/back-compat seam (still experiment-keyed).
     */
    public void overrideExperiment(String name, String group, Object params) {
        experimentOverrides.put(name, new ExperimentResult(true, group, params));
    }

    /** Remove every override previously set on this client. */
    public void clearOverrides() {
        flagOverrides.clear();
        configOverrides.clear();
        experimentOverrides.clear();
    }

    /**
     * Set the attribute names that are usable for targeting but must never be
     * persisted in analytics (LD/Statsig {@code privateAttributes}). These keys
     * are stripped from every outbound {@link #track} payload before it is
     * POSTed to {@code /collect}. Returns {@code this} for chaining.
     */
    public Engine privateAttributes(List<String> attrs) {
        this.privateAttributes = attrs == null ? List.of() : List.copyOf(attrs);
        return this;
    }

    /**
     * Supply a sticky-bucketing store (doc 20 §2). When set, {@link #assignUniverse}
     * locks a unit to its first-assigned variant — changing allocation % or group
     * weights won't re-bucket enrolled units (changing the experiment salt is the
     * reshuffle lever). Absent ⇒ deterministic. Returns {@code this} for chaining.
     */
    public Engine stickyStore(StickyBucketStore store) {
        this.stickyStore = store;
        return this;
    }

    public void init() throws IOException, InterruptedException {
        if (offline) return;
        fetchAll();
        initialized = true;
        scheduleNext();
    }

    public void initOnce() throws IOException, InterruptedException {
        if (offline) return;
        if (initialized) return;
        fetchAll();
        initialized = true;
    }

    @Override public void close() {
        scheduler.shutdownNow();
    }

    public boolean getFlag(String name, Map<String, Object> user) {
        return getFlagDetail(name, user).value();
    }

    /**
     * Evaluate {@code name} and return the resolved value plus the
     * {@code reason} it resolved that way (a {@link FlagDetail} reason
     * constant). Emits the "gate" usage beacon exactly once (never on an
     * {@code OVERRIDE}). The canonical {@link Eval#evalGate} is not modified;
     * the {@code OFF} vs {@code DEFAULT} distinction is computed here at the
     * boundary by reading the same {@code enabled}/{@code killswitch} fields
     * {@code evalGate} reads.
     *
     * <p><b>Fail-safe:</b> a runtime read never throws into the caller — any
     * unexpected error is caught, logged at error level, and a safe
     * {@link FlagDetail#CLIENT_NOT_READY} ({@code value=false}) is returned.
     */
    public FlagDetail getFlagDetail(String name, Map<String, Object> user) {
        try {
            return getFlagDetailImpl(name, user);
        } catch (Throwable t) {
            Log.error("getFlagDetail threw: " + t);
            return new FlagDetail(false, FlagDetail.CLIENT_NOT_READY);
        }
    }

    @SuppressWarnings("unchecked")
    private FlagDetail getFlagDetailImpl(String name, Map<String, Object> user) {
        Boolean override = flagOverrides.get(name);
        if (override != null) return new FlagDetail(override, FlagDetail.OVERRIDE);

        telemetry.emit("gate", name);

        Map<String, Object> blob = flagsBlob;
        if (blob == null) return new FlagDetail(false, FlagDetail.CLIENT_NOT_READY);

        Map<String, Object> gates = (Map<String, Object>) blob.get("gates");
        Map<String, Object> gate = gates == null ? null : (Map<String, Object>) gates.get(name);
        if (gate == null) return new FlagDetail(false, FlagDetail.FLAG_NOT_FOUND);

        // evalGate reads gate.killswitch and gate.enabled; a disabled (or
        // killswitched) gate is OFF, not DEFAULT.
        if (Eval.enabled(gate.get("killswitch")) || !Eval.enabled(gate.get("enabled"))) {
            return new FlagDetail(false, FlagDetail.OFF);
        }

        boolean value = Eval.evalGate(gate, withAnonId(user));
        return new FlagDetail(value, value ? FlagDetail.RULE_MATCH : FlagDetail.DEFAULT);
    }

    /**
     * As {@link #getFlag(String, Map)} but returns {@code defaultValue} only
     * when the flag <em>cannot</em> be evaluated (the client is not initialized
     * or the flag is not in the blob) — never when it evaluates to {@code false}.
     */
    public boolean getFlag(String name, Map<String, Object> user, boolean defaultValue) {
        FlagDetail d = getFlagDetail(name, user);
        String r = d.reason();
        if (FlagDetail.CLIENT_NOT_READY.equals(r) || FlagDetail.FLAG_NOT_FOUND.equals(r)) {
            return defaultValue;
        }
        return d.value();
    }

    /**
     * Default {@code anonymous_id} to the request's {@code __se_anon_id}
     * (resolved by {@link AnonIdFilter}) when the caller passed no explicit
     * unit. A caller-supplied {@code user_id}/{@code anonymous_id} always wins;
     * a no-op when no filter ran.
     */
    private static Map<String, Object> withAnonId(Map<String, Object> user) {
        Object uid = user.get("user_id");
        Object anon = user.get("anonymous_id");
        boolean hasUnit = (uid != null && !"".equals(uid)) || (anon != null && !"".equals(anon));
        String current = AnonId.current();
        if (hasUnit || current == null) return user;
        Map<String, Object> copy = new HashMap<>(user);
        copy.put("anonymous_id", current);
        return copy;
    }

    /**
     * <b>Fail-safe:</b> never throws into the caller — an unexpected error is
     * caught, logged at error level, and {@code null} is returned.
     */
    public Object getConfig(String name) {
        try {
            return getConfigImpl(name);
        } catch (Throwable t) {
            Log.error("getConfig threw: " + t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Object getConfigImpl(String name) {
        Object override = configOverrides.get(name);
        if (override != null) return override;
        telemetry.emit("config", name);
        Map<String, Object> blob = flagsBlob;
        if (blob == null) return null;
        Map<String, Object> configs = (Map<String, Object>) blob.get("configs");
        if (configs == null) return null;
        Map<String, Object> entry = (Map<String, Object>) configs.get(name);
        return entry == null ? null : entry.get("value");
    }

    /**
     * As {@link #getConfig(String)} but returns {@code defaultValue} when the
     * config key is absent (no override and not present in the blob).
     * <b>Fail-safe:</b> never throws — falls back to {@code defaultValue}.
     */
    public Object getConfig(String name, Object defaultValue) {
        try {
            Object value = getConfig(name);
            return value == null ? defaultValue : value;
        } catch (Throwable t) {
            Log.error("getConfig(default) threw: " + t);
            return defaultValue;
        }
    }

    /**
     * Read a killswitch from the flags blob. Without {@code switchKey}, returns
     * {@code true} when the whole killswitch is killed ({@code killed} truthy).
     * With {@code switchKey}, returns {@code true} when that specific named
     * per-key switch is on. Unknown killswitches / switches return {@code false}.
     * Mirrors {@code @shipeasy/sdk}'s {@code getKillswitch}.
     */
    public boolean getKillswitch(String name) {
        return getKillswitch(name, null);
    }

    /**
     * @see #getKillswitch(String)
     * <b>Fail-safe:</b> never throws — an unexpected error is logged at error
     * level and {@code false} is returned.
     */
    public boolean getKillswitch(String name, String switchKey) {
        try {
            return getKillswitchImpl(name, switchKey);
        } catch (Throwable t) {
            Log.error("getKillswitch threw: " + t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean getKillswitchImpl(String name, String switchKey) {
        telemetry.emit("ks", name);
        Map<String, Object> blob = flagsBlob;
        if (blob == null) return false;
        Map<String, Object> all = (Map<String, Object>) blob.get("killswitches");
        if (all == null) return false;
        Map<String, Object> ks = (Map<String, Object>) all.get(name);
        if (ks == null) return false;
        if (switchKey == null) return Eval.enabled(ks.get("killed"));
        // Named-switch semantics (cross-SDK contract): a configured switch key
        // wins; an UNCONFIGURED key falls back to the kill switch's top-level
        // value (so getKillswitch(name, variable) is safe before any per-key
        // override is published).
        Map<String, Object> switches = (Map<String, Object>) ks.get("switches");
        if (switches != null && switches.containsKey(switchKey)) {
            return Eval.enabled(switches.get(switchKey));
        }
        return Eval.enabled(ks.get("killed"));
    }

    /**
     * Register a {@code listener} invoked after a background poll applies NEW
     * data (an HTTP 200, not a 304). Returns a {@link Runnable} that
     * unsubscribes the listener when run. Never fires in local/test/snapshot
     * mode (those perform no polling).
     */
    public Runnable onChange(Runnable listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    private void fireChangeListeners() {
        for (Runnable l : changeListeners) {
            try {
                l.run();
            } catch (Exception e) {
                Log.warn("onChange listener threw: " + e.getMessage());
            }
        }
    }

    /**
     * Evaluate one experiment by name for {@code user} — override → full classify
     * pipeline (targeting → universe holdout → holdout gate → sticky → allocation
     * → group), merging the universe defaults under the assigned variant.
     * Internal: the public surface is {@code universe(name).assign(user)}. Reused
     * by the SSR {@link #evaluate} bootstrap (keyed by experiment name) and by
     * {@link #assignUniverse}.
     */
    @SuppressWarnings("unchecked")
    private Eval.ExpStanding evalExperiment(String name, Map<String, Object> exp, Map<String, Object> user) {
        Map<String, Object> exps = expsBlob;
        String universeName = exp == null ? null : (String) exp.get("universe");
        Map<String, Object> paramDefaults = paramDefaultsFor(exps, universeName);

        ExperimentResult override = experimentOverrides.get(name);
        if (override != null) {
            return Eval.ExpStanding.group(override.group, Eval.mergeParams(paramDefaults, override.params));
        }
        if (flagsBlob == null || exps == null || exp == null) return Eval.ExpStanding.OUT;
        if (!"running".equals(exp.get("status"))) return Eval.ExpStanding.OUT;

        List<Number> holdoutRange = Eval.holdoutRangeFor(exp, exps);
        return Eval.classifyExperiment(exp, flagsBlob, user, holdoutRange, paramDefaults, stickyStore, name);
    }

    /** Universe param defaults out of the exps blob, or {@code null}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramDefaultsFor(Map<String, Object> exps, String universeName) {
        if (exps == null || universeName == null) return null;
        Map<String, Object> universes = (Map<String, Object>) exps.get("universes");
        Map<String, Object> universe = universes == null ? null : (Map<String, Object>) universes.get(universeName);
        return universe == null ? null : Eval.paramDefaultsFromSchema(universe.get("param_schema"));
    }

    /**
     * Assign {@code user} within {@code universeName}. A universe is a
     * mutual-exclusion pool, so a unit lands in <strong>at most one</strong>
     * experiment; the returned {@link Assignment} exposes the variant + resolved
     * params and auto-logs a single exposure when enrolled. An un-enrolled unit
     * still resolves {@code get()} to the universe defaults. Never throws. This is
     * the sole experiment read path (there is no {@code getExperiment} — a caller
     * asks a universe, not an experiment).
     */
    public Assignment assignUniverse(String universeName, Map<String, Object> user) {
        try {
            return assignUniverseImpl(universeName, user);
        } catch (Throwable t) {
            Log.error("assignUniverse threw: " + t);
            return new Assignment(null, null, Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private Assignment assignUniverseImpl(String universeName, Map<String, Object> user) {
        telemetry.emit("experiment", universeName);
        Map<String, Object> u = withAnonId(user == null ? Map.of() : user);
        Map<String, Object> exps = expsBlob;
        Map<String, Object> paramDefaults = paramDefaultsFor(exps, universeName);
        Map<String, Object> notEnrolled = paramDefaults == null ? Map.of() : paramDefaults;
        if (exps == null) return new Assignment(null, null, notEnrolled);

        Map<String, Object> all = (Map<String, Object>) exps.get("experiments");
        if (all == null) return new Assignment(null, null, notEnrolled);

        // Candidate running experiments in this universe. Deterministic order:
        // pool-slice offset asc (slices are disjoint so <=1 matches under pooling),
        // then name. A universe-held-out or unallocated unit falls through to the
        // defaults-only handle.
        java.util.List<Map.Entry<String, Object>> candidates = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            Map<String, Object> exp = (Map<String, Object>) e.getValue();
            if (exp != null && universeName.equals(exp.get("universe")) && "running".equals(exp.get("status"))) {
                candidates.add(e);
            }
        }
        candidates.sort((a, b) -> {
            int oa = ((Number) ((Map<String, Object>) a.getValue()).getOrDefault("poolOffsetBp", 0)).intValue();
            int ob = ((Number) ((Map<String, Object>) b.getValue()).getOrDefault("poolOffsetBp", 0)).intValue();
            return oa != ob ? Integer.compare(oa, ob) : a.getKey().compareTo(b.getKey());
        });

        for (Map.Entry<String, Object> e : candidates) {
            String name = e.getKey();
            Map<String, Object> exp = (Map<String, Object>) e.getValue();
            Eval.ExpStanding s = evalExperiment(name, exp, u);
            if (s.state == Eval.State.GROUP) {
                final String expName = name;
                final String group = s.group;
                // On-read exposure (spec step 7): defer the single exposure to
                // the first param read via the callback, instead of firing it
                // here at assign time.
                return new Assignment(
                        expName,
                        group,
                        s.params == null ? Map.of() : s.params,
                        () -> postExposure(u, expName, group));
            }
            // "holdout"/"out": try the next candidate — under pooling only one
            // slice can match, so the loop naturally lands on the winner.
        }
        return new Assignment(null, null, notEnrolled);
    }

    /**
     * The universe-first experiment read entry point:
     * {@code engine.universe("checkout").assign(user)}. Returns a reusable handle
     * bound to one universe; {@code assign(user)} picks the at-most-one experiment
     * the unit is pooled into and auto-logs a single exposure. See {@link #assignUniverse}.
     */
    public UniverseHandle universe(String name) {
        return new UniverseHandle(this, name);
    }

    /**
     * Batch-evaluate every loaded gate, config and experiment for {@code user}
     * into a bootstrap payload ({@code {flags, configs, experiments,
     * killswitches}}) keyed to match the browser SDK's
     * {@code window.__SE_BOOTSTRAP} shape. Local overrides win. Killswitches are
     * folded into per-gate evaluation (a killed gate reads {@code false}) AND
     * emitted in the standalone {@code killswitches} map ({@code boolean} when a
     * killswitch has no named switches, else a per-switch {@code {key: boolean}}
     * map) to match the browser bootstrap shape. No telemetry (a batch evaluate
     * is not a per-flag exposure).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(Map<String, Object> user) {
        Map<String, Object> u = withAnonId(user);
        Map<String, Object> flags = flagsBlob;
        Map<String, Object> exps = expsBlob;

        Map<String, Object> outFlags = new LinkedHashMap<>();
        Map<String, Object> outConfigs = new LinkedHashMap<>();
        Map<String, Object> outExps = new LinkedHashMap<>();

        if (flags != null) {
            Map<String, Object> gates = (Map<String, Object>) flags.get("gates");
            if (gates != null) {
                for (Map.Entry<String, Object> e : gates.entrySet()) {
                    Boolean ov = flagOverrides.get(e.getKey());
                    outFlags.put(e.getKey(),
                        ov != null ? ov : Eval.evalGate((Map<String, Object>) e.getValue(), u));
                }
            }
            Map<String, Object> configs = (Map<String, Object>) flags.get("configs");
            if (configs != null) {
                for (Map.Entry<String, Object> e : configs.entrySet()) {
                    if (configOverrides.containsKey(e.getKey())) {
                        outConfigs.put(e.getKey(), configOverrides.get(e.getKey()));
                        continue;
                    }
                    Map<String, Object> entry = (Map<String, Object>) e.getValue();
                    outConfigs.put(e.getKey(), entry == null ? null : entry.get("value"));
                }
            }
        }
        // Per-universe param defaults so an SSR client can resolve
        // `universe(name).get()` to a default even when the unit is not enrolled.
        Map<String, Object> outUniverses = new LinkedHashMap<>();
        if (exps != null) {
            Map<String, Object> all = (Map<String, Object>) exps.get("experiments");
            if (all != null) {
                for (Map.Entry<String, Object> e : all.entrySet()) {
                    Map<String, Object> expDef = (Map<String, Object>) e.getValue();
                    String uniName = expDef == null ? null : (String) expDef.get("universe");
                    if (uniName != null && !outUniverses.containsKey(uniName)) {
                        Map<String, Object> pd = paramDefaultsFor(exps, uniName);
                        Map<String, Object> defaults = new LinkedHashMap<>();
                        if (pd != null) defaults.putAll(pd);
                        outUniverses.put(uniName, Map.of("defaults", defaults));
                    }
                    Eval.ExpStanding s = evalExperiment(e.getKey(), expDef, u);
                    Map<String, Object> exp = new LinkedHashMap<>();
                    boolean inExp = s.state == Eval.State.GROUP;
                    exp.put("inExperiment", inExp);
                    exp.put("group", inExp ? s.group : "control");
                    exp.put("params", inExp && s.params != null ? s.params : Map.of());
                    if (uniName != null) exp.put("universe", uniName);
                    outExps.put(e.getKey(), exp);
                }
            }
        }

        Map<String, Object> outKs = new LinkedHashMap<>();
        if (flags != null) {
            Map<String, Object> all = (Map<String, Object>) flags.get("killswitches");
            if (all != null) {
                for (Map.Entry<String, Object> e : all.entrySet()) {
                    Map<String, Object> ks = (Map<String, Object>) e.getValue();
                    if (ks == null) continue;
                    Map<String, Object> switches = (Map<String, Object>) ks.get("switches");
                    if (switches != null && !switches.isEmpty()) {
                        Map<String, Object> perSwitch = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> s : switches.entrySet()) {
                            perSwitch.put(s.getKey(), Eval.enabled(s.getValue()));
                        }
                        outKs.put(e.getKey(), perSwitch);
                    } else {
                        outKs.put(e.getKey(), Eval.enabled(ks.get("killed")));
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flags", outFlags);
        out.put("configs", outConfigs);
        out.put("experiments", outExps);
        out.put("killswitches", outKs);
        out.put("universes", outUniverses);
        return out;
    }

    /**
     * Return the cross-platform SSR bootstrap {@code <script>} tag for a request:
     * {@code se-bootstrap.js} reads its {@code data-*} attributes and hydrates
     * {@code window.__SE_BOOTSTRAP} (and writes the anon cookie). No SDK key is
     * embedded — the server key must never reach the browser.
     *
     * <p>When {@code user} carries an identified attribute (anything other than
     * {@code anonymous_id}), the tag also emits {@code data-user} — the
     * server-known identity the browser SDK adopts on first paint, killing the
     * anon to identified flip. An anonymous request emits no {@code data-user}.
     */
    public String bootstrapScriptTag(Map<String, Object> user, String anonId, String i18nProfile, String baseUrl) {
        Map<String, Object> payload = evaluate(user);
        String base = cdnBase(baseUrl);
        String profile = (i18nProfile == null || i18nProfile.isEmpty()) ? "en:prod" : i18nProfile;
        StringBuilder sb = new StringBuilder();
        sb.append("<script src=\"").append(escapeAttr(base + "/sdk/bootstrap.js")).append("\" ");
        sb.append("data-se-bootstrap ");
        sb.append(attr("data-flags", json(payload.get("flags")))).append(' ');
        sb.append(attr("data-configs", json(payload.get("configs")))).append(' ');
        sb.append(attr("data-experiments", json(payload.get("experiments")))).append(' ');
        sb.append(attr("data-killswitches", json(payload.get("killswitches")))).append(' ');
        sb.append(attr("data-i18n-profile", profile)).append(' ');
        sb.append(attr("data-api-url", base));
        if (anonId != null && !anonId.isEmpty()) sb.append(' ').append(attr("data-anon-id", anonId));
        String dataUser = identityAttrs(user);
        if (dataUser != null) sb.append(' ').append(attr("data-user", dataUser));
        sb.append("></script>");
        return sb.toString();
    }

    /** {@link #bootstrapScriptTag(Map, String, String, String)} with defaults. */
    public String bootstrapScriptTag(Map<String, Object> user) {
        return bootstrapScriptTag(user, null, "en:prod", null);
    }

    /** {@link #bootstrapScriptTag(Map, String, String, String)} with an anon id. */
    public String bootstrapScriptTag(Map<String, Object> user, String anonId) {
        return bootstrapScriptTag(user, anonId, "en:prod", null);
    }

    /**
     * Return the i18n loader {@code <script>} tag. The loader fetches
     * translations for the profile using the PUBLIC client key (safe to embed
     * in HTML).
     */
    public String i18nScriptTag(String clientKey, String profile, String baseUrl) {
        String base = cdnBase(baseUrl);
        String p = (profile == null || profile.isEmpty()) ? "en:prod" : profile;
        return "<script src=\"" + escapeAttr(base + "/sdk/i18n/loader.js") + "\" "
            + attr("data-key", clientKey) + " " + attr("data-profile", p) + "></script>";
    }

    /** {@link #i18nScriptTag(String, String, String)} with the default CDN base. */
    public String i18nScriptTag(String clientKey, String profile) {
        return i18nScriptTag(clientKey, profile, null);
    }

    private static String cdnBase(String override) {
        String base = (override == null || override.isEmpty()) ? DEFAULT_CDN_BASE : override;
        return base.replaceAll("/$", "");
    }

    /**
     * The identity attributes to carry on the bootstrap tag as {@code data-user}:
     * the same {@code user} the tag evaluates, minus its {@code anonymous_id} and
     * any null values. Keys are sorted so the serialized JSON is deterministic.
     * Returns {@code null} when no identified attribute remains (an anonymous
     * request), so no {@code data-user} is emitted — the browser SDK never sees a
     * spurious identity and there is no anon to identified flip on first paint.
     */
    private String identityAttrs(Map<String, Object> identity) {
        if (identity == null || identity.isEmpty()) return null;
        Map<String, Object> traits = new TreeMap<>();
        for (Map.Entry<String, Object> e : identity.entrySet()) {
            if ("anonymous_id".equals(e.getKey())) continue;
            if (e.getValue() == null) continue;
            traits.put(e.getKey(), e.getValue());
        }
        if (traits.isEmpty()) return null;
        return json(traits);
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String attr(String name, String value) {
        return name + "=\"" + escapeAttr(value) + "\"";
    }

    private static String escapeAttr(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Drop caller-marked private attributes from an outbound props bag. */
    private Map<String, Object> stripPrivate(Map<String, Object> props) {
        List<String> priv = privateAttributes;
        if (props == null || priv.isEmpty()) return props;
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (!priv.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * <b>Fail-safe:</b> fire-and-forget and never throws into the caller — any
     * error building or scheduling the send is caught and logged.
     */
    public void track(String userId, String eventName, Map<String, Object> properties) {
        try {
            if (offline) return;
            Map<String, Object> safeProps = stripPrivate(properties);
            Map<String, Object> event = new HashMap<>();
            event.put("type", "metric");
            event.put("event_name", eventName);
            event.put("user_id", userId);
            event.put("ts", Instant.now().toEpochMilli());
            if (safeProps != null && !safeProps.isEmpty()) event.put("properties", safeProps);
            Map<String, Object> body = Map.of("events", List.of(event));
            scheduler.execute(() -> {
                try { post("/collect", mapper.writeValueAsBytes(body)); }
                catch (Exception e) { Log.warn("track failed: " + e.getMessage()); }
            });
        } catch (Throwable t) {
            Log.error("track threw: " + t);
        }
    }

    // ---- see() structured error reporting ----

    /**
     * Report a caught throwable (or a thrown non-throwable problem) via this
     * client. The terminal {@code .to(outcome)} builds the event and
     * fire-and-forgets the POST to {@code /collect}.
     *
     * <pre>{@code
     * client.see(e).causesThe("checkout").to("use cached prices");
     * }</pre>
     */
    public SeeChain see(Object problem) {
        return new SeeChain(problem, this::dispatchSee);
    }

    /** Report a non-throwable problem (a {@link Violation}) via this client. */
    public SeeChain seeViolation(String name) {
        return new SeeChain(new Violation(name), this::dispatchSee);
    }

    /**
     * Mark a throwable as expected control flow (reports nothing). The returned
     * chain's {@code .because(reason)} stamps the throwable; any {@code .extras()}
     * are local-debug only and never transmitted.
     */
    public ControlFlowChain controlFlowException(Throwable e) {
        return new ControlFlowChain(e);
    }

    /** Build the wire event for a finalized chain and fire-and-forget the send. */
    private void dispatchSee(SeeChain chain) {
        if (offline) return;
        try {
            Map<String, Object> extras = stripPrivate(chain.extras());
            Map<String, Object> ev = See.buildSeeEvent(
                chain.problem(),
                chain.subject(),
                chain.outcome(),
                extras,
                "server",
                VERSION,
                env);
            if (!seeLimiter.shouldSend(ev)) return;
            Map<String, Object> body = Map.of("events", List.of(ev));
            scheduler.execute(() -> {
                try { post("/collect", mapper.writeValueAsBytes(body)); }
                catch (Exception e) { Log.warn("see() send failed: " + e.getMessage()); }
            });
        } catch (Exception e) {
            Log.warn("see() dispatch failed: " + e.getMessage());
        }
    }

    /**
     * POST a single exposure for an enrolled {@code (user, experiment, group)}.
     * Deduped per process (bounded set) so repeated {@code assign()} calls in one
     * server don't spam {@code /collect}. Fire-and-forget; no-op in test mode.
     * This is how {@link #assignUniverse} auto-logs — the browser's auto-exposure
     * parity for SSR.
     */
    private void postExposure(Map<String, Object> user, String experiment, String group) {
        try {
            if (offline) return;
            Object uid = user.get("user_id");
            Object anon = user.get("anonymous_id");
            String unit = uid != null ? uid.toString() : (anon != null ? anon.toString() : "");
            String dedupKey = unit + ":" + experiment + ":" + group;
            if (exposureSeen.contains(dedupKey)) return;
            if (exposureSeen.size() > 5000) exposureSeen.clear();
            exposureSeen.add(dedupKey);
            Map<String, Object> event = new HashMap<>();
            event.put("type", "exposure");
            event.put("experiment", experiment);
            event.put("group", group);
            if (uid != null) event.put("user_id", uid);
            if (anon != null) event.put("anonymous_id", anon);
            event.put("ts", Instant.now().toEpochMilli());
            Map<String, Object> body = Map.of("events", List.of(event));
            scheduler.execute(() -> {
                try { post("/collect", mapper.writeValueAsBytes(body)); }
                catch (Exception e) { Log.warn("exposure send failed: " + e.getMessage()); }
            });
        } catch (Throwable t) {
            Log.error("postExposure threw: " + t);
        }
    }

    private void scheduleNext() {
        task = scheduler.schedule(() -> {
            try {
                boolean changed = fetchAll();
                if (changed) fireChangeListeners();
            }
            catch (Exception e) { Log.warn("background poll failed: " + e.getMessage()); }
            finally { scheduleNext(); }
        }, pollIntervalSec, TimeUnit.SECONDS);
    }

    /** @return {@code true} if NEW data (a 200, not a 304) was applied. */
    @SuppressWarnings("unchecked")
    private boolean fetchAll() throws IOException, InterruptedException {
        boolean changed = false;
        HttpResponse<byte[]> flagsRes = httpGet("/sdk/flags", flagsEtag);
        String intervalHeader = flagsRes.headers().firstValue("X-Poll-Interval").orElse(null);
        if (intervalHeader != null) {
            try { pollIntervalSec = Integer.parseInt(intervalHeader); } catch (NumberFormatException ignore) {}
        }
        if (flagsRes.statusCode() == 200) {
            synchronized (lock) {
                flagsRes.headers().firstValue("ETag").ifPresent(e -> flagsEtag = e);
                flagsBlob = mapper.readValue(flagsRes.body(), Map.class);
            }
            changed = true;
        } else if (flagsRes.statusCode() != 304) {
            throw new IOException("/sdk/flags: " + flagsRes.statusCode());
        }

        HttpResponse<byte[]> expsRes = httpGet("/sdk/experiments", expsEtag);
        if (expsRes.statusCode() == 200) {
            synchronized (lock) {
                expsRes.headers().firstValue("ETag").ifPresent(e -> expsEtag = e);
                expsBlob = mapper.readValue(expsRes.body(), Map.class);
            }
            changed = true;
        } else if (expsRes.statusCode() != 304) {
            throw new IOException("/sdk/experiments: " + expsRes.statusCode());
        }
        return changed;
    }

    /**
     * Test seam: apply raw blob bodies as if a poll returned them and fire
     * change listeners (unless in local mode). Lets tests drive {@link #onChange}
     * deterministically without real network. Package-private.
     */
    void applyDataForTest(Map<String, Object> flags, Map<String, Object> experiments) {
        synchronized (lock) {
            if (flags != null) this.flagsBlob = flags;
            if (experiments != null) this.expsBlob = experiments;
        }
        this.initialized = true;
        if (!localMode) fireChangeListeners();
    }

    private HttpResponse<byte[]> httpGet(String path, String etag) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-SDK-Key", apiKey)
            .GET();
        if (etag != null) b.header("If-None-Match", etag);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void post(String path, byte[] body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-SDK-Key", apiKey)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() >= 400) throw new IOException("POST " + path + ": " + res.statusCode());
    }
}
