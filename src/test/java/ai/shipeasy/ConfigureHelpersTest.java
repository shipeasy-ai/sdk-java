package ai.shipeasy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doc-23 configure() family + package-level helpers, all read through the bound
 * {@link Client} (never the {@link Engine}).
 */
class ConfigureHelpersTest {

    @AfterEach
    void reset() {
        Shipeasy.resetForTest();
    }

    @Test
    void configureForTestingSeedsAndReplaces() {
        Shipeasy.configureForTesting(Shipeasy.testOptions()
            .flags(Map.of("new_checkout", true))
            .configs(Map.of("theme", "blue"))
            .experiments(Map.of("price_test", Shipeasy.Variant.of("treatment", Map.of("price", 9)))));

        Client c = new Client(Map.of("user_id", "u_1"));
        assertTrue(c.getFlag("new_checkout"));
        assertEquals("blue", c.getConfig("theme"));
        ExperimentResult r = c.getExperiment("price_test", null);
        assertTrue(r.inExperiment);
        assertEquals("treatment", r.group);

        // REPLACE (not first-wins): a second call wins.
        Shipeasy.configureForTesting(Shipeasy.testOptions().flags(Map.of("new_checkout", false)));
        assertFalse(new Client(Map.of()).getFlag("new_checkout"));
    }

    @Test
    void packageOverridesAndClear() {
        Shipeasy.configureForTesting(Shipeasy.testOptions().flags(Map.of("f", true)));
        Shipeasy.overrideFlag("f", false);
        Shipeasy.overrideConfig("c", 123);
        Shipeasy.overrideExperiment("e", "B", Map.of("v", 2));

        Client c = new Client(Map.of("user_id", "u"));
        assertFalse(c.getFlag("f"));
        assertEquals(123, c.getConfig("c"));
        assertEquals("B", c.getExperiment("e", null).group);

        // Test mode has no blob beneath: clearOverrides drops the seed too.
        Shipeasy.clearOverrides();
        assertNull(new Client(Map.of()).getConfig("c"));
    }

    @Test
    void overrideBeforeConfigureThrows() {
        assertThrows(IllegalStateException.class, () -> Shipeasy.overrideFlag("f", true));
    }

    @Test
    void configureForOfflineLayersOverrides() throws Exception {
        Map<String, Object> snapshot = Map.of(
            "flags", Map.of(
                "gates", Map.of("on_for_all", Map.of("enabled", true, "rolloutPct", 10000, "salt", "s")),
                "configs", Map.of("color", Map.of("value", "green")),
                "killswitches", Map.of()),
            "experiments", Map.of("experiments", Map.of(), "universes", Map.of()));

        Shipeasy.configureForOffline(Shipeasy.offlineOptions().snapshot(snapshot));
        assertTrue(new Client(Map.of("user_id", "u_1")).getFlag("on_for_all"));
        assertEquals("green", new Client(Map.of()).getConfig("color"));

        Shipeasy.overrideFlag("on_for_all", false);
        assertFalse(new Client(Map.of()).getFlag("on_for_all"));
        Shipeasy.clearOverrides();
        assertTrue(new Client(Map.of()).getFlag("on_for_all"));
    }

    @Test
    void configureForOfflineRequiresSource() {
        assertThrows(IllegalArgumentException.class,
            () -> Shipeasy.configureForOffline(Shipeasy.offlineOptions()));
    }
}
