package ai.shipeasy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Environment-derived network & telemetry (egress) defaults (0.15.0).
 *
 * <p>Both the master network switch and per-evaluation tracking default ON in
 * production and OFF in every other environment, so an app that embeds the SDK
 * never phones home from a dev machine or CI unless it opts in. Asserted against
 * a loopback {@code /collect} recorder (same pattern as {@link SeeTest}): a
 * {@code track} either lands a POST or it doesn't.
 *
 * <p>The suite normally pins {@code shipeasy.env=production} (see
 * {@link InertInternalReportExtension}); these tests clear it to simulate a dev
 * host relying on the configured-env fallback, then restore it.
 */
class EnvDefaultsTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private CountDownLatch received;
    private String savedEnvProp;

    @BeforeEach
    void start() throws IOException {
        savedEnvProp = System.getProperty("shipeasy.env");
        received = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collect", (HttpExchange ex) -> {
            ex.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        // Restore the suite-wide production-equivalent egress signal.
        if (savedEnvProp != null) System.setProperty("shipeasy.env", savedEnvProp);
        else System.clearProperty("shipeasy.env");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private boolean sawPost() throws InterruptedException {
        return received.await(600, TimeUnit.MILLISECONDS);
    }

    /** Force the SDK to consult the configured-env fallback (no native signal). */
    private void simulateDevHost() {
        System.clearProperty("shipeasy.env");
    }

    private void simulateProdHost() {
        System.setProperty("shipeasy.env", "production");
    }

    // (a) offline-by-default in dev: no request fires.
    @Test
    void devIsOfflineByDefault_noTrackPost() throws Exception {
        simulateDevHost();
        try (Engine c = new Engine("k", baseUrl(), "dev", false)) {
            c.track("u1", "purchase", Map.of("amount", 1));
            assertFalse(sawPost(), "dev must be quiet: no /collect POST from track");
        }
    }

    // (a') offline-by-default in dev also holds even when the configured env is the
    // default "prod" but the caller has NOT opted network on and the host is dev.
    // Here the configured env IS prod, so with no native signal it reads as
    // production and DOES send — this pins the fallback semantics.
    @Test
    void defaultEnvProdWithNoNativeSignalIsOnline() throws Exception {
        simulateDevHost();
        try (Engine c = new Engine("k", baseUrl())) { // env defaults to "prod"
            c.track("u1", "purchase", Map.of("amount", 1));
            assertTrue(sawPost(), "configured env=prod with no native signal defaults online");
        }
    }

    // (b) explicit network-on overrides the dev default.
    @Test
    void explicitNetworkOnOverridesDevDefault() throws Exception {
        simulateDevHost();
        Engine e = new Engine("k", baseUrl(), "dev",
            /* isTrackingEnabled */ (Boolean) null, /* isNetworkEnabled */ Boolean.TRUE, LogLevel.WARN);
        try (Engine c = e) {
            c.track("u1", "purchase", Map.of("amount", 1));
            assertTrue(sawPost(), "explicit isNetworkEnabled(true) must send even in dev");
        }
    }

    // (b') explicit tracking-off suppresses telemetry even when network is on, but
    // track() (a first-class egress, not telemetry) still flows while network is on.
    @Test
    void explicitNetworkOffSuppressesEvenInProd() throws Exception {
        simulateProdHost();
        Engine e = new Engine("k", baseUrl(), "prod",
            /* isTrackingEnabled */ (Boolean) null, /* isNetworkEnabled */ Boolean.FALSE, LogLevel.WARN);
        try (Engine c = e) {
            c.track("u1", "purchase", Map.of("amount", 1));
            assertFalse(sawPost(), "explicit isNetworkEnabled(false) is fully offline even in prod");
        }
    }

    // (c) on by default in production (native signal present).
    @Test
    void prodIsOnlineByDefault_trackPosts() throws Exception {
        simulateProdHost();
        try (Engine c = new Engine("k", baseUrl(), "dev", false)) {
            // configured env is "dev" but the native production signal wins.
            c.track("u1", "purchase", Map.of("amount", 1));
            assertTrue(sawPost(), "native production signal enables egress regardless of configured env");
        }
    }

    // The same default flows through the documented Shipeasy.configure(...) surface.
    @Test
    void configureIsQuietInDevByDefault() throws Exception {
        simulateDevHost();
        Shipeasy.resetForTest();
        try {
            Shipeasy.configure(Shipeasy.options("k").baseUrl(baseUrl()).env("dev"));
            new Client(Map.of("user_id", "u1")).track("purchase");
            assertFalse(sawPost(), "configure(env=dev) on a dev host is offline by default");
        } finally {
            Shipeasy.resetForTest();
        }
    }

    @Test
    void configureNetworkEnabledTrueOptsBackIn() throws Exception {
        simulateDevHost();
        Shipeasy.resetForTest();
        try {
            Shipeasy.configure(Shipeasy.options("k").baseUrl(baseUrl()).env("dev")
                .isNetworkEnabled(true));
            new Client(Map.of("user_id", "u1")).track("purchase");
            assertTrue(sawPost(), "isNetworkEnabled(true) restores egress in dev");
        } finally {
            Shipeasy.resetForTest();
        }
    }
}
