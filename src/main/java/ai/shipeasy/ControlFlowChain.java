package ai.shipeasy;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * {@code controlFlowException(e).because("because ...")} — marks the throwable
 * as expected control flow and reports <em>nothing</em>. {@code .extras()} on
 * the tail is stored for local debugging only (an expected exception is never
 * transmitted).
 *
 * <p>Throwables are tracked in a process-global weak set (so they can be GC'd
 * normally and never need to be mutable). {@link #isExpected(Throwable)} lets
 * callers query the mark.
 */
public final class ControlFlowChain {

    // Identity-based, weak so marked throwables can still be collected.
    private static final Set<Throwable> EXPECTED =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private final Throwable err;

    ControlFlowChain(Throwable err) {
        this.err = err;
    }

    /**
     * Mark the throwable expected for the given reason (should start with
     * "because" by convention — not enforced) and return a tail for optional
     * local-only {@code extras}.
     */
    public ControlFlowTail because(String reason) {
        markExpected(err);
        return new ControlFlowTail(err, reason);
    }

    static void markExpected(Throwable err) {
        if (err == null) return;
        try {
            EXPECTED.add(err);
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    /** Whether {@code err} was marked expected via {@link #because(String)}. */
    public static boolean isExpected(Throwable err) {
        return err != null && EXPECTED.contains(err);
    }

    /**
     * Tail of a control-flow chain. {@code extras} are accepted for symmetry
     * with {@link SeeChain} and kept for local debugging only — never sent.
     */
    public static final class ControlFlowTail {
        private final Throwable err;
        private final String reason;
        private Map<String, Object> extras;

        ControlFlowTail(Throwable err, String reason) {
            this.err = err;
            this.reason = reason;
        }

        public ControlFlowTail extras(Map<String, Object> extras) {
            this.extras = extras;
            return this;
        }

        /** The reason supplied to {@code because()} (local debug only). */
        public String reason() {
            return reason;
        }

        /** The local-only extras, if any (never transmitted). */
        public Map<String, Object> localExtras() {
            return extras;
        }
    }
}
