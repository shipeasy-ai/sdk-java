Assign a unit within a universe (a mutual-exclusion pool — the unit lands in <=1
experiment), read the assigned params, then record the conversion event on the
same bound `Client`. Assumes `configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Client;
import ai.shipeasy.Assignment;
import java.util.Map;

// construct once per callsite (cheap; binds the user + runs the attributes transform)
Client client = new Client(Map.of("user_id", "u_123"));

// universe(name).assign() -> Assignment
//   name       — the UNIVERSE name (not an experiment); the unit lands in <=1 experiment
//   .name()    — the experiment the unit landed in, or null when not enrolled
//   .group()   — the assigned variant, or null when not enrolled
//   .enrolled()— == (group() != null)
//   .get(field, fallback) — variant override ?? universe default ?? fallback (first get() logs one exposure)
//   .peek(field, fallback) — same read, but NEVER logs an exposure
// assign() takes no arg (user bound at construction) and is side-effect free;
// the single deduped exposure fires on the first .get(...) read.
Assignment exp = client.universe("{{EXPERIMENT_KEY}}").assign();

String label = (String) exp.get("primary_label", "Sign up"); // always safe — falls back when not enrolled

// On conversion — Client-only track (NOT the Engine); the unit is inferred
// from the bound user (user_id, else anonymous_id):
//   track(eventName, props?)
//     eventName — the success event name
//     props     — optional metric properties (private attrs are stripped)
client.track("{{SUCCESS_EVENT}}", Map.of("group", String.valueOf(exp.group()))); // props optional
```
