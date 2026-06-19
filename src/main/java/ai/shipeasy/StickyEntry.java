package ai.shipeasy;

/**
 * One persisted sticky assignment: the assigned {@code group} plus the 8-char
 * salt prefix ({@code salt8}) that was in effect when the unit was bucketed.
 * The salt prefix is the reshuffle key — when the experiment's salt changes,
 * the stored prefix no longer matches and the unit is re-bucketed + overwritten.
 * Mirrors {@code StickyEntry} ({@code { g, s }}) in the canonical TS SDK.
 */
public final class StickyEntry {
    public final String group;
    public final String salt8;

    public StickyEntry(String group, String salt8) {
        this.group = group;
        this.salt8 = salt8;
    }
}
