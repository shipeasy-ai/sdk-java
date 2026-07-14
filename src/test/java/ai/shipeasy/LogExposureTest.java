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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-exposure (server): {@code universe(name).assign(user)} logs a single
 * (deduped) exposure when the unit is enrolled, POSTing one
 * {@code {type:"exposure", experiment, group, user_id, ts}} to {@code /collect}.
 * A not-enrolled unit logs nothing.
 */
class LogExposureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private CountDownLatch received;

    // A fully-allocated experiment that user_beta enrolls into → group "control".
    private static Map<String, Object> runningExps() {
        Map<String, Object> exp = Map.of(
            "universe", "universe_pricing",
            "allocationPct", 10000,
            "salt", "exp_pricing_42",
            "status", "running",
            "groups", List.of(
                Map.of("name", "control", "weight", 9086, "params", Map.of("variant", "a")),
                Map.of("name", "treatment", "weight", 914, "params", Map.of("variant", "b"))));
        return Map.of("experiments", Map.of("pricing_test", exp),
            "universes", Map.of("universe_pricing", java.util.Collections.singletonMap("holdout_range", null)));
    }

    @BeforeEach
    void start() throws IOException {
        received = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collect", (HttpExchange ex) -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            lastBody.set(new String(body));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
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

    @Test
    void enrolledUserPostsOneExposureOnFirstRead() throws Exception {
        try (Engine c = new Engine("k", baseUrl())) {
            c.applyDataForTest(Map.of("gates", Map.of()), runningExps());
            Assignment a = c.universe("universe_pricing").assign(Map.of("user_id", "user_beta"));
            assertEquals("pricing_test", a.name());
            assertEquals("control", a.group());
            // assign() alone is side-effect free — the exposure fires on read.
            assertEquals(false, received.await(500, TimeUnit.MILLISECONDS),
                "assign() alone must not POST an exposure");
            a.get("anything", null); // first read → the single exposure
            Map<String, Object> event = awaitEvent();
            assertEquals("exposure", event.get("type"));
            assertEquals("pricing_test", event.get("experiment"));
            assertEquals("control", event.get("group"));
            assertEquals("user_beta", event.get("user_id"));
            assertNotNull(event.get("ts"));
        }
    }

    // peek() reads a param WITHOUT logging an exposure (on-read opt-out).
    @Test
    void peekReadDoesNotPostExposure() throws Exception {
        try (Engine c = new Engine("k", baseUrl())) {
            c.applyDataForTest(Map.of("gates", Map.of()), runningExps());
            Assignment a = c.universe("universe_pricing").assign(Map.of("user_id", "user_beta"));
            a.peek("anything", null);
            assertEquals(false, received.await(500, TimeUnit.MILLISECONDS),
                "peek() must not POST an exposure");
            a.get("anything", null); // a real read still logs
            Map<String, Object> event = awaitEvent();
            assertEquals("exposure", event.get("type"));
        }
    }

    @Test
    void unenrolledUserPostsNothing() throws Exception {
        try (Engine c = new Engine("k", baseUrl())) {
            // Experiment not running -> not enrolled -> no exposure.
            Map<String, Object> exp = Map.of(
                "universe", "universe_pricing",
                "allocationPct", 10000,
                "salt", "exp_pricing_42",
                "status", "draft",
                "groups", List.of(Map.of("name", "control", "weight", 10000, "params", Map.of())));
            c.applyDataForTest(Map.of("gates", Map.of()),
                Map.of("experiments", Map.of("pricing_test", exp),
                    "universes", Map.of("universe_pricing", java.util.Collections.singletonMap("holdout_range", null))));
            Assignment a = c.universe("universe_pricing").assign(Map.of("user_id", "user_beta"));
            assertEquals(false, a.enrolled());
            a.get("anything", null); // not enrolled → no callback wired → no POST
            // Give the async path a moment; nothing should arrive.
            assertEquals(false, received.await(1, TimeUnit.SECONDS),
                "no exposure must be POSTed for an unenrolled user");
        }
    }

    // A second read for the same (unit, experiment, group) is deduped — only
    // one exposure is POSTed per process.
    @Test
    void repeatedReadDedupesExposure() throws Exception {
        try (Engine c = new Engine("k", baseUrl())) {
            c.applyDataForTest(Map.of("gates", Map.of()), runningExps());
            c.universe("universe_pricing").assign(Map.of("user_id", "user_beta")).get("x", null);
            // First exposure lands and drops the latch to 0.
            Map<String, Object> event = awaitEvent();
            assertEquals("exposure", event.get("type"));
            // A second assign+read for the same (unit, exp, group) must NOT re-POST.
            received = new CountDownLatch(1);
            c.universe("universe_pricing").assign(Map.of("user_id", "user_beta")).get("x", null);
            assertEquals(false, received.await(1, TimeUnit.SECONDS),
                "a repeated read must not POST a second exposure");
        }
    }

    // read on a test-mode client is a no-op for exposure (never touches network).
    @Test
    void assignNoOpExposureInTestMode() {
        try (Engine c = Engine.forTesting()) {
            c.overrideExperiment("pricing_test", "treatment", Map.of());
            // No blob -> no candidate -> not enrolled; must not throw / not POST.
            c.universe("universe_pricing").assign(Map.of("user_id", "user_beta")).get("x", null);
        }
    }
}
