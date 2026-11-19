package ai.shipeasy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-network see() correlation (shipeasy #704): {@link AnonIdFilter} reads the
 * browser's {@code X-SE-Correlation} header and every see() in that request
 * carries it as {@code correlation_id}.
 */
class CorrelationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "c0ffee00-1234-abcd";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private CountDownLatch received;

    @BeforeEach
    void start() throws IOException {
        received = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collect", (HttpExchange ex) -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        SeeExtras.clear();
        AnonId.clear();
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
        return events.get(0);
    }

    /** A minimal HttpServletRequest with one optional header and no cookies. */
    private static HttpServletRequest request(String correlation) {
        return (HttpServletRequest) Proxy.newProxyInstance(
            CorrelationTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, m, args) -> {
                switch (m.getName()) {
                    case "getHeader":
                        return AnonIdFilter.CORRELATION_HEADER.equalsIgnoreCase((String) args[0])
                            ? correlation : null;
                    case "isSecure":
                        return false;
                    case "getCookies":
                        return null;
                    default:
                        return null;
                }
            });
    }

    private static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(
            CorrelationTest.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, m, args) -> null);
    }

    @Test
    void filterSetsCorrelationDuringRequestAndClearsAfter() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(SeeExtras.correlationId());
        new AnonIdFilter().doFilter(request(TOKEN), response(), chain);
        assertEquals(TOKEN, seen.get());
        assertNull(SeeExtras.correlationId(), "token must not leak past the request");
    }

    @Test
    void filterClearsCorrelationWhenChainThrows() {
        FilterChain chain = (rq, rs) -> { throw new ServletException("boom"); };
        assertThrows(ServletException.class,
            () -> new AnonIdFilter().doFilter(request(TOKEN), response(), chain));
        assertNull(SeeExtras.correlationId(), "token must be cleared even on exception");
    }

    @Test
    void filterDropsMalformedToken() throws Exception {
        for (String bad : new String[] {"short", "has spaces in it", "bad!chars_here", "x".repeat(65), ""}) {
            AtomicReference<String> seen = new AtomicReference<>("unset");
            FilterChain chain = (rq, rs) -> seen.set(SeeExtras.correlationId());
            new AnonIdFilter().doFilter(request(bad), response(), chain);
            assertNull(seen.get(), "malformed token must be dropped: " + bad);
        }
    }

    @Test
    void filterWithNoHeaderLeavesNoToken() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("unset");
        FilterChain chain = (rq, rs) -> seen.set(SeeExtras.correlationId());
        new AnonIdFilter().doFilter(request(null), response(), chain);
        assertNull(seen.get());
    }

    @Test
    void eventCarriesCorrelationIdInsideFilteredRequest() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            FilterChain chain = (rq, rs) ->
                c.see(new RuntimeException("x")).causesThe("checkout").to("use cached prices");
            new AnonIdFilter().doFilter(request(TOKEN), response(), chain);
            Map<String, Object> ev = awaitEvent();
            assertEquals(TOKEN, ev.get("correlation_id"));
        }
    }

    @Test
    void eventOmitsCorrelationIdWhenAbsent() throws Exception {
        try (Engine c = new Engine("srv_key", baseUrl())) {
            c.see(new RuntimeException("x")).causesThe("checkout").to("use cached prices");
            Map<String, Object> ev = awaitEvent();
            assertFalse(ev.containsKey("correlation_id"));
        }
    }

    @Test
    void buildSeeEventValidatesToken() {
        Map<String, Object> ok = See.buildSeeEvent(
            new RuntimeException("x"), "s", "o", null, "server", "v", null, "  " + TOKEN + " ");
        assertEquals(TOKEN, ok.get("correlation_id"));
        Map<String, Object> bad = See.buildSeeEvent(
            new RuntimeException("x"), "s", "o", null, "server", "v", null, "not valid!");
        assertFalse(bad.containsKey("correlation_id"));
        Map<String, Object> none = See.buildSeeEvent(
            new RuntimeException("x"), "s", "o", null, "server", "v", null);
        assertFalse(none.containsKey("correlation_id"));
    }

    @Test
    void clearExtrasAlsoClearsCorrelation() {
        SeeExtras.setCorrelationId(TOKEN);
        assertEquals(TOKEN, SeeExtras.correlationId());
        See.clearExtras();
        assertNull(SeeExtras.correlationId());
    }
}
