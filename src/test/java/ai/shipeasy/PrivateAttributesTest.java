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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Private attributes (LD/Statsig parity): keys flagged private are stripped from
 * every outbound {@code track()} payload before it reaches {@code /collect}.
 * Asserted against a real loopback /collect endpoint that captures the POST body.
 */
class PrivateAttributesTest {

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
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitBody() throws Exception {
        assertTrue(received.await(5, TimeUnit.SECONDS), "no /collect POST received");
        Map<String, Object> root = MAPPER.readValue(lastBody.get(), Map.class);
        List<Map<String, Object>> events = (List<Map<String, Object>>) root.get("events");
        assertNotNull(events);
        assertEquals(1, events.size());
        return events.get(0);
    }

    @Test
    void privateAttributesAreStrippedFromTrack() throws Exception {
        try (Engine c = new Engine("k", baseUrl()).privateAttributes(List.of("email", "ssn"))) {
            c.track("u_1", "purchase", Map.of(
                "amount", 49,
                "email", "a@b.com",
                "ssn", "123-45-6789"));
            Map<String, Object> event = awaitBody();
            assertEquals("metric", event.get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) event.get("properties");
            assertNotNull(props);
            assertEquals(49, props.get("amount"));
            assertFalse(props.containsKey("email"), "private attr email must be stripped");
            assertFalse(props.containsKey("ssn"), "private attr ssn must be stripped");
        }
    }

    @Test
    void nonPrivateAttributesPassThroughUnchanged() throws Exception {
        try (Engine c = new Engine("k", baseUrl())) {
            c.track("u_1", "purchase", Map.of("amount", 49, "currency", "USD"));
            Map<String, Object> event = awaitBody();
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) event.get("properties");
            assertEquals(49, props.get("amount"));
            assertEquals("USD", props.get("currency"));
        }
    }
}
