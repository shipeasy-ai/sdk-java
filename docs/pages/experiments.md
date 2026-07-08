# A/B experiments (`universe().assign()` + `track`)

Experiments are read by **universe**. A universe is a mutual-exclusion pool: a
unit lands in **at most one** experiment in it. `assign()` picks that experiment
(if any), returns the assigned group plus its resolved parameters, and auto-logs
a single (deduped) exposure. You read parameters with
`assign().get(field, fallback)` and record a conversion with `track`.

## Read an experiment

```java
import ai.shipeasy.Client;
import ai.shipeasy.Assignment;
import java.util.Map;

Client c = new Client(Map.of("user_id", "u_123")); // construct once per user (cheap)

// Ask the UNIVERSE, not the experiment: the unit lands in <=1 experiment in it.
Assignment cta = c.universe("hero_cta").assign();

// Read a param: variant override ?? universe default ?? your fallback.
String label = (String) cta.get("primary_label", "Sign up");
```

The user is bound at construction, so `assign()` takes no argument.

## The `Assignment` shape

`Assignment` exposes **methods** (it never throws):

```java
public final class Assignment {
    public String  name();                       // the experiment landed in, or null when not enrolled
    public String  group();                       // the assigned variant, or null when not enrolled
    public boolean enrolled();                    // == (group() != null)
    public Object  get(String field, Object fb);  // variant ?? universe default ?? fallback
    public <T> T   get(String field, Class<T> type, T fb); // typed variant of get(...)
}
```

When the unit isn't enrolled (targeting/holdout/allocation), `enrolled()` is
`false`, `group()` and `name()` are `null`, and `get(field, fallback)` returns
the universe default if there is one, else your `fallback` — so reading a param
is always safe.

```java
Assignment cta = c.universe("hero_cta").assign();
if (cta.enrolled()) {
    // cta.group() is the variant, e.g. "treatment"
}
String label = (String) cta.get("primary_label", "Sign up"); // never throws
// Or typed, with a fallback on a type mismatch:
String typed = cta.get("primary_label", String.class, "Sign up");
```

## Track conversions

Record the success event so the analysis pipeline can compute lift. Conversion
events are attributed to the bound user. You already have a `Client` — call
`track` on the **same handle**, so an experiment is end-to-end Client-only. The
unit id is derived from the bound attributes (`user_id`, else `anonymous_id`),
so there is no user argument. Fire your success event — `{{SUCCESS_EVENT}}` —
after the user converts:

```java
Client c = new Client(Map.of("user_id", "u_123"));

Assignment cta = c.universe("hero_cta").assign();
// ... render the assigned variant ...

c.track("{{SUCCESS_EVENT}}", Map.of("amount", 49));  // no-props overload: c.track("{{SUCCESS_EVENT}}")
```

`track` is fire-and-forget (POSTed to `/collect` off the calling thread) and is
a no-op in test / snapshot mode. The analysis pipeline joins these events to
experiment exposures to compute lift and significance. See
[Metrics](../snippets/metrics/track.md) for the full `track` contract.

## Iterating over many users

When you don't have a single bound user — e.g. a batch job scoring many users —
construct a fresh `Client` per user inside the loop. It's cheap (it delegates to
the configuration built once at startup; it opens no connection):

```java
for (Map<String, Object> user : users) {
    Client c = new Client(user); // construct once per user (cheap)
    Assignment cta = c.universe("hero_cta").assign();
    c.track("{{SUCCESS_EVENT}}", Map.of("group", String.valueOf(cta.group())));
}
```

## Exposure logging

`assign()` auto-logs a single (deduped) exposure when the unit is enrolled — the
server no longer exposes a manual `logExposure`. Repeated `assign()` calls for
the same `(unit, experiment, group)` in one process POST only one exposure. See
[Advanced → exposure logging](advanced.md).
