package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class OfflineSnapshotTest {

    // fromSnapshot runs the real eval against the in-memory blobs, no network.
    @Test
    void fromSnapshotEvaluatesWithNoNetwork() {
        Map<String, Object> flags = Map.of(
            "gates", Map.of("new_checkout",
                Map.of("enabled", true, "rolloutPct", 10000, "salt", "s")),
            "configs", Map.of("billing_copy",
                Map.of("value", Map.of("title", "Hi"))));

        try (Engine c = Engine.fromSnapshot(flags, null)) {
            // No init() needed; points at an invalid host but never calls it.
            assertTrue(c.getFlag("new_checkout", Map.of("user_id", "u_1")));
            assertEquals(Map.of("title", "Hi"), c.getConfig("billing_copy"));
            // init/initOnce/track are no-ops in snapshot mode.
            assertDoesNotThrow(() -> { c.init(); c.initOnce(); });
            c.track("u_1", "purchase", Map.of("amount", 9));
        }
    }

    // Overrides apply on top of the snapshot.
    @Test
    void overridesApplyOnTopOfSnapshot() {
        Map<String, Object> flags = Map.of(
            "gates", Map.of("g", Map.of("enabled", true, "rolloutPct", 0)));
        try (Engine c = Engine.fromSnapshot(flags, null)) {
            assertFalse(c.getFlag("g", Map.of("user_id", "u_1")));
            c.overrideFlag("g", true);
            assertTrue(c.getFlag("g", Map.of("user_id", "u_1")));
        }
    }

    // fromFile reads the { "flags": ..., "experiments": ... } shape and evaluates.
    @Test
    void fromFileLoadsAndEvaluates() throws IOException {
        String json = "{"
            + "\"flags\":{\"gates\":{\"g\":{\"enabled\":true,\"rolloutPct\":10000,\"salt\":\"s\"}}},"
            + "\"experiments\":{\"experiments\":{},\"universes\":{}}"
            + "}";
        Path tmp = Files.createTempFile("se-snapshot", ".json");
        try {
            Files.writeString(tmp, json);
            try (Engine c = Engine.fromFile(tmp.toString())) {
                assertTrue(c.getFlag("g", Map.of("user_id", "u_1")));
                assertNull(c.getConfig("absent"));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
