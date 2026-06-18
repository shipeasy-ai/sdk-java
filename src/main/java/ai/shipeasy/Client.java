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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class Client implements AutoCloseable {
    private static final Logger log = Logger.getLogger("shipeasy");
    private static final String DEFAULT_BASE_URL = "https://edge.shipeasy.dev";

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
     * When {@code true} the client performs zero network I/O — {@link #init()},
     * {@link #initOnce()} and {@link #track(String, String, Map)} are no-ops and
     * telemetry is disabled. Set only by {@link #forTesting()}. Evaluation reads
     * the {@code override*} maps below and never touches a fetched blob.
     */
    private final boolean localMode;

    // Local overrides (Statsig-style). Thread-safe to match the volatile-blob
    // concurrency model; an override, when present, wins over any fetched value.
    private final Map<String, Boolean> flagOverrides = new ConcurrentHashMap<>();
    private final Map<String, Object> configOverrides = new ConcurrentHashMap<>();
    private final Map<String, ExperimentResult> experimentOverrides = new ConcurrentHashMap<>();

    // Change listeners fired after a background poll applies NEW (200, not 304)
    // data. Thread-safe to match the volatile-blob concurrency model.
    private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public Client(String apiKey) { this(apiKey, null, "prod", false); }

    public Client(String apiKey, String baseUrl) { this(apiKey, baseUrl, "prod", false); }

    /**
     * @param env              published env reported in usage telemetry ("prod" default)
     * @param disableTelemetry turn off per-evaluation usage beacons (ON by default)
     */
    public Client(String apiKey, String baseUrl, String env, boolean disableTelemetry) {
        this(apiKey, baseUrl, env, disableTelemetry, false);
    }

    private Client(String apiKey, String baseUrl, String env, boolean disableTelemetry, boolean localMode) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl.replaceAll("/$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.localMode = localMode;
        // localMode forces telemetry off regardless of the flag.
        this.telemetry = new Telemetry(
            Telemetry.DEFAULT_TELEMETRY_URL, apiKey, "server", env, disableTelemetry || localMode, this.http);
        if (localMode) this.initialized = true;
    }

    /**
     * A no-network, immediately-usable client for tests. Telemetry is disabled,
     * {@link #init()}/{@link #initOnce()} never fetch, {@link #track} is a no-op,
     * and no API key is required. Seed values with {@link #overrideFlag},
     * {@link #overrideConfig} and {@link #overrideExperiment}.
     *
     * <pre>{@code
     * try (Client c = Client.forTesting()) {
     *     c.overrideFlag("new_checkout", true);
     *     assertTrue(c.getFlag("new_checkout", Map.of()));
     * }
     * }</pre>
     */
    public static Client forTesting() {
        return new Client(null, null, "test", true, true);
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
     * try (Client c = Client.fromFile("snapshot.json")) {
     *     boolean on = c.getFlag("new_checkout", Map.of("user_id", "u_123"));
     * }
     * }</pre>
     */
    public static Client fromFile(String path) throws IOException {
        Client c = new Client(null, null, "test", true, true);
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
    public static Client fromSnapshot(Map<String, Object> flags, Map<String, Object> experiments) {
        Client c = new Client(null, null, "test", true, true);
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
     * Force {@link #getExperiment} for {@code name} to return an in-experiment
     * result with the given {@code group} and {@code params}.
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

    public void init() throws IOException, InterruptedException {
        if (localMode) return;
        fetchAll();
        initialized = true;
        scheduleNext();
    }

    public void initOnce() throws IOException, InterruptedException {
        if (localMode) return;
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
     */
    @SuppressWarnings("unchecked")
    public FlagDetail getFlagDetail(String name, Map<String, Object> user) {
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

    @SuppressWarnings("unchecked")
    public Object getConfig(String name) {
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
     */
    public Object getConfig(String name, Object defaultValue) {
        Object value = getConfig(name);
        return value == null ? defaultValue : value;
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
                log.warning("onChange listener threw: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public ExperimentResult getExperiment(String name, Map<String, Object> user, Object defaultParams) {
        ExperimentResult override = experimentOverrides.get(name);
        if (override != null) return override;
        telemetry.emit("experiment", name);
        Map<String, Object> flags = flagsBlob;
        Map<String, Object> exps = expsBlob;
        Map<String, Object> exp = null;
        if (exps != null) {
            Map<String, Object> all = (Map<String, Object>) exps.get("experiments");
            if (all != null) exp = (Map<String, Object>) all.get(name);
        }
        ExperimentResult r = Eval.evalExperiment(exp, flags, exps, withAnonId(user));
        if (r.params == null) return new ExperimentResult(r.inExperiment, r.group, defaultParams);
        return r;
    }

    public void track(String userId, String eventName, Map<String, Object> properties) {
        if (localMode) return;
        Map<String, Object> event = new HashMap<>();
        event.put("type", "metric");
        event.put("event_name", eventName);
        event.put("user_id", userId);
        event.put("ts", Instant.now().toEpochMilli());
        if (properties != null && !properties.isEmpty()) event.put("properties", properties);
        Map<String, Object> body = Map.of("events", List.of(event));
        scheduler.execute(() -> {
            try { post("/collect", mapper.writeValueAsBytes(body)); }
            catch (Exception e) { log.warning("track failed: " + e.getMessage()); }
        });
    }

    private void scheduleNext() {
        task = scheduler.schedule(() -> {
            try {
                boolean changed = fetchAll();
                if (changed) fireChangeListeners();
            }
            catch (Exception e) { log.warning("background poll failed: " + e.getMessage()); }
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
