---
name: shipeasy-java
description: Use Shipeasy (feature flags, configs, kill switches, A/B experiments, i18n) from Java. Covers configure() + Client(user), getFlag/getConfig/universe().assign(), track, testing, OpenFeature.
---

# Shipeasy Java SDK

Server-side Java SDK for Shipeasy. Evaluate feature flags, dynamic configs, kill
switches, and A/B experiments locally against a cached rules blob. Java 11+.

> The documented surface is exactly **`Shipeasy.configure()`** (setup) and the
> bound **`new Client(user)`** (use), plus the package-level statics below. For
> deeper docs, fetch any page/snippet from the manifest at
> <https://shipeasy-ai.github.io/sdk-java/manifest.json> (raw URLs below).

## Install

Maven (`ai.shipeasy:shipeasy`):

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy</artifactId>
  <version>0.10.0</version>
</dependency>
```

## Configure once, bind per user

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import ai.shipeasy.Assignment;
import java.util.Map;

// Once, at startup. Builds the global engine + kicks off the initial fetch.
// Map your own user object with a one-time attributes transform (optional).
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .attributes((Object u) -> {
        MyUser my = (MyUser) u;
        return Map.of("user_id", my.id(), "plan", my.plan());
    }));
// Long-running server: .poll(true) keeps flags fresh with a background poll —
// you never call init() yourself.

// Per user / per request — user bound at construction, no user arg per call.
Client c = new Client(myUser);                              // construct once per callsite
boolean enabled = c.getFlag("new_checkout");               // bool gate
boolean withDef = c.getFlag("new_checkout", true);         // default only when unresolvable
Object cfg      = c.getConfig("billing_copy", Map.of());   // dynamic config (+ fallback)
boolean killed  = c.getKillswitch("panic_button");         // kill switch
// Named switch: getKillswitch(name, switchKey) — an unconfigured key falls back
// to the kill switch's top-level value.
```

`Shipeasy.configure` is first-config-wins; `new Client(user)` throws if called
before it. Reference:
<https://shipeasy-ai.github.io/sdk-java/pages/configuration.md> ·
<https://shipeasy-ai.github.io/sdk-java/pages/flags.md>

## Experiments + track (Client-only, end to end)

Experiments are read by **universe** (a mutual-exclusion pool — the unit lands in
<=1 experiment). `assign()` auto-logs one deduped exposure when enrolled.

```java
Client c = new Client(myUser);                             // construct once per callsite
Assignment exp = c.universe("hero_cta").assign();          // <=1 experiment in the universe
if (exp.enrolled()) { /* exp.group() is the variant; read params with exp.get(...) */ }
String label = (String) exp.get("primary_label", "Sign up"); // variant ?? universe default ?? fallback

c.track("purchase", Map.of("amount", 49));                 // conversion for the bound user
```

Reference: <https://shipeasy-ai.github.io/sdk-java/pages/experiments.md> · track
snippet <https://shipeasy-ai.github.io/sdk-java/snippets/metrics/track.md>

## Error reporting — see()

```java
import static ai.shipeasy.See.see;
try {
    chargeCard(order);
} catch (Exception e) {
    see(e).causesThe("checkout").extras(Map.of("order_id", order.id()))
          .to("use the backup processor");
}
```

`.to(outcome)` is the terminal (fire-and-forget POST). Use
`controlFlowException(e).because("...")` to mark expected control flow (reports
nothing); `violation("name")...to("...")` for non-throwable problems. Reference:
<https://shipeasy-ai.github.io/sdk-java/pages/error-reporting.md> · snippet
<https://shipeasy-ai.github.io/sdk-java/snippets/ops/see.md>

## Testing (no network, no key)

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

// Seed values up front; reads go through the ordinary new Client(user). Replaces
// prior config, so each test can reconfigure freely.
Shipeasy.configureForTesting(Shipeasy.testOptions()
    .flags(Map.of("new_checkout", true))
    .configs(Map.of("billing_copy", Map.of("title", "Hello"))));
boolean on = new Client(Map.of("user_id", "u_1")).getFlag("new_checkout"); // true
// To assert an assignment, seed a real universe+experiment via configureForOffline,
// then read new Client(user).universe("hero_cta").assign().

Shipeasy.overrideFlag("new_checkout", false);              // flip on the spot
Shipeasy.clearOverrides();                                 // drop every override (incl. the seed)

// Offline: evaluate the REAL rules from a snapshot or JSON file, no network.
Shipeasy.configureForOffline(Shipeasy.offlineOptions().path("shipeasy-snapshot.json"));
```

Reference: <https://shipeasy-ai.github.io/sdk-java/pages/testing.md>

## OpenFeature

```java
import dev.openfeature.sdk.OpenFeatureAPI;
import ai.shipeasy.openfeature.ShipeasyProvider;

// Assumes Shipeasy.configure(...) ran — the no-arg provider resolves it.
OpenFeatureAPI.getInstance().setProviderAndWait(new ShipeasyProvider());
```

Booleans → gates; strings/numbers/objects → dynamic configs. `targetingKey` →
`user_id`. Reference:
<https://shipeasy-ai.github.io/sdk-java/pages/openfeature.md>

## Advanced

- **Anonymous bucketing:** register `AnonIdFilter` (servlet `Filter`) — mints the
  `__se_anon_id` cookie.
- **Private attributes / sticky bucketing:** `Shipeasy.options(key)
  .privateAttributes(List.of("email")).stickyStore(new InMemoryStickyStore())`.
- **SSR:** package-level `Shipeasy.bootstrapScriptTag(user, anonId, profile, baseUrl)`
  + `Shipeasy.i18nScriptTag(clientKey, "en:prod")`.
- **Change listeners:** `Shipeasy.onChange(() -> ...)` (requires `.poll(true)`;
  returns a cancel `Runnable`).

Reference: <https://shipeasy-ai.github.io/sdk-java/pages/advanced.md> ·
<https://shipeasy-ai.github.io/sdk-java/pages/i18n.md>

## i18n

This is a **server** SDK — it has **no `t()`**. It emits the loader `<script>` tag
(`Shipeasy.i18nScriptTag(clientKey, profile)`, using the PUBLIC client key) so the
**browser** client SDK renders translations.
