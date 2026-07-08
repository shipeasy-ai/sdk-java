# Testing

Test mode is a drop-in sibling of `Shipeasy.configure(...)` with **no network,
ever** (no api key needed): `Shipeasy.configureForTesting(...)` seeds the values
your code under test should see and registers the global engine, so the same
`new Client(user)` your production code uses reads them back.

## `Shipeasy.configureForTesting(...)`

Seed flags and configs up front, then read through the ordinary bound `Client`.
It **replaces** any previously-configured engine, so each test can reconfigure
freely. In this mode the rules never fetch, `track()` is a no-op, and
`universe().assign()` logs no exposure.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

Shipeasy.configureForTesting(Shipeasy.testOptions()
    .flags(Map.of("new_checkout", true))                        // name -> bool
    .configs(Map.of("billing_copy", Map.of("title", "Hello")))); // name -> value

Client c = new Client(Map.of("user_id", "u_1"));               // construct once per callsite
boolean enabled = c.getFlag("new_checkout");                  // true
Object cfg = c.getConfig("billing_copy");                     // {title=Hello}
```

To assert an **experiment** assignment, seed a real universe + experiment with
`configureForOffline()` (below) — an experiment override refines an experiment
that lives in a universe; it doesn't invent one in an empty universe. Read it
with `universe(name).assign()`:

```java
import ai.shipeasy.Assignment;

Assignment exp = new Client(Map.of("user_id", "u_1")).universe("hero_cta").assign();
exp.enrolled();                   // true when the seeded experiment enrolled the unit
exp.group();                      // the assigned variant, or null
exp.get("primary_label", "Sign up"); // variant ?? universe default ?? fallback
```

An `.experiments(...)` seed (and `overrideExperiment`) **refines** an experiment
that already lives in a universe — it forces that experiment's variant. It does
not invent an experiment in an empty universe, and it is read by universe, not by
experiment name. On an empty test-mode blob (no snapshot) `universe().assign()`
returns not-enrolled regardless of the seed.

## On-the-spot overrides

Flip individual values mid-test on top of the seed with the package-level
statics. `Shipeasy.clearOverrides()` drops **every** override — including the
`configureForTesting` seed (test mode has no blob beneath, so everything reverts
to the empty-blob defaults).

```java
Shipeasy.overrideFlag("new_checkout", false);
Shipeasy.overrideConfig("billing_copy", Map.of("title", "Bye"));
// Refines an experiment that lives in a universe (seed it via configureForOffline):
Shipeasy.overrideExperiment("checkout_button", "control", Map.of("color", "blue"));

Shipeasy.clearOverrides(); // drops the overrides AND the configureForTesting seed
```

## `Shipeasy.configureForOffline(...)` — real evaluation, no network

Run the **real** evaluator against a captured rules blob (no overrides needed, no
network). Optional `flags`/`configs`/`experiments` overrides layer on top, and
`clearOverrides()` reverts to the snapshot.

```java
// From an in-memory snapshot:
Shipeasy.configureForOffline(Shipeasy.offlineOptions().snapshot(snapshotMap));

// …or from a JSON file on disk:
Shipeasy.configureForOffline(Shipeasy.offlineOptions().path("shipeasy-snapshot.json"));

Client c = new Client(Map.of("user_id", "u_123"));            // construct once per callsite
boolean on = c.getFlag("new_checkout");
```

### Example snapshot file

The file is `{ "flags": <body of /sdk/flags>, "experiments": <body of
/sdk/experiments> }`. A gate's `rolloutPct` is in basis points (`10000` = 100%):

```json
{
  "flags": {
    "gates": {
      "new_checkout": { "enabled": true, "rolloutPct": 10000, "salt": "s" }
    },
    "configs": {
      "billing_copy": { "value": { "title": "Hello" } }
    },
    "killswitches": {}
  },
  "experiments": {
    "experiments": {},
    "universes": {}
  }
}
```
