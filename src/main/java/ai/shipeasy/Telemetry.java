package ai.shipeasy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-evaluation usage telemetry. Fires one fire-and-forget HTTP beacon per
 * evaluation so usage is counted by Cloudflare's native per-path analytics.
 * Mirrors the contract in the TypeScript reference SDK and
 * experiment-platform/15-usage-metering.md. The path carries sha256(apiKey) --
 * never the raw key -- plus side/env, then feature/resource. The 2s dedup
 * window bounds volume under loops.
 */
final class Telemetry {
    static final String DEFAULT_TELEMETRY_URL = "https://t.shipeasy.ai";

    private final boolean disabled;
    private final long dedupeMs = 2000;
    private final String prefix;
    private final Consumer<String> sender;
    private final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();

    Telemetry(String endpoint, String sdkKey, String side, String env, boolean disabled, HttpClient http) {
        this(endpoint, sdkKey, side, env, disabled, defaultSender(http));
    }

    /** Seam constructor: a custom sender receives the beacon URL (used by tests). */
    Telemetry(String endpoint, String sdkKey, String side, String env, boolean disabled, Consumer<String> sender) {
        endpoint = endpoint == null ? "" : endpoint.replaceAll("/$", "");
        this.disabled = disabled || sdkKey == null || sdkKey.isEmpty() || endpoint.isEmpty();
        this.sender = sender;
        this.prefix = this.disabled ? "" : endpoint + "/t/" + sha256Hex(sdkKey) + "/" + side + "/" + enc(env);
    }

    private static Consumer<String> defaultSender(HttpClient http) {
        return url -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // telemetry must never affect the caller
            }
        };
    }

    /** Best-effort usage beacon for one evaluation. Never blocks, never throws. */
    void emit(String feature, String resource) {
        if (disabled) {
            return;
        }
        if (dedupeMs > 0) {
            String key = feature + "/" + resource;
            long now = System.currentTimeMillis();
            Long prev = last.get(key);
            if (prev != null && now - prev < dedupeMs) {
                return;
            }
            last.put(key, now);
        }
        sender.accept(prefix + "/" + feature + "/" + enc(resource));
    }

    /** encodeURIComponent-equivalent: %20 for space (URLEncoder emits "+"). */
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha256Hex(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
