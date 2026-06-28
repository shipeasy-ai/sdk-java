# Configuration

## `Shipeasy.configure(...)` — call once

`configure()` authenticates with your server key, kicks off the initial rules
fetch fire-and-forget, and registers the engine used by `see()`. It is
**first-config-wins** idempotent — the first call wins; later calls are no-ops.

```java
import ai.shipeasy.Shipeasy;

// Simplest form — server key only.
Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
```

Call this once at startup — `main()`, an `@PostConstruct` bean, or a static
initializer. After it returns, `new Client(user)` works anywhere downstream.

## The `attributes` transform

If your domain user object is not already a Shipeasy attribute map, register a
transform **once** at configure time. It runs once, in the `Client`
constructor, mapping your object to the attribute map
(`{ "user_id": ..., "anonymous_id": ..., <attrs> }`):

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .attributes((Object u) -> {
        MyUser my = (MyUser) u;
        return Map.of("user_id", my.id(), "plan", my.plan());
    }));

boolean on = new Client(myUser).getFlag("new_checkout");
```

The default transform is **identity** — if you pass a `Map<String, Object>` to
`new Client(...)`, it is used as the attribute map verbatim.

## Identity defaults

The bound attribute map should carry a stable unit: `user_id` for logged-in
users, or `anonymous_id` for logged-out traffic. If neither is present, the
engine falls back to the request-scoped `__se_anon_id` cookie resolved by
[`AnonIdFilter`](advanced.md). An explicit `user_id`/`anonymous_id` always wins.

## `configure()` options

Build options with `Shipeasy.options(apiKey)` and chain the setters, then pass
them to `Shipeasy.configure(...)`:

| Method | Default | Meaning |
| --- | --- | --- |
| `.baseUrl(String)` | `https://edge.shipeasy.dev` | Override the edge API base URL. |
| `.env(String)` | `"prod"` | Deployment env tagged on usage telemetry and `see()` events. |
| `.disableTelemetry(boolean)` | `false` | Turn off per-evaluation usage beacons. |
| `.poll(boolean)` | `false` | Start the background poll instead of a one-shot fetch. |
| `.privateAttributes(List)` | empty | Targeting-only keys stripped from outbound events. See [Advanced](advanced.md). |
| `.stickyStore(StickyBucketStore)` | none | Pluggable sticky-bucketing store. See [Advanced](advanced.md). |
| `.attributes(Function)` | identity | Map your user object to the attribute map. |

```java
Shipeasy.configure(Shipeasy.options(key)
    .env("staging")
    .disableTelemetry(true));
```

## One-shot vs background poll

By default `configure()` performs a single fire-and-forget fetch. For a
long-running server that should pick up rule changes, pass `.poll(true)` —
`configure()` owns the whole poll lifecycle (initial fetch + periodic refresh,
interval driven by the `X-Poll-Interval` response header). You never start a
poll yourself:

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .poll(true));
```

To react when a poll applies new data, register a change listener with
`Shipeasy.onChange(...)` — see [Advanced](advanced.md).

## Environment variables

The SDK reads no env vars itself — you pass the key explicitly. The convention
is `SHIPEASY_SERVER_KEY` for the server key (and `NEXT_PUBLIC_SHIPEASY_CLIENT_KEY`
or similar for the public client key used by the browser SDK / SSR i18n tag).
