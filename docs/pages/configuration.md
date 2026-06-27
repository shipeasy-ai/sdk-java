# Configuration

## `Shipeasy.configure(...)` — call once

`configure` builds the single process-wide `Engine`, registers it as the
default `see()` engine, and kicks off the initial rules fetch fire-and-forget
(like `Engine.initOnce()`). It is **first-config-wins idempotent** — the first
call wins; later calls return the already-built engine without rebuilding.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Engine;

// Simplest form — server key only.
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
```

Call this once at startup — `main()`, an `@PostConstruct` bean, or a static
initializer. After it returns, `new Client(user)` works anywhere downstream.

## The `attributes` transform

If your domain user object is not already a Shipeasy attribute map, register a
transform **once** at configure time. It runs once, in the `Client`
constructor, mapping your object to the attribute map
(`{ "user_id": ..., "anonymous_id": ..., <attrs> }`):

```java
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

## `Options` knobs

Build `Options` with `Shipeasy.options(apiKey)` and chain:

| Method | Default | Meaning |
| --- | --- | --- |
| `.baseUrl(String)` | `https://edge.shipeasy.dev` | Override the edge API base URL. |
| `.env(String)` | `"prod"` | Deployment env tagged on usage telemetry and `see()` events. |
| `.disableTelemetry(boolean)` | `false` | Turn off per-evaluation usage beacons. |
| `.attributes(Function)` | identity | Map your user object → attribute map. |

```java
Engine engine = Shipeasy.configure(Shipeasy.options(key)
    .env("staging")
    .disableTelemetry(true));
```

## One-shot vs background poll

`configure` performs a single fire-and-forget fetch. For a long-running server
that should pick up rule changes, call `init()` on the returned engine to start
the background poll (interval driven by the `X-Poll-Interval` response header):

```java
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
engine.init(); // starts the background poll loop
```

## Using the `Engine` directly (advanced)

You can construct and drive an `Engine` yourself instead of the global
`configure` path — useful for tests or multiple independent keys:

```java
try (Engine engine = new Engine(System.getenv("SHIPEASY_SERVER_KEY"))) {
    engine.init();
    boolean enabled = engine.getFlag("new_checkout", Map.of("user_id", "u_123"));
    engine.track("u_123", "purchase", Map.of("amount", 49));
}
```

> **Breaking change (0.8.0):** the heavyweight client was renamed
> `Client` → `Engine`; `Client` is now the lightweight user-bound handle.
> Replace `new Client(key)` with `new Engine(key)` (or, preferably,
> `Shipeasy.configure(key)`).

## Environment variables

The SDK reads no env vars itself — you pass the key explicitly. The convention
is `SHIPEASY_SERVER_KEY` for the server key (and `NEXT_PUBLIC_SHIPEASY_CLIENT_KEY`
or similar for the public client key used by the browser SDK / SSR i18n tag).
