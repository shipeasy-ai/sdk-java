package ai.shipeasy;

/**
 * The resolved value of a flag together with the {@code reason} it resolved
 * that way. Returned by {@link Client#getFlagDetail(String, java.util.Map)}.
 * The reason is computed at the SDK boundary (the canonical {@link Eval#evalGate}
 * is untouched) and is one of the {@code Reason} constants below.
 */
public final class FlagDetail {
    /** An override was set for the flag; {@link #value()} is the override. */
    public static final String OVERRIDE = "OVERRIDE";
    /** The client has not been initialized yet (no blob fetched). */
    public static final String CLIENT_NOT_READY = "CLIENT_NOT_READY";
    /** The flag is not present in the fetched blob. */
    public static final String FLAG_NOT_FOUND = "FLAG_NOT_FOUND";
    /** The flag exists but is disabled (or killswitched); value is {@code false}. */
    public static final String OFF = "OFF";
    /** The flag evaluated to {@code true} via its rules/rollout. */
    public static final String RULE_MATCH = "RULE_MATCH";
    /** The flag is on but evaluated to {@code false} for this user. */
    public static final String DEFAULT = "DEFAULT";

    private final boolean value;
    private final String reason;

    public FlagDetail(boolean value, String reason) {
        this.value = value;
        this.reason = reason;
    }

    public boolean value() {
        return value;
    }

    public String reason() {
        return reason;
    }
}
