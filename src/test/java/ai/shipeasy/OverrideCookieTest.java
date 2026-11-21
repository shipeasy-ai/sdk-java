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

    // ---- unsigned dev-only se_ov_dev cookie ----
    static final String DEV = "eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX0";

    private static Engine devEngine(boolean withKey, boolean option) {
        Engine e = engine(withKey);   // snapshot engine = non-production
        e.setDevOverrides(option);
        return e;
    }

    @Test
    void devCookieDecodesVector() {
        OverrideCookie.Overrides ov = OverrideCookie.parseDev("a=1; se_ov_dev=" + DEV + "; b=2");
        assertNotNull(ov);
        assertEquals(Map.of("lobby_hold", true, "old_nav", false), ov.gates);
        assertEquals(Map.of("banner", Map.of("text", "hi")), ov.configs);
        assertEquals(Map.of("checkout_exp", "treatment"), ov.exps);
        assertNotNull(OverrideCookie.parseDev(DEV));
        assertNotNull(OverrideCookie.parseDev("se_ov_dev=" + DEV.replace("e", "%65")));
        assertNull(OverrideCookie.parseDev("se_ov_dev=!!!notjson"));
        assertNull(OverrideCookie.parseDev("a=1"));
    }

    @Test
    void devCookieIgnoredWhenOptionOff() {
        Shipeasy.useEngineForTest(devEngine(false, false), Shipeasy.IDENTITY);
        Client c = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides("se_ov_dev=" + DEV, () -> {
            assertNull(RequestOverrides.current());
            assertFalse(c.getFlag("lobby_hold"));
        });
    }

    @Test
    void devCookieHonoredInNonProdWithOption() {
        Shipeasy.useEngineForTest(devEngine(false, true), Shipeasy.IDENTITY);
        Client c = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides("se_ov_dev=" + DEV, () -> {
            assertEquals(FlagDetail.OVERRIDE, c.getFlagDetail("lobby_hold").reason());
            assertTrue(c.getFlag("lobby_hold"));
            assertFalse(c.getFlag("old_nav"));
            assertEquals(Map.of("text", "hi"), c.getConfig("banner"));
            assertEquals("treatment", c.universe("u").assign().group());
        });
        assertFalse(c.getFlag("lobby_hold"));
    }

    @Test
    void devCookieIgnoredInProductionWithWarning() {
        String prev = System.getProperty("shipeasy.env");
        System.setProperty("shipeasy.env", "production");
        try {
            Engine e = new Engine("k", "http://127.0.0.1:1", "prod", true);
            e.setDevOverrides(true);
            assertFalse(e.devOverridesHonored());
            assertTrue(e.devOverridesWarned());
            e.setDevOverrides(true);   // still a single warning flag
            Shipeasy.useEngineForTest(e, Shipeasy.IDENTITY);
            Client c = new Client(Map.of("user_id", "u1"));
            Shipeasy.withRequestOverrides("se_ov_dev=" + DEV, () -> assertNull(RequestOverrides.current()));
        } finally {
            if (prev == null) System.clearProperty("shipeasy.env"); else System.setProperty("shipeasy.env", prev);
            Shipeasy.resetForTest();
        }
    }

    @Test
    void signedCookieBeatsDevCookiePerName() {
        Shipeasy.useEngineForTest(devEngine(true, true), Shipeasy.IDENTITY);
        Client c = new Client(Map.of("user_id", "u1"));
        // dev says lobby_hold=false, extra=true; signed says lobby_hold=true.
        String json = "{\"gates\":{\"lobby_hold\":false,\"prog\":true}}";
        String dev = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Shipeasy.withRequestOverrides("se_ov=" + COOKIE + "; se_ov_dev=" + dev, () -> {
            assertTrue(c.getFlag("lobby_hold"));   // signed wins
            assertTrue(c.getFlag("prog"));         // dev-only name still applies
        });
    }

    // ---- X-Shipeasy-Overrides header fallback ----

    @Test
    void headerOnlySignedCookieApplies() {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides(null, "se_ov=" + COOKIE, () -> assertTrue(c.getFlag("lobby_hold")));
        boolean v = Shipeasy.withRequestOverrides("a=1", "se_ov=" + COOKIE, () -> c.getFlag("lobby_hold"));
        assertTrue(v);
        Shipeasy.withRequestOverrides(null, "se_ov=" + TAMPERED, () -> assertFalse(c.getFlag("lobby_hold")));
    }

    @Test
    void headerOnlyDevCookieOnlyUnderGuards() {
        Shipeasy.useEngineForTest(devEngine(false, false), Shipeasy.IDENTITY);
        Client off = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides(null, "se_ov_dev=" + DEV, () -> assertFalse(off.getFlag("lobby_hold")));
        Shipeasy.useEngineForTest(devEngine(false, true), Shipeasy.IDENTITY);
        Client on = new Client(Map.of("user_id", "u1"));
        Shipeasy.withRequestOverrides(null, "se_ov_dev=" + DEV, () -> assertTrue(on.getFlag("lobby_hold")));
    }

    @Test
    void cookieBeatsHeaderForSameName() {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        // Cookie header has a tampered se_ov (name present => it wins and fails closed);
        // the header's valid one must not rescue it.
        Shipeasy.withRequestOverrides("se_ov=" + TAMPERED, "se_ov=" + COOKIE, () -> assertFalse(c.getFlag("lobby_hold")));
        // dev: cookie value wins over the header's
        Shipeasy.useEngineForTest(devEngine(false, true), Shipeasy.IDENTITY);
        Client d = new Client(Map.of("user_id", "u1"));
        String offDev = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"gates\":{\"lobby_hold\":false}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Shipeasy.withRequestOverrides("se_ov_dev=" + offDev, "se_ov_dev=" + DEV, () -> assertFalse(d.getFlag("lobby_hold")));
    }

    @Test
    void filterReadsOverridesHeader() throws Exception {
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        AtomicReference<Boolean> seen = new AtomicReference<>();
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {HttpServletRequest.class},
            (p, m, a) -> {
                switch (m.getName()) {
                    case "getHeader": return "X-Shipeasy-Overrides".equals(a[0]) ? "se_ov=" + COOKIE : null;
                    case "isSecure": return false;
                    default: return null;
                }
            });
        HttpServletResponse res = (HttpServletResponse) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> null);
        new AnonIdFilter().doFilter(req, res, (rq, rs) -> seen.set(c.getFlag("lobby_hold")));
        assertEquals(Boolean.TRUE, seen.get());
    }

    @Test
    void unrelatedSingleCookieIsNotABareValue() {
        assertNull(OverrideCookie.extract("theme=a.b"));
        assertNull(OverrideCookie.parseDev("theme=abc"));
        use(true);
        Client c = new Client(Map.of("user_id", "u1"));
        // an unrelated cookie must not block the header fallback
        Shipeasy.withRequestOverrides("theme=a.b", "se_ov=" + COOKIE, () -> assertTrue(c.getFlag("lobby_hold")));
    }

    // The suite pins shipeasy.env=production (InertInternalReportExtension); the
    // snapshot-engine dev tests above pass via localMode, so ALSO exercise the real
    // env-derived path with an explicit non-production env, restored afterwards.
    @Test
    void devCookieHonoredViaExplicitNonProdEnv() {
        String prev = System.getProperty("shipeasy.env");
        System.setProperty("shipeasy.env", "development");
        try {
            Engine e = new Engine("k", "http://127.0.0.1:1", "dev", true);
            e.setDevOverrides(true);
            assertTrue(e.devOverridesHonored());
            assertFalse(e.devOverridesWarned());
            e.applyDataForTest(Map.of("gates", Map.of("lobby_hold", Map.of("enabled", true, "rolloutPct", 0, "salt", "s"))), null);
            Shipeasy.useEngineForTest(e, Shipeasy.IDENTITY);
            Client c = new Client(Map.of("user_id", "u1"));
            Shipeasy.withRequestOverrides(null, "se_ov_dev=" + DEV, () -> assertTrue(c.getFlag("lobby_hold")));
            assertFalse(c.getFlag("lobby_hold"));
        } finally {
            if (prev == null) System.clearProperty("shipeasy.env"); else System.setProperty("shipeasy.env", prev);
            Shipeasy.resetForTest();
        }
    }
}
