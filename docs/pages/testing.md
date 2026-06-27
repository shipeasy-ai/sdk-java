# Testing

The SDK ships no-network factories so unit tests run deterministically without
hitting the edge.

## `Engine.forTesting()`

Returns a no-network engine: telemetry is disabled, `init()`/`initOnce()` and
`track()` are no-ops (they never reach the network), and **no API key is
required**. Seed each entity with the `override*` setters; an override always
wins over any fetched value.

```java
import ai.shipeasy.Engine;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

try (Engine c = Engine.forTesting()) {
    // Flags
    c.overrideFlag("new_checkout", true);
    boolean enabled = c.getFlag("new_checkout", Map.of()); // true

    // Configs (any JSON-shaped value)
    c.overrideConfig("billing_copy", Map.of("title", "Hello"));
    Object cfg = c.getConfig("billing_copy"); // {title=Hello}

    // Experiments → ExperimentResult(true, group, params)
    c.overrideExperiment("checkout_button", "treatment", Map.of("color", "green"));
    ExperimentResult r = c.getExperiment("checkout_button", Map.of(),
        Map.of("color", "blue"));
    // r.inExperiment == true, r.group == "treatment", r.params == {color=green}

    // track() is a no-op here — no key, no network call
    c.track("u_123", "purchase", Map.of("amount", 49));

    c.clearOverrides(); // back to default (no-override) behavior
}
```

The `override*` and `clearOverrides()` setters are also callable on a normal
`Engine`.

## Offline snapshots — real evaluation, no network

Run the **real** evaluator against a captured rules blob (no overrides needed,
no network). The JSON file has the shape
`{ "flags": <body of /sdk/flags>, "experiments": <body of /sdk/experiments> }`:

```java
try (Engine c = Engine.fromFile("snapshot.json")) {
    boolean on = c.getFlag("new_checkout", Map.of("user_id", "u_123"));
}

// …or the same two blobs in memory:
try (Engine c = Engine.fromSnapshot(flagsBlob, experimentsBlob)) {
    Object cfg = c.getConfig("billing_copy", "fallback");
}
```

Snapshot engines are no-network just like `forTesting()` —
`init()`/`initOnce()`/`track()` are no-ops, telemetry is off, and `override*`
values still apply **on top of** the snapshot.

## Wiring a test engine into the global `Client` path

If your code under test uses `new Client(user)`, install the test/snapshot
engine as the global engine in your test setup (the SDK exposes the package-level
seam used by its own tests) — or refactor the code to take an injected `Engine`
and call the engine methods directly. For most unit tests, asserting against
`Engine.forTesting()` / `Engine.fromSnapshot(...)` directly is the simplest path.
