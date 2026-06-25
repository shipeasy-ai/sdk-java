package ai.shipeasy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder returned by {@link Engine#see(Object)} /
 * {@link Engine#seeViolation(String)}. Accumulates the consequence
 * ({@code causesThe}) and {@code extras}; the terminal {@code to(outcome)}
 * builds the wire event and fire-and-forgets the send.
 *
 * <p>{@code causesThe()} and {@code extras()} may be called in any order before
 * {@code to()}. {@code extras()} merges on repeat (later wins). {@code to()} may
 * be called once — calling it again is a no-op.
 */
public final class SeeChain {

    /** Receives a finalized chain and performs the (best-effort) send. */
    interface Dispatch {
        void dispatch(SeeChain chain);
    }

    private static final Dispatch NOOP = c -> {};

    private final Object problem;
    private final Dispatch dispatch;
    private String subject;
    private String outcome;
    private Map<String, Object> extras;
    private boolean done;

    SeeChain(Object problem, Dispatch dispatch) {
        this.problem = problem;
        this.dispatch = dispatch == null ? NOOP : dispatch;
    }

    /** A chain that drops everything — used when no default client exists. */
    static SeeChain noop(Object problem) {
        return new SeeChain(problem, NOOP);
    }

    public SeeChain causesThe(String subject) {
        this.subject = subject;
        return this;
    }

    public SeeChain extras(Map<String, Object> extras) {
        if (extras != null && !extras.isEmpty()) {
            if (this.extras == null) this.extras = new LinkedHashMap<>();
            this.extras.putAll(extras);
        }
        return this;
    }

    /** Terminal: build the event and fire-and-forget the report (idempotent). */
    public void to(String outcome) {
        if (done) return;
        done = true;
        this.outcome = outcome;
        try {
            dispatch.dispatch(this);
        } catch (Throwable ignore) {
            // Reporting must never raise into caller code.
        }
    }

    // ---- Accessors for the client dispatcher ----

    Object problem() {
        return problem;
    }

    String subject() {
        return (subject == null || subject.isEmpty()) ? See.DEFAULT_SUBJECT : subject;
    }

    String outcome() {
        return (outcome == null || outcome.isEmpty()) ? See.DEFAULT_OUTCOME : outcome;
    }

    Map<String, Object> extras() {
        return extras;
    }
}
