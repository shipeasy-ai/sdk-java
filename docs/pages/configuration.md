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
| `.baseUrl(String)` | `https://api.shipeasy.ai` | Override the edge API base URL. |
| `.env(String)` | `"prod"` | Deployment env tagged on usage telemetry and `see()` events. |
| `.disableTelemetry(boolean)` | `false` | Turn off per-evaluation usage beacons. |
| `.disableInternalErrorReporting(boolean)` | `false` | Turn off SDK self-monitoring (internal errors reported to Shipeasy's own project). See below. |
| `.poll(boolean)` | `false` | Start the background poll instead of a one-shot fetch. |
| `.privateAttributes(List)` | empty | Targeting-only keys stripped from outbound events. See [Advanced](advanced.md). |
| `.stickyStore(StickyBucketStore)` | none | Pluggable sticky-bucketing store. See [Advanced](advanced.md). |
| `.logLevel(LogLevel)` | `WARN` | Verbosity of the SDK's own diagnostics. See below. |
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

## Fail-safe reads & the `logLevel` option

Runtime reads never throw into your code. `getFlag` / `getFlagDetail` /
`getConfig` / `universe().assign()` / `getKillswitch` — and `track` / `see()` —
always return a safe default on any unexpected error rather than propagate it:
`getFlag` → your default (or `false`), `getConfig` → your default (or `null`),
`universe().assign()` → a not-enrolled `Assignment` (`group() == null`, `get()`
resolves the universe default or your fallback), `getKillswitch` → `false`. A
flag read can never take down a request path.

Setup and lifecycle calls still throw loudly, because they signal
misconfiguration you want to catch at boot: `new Client(user)` before
`configure(...)`, `configureForOffline(...)`, and `new ShipeasyProvider()`.

When a read swallows an error it logs a diagnostic. Control that verbosity with
`.logLevel(LogLevel)` — ordered `SILENT < ERROR < WARN < INFO < DEBUG`, default
`WARN`. A message at level `L` is emitted only when the configured level is at
least `L`; `LogLevel.SILENT` mutes the SDK entirely. This gates only the SDK's
own diagnostics — never what your application logs.

```java
import ai.shipeasy.LogLevel;
import ai.shipeasy.Shipeasy;

Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .logLevel(LogLevel.SILENT));   // quiet the SDK's internal diagnostics
```

## SDK self-monitoring

When a runtime read hits the `Client`'s last-resort guard and returns a safe
default, that error is a bug on Shipeasy's side, not yours. In addition to
logging it locally, the SDK reports it to **Shipeasy's own project** — a
dedicated, baked-in destination, entirely separate from your `see()` reporting.
These internal errors never land in your project or your Errors tab; they let the
SDK team track and fix SDK bugs across every app the SDK runs in. The report is
fire-and-forget (it can never slow down or break a read), deduped, and carries
only the error plus a stable subject (the guarded operation, e.g.
`Client.getFlag`). It is on by default and never sends in test/offline mode.

Opt out with `.disableInternalErrorReporting(true)`:

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .disableInternalErrorReporting(true));
```

## Environment variables

The SDK reads no env vars itself — you pass the key explicitly. The convention
is `SHIPEASY_SERVER_KEY` for the server key (and `NEXT_PUBLIC_SHIPEASY_CLIENT_KEY`
or similar for the public client key used by the browser SDK / SSR i18n tag).
