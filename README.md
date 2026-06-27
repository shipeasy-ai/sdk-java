# shipeasy (Java)

Server SDK for [Shipeasy](https://shipeasy.dev).

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy</artifactId>
  <version>0.8.0</version>
</dependency>
```

📖 **Documentation:** [Installation & configuration](docs/pages/installation.md)
(Maven/Gradle, Spring Boot, Servlet/Jakarta, plain `main()`) · [full docs](docs/)

Configure once at startup, then build a user-bound `Client` per request — every
evaluation call takes **no user argument** because the user is bound:

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

// Once, at startup (e.g. main() or an @PostConstruct bean). Builds the single
// global engine and kicks off the initial fetch fire-and-forget.
Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

// Per user / per request. The user is bound at construction.
Client c = new Client(Map.of("user_id", "u_123", "plan", "pro"));

boolean enabled = c.getFlag("new_checkout");
Object cfg      = c.getConfig("billing_copy");
ExperimentResult r = c.getExperiment("checkout_button", Map.of("color", "blue"));
boolean killed  = c.getKillswitch("panic_button");
```

### Mapping your own user object

Pass an `attributes` transform once at `configure` time; `new Client(yourUser)`
runs it once and binds the resulting attribute map:

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .attributes((Object u) -> {
        MyUser my = (MyUser) u;
        return Map.of("user_id", my.id(), "plan", my.plan());
    }));

boolean on = new Client(myUser).getFlag("new_checkout");
```

The default transform is identity — if you pass a `Map<String, Object>` it is
used as the attribute map verbatim.

### Engine (advanced)

`Shipeasy.configure(...)` returns the underlying `Engine` — the heavyweight
handle that owns the API key, HTTP, the blob cache, the poll timer, `track()`,
`see()` and the offline/test factories. Long-running servers can call
`init()` on it to also start the background poll, or use it directly:

```java
import ai.shipeasy.Engine;

try (Engine engine = new Engine(System.getenv("SHIPEASY_SERVER_KEY"))) {
    engine.init();
    boolean enabled = engine.getFlag("new_checkout", Map.of("user_id", "u_123"));
    engine.track("u_123", "purchase", Map.of("amount", 49));
}
```

> **Breaking change (0.8.0):** the heavyweight client was renamed `Client` →
> `Engine`, and `Client` is now the lightweight user-bound handle above. Replace
> `new Client(key)` with `new Engine(key)` (or, preferably, `Shipeasy.configure(key)`).

## Anonymous visitors (zero-config bucketing)

For logged-out traffic you need a *stable* unit so a fractional rollout buckets
the same on the server and in the browser. `AnonIdFilter` is a servlet `Filter`
that mints the shared `__se_anon_id` first-party cookie (used by every Shipeasy
SDK, incl. the browser) for any request without one; evaluations then **default
to it** as `anonymous_id`, so a logged-out request needs no per-call wiring.

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

`jakarta.servlet-api` is a `provided` dependency — your container already
supplies it, so this adds nothing to your deployment. Non-servlet stacks (Ktor,
http4k, Javalin) can use the `AnonId` primitives directly. An explicit
`user_id`/`anonymous_id` always wins. The cookie is non-`HttpOnly` by design so
the browser SDK buckets identically; a request with **no** unit still resolves a
fully-rolled (100%) gate as on. Cookie name + format are a cross-SDK contract —
see `18-identity-bucketing.md`.

## Server-side rendering (SSR)

Emit the request's evaluated flags as a declarative `<script>` tag so the
browser SDK has them on first paint. `bootstrapScriptTag` carries the payload in
`data-*` attributes (**no key**); the static `se-bootstrap.js` loader hydrates
`window.__SE_BOOTSTRAP` and writes the `__se_anon_id` cookie so the browser
buckets identically to the server.

```java
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
Map<String, Object> user = Map.of("user_id", "u_123");

// Two tags for the document <head>. The PUBLIC client key (not the server
// key) goes on the i18n loader tag.
String head = engine.bootstrapScriptTag(user, anonId)
            + engine.i18nScriptTag(clientKey, "en:prod");

// …or get the raw payload ({flags, configs, experiments, killswitches}):
Map<String, Object> boot = engine.evaluate(user);
```

The SSR/bootstrap helpers live on the `Engine` (the per-request payload is a
whole-blob batch evaluate, not a single bound user's lookup).

Overloads let you omit the anon id, or pass `i18nProfile` / `baseUrl`
(defaults to `https://cdn.shipeasy.ai`).

## Default values

`getFlag`/`getConfig` have default-value overloads. The default is returned
**only when the value cannot be resolved** — the client is not initialized yet
or the key is absent — never when a flag legitimately evaluates to `false`:

```java
Client c = new Client(Map.of("user_id", "u_123"));

// returns `true` only if the flag is missing or the engine isn't ready;
// a flag that evaluates to false returns false (not the default).
boolean enabled = c.getFlag("new_checkout", true);

// returns the fallback when the config key is absent.
Object copy = c.getConfig("billing_copy", Map.of("title", "Default"));
```

(The same default-value overloads exist on the `Engine` with an explicit user
argument: `engine.getFlag(name, user, default)`.)

## Evaluation detail

`getFlagDetail` returns the resolved value together with the `reason` it
resolved that way, for logging and debugging:

```java
FlagDetail d = new Client(Map.of("user_id", "u_123")).getFlagDetail("new_checkout");
d.value();   // boolean
d.reason();  // one of the FlagDetail.* reason constants
```

The reason is one of:

| Reason             | Meaning                                                |
| ------------------ | ------------------------------------------------------ |
| `OVERRIDE`         | An override was set (short-circuits; no telemetry).    |
| `CLIENT_NOT_READY` | No blob fetched yet (not initialized).                 |
| `FLAG_NOT_FOUND`   | The flag is not present in the fetched blob.           |
| `OFF`              | The flag is disabled or killswitched.                  |
| `RULE_MATCH`       | The flag evaluated to `true` via its rules/rollout.    |
| `DEFAULT`          | The flag is on but evaluated to `false` for this user. |

`getFlag(name)` is just `getFlagDetail(name).value()`.

## Change listeners

Register a listener that fires after a background poll applies **new** data
(an HTTP 200, not a 304). `onChange` returns a cancel `Runnable`:

```java
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
Runnable cancel = engine.onChange(() -> log.info("flags updated"));
// ... later
cancel.run(); // unsubscribe
```

Listeners never fire in local/test/snapshot mode (those do no polling) and a
throwing listener is isolated — it's logged, others still run.

## Offline snapshot

Run fully offline against a captured snapshot — no network, real evaluation.
`fromFile` reads a JSON file of the shape
`{ "flags": <body of /sdk/flags>, "experiments": <body of /sdk/experiments> }`;
`fromSnapshot` takes the same two blobs in memory:

```java
try (Engine engine = Engine.fromFile("snapshot.json")) {
    boolean on = engine.getFlag("new_checkout", Map.of("user_id", "u_123"));
}

try (Engine engine = Engine.fromSnapshot(flagsBlob, experimentsBlob)) {
    Object cfg = engine.getConfig("billing_copy", "fallback");
}
```

Snapshot engines are no-network like `forTesting()` — `init()`/`initOnce()`/
`track()` are no-ops, telemetry is off, and `override*` values apply on top of
the snapshot.

## Testing

`Engine.forTesting()` returns a no-network engine for unit tests: telemetry is
disabled, `init()`/`initOnce()` and `track()` are no-ops (they never reach the
network), and **no API key is required**. Seed each entity with the `override*`
setters; an override always wins over any fetched value, and `clearOverrides()`
resets them. The setters are also callable on a normal `Engine`.

```java
import ai.shipeasy.Engine;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

try (Engine c = Engine.forTesting()) {
    // Flags
    c.overrideFlag("new_checkout", true);
    boolean enabled = c.getFlag("new_checkout", Map.of()); // true

    // Configs (any JSON-shaped value)
    c.overrideConfig("billing_copy", Map.of("title", "Hello"));
    Object cfg = c.getConfig("billing_copy"); // {title=Hello}

    // Experiments → ExperimentResult(true, group, params)
    c.overrideExperiment("checkout_button", "treatment", Map.of("color", "green"));
    ExperimentResult r = c.getExperiment("checkout_button", Map.of(),
        Map.of("color", "blue"));
    // r.inExperiment == true, r.group == "treatment", r.params == {color=green}

    // track() is a no-op here — no key, no network call
    c.track("u_123", "purchase", Map.of("amount", 49));

    c.clearOverrides(); // back to default (no-override) behavior
}
```
