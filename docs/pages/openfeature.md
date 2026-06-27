# OpenFeature provider

The Java SDK ships a **server** OpenFeature provider —
`ai.shipeasy.openfeature.ShipeasyProvider` — so apps standardized on the CNCF
[OpenFeature](https://openfeature.dev) API can plug Shipeasy in as the backing
provider.

`dev.openfeature:sdk` is a `provided`-scope dependency: the consuming app
supplies it, and non-OpenFeature users never load this class.

## Wiring

```java
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.MutableContext;
import ai.shipeasy.Engine;
import ai.shipeasy.openfeature.ShipeasyProvider;

Engine engine = new Engine(System.getenv("SHIPEASY_SERVER_KEY"));
OpenFeatureAPI.getInstance().setProviderAndWait(new ShipeasyProvider(engine));

var of = OpenFeatureAPI.getInstance().getClient();
boolean on = of.getBooleanValue("new_checkout", false, new MutableContext("u1"));
```

`setProviderAndWait` triggers `initialize`, which calls `engine.initOnce()` to
load the rules blob once; OpenFeature fires `Ready` on return. `shutdown()`
closes the engine.

## How values map

The provider is a pure adapter over `Engine` — no change to evaluation:

- **Booleans** → gate evaluation (`Engine.getFlagDetail`).
- **Strings, integers, doubles, objects** → dynamic configs
  (`Engine.getConfig`), with type coercion. A wrong-typed config returns the
  default with `TYPE_MISMATCH`.

## Context → user

The OpenFeature `targetingKey` becomes Shipeasy's `user_id`; every other
context attribute is carried through verbatim for targeting.

## Reason mapping

The provider maps Shipeasy `FlagDetail` reasons onto OpenFeature reasons:

| Shipeasy reason | OpenFeature reason | Error code |
| --- | --- | --- |
| `RULE_MATCH` | `TARGETING_MATCH` | — |
| `DEFAULT` | `DEFAULT` | — |
| `OFF` | `DISABLED` | — |
| `OVERRIDE` | `STATIC` | — |
| `FLAG_NOT_FOUND` | `ERROR` | `FLAG_NOT_FOUND` |
| `CLIENT_NOT_READY` | `ERROR` | `PROVIDER_NOT_READY` |
