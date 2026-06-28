# Error reporting — `see()`

The Java SDK ships the `see()` surface: structured error reporting that
documents an error's product **consequence**, not just its stack. It mirrors
`@shipeasy/sdk` (TS) and the other server SDKs. Reports are POSTed
fire-and-forget to `/collect`.

## The grammar

```java
import static ai.shipeasy.See.see;

try {
    chargeCard(order);
} catch (Exception e) {
    see(e)
        .causesThe("checkout")
        .extras(Map.of("order_id", order.id()))
        .to("use the backup processor");
}
```

- `see(problem)` — start a report for a caught `Throwable` (or any object).
- `.causesThe(subject)` — what part of the product is affected (default `"app"`).
- `.extras(Map)` — structured context (merged on repeat; later wins).
- `.to(outcome)` — **terminal**. Builds the wire event and fire-and-forgets the
  send. `causesThe()` / `extras()` may be called in any order before `.to()`.
  Calling `.to()` twice is a no-op; a chain that never calls `.to()` sends
  nothing.

Reporting never raises into your code — a failure in dispatch is swallowed and
logged.

## Dispatch

The static `ai.shipeasy.See.see(...)` dispatches against the engine that
`Shipeasy.configure(...)` built — no handle to pass. A global call before
`configure` runs logs a warning and returns a no-op chain — it never throws.

## Violations (non-throwable problems)

Report a named problem that isn't an exception:

```java
import static ai.shipeasy.See.violation;

violation("inventory_out_of_sync")
    .causesThe("fulfillment")
    .to("fall back to the nightly count");
```

## Expected control flow (reports nothing)

Mark a throwable as expected control flow so it is **not** reported — only the
mark is stamped on the throwable:

```java
import static ai.shipeasy.See.controlFlowException;

try {
    return parse(input);
} catch (RetryableException e) {
    controlFlowException(e).because("the upstream told us to retry");
    throw e; // re-thrown; nothing is sent
}
```

`ControlFlowChain.isExpected(throwable)` lets callers query the mark. Any
`.extras()` on the tail are local-debug only and never transmitted.

## Limits

`see()` self-protects: messages/stacks are truncated, extras capped (20 keys,
200-char values), a 30s dedup window suppresses duplicates, and the per-process
cap is 25 sends. Private attributes (see [Advanced](advanced.md)) are stripped
from outbound extras.
