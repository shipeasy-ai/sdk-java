# Advanced

## Exposure logging

Exposure is **automatic**: `universe(name).assign()` POSTs one
`{type:"exposure", experiment, group, user_id, ts}` event to `/collect` when the
unit is enrolled — there is no manual `logExposure`. Just assign at the point you
present the experiment:

```java
// Assumes Shipeasy.configure(...) ran at startup — see Installation.
Client c = new Client(Map.of("user_id", "u_123", "plan", "pro")); // construct once per callsite
Assignment a = c.universe("hero_cta").assign(); // auto-logs one exposure when enrolled
```

The exposure is **deduped per process**: repeated `assign()` calls for the same
`(unit, experiment, group)` POST only one exposure. No-op in test/snapshot mode
or when the unit isn't enrolled.

## Private attributes

Mark attribute names that may be used for targeting but must **never** be
persisted in analytics (LD/Statsig `privateAttributes`). The server evaluates
locally, so private attrs never leave for evaluation; the only egress is
`/collect`, and these keys are stripped from every outbound `track()` payload
(and `see()` extras):

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .privateAttributes(List.of("email", "ip")));
```

## Bucketing identifier (`bucketBy`)

`bucketBy` is a **server-side experiment property** — it is set on the
experiment in the Shipeasy dashboard (e.g. `company_id`), and the SDK reads it
from the experiment blob automatically. When set, the experiment buckets on that
attribute instead of `user_id`/`anonymous_id`. Make sure the bucketing attribute
is present in the user map you pass. There is no per-call `bucketBy` knob in the
SDK.

## Sticky bucketing

Supply a `StickyBucketStore` so an enrolled unit stays locked to its
first-assigned variant even if you change allocation % or group weights
(changing the experiment salt is the reshuffle lever). Absent ⇒ deterministic
(fully backward compatible):

```java
import ai.shipeasy.InMemoryStickyStore;

Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .stickyStore(new InMemoryStickyStore()));
```

`InMemoryStickyStore` is a process-local, thread-safe store (good for tests and
single-process servers). Implement `StickyBucketStore` (`get(unit)` /
`set(unit, exp, entry)`) over a shared cache (Redis, DB) for multi-process
deployments.

## Anonymous visitors — `AnonIdFilter` (zero-config bucketing)

For logged-out traffic you need a *stable* unit so a fractional rollout buckets
the same on the server and in the browser. `AnonIdFilter` is a servlet `Filter`
that mints the shared `__se_anon_id` first-party cookie for any request without
one; evaluations then **default to it** as `anonymous_id`, so a logged-out
request needs no per-call wiring.

```java
// Spring Boot
@Bean
FilterRegistrationBean<AnonIdFilter> shipeasyAnonId() {
    var reg = new FilterRegistrationBean<>(new AnonIdFilter());
    reg.addUrlPatterns("/*");
    return reg;
}
```

```java
// logged-out request → buckets on the __se_anon_id cookie automatically
new Client(Map.of()).getFlag("new_checkout");
```

An explicit `user_id`/`anonymous_id` always wins. The cookie is non-`HttpOnly`
by design so the browser SDK buckets identically. Non-servlet stacks (Ktor,
http4k, Javalin) can use the `AnonId` primitives directly.

## Server-side rendering (SSR) bootstrap

Emit the request's evaluated flags as a declarative `<script>` tag so the
browser SDK has them on first paint. `bootstrapScriptTag` carries the payload in
`data-*` attributes (**no key** — the server key must never reach the browser);
the static `se-bootstrap.js` loader hydrates `window.__SE_BOOTSTRAP` and writes
the `__se_anon_id` cookie:

```java
// Assumes Shipeasy.configure(...) ran at startup — see Installation.
Map<String, Object> user = Map.of("user_id", "u_123");

String head = Shipeasy.bootstrapScriptTag(user, anonId, "en:prod", null)
            + Shipeasy.i18nScriptTag(clientKey, "en:prod");
```

Overloads let you omit the anon id, or pass `i18nProfile` / `baseUrl` (defaults
to `https://cdn.shipeasy.ai`).

## Change listeners

Register a listener that fires after a background poll applies **new** data (an
HTTP 200, not a 304). `onChange` returns a cancel `Runnable`:

```java
// Start the background poll so listeners can fire (configure owns the lifecycle):
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY")).poll(true));

Runnable cancel = Shipeasy.onChange(() -> log.info("flags updated"));
// ... later
cancel.run(); // unsubscribe
```

Listeners never fire in local/test/snapshot mode (those do no polling) and a
throwing listener is isolated — it's logged, others still run.
