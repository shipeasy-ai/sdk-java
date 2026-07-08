package ai.shipeasy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
 * The lightweight, user-bound {@link Client} + the global
 * {@link Shipeasy#configure} front door.
 */
class BoundClientTest {

    @AfterEach
    void reset() {
        Shipeasy.resetForTest();
    }

    private static Engine engineWithGate(String name, int rolloutPct) {
        Map<String, Object> gate = Map.of("enabled", true, "rolloutPct", rolloutPct, "salt", "s");
        Map<String, Object> flags = Map.of("gates", Map.of(name, gate));
        return Engine.fromSnapshot(flags, null);
    }

    // configure() then new Client(user).getFlag(...) works with the identity
    // transform (the user object IS the attribute map).
    @Test
    void configureThenBoundGetFlag() {
        Shipeasy.useEngineForTest(engineWithGate("g", 10000), Shipeasy.IDENTITY);

        Client c = new Client(Map.of("user_id", "u_1"));
        assertTrue(c.getFlag("g"));
        assertFalse(c.getFlag("absent"));
        assertTrue(c.getFlag("absent", true));   // default returned when unevaluable
        assertEquals(FlagDetail.RULE_MATCH, c.getFlagDetail("g").reason());
    }

    // The attributes transform is applied: a raw user object is mapped to the
    // Shipeasy attribute map, and evaluation uses the mapped attrs (the gate
    // targets plan=pro, which only the mapped map carries).
    @Test
    void attributesTransformApplied() {
        Map<String, Object> gate = Map.of(
            "enabled", true,
            "rolloutPct", 10000,
            "salt", "s",
            "rules", java.util.List.of(Map.of("attr", "plan", "op", "eq", "value", "pro")));
        Engine engine = Engine.fromSnapshot(Map.of("gates", Map.of("g", gate)), null);

        // Transform: customer's own user record -> Shipeasy attribute map.
        Shipeasy.useEngineForTest(engine, raw -> {
            String[] u = (String[]) raw; // [id, plan]
            return Map.of("user_id", u[0], "plan", u[1]);
        });

        assertTrue(new Client(new String[]{"u_1", "pro"}).getFlag("g"));
        assertFalse(new Client(new String[]{"u_1", "free"}).getFlag("g"));

        // The bound attribute map is the transform's output, not the raw user.
        Client pro = new Client(new String[]{"u_1", "pro"});
        assertEquals("u_1", pro.attributes().get("user_id"));
        assertEquals("pro", pro.attributes().get("plan"));
    }

    // Constructing a Client before configure() fails loudly.
    @Test
    void constructBeforeConfigureThrows() {
        Shipeasy.resetForTest(); // ensure no engine
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new Client(Map.of("user_id", "u_1")));
        assertTrue(ex.getMessage().contains("configure"));
    }

    // getConfig / universe().assign() / getKillswitch forward to the engine with
    // the bound attrs (no user argument).
    @Test
    void configExperimentKillswitchForward() {
        Map<String, Object> flags = Map.of(
            "configs", Map.of("copy", Map.of("value", Map.of("title", "Hi"))),
            "killswitches", Map.of(
                "panic", Map.of("killed", true),
                "feature", Map.of("killed", false,
                    "switches", Map.of("beta", true))));
        Engine engine = Engine.fromSnapshot(flags, Map.of("experiments", Map.of(), "universes", Map.of()));
        Shipeasy.useEngineForTest(engine, Shipeasy.IDENTITY);

        Client c = new Client(Map.of("user_id", "u_1"));
        assertEquals(Map.of("title", "Hi"), c.getConfig("copy"));
        assertEquals("fallback", c.getConfig("absent", "fallback"));

        // experiment: an empty universe enrols nobody -> not enrolled, get()
        // resolves the caller's fallback (no universe defaults present).
        Assignment a = c.universe("none").assign();
        assertFalse(a.enrolled());
        assertNull(a.group());
        assertEquals("v", a.get("k", "v"));

        assertTrue(c.getKillswitch("panic"));
        assertFalse(c.getKillswitch("feature"));
        assertTrue(c.getKillswitch("feature", "beta"));
        assertFalse(c.getKillswitch("feature", "ga"));
        assertFalse(c.getKillswitch("unknown"));
    }

    // The bound Client.track derives the unit id from the bound attributes
    // (user_id, else anonymous_id) and reaches the engine's /collect POST with it.
    @Test
    void boundTrackDerivesUnitFromAttributes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collect", (HttpExchange ex) -> {
            body.set(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
        try (Engine engine = new Engine("k", "http://127.0.0.1:" + server.getAddress().getPort())) {
            engine.applyDataForTest(Map.of("gates", Map.of()), null);
            Shipeasy.useEngineForTest(engine, Shipeasy.IDENTITY);

            new Client(Map.of("user_id", "u_42")).track("checkout", Map.of("amount", 9));

            assertTrue(received.await(5, TimeUnit.SECONDS), "no /collect POST received");
            Map<String, Object> root = mapper.readValue(body.get(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) root.get("events");
            assertEquals(1, events.size());
            Map<String, Object> event = events.get(0);
            assertEquals("metric", event.get("type"));
            assertEquals("checkout", event.get("event_name"));
            assertEquals("u_42", event.get("user_id"));
            assertNotNull(event.get("properties"));
        } finally {
            server.stop(0);
        }
    }

    // No props overload and anonymous_id fallback when no user_id is bound.
    @Test
    void boundTrackFallsBackToAnonymousId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collect", (HttpExchange ex) -> {
            body.set(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
        try (Engine engine = new Engine("k", "http://127.0.0.1:" + server.getAddress().getPort())) {
            engine.applyDataForTest(Map.of("gates", Map.of()), null);
            Shipeasy.useEngineForTest(engine, Shipeasy.IDENTITY);

            new Client(Map.of("anonymous_id", "anon_7")).track("view");

            assertTrue(received.await(5, TimeUnit.SECONDS), "no /collect POST received");
            Map<String, Object> root = mapper.readValue(body.get(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) root.get("events");
            Map<String, Object> event = events.get(0);
            assertEquals("view", event.get("event_name"));
            assertEquals("anon_7", event.get("user_id"));
        } finally {
            server.stop(0);
        }
    }

    // The bound Client.universe().assign() re-evaluates with the bound attributes
    // and auto-logs one exposure for an enrolled user.
    @Test
    void boundAssignPostsExposureForEnrolledUser() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collect", (HttpExchange ex) -> {
            body.set(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
        Map<String, Object> exp = Map.of(
            "universe", "universe_pricing",
            "allocationPct", 10000,
            "salt", "exp_pricing_42",
            "status", "running",
            "groups", List.of(
                Map.of("name", "control", "weight", 9086, "params", Map.of("variant", "a")),
                Map.of("name", "treatment", "weight", 914, "params", Map.of("variant", "b"))));
        try (Engine engine = new Engine("k", "http://127.0.0.1:" + server.getAddress().getPort())) {
            engine.applyDataForTest(Map.of("gates", Map.of()),
                Map.of("experiments", Map.of("pricing_test", exp),
                    "universes", Map.of("universe_pricing", java.util.Collections.singletonMap("holdout_range", null))));
            Shipeasy.useEngineForTest(engine, Shipeasy.IDENTITY);

            Assignment a = new Client(Map.of("user_id", "user_beta")).universe("universe_pricing").assign();
            assertTrue(a.enrolled());

            assertTrue(received.await(5, TimeUnit.SECONDS), "no /collect POST received");
            Map<String, Object> root = mapper.readValue(body.get(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) root.get("events");
            Map<String, Object> event = events.get(0);
            assertEquals("exposure", event.get("type"));
            assertEquals("pricing_test", event.get("experiment"));
            assertEquals("user_beta", event.get("user_id"));
        } finally {
            server.stop(0);
        }
    }

    // configure(apiKey) is first-config-wins idempotent and registers the engine
    // as the see() default. (No network: the engine is real but points at an
    // invalid host; we only assert configure returns a stable engine.)
    @Test
    void configureFirstWins() {
        Engine first = Shipeasy.configure(
            Shipeasy.options("k").baseUrl("https://edge.invalid"));
        Engine second = Shipeasy.configure(
            Shipeasy.options("other").baseUrl("https://edge.invalid"));
        assertEquals(first, second);
        assertEquals(first, Shipeasy.engine());
    }
}
