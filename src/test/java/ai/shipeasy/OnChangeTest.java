package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnChangeTest {

    // A listener fires when changed data is applied; the cancel Runnable
    // unsubscribes it. Driven via the internal apply-data seam (no network).
    @Test
    void onChangeFiresAndCancels() {
        try (Engine c = new Engine("k", "https://edge.invalid")) {
            AtomicInteger hits = new AtomicInteger();
            Runnable cancel = c.onChange(hits::incrementAndGet);

            c.applyDataForTest(Map.of("gates", Map.of()), null);
            assertEquals(1, hits.get());

            c.applyDataForTest(Map.of("gates", Map.of("g", Map.of())), null);
            assertEquals(2, hits.get());

            cancel.run();
            c.applyDataForTest(Map.of("gates", Map.of()), null);
            assertEquals(2, hits.get(), "cancelled listener must not fire");
        }
    }

    // A throwing listener is isolated: others still fire, no exception escapes.
    @Test
    void onChangeIsolatesThrows() {
        try (Engine c = new Engine("k", "https://edge.invalid")) {
            AtomicInteger good = new AtomicInteger();
            c.onChange(() -> { throw new RuntimeException("boom"); });
            c.onChange(good::incrementAndGet);

            c.applyDataForTest(Map.of("gates", Map.of()), null);
            assertEquals(1, good.get());
        }
    }

    // Applied data is reflected in subsequent evaluations.
    @Test
    void appliedDataIsLive() {
        try (Engine c = new Engine("k", "https://edge.invalid")) {
            assertEquals(FlagDetail.CLIENT_NOT_READY, c.getFlagDetail("g", Map.of()).reason());
            c.applyDataForTest(
                Map.of("gates", Map.of("g",
                    Map.of("enabled", true, "rolloutPct", 10000))),
                null);
            assertTrue(c.getFlag("g", Map.of("user_id", "u_1")));
        }
    }

    // Listeners never fire in local/snapshot mode (no polling there).
    @Test
    void onChangeNeverFiresInLocalMode() {
        try (Engine c = Engine.fromSnapshot(Map.of("gates", Map.of()), null)) {
            AtomicInteger hits = new AtomicInteger();
            c.onChange(hits::incrementAndGet);
            c.applyDataForTest(Map.of("gates", Map.of("g", Map.of())), null);
            assertEquals(0, hits.get());
        }
    }
}
