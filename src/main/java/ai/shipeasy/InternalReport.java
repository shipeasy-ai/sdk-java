package ai.shipeasy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Internal self-monitoring channel — SDK bugs that are "on our end".
 *
 * <p>When the SDK swallows one of its OWN internal errors (the last-resort guard
 * in {@link Client}, whose per-read {@code try/catch} keeps a
 * {@code getFlag}/{@code getConfig}/… from throwing into product code even when
 * an internal invariant is violated), it ALSO ships a structured {@code see}
 * event here — to Shipeasy's OWN project, NOT the consumer's — so the SDK team
 * can track SDK-internal failures across every app the SDK runs in.
 *
 * <p>This is deliberately distinct from the customer-facing {@code see()} path
 * ({@link See}/{@link Engine#see}), which authenticates with the consumer's key
 * and lands in the consumer's dashboard. Internal errors must never pollute a
 * customer's Errors tab, and the SDK team must see them centrally — so this
 * channel has its own baked-in destination + credential.
 *
 * <p>Guarantees (identical to telemetry/see): fire-and-forget, never blocks,
 * never throws into product code, deduped/rate-limited. A failed send is
 * swallowed silently — it must never log (that would risk recursion through the
 * guard).
 */
final class InternalReport {

    // ---- Baked-in destination ----
    //
    // The main Shipeasy project. The credential is a PUBLIC client key — the
    // same class of credential already embedded verbatim in every browser bundle
    // that ships the client SDK — so baking it into the published package is
    // safe. {@code /collect} treats it as a write-only ingest key; it grants no
    // read access. The canonical ingest host is api.shipeasy.ai (the SDK default
    // baseUrl), which routes /collect to the edge worker.
    static final String INGEST_URL = "https://api.shipeasy.ai/collect";

    /** Live ingest destination. Only a test seam ever changes it (see {@link #setIngestUrlForTest}). */
    private static volatile String ingestUrl = INGEST_URL;

    // Sentinel used until the real key is minted + baked. While {@code ingestKey}
    // is still the placeholder the channel stays fully inert (see
    // {@link #report}), so a build that ships before the key is provisioned never
    // fires doomed requests. Mint the key with:
    //   shipeasy keys create --type client --env prod \
    //     --name "SDK internal error self-reporting" --scopes events:write
    // then replace the {@code ingestKey} initializer below with the returned value.
    static final String PLACEHOLDER_KEY = "sdk_client_REPLACE_WITH_SHIPEASY_INTERNAL_ERROR_KEY";

    /** The baked ingest key. Swap the placeholder here for the real minted key. */
    private static volatile String ingestKey = "sdk_client_00bd4608a03e4084922978f9522614d5";

    // Stable consequence. The {@code label} (the Client read's operation name,
    // e.g. "Client.getFlag") is the subject; the outcome is fixed. Both are
    // constant per operation — no variable data — so occurrences of the same
    // internal bug fold into one issue on our dashboard. {@code sdk} marks which
    // language SDK reported it.
    static final String OUTCOME = "returned a safe default";
    static final String SDK_ID = "java";
    static final String SIDE = "server";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // A tiny daemon pool so the send never blocks the caller and never keeps the
    // JVM alive.
    private static final ExecutorService SENDER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "shipeasy-internal-report");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // Bounds network chatter from a hot internal-error loop (30s dedup window + a
    // hard per-process cap). The backend dedupes by fingerprint anyway.
    private static volatile SeeLimiter limiter = new SeeLimiter();

    /** SDK version reported on the event; {@code null} until a context is set. */
    private static volatile String sdkVersion;

    /**
     * Whether the channel is armed. Set once per process from the {@link Engine}
     * constructor (last wins). {@code false} until configured (a report before
     * configure is a no-op — nothing to attribute it to), when the caller opts
     * out via {@code disableInternalErrorReporting}, and in test/offline mode.
     */
    private static volatile boolean enabled = false;

    private InternalReport() {}

    /** True once a real key has been baked in (not the placeholder sentinel). */
    private static boolean keyConfigured() {
        return ingestKey != null && !ingestKey.equals(PLACEHOLDER_KEY);
    }

    /**
     * Wire the self-monitoring channel. Called from the {@link Engine}
     * constructor with the bundle's version. {@code enabled} defaults on; it is
     * forced off in test/offline mode (no network) and when the caller opts out
     * via {@code disableInternalErrorReporting}.
     */
    static void setContext(String sdkVersion, boolean enabled) {
        InternalReport.sdkVersion = sdkVersion;
        InternalReport.enabled = enabled;
    }

    /**
     * Report an SDK-internal error to Shipeasy's own project. Called from the
     * last-resort guard in {@link Client}. {@code label} is the swallowed
     * operation (e.g. "Client.getFlag") and becomes the stable issue subject.
     * Never throws.
     */
    static void report(String label, Throwable err) {
        try {
            if (!enabled || !keyConfigured()) return;
            // env=null: internal reports carry only the SDK-side context, never a
            // consumer env/url.
            Map<String, Object> ev = See.buildSeeEvent(
                err,
                label,
                OUTCOME,
                Map.of("sdk", SDK_ID),
                SIDE,
                sdkVersion,
                null);
            if (!limiter.shouldSend(ev)) return;
            byte[] body = MAPPER.writeValueAsBytes(Map.of("events", List.of(ev)));
            send(body);
        } catch (Throwable ignore) {
            // Self-reporting must never throw into product code.
        }
    }

    private static void send(byte[] body) {
        try {
            SENDER.execute(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(ingestUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-SDK-Key", ingestKey)
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                    HTTP.send(req, HttpResponse.BodyHandlers.discarding());
                } catch (Throwable ignore) {
                    // Self-reporting must never surface a network error, and must
                    // never log (that would risk recursion through the guard).
                }
            });
        } catch (Throwable ignore) {
            // e.g. executor rejected the task — swallow.
        }
    }

    // ---- Test seams (package-private) ----

    /** Reset module state so a spec starts from a clean, inert channel. */
    static void resetForTest() {
        enabled = false;
        sdkVersion = null;
        limiter = new SeeLimiter();
        ingestKey = PLACEHOLDER_KEY;
        ingestUrl = INGEST_URL;
    }

    /**
     * Stand in a real-looking key so specs can exercise the send path without the
     * (deliberately inert) placeholder blocking it.
     */
    static void setIngestKeyForTest(String key) {
        ingestKey = key;
    }

    /** Point the ingest send at a loopback /collect so specs can capture the wire. */
    static void setIngestUrlForTest(String url) {
        ingestUrl = url;
    }
}
