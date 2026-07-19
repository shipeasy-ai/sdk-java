package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gatekeeper {@code stack} evaluation in the server SDK.
 *
 * <p>Regression guard for the bug where {@link Eval#evalGate} read only the flat
 * {@code rules}+{@code rolloutPct} columns and ignored a modern gate's ordered
 * {@code stack}. The canonical model is the stack (mirrors {@code @shipeasy/core}
 * evalGatekeeper + the edge worker); the flat columns are a lossy approximation
 * that can invert the result (a whitelist condition at 100% followed by a 0%
 * public rollout flattens to {@code rolloutPct: 0}). These vectors lock the SDK
 * to the stack.
 */
class GateStackTest {

    private static final int MOD = 10000;
    private static final String P = "e976b15e-3ccc-44d3-821d-87f06d5a0e43";

    private static Engine withGate(String name, Map<String, Object> gate) {
        return Engine.fromSnapshot(Map.of("gates", Map.of(name, gate)), null);
    }

    // The exact shape the KV rebuild ships for a whitelist gatekeeper: a
    // condition (no explicit rolloutPct means 100%) that whitelists a project,
    // then a locked 0% public rollout. The flat columns are the lossy
    // approximation and must NOT decide the result.
    private static Map<String, Object> whitelistGate() {
        return Map.of(
                "name", "release_module",
                "enabled", 1,
                "salt", "caf3a1ae",
                "rules", List.of(Map.of("attr", "project_id", "op", "in", "value", List.of(P))),
                "rolloutPct", 0,
                "stack", List.of(
                        Map.of("id", "gq578snc", "type", "condition", "pass", "all",
                                "rules", List.of(Map.of("attr", "project_id", "op", "in", "value", List.of(P)))),
                        Map.of("id", "gu0uein4", "type", "rollout", "rolloutPct", 0,
                                "bucketBy", "user_id", "salt", "public")));
    }

    @Test
    void whitelistedCallerPassesDespiteFlatZeroRollout() {
        try (Engine c = withGate("release_module", whitelistGate())) {
            // The regression: the flat path would read "matches whitelist AND 0%
            // bucket" = false. The stack short-circuits on the 100% condition.
            assertTrue(c.getFlag("release_module", Map.of("user_id", "cdewqzx@gmail.com", "project_id", P)));
        }
    }

    @Test
    void nonWhitelistedCallerHidden() {
        try (Engine c = withGate("release_module", whitelistGate())) {
            assertFalse(c.getFlag("release_module",
                    Map.of("user_id", "someone@else.com", "project_id", "other-project")));
        }
    }

    @Test
    void whitelistedCallerWithNoIdentityPasses() {
        try (Engine c = withGate("release_module", whitelistGate())) {
            // No user_id/anonymous_id: a fully-rolled (100%) condition is
            // answerable without a unit id.
            assertTrue(c.getFlag("release_module", Map.of("project_id", P)));
        }
    }

    @Test
    void matchingConditionStillGatesOnItsOwnRollout() {
        Map<String, Object> gate = Map.of(
                "name", "g", "enabled", 1, "salt", "s", "rules", List.of(), "rolloutPct", 0,
                "stack", List.of(Map.of(
                        "id", "c1", "type", "condition", "pass", "all",
                        "rules", List.of(Map.of("attr", "project_id", "op", "in", "value", List.of(P))),
                        "rolloutPct", 0))); // matched but 0% -> never
        try (Engine c = withGate("g", gate)) {
            assertFalse(c.getFlag("g", Map.of("user_id", "u1", "project_id", P)));
        }
    }

    @Test
    void passAnyConditionMatchesEitherBranch() {
        Map<String, Object> gate = Map.of(
                "name", "g", "enabled", 1, "salt", "s", "rules", List.of(), "rolloutPct", 0,
                "stack", List.of(Map.of(
                        "id", "c1", "type", "condition", "pass", "any",
                        "rules", List.of(
                                Map.of("attr", "plan", "op", "eq", "value", "pro"),
                                Map.of("attr", "project_id", "op", "in", "value", List.of(P))))));
        try (Engine c = withGate("g", gate)) {
            // plan misses but project_id matches -> pass.
            assertTrue(c.getFlag("g", Map.of("user_id", "u", "plan", "free", "project_id", P)));
            // neither branch matches -> fail.
            assertFalse(c.getFlag("g", Map.of("user_id", "u", "plan", "free", "project_id", "x")));
        }
    }

    @Test
    void fallsThroughToCatchAllRollout() {
        Map<String, Object> gate = Map.of(
                "name", "g", "enabled", 1, "salt", "s", "rules", List.of(), "rolloutPct", 0,
                "stack", List.of(
                        Map.of("id", "c1", "type", "condition", "pass", "all",
                                "rules", List.of(Map.of("attr", "project_id", "op", "in", "value", List.of(P)))),
                        Map.of("id", "public", "type", "rollout", "rolloutPct", MOD))); // everyone else: 100%
        try (Engine c = withGate("g", gate)) {
            assertTrue(c.getFlag("g", Map.of("user_id", "u", "project_id", "not-whitelisted")));
        }
    }

    @Test
    void disabledOrKilledStackedGateIsOff() {
        Map<String, Object> base = whitelistGate();
        Map<String, Object> disabled = new java.util.HashMap<>(base);
        disabled.put("enabled", 0);
        try (Engine c = withGate("g", disabled)) {
            assertFalse(c.getFlag("g", Map.of("user_id", "u", "project_id", P)));
        }
        Map<String, Object> killed = new java.util.HashMap<>(base);
        killed.put("killswitch", 1);
        try (Engine c = withGate("g", killed)) {
            assertFalse(c.getFlag("g", Map.of("user_id", "u", "project_id", P)));
        }
    }

    @Test
    void stackLessGateUsesLegacyFlatPath() {
        Engine c = Engine.fromSnapshot(Map.of("gates", Map.of(
                "on", Map.of("name", "on", "enabled", 1, "salt", "s", "rules", List.of(), "rolloutPct", MOD),
                "off", Map.of("name", "off", "enabled", 1, "salt", "s", "rules", List.of(), "rolloutPct", 0))), null);
        try (c) {
            assertTrue(c.getFlag("on", Map.of("user_id", "u")));
            assertFalse(c.getFlag("off", Map.of("user_id", "u")));
        }
    }
}
