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
 * be called once — calling it again is a no-op. The terminal also accepts extras
 * inline as {@link #to(String, Map)}, folded like a final {@code extras()} call,
 * so there is no ordering to remember.
 *
 * <p>Any ambient per-request extras buffered via {@link See#addExtras(Map)} merge
 * <em>under</em> the chain's own extras at dispatch time (a chained key of the
 * same name wins over an ambient one).
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
        dispatchTerminal(outcome);
    }

    /**
     * Terminal with inline extras — {@code .to(outcome, extras)}. The {@code extras}
     * are merged like a final {@code .extras(...)} call (folded under nothing —
     * later wins over any earlier {@code .extras}), so there is no ordering to
     * remember. Idempotent, like {@link #to(String)}.
     */
    public void to(String outcome, Map<String, Object> extras) {
        if (done) return;
        extras(extras);
        dispatchTerminal(outcome);
    }

    private void dispatchTerminal(String outcome) {
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

    /**
     * The chain's own extras merged OVER the ambient per-request buffer
     * ({@link See#addExtras}), so a chained key of the same name wins over an
     * ambient one. Sanitizing / private-attribute stripping happens downstream at
     * build time, exactly as for chained extras.
     */
    Map<String, Object> extras() {
        Map<String, Object> ambient = SeeExtras.current();
        if (ambient == null) return extras;
        if (extras == null || extras.isEmpty()) return ambient;
        ambient.putAll(extras); // chain wins on key collision
        return ambient;
    }
}
