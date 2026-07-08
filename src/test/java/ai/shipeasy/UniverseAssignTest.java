package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Universe-first assignment (the mutual-exclusion pool model, doc 20 §B).
 *
 * <p>{@code engine.universe(name).assign(user)} returns an {@link Assignment}: the
 * <=1 experiment the unit landed in within the universe, its variant, and resolved
 * params (variant override ?? universe default ?? fallback). These specs lock the
 * merge (§B2), the not-enrolled defaults path, pooled mutual exclusion (§B4),
 * reserved headroom (§B5), and the holdout gate (§B3). Mirrors the canonical TS
 * SDK's {@code universe-assign.test.ts}. Blobs are seeded directly (no network).
 */
class UniverseAssignTest {

    private static final int MOD = 10000;

    private static int universeSeg(String universe, String uid) {
        return Murmur3.bucket(universe + ":" + uid, MOD);
    }

    private static Engine engine(Map<String, Object> flags, Map<String, Object> exps) {
        return Engine.fromSnapshot(flags, exps);
    }

    // ---- param merge (§B2) -------------------------------------------------

    // A universe owns button_color=red, size=1. The one running experiment's
    // assigned variant overrides only button_color.
    @Test
    void variantOverrideWinsUnsetInheritsUnknownFallsBack() {
        Map<String, Object> exps = Map.of(
            "universes", Map.of("u", Map.of(
                "param_schema", List.of(
                    Map.of("name", "button_color", "type", "string", "default", "red"),
                    Map.of("name", "size", "type", "int", "default", 1)))),
            "experiments", Map.of("exp", Map.of(
                "universe", "u",
                "allocationPct", 10000,
                "salt", "s",
                "status", "running",
                "groups", List.of(Map.of("name", "treatment", "weight", 10000,
                    "params", Map.of("button_color", "blue"))))));
        try (Engine c = engine(Map.of("gates", Map.of()), exps)) {
            Assignment a = c.universe("u").assign(Map.of("user_id", "u1"));
            assertTrue(a.enrolled());
            assertEquals("treatment", a.group());
            // Overridden by the variant.
            assertEquals("blue", a.get("button_color", "x"));
            // Not overridden -> inherited from the universe default.
            assertEquals(1, a.get("size", 99));
            // Absent everywhere -> the caller's fallback.
            assertEquals("fb", a.get("missing", "fb"));
        }
    }

    // ---- not enrolled still gets universe defaults -------------------------

    @Test
    void notEnrolledResolvesUniverseDefault() {
        Map<String, Object> exps = Map.of(
            "universes", Map.of("u", Map.of(
                "param_schema", List.of(
                    Map.of("name", "button_color", "type", "string", "default", "red")))),
            "experiments", Map.of("exp", Map.of(
                "universe", "u",
                "allocationPct", 0, // nobody allocated
                "salt", "s",
                "status", "running",
                "groups", List.of(Map.of("name", "treatment", "weight", 10000,
                    "params", Map.of("button_color", "blue"))))));
        try (Engine c = engine(Map.of("gates", Map.of()), exps)) {
            Assignment a = c.universe("u").assign(Map.of("user_id", "u1"));
            assertFalse(a.enrolled());
            assertNull(a.group());
            // Not enrolled -> universe default, not the variant override.
            assertEquals("red", a.get("button_color", "fb"));
        }
    }

    // ---- pooled mutual exclusion (§B4) -------------------------------------

    // Two experiments in ONE universe, hashVersion 2, disjoint pool slices:
    //   A = [0, 4000), B = [4000, 8000). Segment >= 8000 is unallocated headroom.
    @Test
    void pooledMutualExclusion() {
        Map<String, Object> exps = Map.of(
            "universes", Map.of("u", java.util.Collections.singletonMap("holdout_range", null)),
            "experiments", Map.of(
                "expA", Map.of(
                    "universe", "u", "hashVersion", 2, "poolOffsetBp", 0, "poolSizeBp", 4000,
                    "allocationPct", 10000, "salt", "sA", "status", "running",
                    "groups", List.of(Map.of("name", "A", "weight", 10000, "params", Map.of()))),
                "expB", Map.of(
                    "universe", "u", "hashVersion", 2, "poolOffsetBp", 4000, "poolSizeBp", 4000,
                    "allocationPct", 10000, "salt", "sB", "status", "running",
                    "groups", List.of(Map.of("name", "B", "weight", 10000, "params", Map.of())))));
        try (Engine c = engine(Map.of("gates", Map.of()), exps)) {
            int inA = 0, inB = 0, neither = 0;
            for (int i = 0; i < 400; i++) {
                String uid = "u" + i;
                Assignment a = c.universe("u").assign(Map.of("user_id", uid));
                int seg = universeSeg("u", uid);
                if ("expA".equals(a.name())) {
                    inA++;
                    assertTrue(seg < 4000);
                } else if ("expB".equals(a.name())) {
                    inB++;
                    assertTrue(seg >= 4000 && seg < 8000);
                } else {
                    neither++;
                    assertFalse(a.enrolled());
                    assertTrue(seg >= 8000);
                }
            }
            // The partition is real: all three buckets are populated over 400 users.
            assertTrue(inA > 0);
            assertTrue(inB > 0);
            assertTrue(neither > 0);
            assertEquals(400, inA + inB + neither);
        }
    }

    // ---- reserved headroom (§B5) -------------------------------------------

    // 100% allocation, one group summing to 5000 with reservedHeadroomBp 5000:
    // units whose group hash falls in the reserved tail are left not-enrolled.
    @Test
    void reservedHeadroomLeavesTailUnassigned() {
        Map<String, Object> exps = Map.of(
            "universes", Map.of("u", java.util.Collections.singletonMap("holdout_range", null)),
            "experiments", Map.of("exp", Map.of(
                "universe", "u",
                "allocationPct", 10000,
                "reservedHeadroomBp", 5000,
                "salt", "s",
                "status", "running",
                "groups", List.of(Map.of("name", "control", "weight", 5000, "params", Map.of())))));
        try (Engine c = engine(Map.of("gates", Map.of()), exps)) {
            int enrolled = 0, reserved = 0;
            for (int i = 0; i < 400; i++) {
                Assignment a = c.universe("u").assign(Map.of("user_id", "u" + i));
                if (a.enrolled()) enrolled++;
                else reserved++;
            }
            // Both populated: allocation is 100% yet the reserved tail carves out ~half.
            assertTrue(enrolled > 0);
            assertTrue(reserved > 0);
        }
    }

    // ---- holdoutGate (§B3) -------------------------------------------------

    @Test
    void holdoutGateForcesHoldout() {
        Map<String, Object> flags = Map.of("gates", Map.of(
            // enabled, 100% rollout, no rules -> passes for every identified unit.
            "hg", Map.of("rules", List.of(), "rolloutPct", 10000, "salt", "hg", "enabled", 1)));
        Map<String, Object> exps = Map.of(
            "universes", Map.of("u", java.util.Collections.singletonMap("holdout_range", null)),
            "experiments", Map.of("exp", Map.of(
                "universe", "u",
                "holdoutGate", "hg",
                "allocationPct", 10000,
                "salt", "s",
                "status", "running",
                "groups", List.of(Map.of("name", "treatment", "weight", 10000, "params", Map.of())))));
        try (Engine c = engine(flags, exps)) {
            Assignment a = c.universe("u").assign(Map.of("user_id", "u1"));
            assertFalse(a.enrolled());
            assertNull(a.group());
        }
    }
}
