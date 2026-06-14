package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonIdTest {

    @Test
    void mintIsValidUuid() {
        String id = AnonId.mint();
        assertTrue(AnonId.isValid(id));
        assertEquals(36, id.length());
        assertNotEquals(AnonId.mint(), AnonId.mint());
    }

    @Test
    void rejectsTampered() {
        assertFalse(AnonId.isValid("bad value!"));
        assertFalse(AnonId.isValid(null));
        assertFalse(AnonId.isValid(""));
    }

    @Test
    void buildSetCookieContract() {
        String h = AnonId.buildSetCookie("abc", true);
        assertTrue(h.contains("__se_anon_id=abc"));
        assertTrue(h.contains("Path=/"));
        assertTrue(h.contains("Max-Age=31536000"));
        assertTrue(h.contains("SameSite=Lax"));
        assertTrue(h.contains("Secure"));
        assertFalse(h.contains("HttpOnly"));
        assertFalse(AnonId.buildSetCookie("abc", false).contains("Secure"));
    }

    @Test
    void threadLocalCurrent() {
        assertEquals(null, AnonId.current());
        AnonId.setCurrent("anon-1");
        assertEquals("anon-1", AnonId.current());
        AnonId.clear();
        assertEquals(null, AnonId.current());
    }

    // The no-unit eval rule is a cross-SDK contract.
    @Test
    void evalGateNoUnit() {
        assertTrue(Eval.evalGate(Map.of("enabled", 1, "salt", "s", "rolloutPct", 10000), Map.of()));
        assertFalse(Eval.evalGate(Map.of("enabled", 1, "salt", "s", "rolloutPct", 5000), Map.of()));
        assertFalse(Eval.evalGate(Map.of("enabled", 0, "rolloutPct", 10000), Map.of()));
        assertFalse(Eval.evalGate(Map.of("enabled", 1, "killswitch", 1, "rolloutPct", 10000), Map.of()));

        Map<String, Object> ruled = Map.of(
                "enabled", 1, "salt", "s", "rolloutPct", 10000,
                "rules", List.of(Map.of("attr", "plan", "op", "eq", "value", "pro")));
        assertFalse(Eval.evalGate(ruled, Map.of()));
        assertTrue(Eval.evalGate(ruled, Map.of("plan", "pro")));

        assertFalse(Eval.evalGate(Map.of("enabled", 1, "salt", "s", "rolloutPct", 0), Map.of("user_id", "u1")));
    }
}
