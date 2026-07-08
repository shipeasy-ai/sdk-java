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
            .configs(Map.of("theme", "blue")));

        Client c = new Client(Map.of("user_id", "u_1"));
        assertTrue(c.getFlag("new_checkout"));
        assertEquals("blue", c.getConfig("theme"));

        // REPLACE (not first-wins): a second call wins.
        Shipeasy.configureForTesting(Shipeasy.testOptions().flags(Map.of("new_checkout", false)));
        assertFalse(new Client(Map.of()).getFlag("new_checkout"));
    }

    // An experiment override surfaces through universe().assign() only when the
    // experiment exists in the universe — overrides refine a real experiment,
    // they don't invent one in an empty universe. Seed a real (offline)
    // experiment, then force the enrolment with overrideExperiment.
    @Test
    void experimentOverrideSurfacesThroughAssign() throws Exception {
        Map<String, Object> exp = Map.of(
            "universe", "pricing",
            "allocationPct", 10000,
            "salt", "s",
            "status", "running",
            "groups", java.util.List.of(Map.of("name", "control", "weight", 10000, "params", Map.of("price", 0))));
        Map<String, Object> snapshot = Map.of(
            "flags", Map.of("gates", Map.of(), "configs", Map.of(), "killswitches", Map.of()),
            "experiments", Map.of(
                "universes", Map.of("pricing", java.util.Collections.singletonMap("holdout_range", null)),
                "experiments", Map.of("price_test", exp)));
        Shipeasy.configureForOffline(Shipeasy.offlineOptions().snapshot(snapshot)
            .experiments(Map.of("price_test", Shipeasy.Variant.of("treatment", Map.of("price", 9)))));

        Assignment a = new Client(Map.of("user_id", "u_1")).universe("pricing").assign();
        assertTrue(a.enrolled());
        assertEquals("treatment", a.group());
        assertEquals(9, a.get("price", 0));
    }

    @Test
    void packageOverridesAndClear() throws Exception {
        Map<String, Object> exp = Map.of(
            "universe", "u",
            "allocationPct", 10000,
            "salt", "s",
            "status", "running",
            "groups", java.util.List.of(Map.of("name", "A", "weight", 10000, "params", Map.of("v", 1))));
        Map<String, Object> snapshot = Map.of(
            "flags", Map.of("gates", Map.of(), "configs", Map.of(), "killswitches", Map.of()),
            "experiments", Map.of(
                "universes", Map.of("u", java.util.Collections.singletonMap("holdout_range", null)),
                "experiments", Map.of("e", exp)));
        Shipeasy.configureForOffline(Shipeasy.offlineOptions().snapshot(snapshot).flags(Map.of("f", true)));
        Shipeasy.overrideFlag("f", false);
        Shipeasy.overrideConfig("c", 123);
        Shipeasy.overrideExperiment("e", "B", Map.of("v", 2));

        Client c = new Client(Map.of("user_id", "u"));
        assertFalse(c.getFlag("f"));
        assertEquals(123, c.getConfig("c"));
        assertEquals("B", c.universe("u").assign().group());

        // clearOverrides drops the override layer; the experiment reverts to its
        // real assignment (group A).
        Shipeasy.clearOverrides();
        assertNull(new Client(Map.of()).getConfig("c"));
        assertEquals("A", new Client(Map.of("user_id", "u")).universe("u").assign().group());
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
