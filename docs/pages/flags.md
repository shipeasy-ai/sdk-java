# Feature flags

`getFlag` evaluates a boolean feature gate for the bound user against the cached
rules blob. Evaluation is **local** — no network call per evaluation.

## Reading a flag

Construct a `Client` for the user, then read the gate by name:

```java
import ai.shipeasy.Client;
import java.util.Map;

Client c = new Client(Map.of("user_id", "u_123", "plan", "pro"));
boolean enabled = c.getFlag("new_checkout");
```

## Boolean semantics

`getFlag` returns `true` only when the gate is enabled **and** the user matches
its rules/rollout; otherwise `false`. A disabled or kill-switched gate is
`false`.

## Default / fallback behaviour

The default-value overload returns the default **only when the value cannot be
resolved** — the engine isn't initialized yet, or the flag is absent — **never**
when a flag legitimately evaluates to `false`:

```java
// returns `true` only if the flag is missing or the engine isn't ready;
// a flag that evaluates to false returns false (not the default).
boolean enabled = c.getFlag("new_checkout", true);
```

## Evaluation detail and reason

`getFlagDetail` returns the resolved value plus the `reason` it resolved that
way — useful for logging and debugging. `getFlag(name)` is exactly
`getFlagDetail(name).value()`.

```java
FlagDetail d = c.getFlagDetail("new_checkout");
d.value();   // boolean
d.reason();  // one of the FlagDetail.* reason constants
```

| Reason (`FlagDetail.*`) | Meaning |
| --- | --- |
| `OVERRIDE` | An override was set (short-circuits; no telemetry). |
| `CLIENT_NOT_READY` | No blob fetched yet (engine not initialized). |
| `FLAG_NOT_FOUND` | The flag is not present in the fetched blob. |
| `OFF` | The flag is disabled or kill-switched. |
| `RULE_MATCH` | The flag evaluated to `true` via its rules/rollout. |
| `DEFAULT` | The flag is on but evaluated to `false` for this user. |
