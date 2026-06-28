# Testing

Test mode is a drop-in sibling of `Shipeasy.configure(...)` with **no network,
ever** (no api key needed): `Shipeasy.configureForTesting(...)` seeds the values
your code under test should see and registers the global engine, so the same
`new Client(user)` your production code uses reads them back.

## `Shipeasy.configureForTesting(...)`

Seed flags, configs, and experiments up front, then read through the ordinary
bound `Client`. It **replaces** any previously-configured engine, so each test can
reconfigure freely.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

Shipeasy.configureForTesting(Shipeasy.testOptions()
    .flags(Map.of("new_checkout", true))                       // name -> bool
    .configs(Map.of("billing_copy", Map.of("title", "Hello"))) // name -> value
    .experiments(Map.of(                                       // name -> Variant.of(group, params)
        "checkout_button", Shipeasy.Variant.of("treatment", Map.of("color", "green")))));

Client c = new Client(Map.of("user_id", "u_1"));               // construct once per callsite
boolean enabled = c.getFlag("new_checkout");                  // true
Object cfg = c.getConfig("billing_copy");                     // {title=Hello}
ExperimentResult r = c.getExperiment("checkout_button", Map.of("color", "blue"));
// r.inExperiment == true, r.group == "treatment", r.params == {color=green}
```

## On-the-spot overrides

Flip individual values mid-test on top of the seed with the package-level
statics. `Shipeasy.clearOverrides()` drops **every** override — including the
`configureForTesting` seed (test mode has no blob beneath, so everything reverts
to the empty-blob defaults).

```java
Shipeasy.overrideFlag("new_checkout", false);
Shipeasy.overrideConfig("billing_copy", Map.of("title", "Bye"));
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
