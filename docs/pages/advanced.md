# Advanced

## Exposure logging

Exposure is **automatic** but fires **on read**, not at `assign()` time:
`assign()` is side-effect free, and the first `get(...)` on an enrolled
assignment POSTs one `{type:"exposure", experiment, group, user_id, ts}` event to
`/collect`. An assignment that is computed but never read logs nothing. There is
no manual `logExposure`. Just assign and read at the point you present the
experiment:

```java
// Assumes Shipeasy.configure(...) ran at startup — see Installation.
Client c = new Client(Map.of("user_id", "u_123", "plan", "pro")); // construct once per callsite
Assignment a = c.universe("hero_cta").assign();  // side-effect free
String label = (String) a.get("primary_label", "Sign up"); // first get() logs the exposure
String peeked = (String) a.peek("primary_label", "Sign up"); // read WITHOUT logging an exposure
```

The exposure is **deduped per process** and, durably, per
`(unit, experiment, group)` server-side: repeated reads for the same
`(unit, experiment, group)` POST only one exposure. Use `peek(...)` to read a
param without logging at all. No-op in test/snapshot mode or when the unit isn't
enrolled.

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
the `/sdk/runtime.js` browser runtime reads them, installs `window.shipeasy`,
republishes `window.__SE_BOOTSTRAP` for the npm client SDK and writes the
`__se_anon_id` cookie so the browser buckets identically to the server:

```java
// Assumes Shipeasy.configure(...) ran at startup — see Installation.
Map<String, Object> user = Map.of("user_id", "u_123");

String head = Shipeasy.bootstrapScriptTag(user, anonId)
            + Shipeasy.i18nScriptTag();
```

### Every argument is optional

The tag helpers overload down to a no-argument call: the client key, profile,
project id and CDN base all come from the `configure` options, and a `null`
argument (or no `user`) falls back the same way.

| Helper | No-argument form | Defaults from the options |
| --- | --- | --- |
| `Shipeasy.i18nScriptTag()` | `i18nScriptTag(clientKey, profile, baseUrl)` | `.clientKey`, `.profile`, `.cdnBaseUrl` |
| `Shipeasy.bootstrapScriptTag()` | `bootstrapScriptTag(user, anonId, i18nProfile, baseUrl)` | anonymous request, `.profile`, `.cdnBaseUrl` |
| `Shipeasy.devtoolsScriptTag()` | `devtoolsScriptTag(projectId, clientKey, baseUrl, defer)` | `.projectId`, `.clientKey`, `.cdnBaseUrl` |

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .clientKey(System.getenv("SHIPEASY_CLIENT_KEY"))   // PUBLIC key, for the tags
    .projectId(System.getenv("SHIPEASY_PROJECT_ID"))   // for the devtools tag
    .profile("en:prod"));
```

A tag still renders when a value is missing (the browser bundle reports what it
needs), but the SDK logs a warning naming the option to fill in — once per
option, not once per render.

### Devtools overlay tag

`Shipeasy.devtoolsScriptTag()` emits the hosted devtools overlay bundle —
nothing to install, no overlay code in your artifact. It reads the project id and
public client key off the tag and opens with **Shift+Alt+S** or on any page
loaded with `?se=1`. It is deferred unless you pass `defer = false`: a developer
tool never belongs on the critical rendering path.

```java
// Render it for your own team only.
String devtools = user.isStaff() ? Shipeasy.devtoolsScriptTag() : "";
```

### Identity coherence (no anon to identified flip)

When the `user` you evaluate carries an identified attribute (a `user_id`,
`email`, or any trait other than `anonymous_id`), the tag also emits a
`data-user` attribute — the HTML-escaped JSON of those traits, with
`anonymous_id` and null values dropped. The browser SDK reads it and adopts that
server-known identity on first paint, so a Java backend with a JS frontend never
shows the anonymous-then-identified flip (the same identity buckets flags on both
sides). An anonymous request — only an `anonymous_id`, or an empty user — emits no
`data-user`. See `experiment-platform/18-identity-bucketing.md`.

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
