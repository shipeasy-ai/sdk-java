# OpenFeature provider

The Java SDK ships a **server** OpenFeature provider —
`ai.shipeasy.openfeature.ShipeasyProvider` — so apps standardized on the CNCF
[OpenFeature](https://openfeature.dev) API can plug Shipeasy in as the backing
provider.

`dev.openfeature:sdk` is a `provided`-scope dependency: the consuming app
supplies it, and non-OpenFeature users never load this class.

## Wiring

Assumes `Shipeasy.configure(...)` ran at startup — see [Installation](installation.md).
The **no-arg** `new ShipeasyProvider()` resolves the configured global engine, so
OpenFeature is wired without naming the engine:

```java
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.MutableContext;
import ai.shipeasy.openfeature.ShipeasyProvider;

// new ShipeasyProvider() (no arg) resolves the engine built by Shipeasy.configure(...)
OpenFeatureAPI.getInstance().setProviderAndWait(new ShipeasyProvider());

var of = OpenFeatureAPI.getInstance().getClient();
boolean on = of.getBooleanValue("new_checkout", false, new MutableContext("u1"));
```

`setProviderAndWait` triggers `initialize`, which loads the rules blob once;
OpenFeature fires `Ready` on return.

## How values map

The provider is a pure adapter — no change to evaluation:

- **Booleans** → gate evaluation.
- **Strings, integers, doubles, objects** → dynamic configs, with type coercion.
  A wrong-typed config returns the default with `TYPE_MISMATCH`.

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
