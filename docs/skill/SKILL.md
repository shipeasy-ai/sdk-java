---
name: shipeasy-java
description: Use Shipeasy (feature flags, configs, kill switches, A/B experiments, i18n) from Java. Covers configure() + Client(user), getFlag/getConfig/getExperiment, track, testing, OpenFeature.
---

# Shipeasy Java SDK

Server-side Java SDK for Shipeasy. Evaluate feature flags, dynamic configs, kill
switches, and A/B experiments locally against a cached rules blob.

## Install

Maven (`ai.shipeasy:shipeasy`, Java 17+):

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy</artifactId>
  <version>0.8.0</version>
</dependency>
```

## Configure once, bind per user

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import ai.shipeasy.Engine;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

// Once, at startup. Builds the global Engine, kicks off the initial fetch.
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
// engine.init(); // optional: start the background poll for long-running servers

// Per user / per request — user bound at construction, no user arg per call.
Client c = new Client(Map.of("user_id", "u_123", "plan", "pro"));

boolean enabled    = c.getFlag("new_checkout");            // bool gate
boolean withDefault= c.getFlag("new_checkout", true);      // default only when unresolvable
Object cfg         = c.getConfig("billing_copy");          // dynamic config
Object cfgOrFallbk = c.getConfig("billing_copy", Map.of()); // with fallback
boolean killed     = c.getKillswitch("panic_button");      // kill switch
```

Map your own user object with a one-time `attributes` transform:

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .attributes((Object u) -> {
        MyUser my = (MyUser) u;
        return Map.of("user_id", my.id(), "plan", my.plan());
    }));
boolean on = new Client(myUser).getFlag("new_checkout");
```

The default transform is identity — a `Map<String,Object>` is used verbatim.

## Experiments + track

```java
ExperimentResult r = c.getExperiment("checkout_button", Map.of("color", "blue"));
if (r.inExperiment && "treatment".equals(r.group)) {
    @SuppressWarnings("unchecked")
    Map<String, Object> params = (Map<String, Object>) r.params;
    // render variant using params
}

// track() lives on the Engine (fire-and-forget to /collect):
engine.track("u_123", "purchase", Map.of("amount", 49));

// the server never auto-logs exposure — call it when you present the treatment:
engine.logExposure("u_123", "checkout_button");
```

## Evaluation reason

```java
FlagDetail d = c.getFlagDetail("new_checkout");
d.value();   // boolean
d.reason();  // OVERRIDE / CLIENT_NOT_READY / FLAG_NOT_FOUND / OFF / RULE_MATCH / DEFAULT
```

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
nothing); `violation("name")...to("...")` for non-throwable problems.

## Testing

```java
import ai.shipeasy.Engine;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

try (Engine t = Engine.forTesting()) {        // no key, no network
    t.overrideFlag("new_checkout", true);
    t.overrideConfig("billing_copy", Map.of("title", "Hello"));
    t.overrideExperiment("checkout_button", "treatment", Map.of("color", "green"));
    assert t.getFlag("new_checkout", Map.of());
    t.clearOverrides();
}

// real evaluation against a captured snapshot, no network:
try (Engine t = Engine.fromFile("snapshot.json")) {
    boolean on = t.getFlag("new_checkout", Map.of("user_id", "u_123"));
}
// or Engine.fromSnapshot(flagsBlob, experimentsBlob)
```

## OpenFeature

```java
import dev.openfeature.sdk.OpenFeatureAPI;
import ai.shipeasy.Engine;
import ai.shipeasy.openfeature.ShipeasyProvider;

Engine engine = new Engine(System.getenv("SHIPEASY_SERVER_KEY"));
OpenFeatureAPI.getInstance().setProviderAndWait(new ShipeasyProvider(engine));
boolean on = OpenFeatureAPI.getInstance().getClient()
    .getBooleanValue("new_checkout", false, new MutableContext("u1"));
```

Booleans → gates; strings/numbers/objects → dynamic configs. `targetingKey` →
`user_id`.

## Advanced

- **Anonymous bucketing:** register `AnonIdFilter` (servlet `Filter`) — mints
  the `__se_anon_id` cookie; logged-out evals default to it.
- **Private attributes:** `engine.privateAttributes(List.of("email"))` — stripped from outbound telemetry.
- **Sticky bucketing:** `engine.stickyStore(new InMemoryStickyStore())`.
- **`bucketBy`** is a server-side experiment property (read from the blob), not a per-call knob.
- **SSR:** `engine.bootstrapScriptTag(user, anonId)` + `engine.i18nScriptTag(clientKey, "en:prod")`.
- **Change listeners:** `engine.onChange(() -> ...)` (fires on a 200 poll; returns a cancel `Runnable`).

## i18n

This is a **server** SDK — it has **no `t()`**. It emits the loader `<script>`
tag (`engine.i18nScriptTag(clientKey, profile)`, using the PUBLIC client key) so
the **browser** client SDK renders translations. Server-side string lookup does
not exist here.
