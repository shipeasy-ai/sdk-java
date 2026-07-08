package ai.shipeasy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.12.0 fail-safe contract: RUNTIME reads never throw into the caller, and
 * the SDK's own diagnostics are gated by {@link LogLevel} (default WARN).
 */
class NoThrowLoggingTest {

    @AfterEach
    void reset() {
        Shipeasy.resetForTest();
        // Restore the default level so other tests are unaffected.
        Log.setLevel(LogLevel.WARN);
    }

    /** A JUL handler that captures every record on the "shipeasy" logger. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();
        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private CapturingHandler attach() {
        Logger logger = Logger.getLogger("shipeasy");
        logger.setUseParentHandlers(false);
        // Let every level reach the handler; the SDK gates emission itself.
        logger.setLevel(Level.ALL);
        CapturingHandler h = new CapturingHandler();
        h.setLevel(Level.ALL);
        logger.addHandler(h);
        return h;
    }

    private void detach(CapturingHandler h) {
        Logger.getLogger("shipeasy").removeHandler(h);
    }

    // (a) A runtime read whose internal state would break the read still returns
    // the documented safe default and does NOT throw. A snapshot whose "gates"
    // entry is not a Map forces a ClassCastException deep inside the read; the
    // defensive try/catch(Throwable) must swallow it and return the default.
    @Test
    void runtimeReadNeverThrowsAndReturnsDefault() {
        // "gates" is a String, not a Map -> the internal cast throws.
        Map<String, Object> flags = Map.of("gates", "not-a-map");
        Engine e = Engine.fromSnapshot(flags, null);
        Shipeasy.useEngineForTest(e, null);

        Client client = new Client(Map.of("user_id", "u_1"));

        // getFlag(name, default) must fall back to the default, not throw.
        assertDoesNotThrow(() -> client.getFlag("some_gate", true));
        assertTrue(client.getFlag("some_gate", true));
        assertFalse(client.getFlag("some_gate", false));

        // getFlagDetail returns a safe not-ready detail.
        FlagDetail d = client.getFlagDetail("some_gate");
        assertNotNull(d);
        assertFalse(d.value());

        // The other reads also fail safe.
        assertDoesNotThrow(() -> client.getFlag("some_gate"));
        assertFalse(client.getFlag("some_gate"));
    }

    // Experiment read fails safe to a not-enrolled control result.
    @Test
    void experimentReadFailsSafeToControl() {
        // "experiments" is a String, not a Map -> internal cast throws.
        Map<String, Object> exps = Map.of("experiments", "not-a-map");
        Engine e = Engine.fromSnapshot(null, exps);
        Shipeasy.useEngineForTest(e, null);

        Client client = new Client(Map.of("user_id", "u_1"));
        ExperimentResult r = assertDoesNotThrow(
            () -> client.getExperiment("exp_x", Map.of("k", "v")));
        assertNotNull(r);
        assertFalse(r.inExperiment);
        assertEquals("control", r.group);
        assertEquals(Map.of("k", "v"), r.params);
    }

    // (b) LogLevel SILENT mutes the SDK's diagnostics; WARN emits them.
    @Test
    void silentMutesWarnEmits() {
        CapturingHandler h = attach();
        try {
            // WARN (default): a warn diagnostic is emitted.
            Log.setLevel(LogLevel.WARN);
            h.records.clear();
            Log.warn("hello-warn");
            assertEquals(1, h.records.size(), "WARN level should emit a warn record");
            assertEquals("hello-warn", h.records.get(0).getMessage());

            // SILENT: nothing is emitted, at any level.
            Log.setLevel(LogLevel.SILENT);
            h.records.clear();
            Log.error("x");
            Log.warn("y");
            Log.info("z");
            Log.debug("w");
            assertTrue(h.records.isEmpty(), "SILENT level should mute all diagnostics");
        } finally {
            detach(h);
        }
    }

    // The level threads through the Engine constructor into the static Log
    // facade (last engine constructed wins) — the same path Shipeasy.configure
    // uses via Options.logLevel(...). Built with an in-memory snapshot so no
    // network is touched.
    @Test
    void logLevelThreadsThroughToLog() {
        // A no-network engine carries LogLevel.WARN by default...
        Engine.fromSnapshot(Map.of(), Map.of());
        assertEquals(LogLevel.WARN, Log.level());
        // ...and the explicit-level constructor (used by configure) sets it.
        new Engine(null, null, "test", true, LogLevel.SILENT).close();
        assertEquals(LogLevel.SILENT, Log.level());
    }

    // Null level resolves to WARN.
    @Test
    void nullLevelResolvesToWarn() {
        Log.setLevel(null);
        assertEquals(LogLevel.WARN, Log.level());
    }
}
