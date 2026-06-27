# Overview

`shipeasy` (`ai.shipeasy:shipeasy`) is the **server-side Java SDK** for
[Shipeasy](https://shipeasy.dev) — feature flags, dynamic configs, kill
switches, and A/B experiments, evaluated locally against a cached rules blob
fetched from the edge.

## The mental model: `configure()` once, `Client(user)` per request

Configure the SDK **once** at startup. This builds the single, process-wide
**`Engine`** and kicks off the initial rules fetch (fire-and-forget):

```java
import ai.shipeasy.Shipeasy;

Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY")); // once, at startup
```

Then, per user / per request, construct a lightweight **`Client`** bound to that
user. Every evaluation call takes **no user argument** — the user is bound at
construction:

```java
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

Client c = new Client(Map.of("user_id", "u_123", "plan", "pro"));

boolean enabled     = c.getFlag("new_checkout");
Object cfg          = c.getConfig("billing_copy");
ExperimentResult r  = c.getExperiment("checkout_button", Map.of("color", "blue"));
boolean killed      = c.getKillswitch("panic_button");
```

## Engine vs Client

| | `Engine` | `Client` |
| --- | --- | --- |
| Weight | Heavyweight — owns the API key, HTTP, blob cache, poll timer | Lightweight — forwards to the global `Engine` |
| Lifetime | One per process (built by `configure`) | One per user / request |
| User argument | Explicit `user` map per call (`getFlag(name, user)`) | Bound at construction (`getFlag(name)`) |
| Owns | `track()`, `see()`, `logExposure()`, SSR helpers, the offline/test factories | nothing — pure delegation |

`Shipeasy.configure(...)` **returns** the `Engine`, so you can reach the
heavyweight surface (background poll via `init()`, `track()`, SSR bootstrap,
`see()`) when you need it. `Client` is just the ergonomic per-user front door.

## Pages

- [Installation](installation.md) — Maven coordinates, min JDK, imports.
- [Configuration](configuration.md) — `configure()`, the `attributes` transform, env vars, the `Engine` return.
- [Flags](flags.md) — `getFlag`, defaults, `getFlagDetail`.
- [Configs](configs.md) — `getConfig`, typed values, defaults.
- [Kill switches](killswitches.md) — `getKillswitch` and named switches.
- [Experiments](experiments.md) — `getExperiment`, `ExperimentResult`, `track`.
- [i18n](i18n.md) — SSR bootstrap for the browser SDK (server SDK has no `t()`).
- [Error reporting](error-reporting.md) — `see()` structured error reporting.
- [Testing](testing.md) — `Engine.forTesting()`, `fromFile`/`fromSnapshot`, `override*`.
- [OpenFeature](openfeature.md) — the `ShipeasyProvider` server provider.
- [Advanced](advanced.md) — manual exposure, private attributes, sticky bucketing, anon-id middleware, change listeners.
