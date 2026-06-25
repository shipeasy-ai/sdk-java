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
 * Manual exposure (server): the server never auto-logs. {@code logExposure}
 * re-evaluates the experiment and, only when the user is enrolled, POSTs one
 * {@code {type:"exposure", experiment, group, user_id, ts}} to {@code /collect}.
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
        return Map.of("experiments", Map.of("pricing_test", exp), "universes", Map.of());
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
    void enrolledUserPostsOneExposure() throws Exception {
        try (Engine c = new Engine("k", baseUrl())) {
            c.applyDataForTest(Map.of("gates", Map.of()), runningExps());
            c.logExposure("user_beta", "pricing_test");
            Map<String, Object> event = awaitEvent();
            assertEquals("exposure", event.get("type"));
            assertEquals("pricing_test", event.get("experiment"));
            assertEquals("control", event.get("group"));
            assertEquals("user_beta", event.get("user_id"));
            assertNotNull(event.get("ts"));
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
                Map.of("experiments", Map.of("pricing_test", exp), "universes", Map.of()));
            c.logExposure("user_beta", "pricing_test");
            // Give the async path a moment; nothing should arrive.
            assertEquals(false, received.await(1, TimeUnit.SECONDS),
                "no exposure must be POSTed for an unenrolled user");
        }
    }

    @Test
    void mapOverloadResolvesTargeting() throws Exception {
        try (Engine c = new Engine("k", baseUrl())) {
            c.applyDataForTest(Map.of("gates", Map.of()), runningExps());
            c.logExposure(Map.of("user_id", "user_beta"), "pricing_test");
            Map<String, Object> event = awaitEvent();
            assertEquals("exposure", event.get("type"));
            assertEquals("user_beta", event.get("user_id"));
        }
    }

    // logExposure on a test-mode client is a no-op (never touches the network).
    @Test
    void logExposureNoOpInTestMode() {
        try (Engine c = Engine.forTesting()) {
            c.overrideExperiment("pricing_test", "treatment", Map.of());
            c.logExposure("user_beta", "pricing_test"); // must not throw / not POST
        }
    }
}
