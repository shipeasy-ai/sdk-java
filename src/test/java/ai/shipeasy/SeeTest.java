package ai.shipeasy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code see()} structured error reporting. Asserted against a real loopback
 * /collect endpoint that captures POST bodies (same pattern as
 * {@link PrivateAttributesTest}).
 */
class SeeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private CountDownLatch received;

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
        // Never leak the ambient buffer into the next test on this thread.
        See.clearExtras();
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

    private void assertNoSend() throws Exception {
        assertFalse(received.await(500, TimeUnit.MILLISECONDS), "expected no /collect POST");
    }

    @Test
    void caughtThrowableReportsErrorEvent() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl(), "prod", false)) {
            try {
                throw new IllegalArgumentException("boom");
            } catch (IllegalArgumentException e) {
                c.see(e).causesThe("checkout").to("use cached prices");
            }
            Map<String, Object> ev = awaitEvent();
            assertEquals("error", ev.get("type"));
            assertEquals("caught", ev.get("kind"));
            assertEquals("IllegalArgumentException", ev.get("error_type"));
            assertEquals("boom", ev.get("message"));
            assertEquals("checkout", ev.get("subject"));
            assertEquals("use cached prices", ev.get("outcome"));
            assertEquals("server", ev.get("side"));
            assertEquals(Engine.VERSION, ev.get("sdk_version"));
            assertEquals("prod", ev.get("env"));
            assertTrue(ev.containsKey("stack"));
            assertNotNull(ev.get("ts"));
        }
    }

    @Test
    void violationUsesViolationKindAndNoStack() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            c.seeViolation("large query").causesThe("search results").to("be trimmed");
            Map<String, Object> ev = awaitEvent();
            assertEquals("violation", ev.get("kind"));
            assertEquals("large query", ev.get("error_type"));
            assertEquals("large query", ev.get("message"));
            assertEquals("search results", ev.get("subject"));
            assertFalse(ev.containsKey("stack"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void extrasAreSanitizedAndSent() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            Map<String, Object> extras = new HashMap<>();
            extras.put("photo_id", "p1");
            extras.put("size", 42);
            extras.put("ok", true);
            extras.put("skip", null);
            c.see(new RuntimeException("x")).causesThe("photo upload").extras(extras).to("be rejected");
            Map<String, Object> ev = awaitEvent();
            Map<String, Object> out = (Map<String, Object>) ev.get("extras");
            assertEquals("p1", out.get("photo_id"));
            assertEquals(42, out.get("size"));
            assertEquals(true, out.get("ok"));
            assertFalse(out.containsKey("skip"), "null value must be dropped");
            assertEquals(3, out.size());
        }
    }

    @Test
    void extrasCapKeysAndValueLength() {
        Map<String, Object> big = new HashMap<>();
        for (int i = 0; i < 30; i++) big.put("k" + i, i);
        big.put("long", "x".repeat(500));
        Map<String, Object> out = See.sanitizeExtras(big);
        assertNotNull(out);
        assertTrue(out.size() <= 20, "must cap at 20 keys");
        for (Object v : out.values()) {
            if (v instanceof String) {
                assertTrue(((String) v).length() <= 200, "string values truncated to 200");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void inlineExtrasOnToMergeOverPriorExtras() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            Map<String, Object> first = new HashMap<>();
            first.put("order_id", "o1");
            first.put("stage", "early");
            Map<String, Object> inline = new HashMap<>();
            inline.put("stage", "late"); // overrides the prior .extras value
            inline.put("attempt", 2);
            c.see(new RuntimeException("x"))
                .causesThe("checkout")
                .extras(first)
                .to("use cached prices", inline);
            Map<String, Object> ev = awaitEvent();
            Map<String, Object> out = (Map<String, Object>) ev.get("extras");
            assertEquals("o1", out.get("order_id"));
            assertEquals("late", out.get("stage"), "inline .to extras must win over a prior .extras");
            assertEquals(2, out.get("attempt"));
            assertEquals("use cached prices", ev.get("outcome"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambientExtrasMergeIntoLaterReport() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            See.addExtras(Map.of("order_id", "o9", "tenant", "acme"));
            c.see(new RuntimeException("x")).causesThe("checkout").to("use cached prices");
            Map<String, Object> ev = awaitEvent();
            Map<String, Object> out = (Map<String, Object>) ev.get("extras");
            assertEquals("o9", out.get("order_id"));
            assertEquals("acme", out.get("tenant"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambientExtrasAttachToMultipleReports() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            See.addExtras(Map.of("tenant", "acme"));

            c.see(new RuntimeException("a")).causesThe("checkout").to("first");
            Map<String, Object> ev1 = awaitEvent();
            assertEquals("acme", ((Map<String, Object>) ev1.get("extras")).get("tenant"));

            // Reset the capture latch for the second report.
            received = new java.util.concurrent.CountDownLatch(1);
            c.see(new RuntimeException("b")).causesThe("search").to("second");
            Map<String, Object> ev2 = awaitEvent();
            assertEquals("acme", ((Map<String, Object>) ev2.get("extras")).get("tenant"),
                "ambient extras must attach to every report, not just the first");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void chainedExtrasOverrideAmbientKey() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            See.addExtras(Map.of("stage", "ambient", "tenant", "acme"));
            c.see(new RuntimeException("x"))
                .causesThe("checkout")
                .extras(Map.of("stage", "chained"))
                .to("use cached prices");
            Map<String, Object> ev = awaitEvent();
            Map<String, Object> out = (Map<String, Object>) ev.get("extras");
            assertEquals("chained", out.get("stage"), "chained extras win over an ambient key");
            assertEquals("acme", out.get("tenant"), "non-colliding ambient key still attaches");
        }
    }

    @Test
    void clearExtrasEmptiesTheBuffer() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            See.addExtras(Map.of("order_id", "o9"));
            See.clearExtras();
            c.see(new RuntimeException("x")).causesThe("checkout").to("use cached prices");
            Map<String, Object> ev = awaitEvent();
            assertFalse(ev.containsKey("extras"), "cleared buffer must not attach to a later report");
        }
    }

    @Test
    void controlFlowMarksAndReportsNothing() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            RuntimeException e = new RuntimeException("not a Foo");
            c.controlFlowException(e).because("because it wasn't an encoded Foo")
                .extras(Map.of("tried", "Foo"));
            assertTrue(ControlFlowChain.isExpected(e));
            assertNoSend();
        }
    }

    @Test
    void toIsRequiredNoSendWithoutTerminal() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            c.see(new RuntimeException("x")).causesThe("checkout"); // no .to()
            assertNoSend();
        }
    }

    @Test
    void toIsIdempotent() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            SeeChain chain = c.see(new RuntimeException("x")).causesThe("checkout");
            chain.to("a");
            chain.to("b"); // no-op: the second outcome must never reach the wire
            // Only one event is built (the chain's `done` flag guards `to()`):
            // the captured event carries the first outcome, never "b".
            Map<String, Object> ev = awaitEvent();
            assertEquals("a", ev.get("outcome"));
        }
    }

    @Test
    void defaultsWhenConsequenceOmitted() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            c.see(new RuntimeException("x")).to("be incomplete");
            Map<String, Object> ev = awaitEvent();
            assertEquals("app", ev.get("subject"));
            assertEquals("be incomplete", ev.get("outcome"));
        }
    }

    @Test
    void localModeIsNoop() throws Exception {
        try (Engine c = Engine.forTesting()) {
            c.see(new RuntimeException("x")).causesThe("checkout").to("use cached prices");
            assertNoSend();
        }
    }

    @Test
    void staticFacadeUsesLastConstructedClient() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            See.see(new RuntimeException("global")).causesThe("dashboard").to("show cached data");
            Map<String, Object> ev = awaitEvent();
            assertEquals("dashboard", ev.get("subject"));
        }
    }

    @Test
    void staticFacadeBeforeClientWarnsAndDrops() throws Exception {
        See.setDefaultEngine(null);
        // Must not throw, must not send.
        See.see(new RuntimeException("x")).causesThe("checkout").to("use cached prices");
        See.violation("v").to("nothing");
        assertNoSend();
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateAttributesStrippedFromExtras() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl()).privateAttributes(List.of("secret"))) {
            Map<String, Object> extras = new HashMap<>();
            extras.put("secret", "shh");
            extras.put("ok", "yes");
            c.see(new RuntimeException("x")).causesThe("checkout").extras(extras).to("use cached prices");
            Map<String, Object> ev = awaitEvent();
            Map<String, Object> out = (Map<String, Object>) ev.get("extras");
            assertFalse(out.containsKey("secret"), "private attr must be stripped from extras");
            assertEquals("yes", out.get("ok"));
        }
    }
}
