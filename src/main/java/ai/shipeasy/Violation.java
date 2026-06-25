package ai.shipeasy;

/**
 * A non-throwable problem reported via {@link See#violation(String)} /
 * {@link Engine#seeViolation(String)}. The {@code name} is a stable fingerprint
 * key — put variable data in {@code .extras()}, never in the name.
 */
public final class Violation {
    private final String name;

    public Violation(String name) {
        this.name = String.valueOf(name);
    }

    public String name() {
        return name;
    }
}
