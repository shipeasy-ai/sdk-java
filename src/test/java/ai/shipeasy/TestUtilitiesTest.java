package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TestUtilitiesTest {
    // forTesting() needs no network and no API key, and is usable without init().
    @Test
    void forTestingNeedsNoNetworkOrKey() {
        try (Engine c = Engine.forTesting()) {
            // No init()/key required: an unseeded flag is simply false (no fetch).
            assertFalse(c.getFlag("anything", Map.of()));
            assertNull(c.getConfig("anything"));
        }
    }

    // init()/initOnce() are no-ops in test mode (never throw, never fetch).
    @Test
    void initIsNoOpInTestMode() {
        assertDoesNotThrow(() -> {
            try (Engine c = Engine.forTesting()) {
                c.init();
                c.initOnce();
            }
        });
    }

    // A real experiment in universe "checkout": group "control" for every unit.
    private static Map<String, Object> checkoutSnapshotExps() {
        Map<String, Object> exp = Map.of(
            "universe", "checkout",
            "allocationPct", 10000,
            "salt", "s",
            "status", "running",
            "groups", java.util.List.of(Map.of("name", "control", "weight", 10000, "params", Map.of("color", "grey"))));
        return Map.of(
            "universes", Map.of("checkout", java.util.Collections.singletonMap("holdout_range", null)),
            "experiments", Map.of("checkout_button", exp));
    }

    // Each override is returned by the matching getter; an experiment override
    // surfaces through universe().assign() when the experiment lives in the blob.
    @Test
    void overridesWin() {
        try (Engine c = Engine.fromSnapshot(Map.of("gates", Map.of()), checkoutSnapshotExps())) {
            c.overrideFlag("new_checkout", true);
            assertTrue(c.getFlag("new_checkout", Map.of()));

            c.overrideConfig("billing_copy", Map.of("title", "Hi"));
            assertEquals(Map.of("title", "Hi"), c.getConfig("billing_copy"));

            c.overrideExperiment("checkout_button", "treatment", Map.of("color", "green"));
            Assignment a = c.universe("checkout").assign(Map.of("user_id", "u1"));
            assertTrue(a.enrolled());
            assertEquals("treatment", a.group());
            assertEquals("green", a.get("color", "blue"));
        }
    }

    // clearOverrides() resets every override back to the no-override behavior;
    // the experiment reverts to its real assignment (group control).
    @Test
    void clearOverridesResets() {
        try (Engine c = Engine.fromSnapshot(Map.of("gates", Map.of()), checkoutSnapshotExps())) {
            c.overrideFlag("new_checkout", true);
            c.overrideConfig("billing_copy", "x");
            c.overrideExperiment("checkout_button", "treatment", Map.of("color", "green"));

            c.clearOverrides();

            assertFalse(c.getFlag("new_checkout", Map.of()));
            assertNull(c.getConfig("billing_copy"));
            Assignment a = c.universe("checkout").assign(Map.of("user_id", "u1"));
            assertTrue(a.enrolled());
            assertEquals("control", a.group());
            assertEquals("grey", a.get("color", "blue"));
        }
    }

    // track() is a no-op in test mode — no network, never throws.
    @Test
    void trackIsNoOp() {
        assertDoesNotThrow(() -> {
            try (Engine c = Engine.forTesting()) {
                c.track("u_123", "purchase", Map.of("amount", 49));
            }
        });
    }

    // Overrides also work on a normal (non-test) client.
    @Test
    void overridesWorkOnNormalClient() {
        try (Engine c = new Engine("dummy-key", "https://edge.invalid")) {
            c.overrideFlag("new_checkout", true);
            assertTrue(c.getFlag("new_checkout", Map.of()));
        }
    }
}
