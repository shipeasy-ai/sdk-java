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
        try (Client c = Client.forTesting()) {
            // No init()/key required: an unseeded flag is simply false (no fetch).
            assertFalse(c.getFlag("anything", Map.of()));
            assertNull(c.getConfig("anything"));
        }
    }

    // init()/initOnce() are no-ops in test mode (never throw, never fetch).
    @Test
    void initIsNoOpInTestMode() {
        assertDoesNotThrow(() -> {
            try (Client c = Client.forTesting()) {
                c.init();
                c.initOnce();
            }
        });
    }

    // Each override is returned by the matching getter.
    @Test
    void overridesWin() {
        try (Client c = Client.forTesting()) {
            c.overrideFlag("new_checkout", true);
            assertTrue(c.getFlag("new_checkout", Map.of()));

            c.overrideConfig("billing_copy", Map.of("title", "Hi"));
            assertEquals(Map.of("title", "Hi"), c.getConfig("billing_copy"));

            c.overrideExperiment("checkout_button", "treatment", Map.of("color", "green"));
            ExperimentResult r = c.getExperiment("checkout_button", Map.of(), Map.of("color", "blue"));
            assertTrue(r.inExperiment);
            assertEquals("treatment", r.group);
            assertEquals(Map.of("color", "green"), r.params);
        }
    }

    // clearOverrides() resets every override back to the no-override behavior.
    @Test
    void clearOverridesResets() {
        try (Client c = Client.forTesting()) {
            c.overrideFlag("new_checkout", true);
            c.overrideConfig("billing_copy", "x");
            c.overrideExperiment("checkout_button", "treatment", Map.of("color", "green"));

            c.clearOverrides();

            assertFalse(c.getFlag("new_checkout", Map.of()));
            assertNull(c.getConfig("billing_copy"));
            ExperimentResult r = c.getExperiment("checkout_button", Map.of(), Map.of("color", "blue"));
            assertFalse(r.inExperiment);
            assertEquals(Map.of("color", "blue"), r.params);
        }
    }

    // track() is a no-op in test mode — no network, never throws.
    @Test
    void trackIsNoOp() {
        assertDoesNotThrow(() -> {
            try (Client c = Client.forTesting()) {
                c.track("u_123", "purchase", Map.of("amount", 49));
            }
        });
    }

    // Overrides also work on a normal (non-test) client.
    @Test
    void overridesWorkOnNormalClient() {
        try (Client c = new Client("dummy-key", "https://edge.invalid")) {
            c.overrideFlag("new_checkout", true);
            assertTrue(c.getFlag("new_checkout", Map.of()));
        }
    }
}
