# A/B experiments

`getExperiment` buckets the bound user into an A/B experiment and returns the
variant assignment plus its parameters.

## Reading an experiment

```java
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

Client c = new Client(Map.of("user_id", "u_123"));

// The second argument is the default params used when the user is NOT enrolled.
ExperimentResult r = c.getExperiment("checkout_button", Map.of("color", "blue"));
```

## The `ExperimentResult` shape

`ExperimentResult` exposes three **public fields** — read them directly, there
are no getter methods:

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

A user who is not enrolled returns `r.inExperiment == false`,
`r.group == "control"`, and `r.params` set to the **defaultParams** you passed.

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

c.track("{{SUCCESS_EVENT}}", Map.of("amount", 49));  // no-props overload: c.track("{{SUCCESS_EVENT}}")
```

`track` is fire-and-forget (POSTed to `/collect` off the calling thread) and is
a no-op in test / snapshot mode. The analysis pipeline joins these events to
experiment exposures to compute lift and significance. See
[Metrics](../snippets/metrics/track.md) for the full `track` contract.

## Exposure logging

The server is stateless and never auto-logs exposure. When you actually
*present* the treatment, call `c.logExposure("checkout_button")` on the bound
`Client` — it re-evaluates with the bound attributes (so targeting gates and
`bucketBy` resolve correctly) and emits exactly one exposure for an enrolled
user:

```java
c.logExposure("checkout_button");
```

See [Advanced → Manual exposure logging](advanced.md).
