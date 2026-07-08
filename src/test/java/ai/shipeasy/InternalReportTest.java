package ai.shipeasy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal self-monitoring channel: when the SDK swallows an internal ("on our
 * end") error via the {@link Client} last-resort guard, it also ships a
 * structured see event to Shipeasy's OWN project — a baked-in destination +
 * public client key, distinct from the consumer's see() path. These tests pin
 * the wire shape, the enable gating, the dedup, and the no-throw guarantee.
 *
 * <p>Asserted against a real loopback endpoint that captures POST bodies + the
 * {@code X-SDK-Key} header (same pattern as {@link SeeTest}). The baked ingest
 * URL + key are swapped to the loopback via package-private test seams.
 */
class InternalReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // A real-looking client key to exercise the send path (the baked default is
    // an inert placeholder until the real key is minted).
    private static final String FAKE_KEY = "sdk_client_testfakekey00000000000000000000";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();
    private CountDownLatch received;

    @BeforeEach
    void start() throws IOException {
        received = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collect", (HttpExchange ex) -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            lastBody.set(new String(body));
            lastKey.set(ex.getRequestHeaders().getFirst("X-SDK-Key"));
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
        InternalReport.resetForTest();
        InternalReport.setIngestKeyForTest(FAKE_KEY);
        InternalReport.setIngestUrlForTest(collectUrl());
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        InternalReport.resetForTest();
    }

    private String collectUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/collect";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitEvent() throws Exception {
        assertTrue(received.await(5, TimeUnit.SECONDS), "no /collect POST received");
        Map<String, Object> root = MAPPER.readValue(lastBody.get(), Map.class);
        List<Map<String, Object>> events = (List<Map<String, Object>>) root.get("events");
        assertNotNull(events);
        assertEquals(1, events.size());
        return events.get(0);
    }

    private void assertNoSend() throws Exception {
        assertFalse(received.await(500, TimeUnit.MILLISECONDS), "expected no /collect POST");
    }

    // ---- destination + wire shape ----

    @Test
    void postsToBakedIngestWithPublicClientKey() throws Exception {
        InternalReport.setContext("9.9.9", true);
        InternalReport.report("Client.getFlag", new IllegalStateException("cannot read foo"));
        awaitEvent();
        assertEquals(FAKE_KEY, lastKey.get());
    }

    @Test
    void buildsStableConsequenceWithSdkMarker() throws Exception {
        InternalReport.setContext("9.9.9", true);
        InternalReport.report("Client.getExperiment", new RuntimeException("boom"));
        Map<String, Object> ev = awaitEvent();
        assertEquals("error", ev.get("type"));
        assertEquals("caught", ev.get("kind"));
        assertEquals("Client.getExperiment", ev.get("subject"));
        assertEquals("returned a safe default", ev.get("outcome"));
        assertEquals("RuntimeException", ev.get("error_type"));
        assertEquals("boom", ev.get("message"));
        assertEquals("server", ev.get("side"));
        assertEquals("9.9.9", ev.get("sdk_version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> extras = (Map<String, Object>) ev.get("extras");
        assertEquals("java", extras.get("sdk"));
    }

    @Test
    void doesNotAttachConsumerEnvOrUrl() throws Exception {
        InternalReport.setContext("9.9.9", true);
        InternalReport.report("Client.getKillswitch", new RuntimeException("x"));
        Map<String, Object> ev = awaitEvent();
        assertNull(ev.get("env"), "no consumer env on internal reports");
        assertNull(ev.get("url"), "no consumer url on internal reports");
    }

    // ---- enable gating ----

    @Test
    void noopBeforeContextIsSet() throws Exception {
        // resetForTest() left it disabled; do not call setContext.
        InternalReport.report("Client.getFlag", new RuntimeException("boom"));
        assertNoSend();
    }

    @Test
    void noopWhenDisabled() throws Exception {
        InternalReport.setContext("9.9.9", false);
        InternalReport.report("Client.getFlag", new RuntimeException("boom"));
        assertNoSend();
    }

    @Test
    void inertWhileIngestKeyIsUnprovisionedPlaceholder() throws Exception {
        InternalReport.setIngestKeyForTest(InternalReport.PLACEHOLDER_KEY);
        InternalReport.setContext("9.9.9", true);
        InternalReport.report("Client.getFlag", new RuntimeException("boom"));
        assertNoSend();
    }

    // ---- resilience ----

    @Test
    void dedupesIdenticalInternalErrorsWithinWindow() throws Exception {
        InternalReport.setContext("9.9.9", true);
        // Same throwable => same top stack frame => one fingerprint (mirrors a hot
        // loop re-throwing from the same line).
        RuntimeException err = new RuntimeException("same");
        InternalReport.report("Client.getFlag", err);
        InternalReport.report("Client.getFlag", err);
        awaitEvent();
        // Give any (incorrect) second send a chance to land, then assert exactly one.
        Thread.sleep(300);
        assertEquals(1, hits.get(), "identical internal errors dedupe to one send");
    }

    @Test
    void neverThrowsEvenWithBadIngestTarget() {
        InternalReport.setIngestUrlForTest("http://127.0.0.1:1/collect"); // refused
        InternalReport.setContext("9.9.9", true);
        assertDoesNotThrow(() ->
            InternalReport.report("Client.getFlag", new RuntimeException("boom")));
    }

    // ---- guard integration (Client last-resort guard) ----

    /** A value whose {@code toString()} throws — corrupts the bound attribute map
     * so an otherwise fail-safe read surfaces an internal error into the Client's
     * last-resort guard. */
    private static final class Exploding {
        @Override public String toString() {
            throw new IllegalStateException("internal invariant");
        }
    }

    @Test
    void clientGuardReportsSwallowedErrorAndStillReturnsFallback() throws Exception {
        Engine ok = Engine.forTesting();
        Shipeasy.useEngineForTest(ok, null);
        // Constructing the (localMode) engine forces the channel OFF; re-arm it
        // AFTER, and re-point the (baked) URL/key which the fresh context leaves.
        InternalReport.setContext("9.9.9", true);
        InternalReport.setIngestKeyForTest(FAKE_KEY);
        InternalReport.setIngestUrlForTest(collectUrl());
        try {
            // Client.track derives the unit id via attributes.get("user_id").toString()
            // — a corrupt value makes that throw, which the Client's last-resort
            // guard swallows (returning nothing) and reports as "Client.track".
            Client client = new Client(Map.of("user_id", new Exploding()));
            assertDoesNotThrow(() -> client.track("checkout"));
            Map<String, Object> ev = awaitEvent();
            assertEquals("Client.track", ev.get("subject"));
            assertEquals("returned a safe default", ev.get("outcome"));
            assertEquals("internal invariant", ev.get("message"));
        } finally {
            Shipeasy.resetForTest();
        }
    }

    @Test
    void clientGuardDoesNotReportWhenReadSucceeds() throws Exception {
        Engine ok = Engine.forTesting();
        ok.overrideFlag("new_checkout", true);
        Shipeasy.useEngineForTest(ok, null);
        InternalReport.setContext("9.9.9", true); // re-arm after the localMode engine disabled it
        InternalReport.setIngestKeyForTest(FAKE_KEY);
        InternalReport.setIngestUrlForTest(collectUrl());
        try {
            boolean on = new Client(Map.of("user_id", "u1")).getFlag("new_checkout");
            assertTrue(on);
            assertNoSend();
        } finally {
            Shipeasy.resetForTest();
        }
    }
}
