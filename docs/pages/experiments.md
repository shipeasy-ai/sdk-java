# A/B experiments

`getExperiment` buckets a user into an A/B experiment and returns the variant
assignment plus its parameters.

## Bound `Client` form

```java
Client c = new Client(Map.of("user_id", "u_123"));

// The second argument is the default params used when the user is NOT enrolled.
ExperimentResult r = c.getExperiment("checkout_button", Map.of("color", "blue"));
```

## The `ExperimentResult` shape

```java
public final class ExperimentResult {
    public final boolean inExperiment; // is the user enrolled?
    public final String  group;        // assigned variant (e.g. "control" / "treatment")
    public final Object  params;       // variant params (Map), or the defaults if not enrolled
}
```

```java
if (r.inExperiment) {
    @SuppressWarnings("unchecked")
    Map<String, Object> params = (Map<String, Object>) r.params;
    String color = (String) params.get("color");
    // render the assigned variant
}
```

A user who is not enrolled returns `inExperiment == false`, `group == "control"`,
and `params` set to the **defaultParams** you passed.

## Low-level `Engine` form

The engine takes the user map explicitly:

```java
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
ExperimentResult r = engine.getExperiment(
    "checkout_button",
    Map.of("user_id", "u_123"),
    Map.of("color", "blue"));  // default params
```

## Tracking conversions with `track(...)`

Conversion events are recorded with `track`, **right on the bound `Client`** —
the same handle you already have for `getExperiment`. The unit id is derived
from the bound attributes (`user_id`, else `anonymous_id`), so there is no user
argument. Fire your success event — `{{SUCCESS_EVENT}}` — after the user
converts:

```java
Client c = new Client(Map.of("user_id", "u_123"));

ExperimentResult r = c.getExperiment("checkout_button", Map.of("color", "blue"));
// ... render the variant ...

c.track("{{SUCCESS_EVENT}}", Map.of("amount", 49));  // no props overload: c.track("{{SUCCESS_EVENT}}")
```

`track` is fire-and-forget (POSTed to `/collect` off the calling thread) and is
a no-op in test / snapshot mode. The analysis pipeline joins these events to
experiment exposures to compute lift and significance.

> **Exposure logging:** the server is stateless and never auto-logs exposure.
> When you actually *present* the treatment, call
> `c.logExposure("checkout_button")` on the bound `Client` — it re-evaluates
> with the bound attributes and emits one exposure for an enrolled user.

## Low-level `Engine` forms (advanced)

The `Engine` exposes the same operations with an explicit user argument — use
these only when you don't have a bound `Client` (e.g. a batch job iterating over
users):

```java
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
engine.track("u_123", "{{SUCCESS_EVENT}}", Map.of("amount", 49));
engine.logExposure("u_123", "checkout_button");   // or logExposure(userMap, name)
```

See [Advanced](advanced.md).
