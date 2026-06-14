package ai.shipeasy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public Client(String apiKey) { this(apiKey, null, "prod", false); }

    public Client(String apiKey, String baseUrl) { this(apiKey, baseUrl, "prod", false); }

    /**
     * @param env              published env reported in usage telemetry ("prod" default)
     * @param disableTelemetry turn off per-evaluation usage beacons (ON by default)
     */
    public Client(String apiKey, String baseUrl, String env, boolean disableTelemetry) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE_URL : baseUrl.replaceAll("/$", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.telemetry = new Telemetry(
            Telemetry.DEFAULT_TELEMETRY_URL, apiKey, "server", env, disableTelemetry, this.http);
    }

    public void init() throws IOException, InterruptedException {
        fetchAll();
        initialized = true;
        scheduleNext();
    }

    public void initOnce() throws IOException, InterruptedException {
        if (initialized) return;
        fetchAll();
        initialized = true;
    }

    @Override public void close() {
        scheduler.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    public boolean getFlag(String name, Map<String, Object> user) {
        telemetry.emit("gate", name);
        Map<String, Object> blob = flagsBlob;
        if (blob == null) return false;
        Map<String, Object> gates = (Map<String, Object>) blob.get("gates");
        return gates != null && Eval.evalGate((Map<String, Object>) gates.get(name), withAnonId(user));
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
        telemetry.emit("config", name);
        Map<String, Object> blob = flagsBlob;
        if (blob == null) return null;
        Map<String, Object> configs = (Map<String, Object>) blob.get("configs");
        if (configs == null) return null;
        Map<String, Object> entry = (Map<String, Object>) configs.get(name);
        return entry == null ? null : entry.get("value");
    }

    @SuppressWarnings("unchecked")
    public ExperimentResult getExperiment(String name, Map<String, Object> user, Object defaultParams) {
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
            try { fetchAll(); }
            catch (Exception e) { log.warning("background poll failed: " + e.getMessage()); }
            finally { scheduleNext(); }
        }, pollIntervalSec, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private void fetchAll() throws IOException, InterruptedException {
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
        } else if (flagsRes.statusCode() != 304) {
            throw new IOException("/sdk/flags: " + flagsRes.statusCode());
        }

        HttpResponse<byte[]> expsRes = httpGet("/sdk/experiments", expsEtag);
        if (expsRes.statusCode() == 200) {
            synchronized (lock) {
                expsRes.headers().firstValue("ETag").ifPresent(e -> expsEtag = e);
                expsBlob = mapper.readValue(expsRes.body(), Map.class);
            }
        } else if (expsRes.statusCode() != 304) {
            throw new IOException("/sdk/experiments: " + expsRes.statusCode());
        }
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
