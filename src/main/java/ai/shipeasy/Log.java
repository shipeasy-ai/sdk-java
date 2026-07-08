package ai.shipeasy;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny leveled logging façade for the SDK's own diagnostics. Wraps the
 * {@code "shipeasy"} {@link java.util.logging.Logger} and gates every message on
 * a process-wide {@link LogLevel} (default {@link LogLevel#WARN}), resolved from
 * {@link Shipeasy.Options#logLevel(LogLevel)} via the {@link Engine} constructor.
 *
 * <p>All emit methods are static and are guaranteed never to throw — logging a
 * diagnostic must never take down a caller's read/track/see path. A message at
 * level {@code L} is emitted iff the configured level is {@code >= L}
 * (ordering {@code SILENT < ERROR < WARN < INFO < DEBUG}).
 */
final class Log {
    private static final Logger LOGGER = Logger.getLogger("shipeasy");

    /** Process-wide resolved level; the last Engine constructed wins. */
    private static volatile LogLevel level = LogLevel.WARN;

    private Log() {}

    /** Set the process-wide SDK log level. {@code null} resolves to {@link LogLevel#WARN}. */
    static void setLevel(LogLevel l) {
        level = l == null ? LogLevel.WARN : l;
    }

    static LogLevel level() {
        return level;
    }

    /** {@code true} iff a message at {@code at} should be emitted under the current level. */
    private static boolean enabled(LogLevel at) {
        return level.ordinal() >= at.ordinal();
    }

    static void error(String msg) {
        emit(LogLevel.ERROR, Level.SEVERE, msg);
    }

    static void warn(String msg) {
        emit(LogLevel.WARN, Level.WARNING, msg);
    }

    static void info(String msg) {
        emit(LogLevel.INFO, Level.INFO, msg);
    }

    static void debug(String msg) {
        emit(LogLevel.DEBUG, Level.FINE, msg);
    }

    private static void emit(LogLevel at, Level julLevel, String msg) {
        try {
            if (enabled(at)) LOGGER.log(julLevel, msg);
        } catch (Throwable ignore) {
            // Logging must never throw into the caller.
        }
    }
}
