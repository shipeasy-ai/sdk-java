package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sticky bucketing (doc 20 §2). Mirrors the canonical TS server eval: after the
 * holdout, before allocation, a stored entry whose salt prefix still matches
 * skips the allocation gate and returns the stored group without re-picking.
 */
class StickyBucketingTest {

    // A fully-allocated, single-control experiment that user_beta enrolls in.
    // salt "exp_pricing_42" -> salt8 "exp_pric".
    private static Map<String, Object> exp(int allocationPct, String salt) {
        return Map.of(
            "universe", "universe_pricing",
            "allocationPct", allocationPct,
            "salt", salt,
            "status", "running",
            "groups", List.of(
                Map.of("name", "control", "weight", 9086, "params", Map.of("variant", "a")),
                Map.of("name", "treatment", "weight", 914, "params", Map.of("variant", "b"))));
    }

    private static final Map<String, Object> USER = Map.of("user_id", "user_beta");

    // No store -> deterministic, identical to the legacy four-arg call.
    @Test
    void absentStoreIsDeterministic() {
        ExperimentResult a = Eval.evalExperiment(exp(10000, "exp_pricing_42"), Map.of(), Map.of(), USER);
        ExperimentResult b = Eval.evalExperiment(
            exp(10000, "exp_pricing_42"), Map.of(), Map.of(), USER, null, "pricing_test");
        assertTrue(a.inExperiment);
        assertEquals(a.group, b.group);
    }

    // A fresh pick is persisted under (unit, exp) with the salt8 prefix.
    @Test
    void freshPickIsStored() {
        InMemoryStickyStore store = new InMemoryStickyStore();
        ExperimentResult r = Eval.evalExperiment(
            exp(10000, "exp_pricing_42"), Map.of(), Map.of(), USER, store, "pricing_test");
        assertTrue(r.inExperiment);
        StickyEntry entry = store.get("user_beta").get("pricing_test");
        assertNotNull(entry);
        assertEquals(r.group, entry.group);
        assertEquals("exp_pric", entry.salt8);
    }

    // A pre-seeded entry whose salt prefix matches skips the allocation gate:
    // even at 0% allocation (would normally exclude) the unit stays enrolled
    // with the stored group, with NO re-pick.
    @Test
    void stickyEntrySkipsAllocationGate() {
        InMemoryStickyStore store = new InMemoryStickyStore();
        store.set("user_beta", "pricing_test", new StickyEntry("treatment", "exp_pric"));
        ExperimentResult r = Eval.evalExperiment(
            exp(0, "exp_pricing_42"), Map.of(), Map.of(), USER, store, "pricing_test");
        assertTrue(r.inExperiment);
        assertEquals("treatment", r.group); // stored group, NOT the fresh pick
        assertEquals(Map.of("variant", "b"), r.params); // params resolved from current blob
    }

    // A salt-prefix mismatch re-buckets and overwrites the stale entry.
    @Test
    void saltMismatchRebucketsAndOverwrites() {
        InMemoryStickyStore store = new InMemoryStickyStore();
        store.set("user_beta", "pricing_test", new StickyEntry("treatment", "OLDSALT8"));
        ExperimentResult r = Eval.evalExperiment(
            exp(10000, "exp_pricing_42"), Map.of(), Map.of(), USER, store, "pricing_test");
        assertTrue(r.inExperiment);
        StickyEntry entry = store.get("user_beta").get("pricing_test");
        assertEquals("exp_pric", entry.salt8); // overwritten with the current prefix
        assertEquals(r.group, entry.group);
    }

    // A stored group that no longer exists in the experiment falls through to a
    // fresh re-bucket + overwrite.
    @Test
    void missingStoredGroupRebuckets() {
        InMemoryStickyStore store = new InMemoryStickyStore();
        store.set("user_beta", "pricing_test", new StickyEntry("ghost_group", "exp_pric"));
        ExperimentResult r = Eval.evalExperiment(
            exp(10000, "exp_pricing_42"), Map.of(), Map.of(), USER, store, "pricing_test");
        assertTrue(r.inExperiment);
        assertFalse("ghost_group".equals(r.group));
        assertEquals(r.group, store.get("user_beta").get("pricing_test").group);
    }

    // Integration through Engine.getExperiment with a supplied store: a unit
    // bucketed at full allocation stays enrolled after the allocation shrinks.
    @Test
    void clientStickyStoreLocksAssignment() {
        InMemoryStickyStore store = new InMemoryStickyStore();
        try (Engine c = Engine.forTesting().stickyStore(store)) {
            Map<String, Object> exps = Map.of("experiments",
                Map.of("pricing_test", exp(10000, "exp_pricing_42")));
            c.applyDataForTest(Map.of("gates", Map.of()), exps);

            ExperimentResult first = c.getExperiment("pricing_test", USER, Map.of());
            assertTrue(first.inExperiment);
            String pinned = first.group;

            // Allocation collapses to 0% — deterministic eval would drop the unit.
            Map<String, Object> shrunk = Map.of("experiments",
                Map.of("pricing_test", exp(0, "exp_pricing_42")));
            c.applyDataForTest(Map.of("gates", Map.of()), shrunk);

            ExperimentResult second = c.getExperiment("pricing_test", USER, Map.of());
            assertTrue(second.inExperiment); // still in, thanks to the store
            assertEquals(pinned, second.group);
        }
    }

    // In-memory store: get on an unknown unit is null; set then get round-trips.
    @Test
    void inMemoryStoreRoundTrips() {
        InMemoryStickyStore store = new InMemoryStickyStore();
        assertNull(store.get("nobody"));
        store.set("u1", "exp_a", new StickyEntry("control", "salt1234"));
        store.set("u1", "exp_b", new StickyEntry("treatment", "salt5678"));
        assertEquals("control", store.get("u1").get("exp_a").group);
        assertEquals("treatment", store.get("u1").get("exp_b").group);
    }
}
