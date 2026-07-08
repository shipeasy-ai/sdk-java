# Overview

`shipeasy` (`ai.shipeasy:shipeasy`) is the **server-side Java SDK** for
[Shipeasy](https://shipeasy.ai) — feature flags, dynamic configs, kill switches,
A/B experiments, metric tracking, and `see()` error reporting. Rules are
evaluated **locally** against a cached blob fetched from the edge, so there is no
network call per evaluation.

## The mental model: `configure()` once, `new Client(user)` per request

You learn exactly two things. **First**, configure the SDK once at startup. This
authenticates with your server key and kicks off the initial rules fetch:

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

// Once, at startup (main(), an @PostConstruct bean, a static initializer):
Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

// Per user / per request, anywhere downstream. The user is bound at
// construction, so every read takes NO user argument:
Client c = new Client(Map.of("user_id", "u_123", "plan", "pro"));

boolean enabled = c.getFlag("new_checkout");
Object  cfg     = c.getConfig("billing_copy");
boolean killed  = c.getKillswitch("panic_button");

Assignment cta = c.universe("hero_cta").assign(); // <=1 experiment; auto-logs exposure
c.track("purchase", Map.of("amount", 49));  // record the conversion
```

`new Client(user)` is **cheap** — it owns no HTTP connection, blob cache, or poll
timer. It runs the configured `attributes` transform on your user object once,
binds the resulting attribute map, and forwards every call to the single
process-wide engine that `configure()` built. Construct one per request and
throw it away.

Constructing a `Client` before `configure()` has run throws
`IllegalStateException`.

## Pages

- [Installation](installation.md) — Maven/Gradle coordinates, per-framework wiring, and the canonical `configure()` setup with its options table.
- [Configuration](configuration.md) — `configure()`, the `attributes` transform, one-shot vs background poll, env vars.
- [Flags](flags.md) — `getFlag`, defaults, `getFlagDetail`.
- [Configs](configs.md) — `getConfig`, typed values, defaults.
- [Kill switches](killswitches.md) — `getKillswitch` and named per-key switches.
- [Experiments](experiments.md) — `universe().assign()`, `Assignment`, `track`.
- [i18n](i18n.md) — SSR bootstrap for the browser SDK (the server SDK has no `t()`).
- [Error reporting](error-reporting.md) — `see()` structured error reporting.
- [Testing](testing.md) — `configureForTesting()` / `configureForOffline()` + the override statics.
- [OpenFeature](openfeature.md) — the `ShipeasyProvider` server provider.
- [Advanced](advanced.md) — auto-exposure, private attributes, sticky bucketing, anon-id middleware, change listeners, SSR.
