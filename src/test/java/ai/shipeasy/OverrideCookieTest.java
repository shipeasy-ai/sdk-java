package ai.shipeasy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Signed DevTools override cookie (se_ov): verifier fixture + request scope + Client reads. */
class OverrideCookieTest {

    // Cross-impl fixture (shared with every SDK / core's cookie.ts).
    static final String KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA";
    static final String KEY_ID = "k1";
    static final String COOKIE = "eyJ2IjoxLCJwaWQiOiJwcm9qX2ZpeHR1cmUiLCJrZXlJZCI6ImsxIiwibm9uY2UiOiJmaXh0dXJlLW5vbmNlIiwiZXhwIjo0MTAyNDQ0ODAwLCJvdiI6eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX19.8qd-pHu1e96Th-jcu4BZOhLmZjwhKrgn-uI8IWBr5Og";
    static final String EXPIRED = "eyJ2IjoxLCJwaWQiOiJwcm9qX2ZpeHR1cmUiLCJrZXlJZCI6ImsxIiwibm9uY2UiOiJmaXh0dXJlLW5vbmNlIiwiZXhwIjoxMDAwLCJvdiI6eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX19.fZEp7eGjZSdi5EvTB-5NZLbZpVXbcWbIaEYRmPvPbd8";
    static final String TAMPERED = "eyJ2IjoxLCJwaWQiOiJwcm9qX2ZpeHR1cmUiLCJrZXlJZCI6ImsxIiwibm9uY2UiOiJmaXh0dXJlLW5vbmNlIiwiZXhwIjo0MTAyNDQ0ODAwLCJvdiI6eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX19.8qd-pHu1e96Th-jcu4BZOhLmZjwhKrgn-uI8IWBr5AA";
    static final long NOW = 1_800_000_000L;

    @AfterEach
    void reset() {
        Shipeasy.resetForTest();
    }

    private static Engine engine(boolean withKey) {
        Map<String, Object> gate = Map.of("enabled", true, "rolloutPct", 0, "salt", "s");
        Map<String, Object> flags = new HashMap<>();
        flags.put("gates", Map.of("lobby_hold", gate, "old_nav", Map.of("enabled", true, "rolloutPct", 10000, "salt", "s"),
            "prog", gate));
        flags.put("killswitches", Map.of("ks", Map.of("killed", false)));
        flags.put("configs", Map.of("banner", Map.of("value", Map.of("text", "orig"))));
        if (withKey) {
            flags.put("overrideKey", KEY);
            flags.put("overrideKeyId", KEY_ID);
        }
        Map<String, Object> exps = Map.of(
            "universes", Map.of("u", Map.of()),
            "experiments", Map.of("checkout_exp", Map.of(
                "universe", "u", "status", "running", "salt", "x",
                "groups", List.of(
                    Map.of("name", "control", "weight", 5000, "params", Map.of("color", "blue")),
                    Map.of("name", "treatment", "weight", 5000, "params", Map.of("color", "green"))))));
        return Engine.fromSnapshot(flags, exps);
    }

    private static void use(boolean withKey) {
        Shipeasy.useEngineForTest(engine(withKey), Shipeasy.IDENTITY);
    }

    @Test
    void fixtureVerifies() {
        OverrideCookie.Overrides ov = OverrideCookie.verify(COOKIE, KEY, KEY_ID, NOW);
        assertNotNull(ov);
        assertEquals(Map.of("lobby_hold", true, "old_nav", false), ov.gates);
        assertEquals(Map.of("banner", Map.of("text", "hi")), ov.configs);
        assertEquals(Map.of("checkout_exp", "treatment"), ov.exps);
    }

    @Test
    void rejectsExpiredTamperedWrongKeyIdAndNoKey() {
        assertNull(OverrideCookie.verify(EXPIRED, KEY, KEY_ID, NOW));
        assertNull(OverrideCookie.verify(TAMPERED, KEY, KEY_ID, NOW));
        assertNull(OverrideCookie.verify(COOKIE, KEY, "k2", NOW));
        assertNull(OverrideCookie.verify(COOKIE, null, KEY_ID, NOW));
        assertNull(OverrideCookie.verify(COOKIE, "AAAA", KEY_ID, NOW));       // wrong Kp
        assertNull(OverrideCookie.verify("nodot", KEY, KEY_ID, NOW));
        assertNull(OverrideCookie.verify("a.b.c", KEY, KEY_ID, NOW));
        assertNull(OverrideCookie.verify(".sig", KEY, KEY_ID, NOW));
        assertNull(OverrideCookie.verify("!!!.???", KEY, KEY_ID, NOW));
    }

    @Test
    void extractsFromCookieHeaderOrBareValue() {
        assertEquals(COOKIE, OverrideCookie.extract(COOKIE));
        assertEquals(COOKIE, OverrideCookie.extract("a=1; se_ov=" + COOKIE + "; b=2"));
        assertEquals(COOKIE, OverrideCookie.extract("se_ov=" + COOKIE));
        assertEquals("a.b", OverrideCookie.extract("se_ov=a%2Eb; x=1"));
        assertNull(OverrideCookie.extract("a=1; b=2"));
        assertNull(OverrideCookie.extract("nodots"));
        assertNull(OverrideCookie.extract(null));
        assertNull(OverrideCookie.extract(""));
    }

    @Test
    void getFlagOverriddenInsideScopeOnly() {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        String header = "x=1; se_ov=" + COOKIE;
        assertFalse(c.getFlag("lobby_hold"));
        assertTrue(c.getFlag("old_nav"));
        Shipeasy.withRequestOverrides(header, () -> {
            FlagDetail d = c.getFlagDetail("lobby_hold");
            assertTrue(d.value());
            assertEquals(FlagDetail.OVERRIDE, d.reason());
            assertFalse(c.getFlag("old_nav"));
            assertEquals("OVERRIDE", c.getFlagDetail("old_nav").reason());
            assertEquals(Map.of("text", "hi"), c.getConfig("banner"));
            assertEquals("treatment", c.universe("u").assign().group());
            assertEquals("green", c.universe("u").assign().peek("color", null));
        });
        // outside the scope: unchanged and cleared
        assertNull(RequestOverrides.current());
        assertFalse(c.getFlag("lobby_hold"));
        assertEquals(Map.of("text", "orig"), c.getConfig("banner"));
    }

    @Test
    void supplierVariantReturnsAndClearsOnThrow() {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        boolean v = Shipeasy.withRequestOverrides(COOKIE, () -> c.getFlag("lobby_hold"));
        assertTrue(v);
        assertThrows(IllegalStateException.class, () ->
            Shipeasy.withRequestOverrides(COOKIE, (Runnable) () -> { throw new IllegalStateException("x"); }));
        assertNull(RequestOverrides.current());
    }

    @Test
    void killSwitchSharesGateNamespace() {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        String cookie = COOKIE;
        assertFalse(c.getKillswitch("ks"));
        // fixture has no "ks"; sanity: a gate-named switch override applies to whole-switch reads
        Shipeasy.withRequestOverrides(cookie, () -> assertFalse(c.getKillswitch("ks")));
        Shipeasy.withRequestOverrides(cookie, () -> assertTrue(c.getKillswitch("lobby_hold")));
        Shipeasy.withRequestOverrides(cookie, () -> assertFalse(c.getKillswitch("lobby_hold", "someKey")));
    }

    @Test
    void noKeyServedMeansNoOverrides() {
        use(false);
        Client c = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides(COOKIE, () -> {
            assertNull(RequestOverrides.current());
            assertFalse(c.getFlag("lobby_hold"));
        });
    }

    @Test
    void invalidCookiesAreIgnoredInScope() {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        for (String bad : new String[] {EXPIRED, TAMPERED, "junk", null}) {
            Shipeasy.withRequestOverrides(bad, () -> assertFalse(c.getFlag("lobby_hold")));
        }
    }

    @Test
    void programmaticOverrideBeatsCookie() {
        use(true);
        Shipeasy.overrideFlag("lobby_hold", false);
        Shipeasy.overrideConfig("banner", Map.of("text", "prog"));
        Client c = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides(COOKIE, () -> {
            assertFalse(c.getFlag("lobby_hold"));
            assertEquals(Map.of("text", "prog"), c.getConfig("banner"));
        });
    }

    @Test
    void scopesNestAndRestore() {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides(COOKIE, () -> {
            Shipeasy.withRequestOverrides(null, () -> assertFalse(c.getFlag("lobby_hold")));
            assertTrue(c.getFlag("lobby_hold"));
        });
    }

    @Test
    void filterScopesAndClears() throws Exception {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        AtomicReference<Boolean> seen = new AtomicReference<>();
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {HttpServletRequest.class},
            (p, m, a) -> {
                switch (m.getName()) {
                    case "getHeader": return "Cookie".equals(a[0]) ? "se_ov=" + COOKIE : null;
                    case "isSecure": return false;
                    default: return null;
                }
            });
        HttpServletResponse res = (HttpServletResponse) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> null);
        FilterChain chain = (rq, rs) -> seen.set(c.getFlag("lobby_hold"));
        new AnonIdFilter().doFilter(req, res, chain);
        assertEquals(Boolean.TRUE, seen.get());
        assertNull(RequestOverrides.current());
        assertFalse(c.getFlag("lobby_hold"));
    }
}
