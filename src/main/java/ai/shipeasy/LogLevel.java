package ai.shipeasy;

/**
 * Verbosity of the SDK's internal diagnostic logging. Ordered
 * {@code SILENT < ERROR < WARN < INFO < DEBUG}: a message logged at level
 * {@code L} is emitted iff the configured level is {@code >= L}. The default is
 * {@link #WARN}.
 *
 * <p>Set it via {@link Shipeasy.Options#logLevel(LogLevel)}. These levels gate
 * the SDK's own diagnostics (network failures, decode errors, misuse warnings);
 * they never affect what your application logs, and the runtime read/track/see
 * paths never throw regardless of level.
 */
public enum LogLevel {
    SILENT,
    ERROR,
    WARN,
    INFO,
    DEBUG;
}
