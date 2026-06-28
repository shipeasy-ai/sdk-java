# Installation

## Requirements

- **Java 17+** (the SDK uses `java.net.http.HttpClient` and modern language features).
- A Shipeasy **server key** (`SHIPEASY_SERVER_KEY`).

## Coordinates

### Maven

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy</artifactId>
  <version>0.10.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("ai.shipeasy:shipeasy:0.10.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'ai.shipeasy:shipeasy:0.10.0'
```

## Optional, `provided`-scope dependencies

These are **not** pulled into your deployment unless you already supply them:

- `jakarta.servlet-api` — only needed for the [`AnonIdFilter`](advanced.md)
  servlet filter that mints the shared `__se_anon_id` cookie. Your container
  already supplies it.
- `dev.openfeature:sdk` — only needed for the [OpenFeature provider](openfeature.md).

## Imports

```java
import ai.shipeasy.Shipeasy;          // configure() entry point + package-level statics
import ai.shipeasy.Client;            // the cheap, user-bound handle for all reads
import ai.shipeasy.ExperimentResult;  // experiment return type
import ai.shipeasy.FlagDetail;        // value + reason
```

---

## Configure once, then bind a `Client` per request

Configuration happens **once per process**. `Shipeasy.configure(...)`
authenticates with your server key, kicks off the initial rules fetch
(fire-and-forget), and registers the engine used by `see()`. It is
**first-config-wins** idempotent — the first call wins; later calls are no-ops.

After it returns, construct a cheap, user-bound `Client` per request. Every read
then takes **no user argument** because the user is bound at construction.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

// Once, at startup.
Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

// Per user / per request.
Client c = new Client(Map.of("user_id", "u_123", "plan", "pro"));

boolean enabled = c.getFlag("new_checkout");
```

### The `attributes` transform

If your domain user object is not already a Shipeasy attribute map, register a
transform **once** at `configure()` time. It runs once, in the `Client`
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

The default transform is **identity** — pass a `Map<String, Object>` to
`new Client(...)` and it is used as the attribute map verbatim.

### Identity default

The bound attribute map should carry a stable unit: `user_id` for logged-in
users, or `anonymous_id` for logged-out traffic. If neither is present, the
engine falls back to the request-scoped `__se_anon_id` cookie resolved by
[`AnonIdFilter`](advanced.md). An explicit `user_id`/`anonymous_id` always wins.

### One-shot vs background poll

By default `configure()` performs a single fire-and-forget fetch. For a
long-running server that should pick up rule changes without a redeploy, pass
`.poll(true)` — `configure()` then owns the full poll lifecycle (initial fetch +
periodic refresh). You never start a poll yourself:

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .poll(true));
```

### `configure()` options

Build options with `Shipeasy.options(apiKey)` and chain the setters, then pass
the result to `Shipeasy.configure(...)`:

| Method | Default | Meaning |
| --- | --- | --- |
| `.baseUrl(String)` | `https://edge.shipeasy.dev` | Override the edge API base URL. |
| `.env(String)` | `"prod"` | Deployment env tagged on usage telemetry and `see()` events. |
| `.disableTelemetry(boolean)` | `false` | Turn off per-evaluation usage beacons. |
| `.poll(boolean)` | `false` | Start the background poll (initial fetch + periodic refresh) instead of a one-shot fetch. |
| `.privateAttributes(List)` | empty | Attribute keys usable for targeting but stripped from outbound events. See [Advanced](advanced.md). |
| `.stickyStore(StickyBucketStore)` | none | Pluggable sticky-bucketing store. See [Advanced](advanced.md). |
| `.attributes(Function)` | identity | Map your user object to the attribute map. |

```java
Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
    .env("staging")
    .poll(true)
    .disableTelemetry(true));
```

### Environment variables

The SDK reads no env vars itself — you pass the key explicitly. The convention
is `SHIPEASY_SERVER_KEY` for the server key (and a `*_CLIENT_KEY` public key for
the browser SDK / SSR i18n tag — never the server key in the browser).

---

## Framework wiring

Configure exactly **once**; the location is the only thing that differs per
framework. Build a `Client` per request thereafter.

### Spring Boot — `@PostConstruct`

Run `configure()` from a bean's `@PostConstruct`:

```java
import ai.shipeasy.Shipeasy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
class ShipeasyConfig {
    @PostConstruct
    void init() {
        // For a long-running server, start the background poll:
        Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
            .poll(true));
    }
}
```

### Spring Boot — `@PostConstruct` with the `attributes` transform

When you map your own principal type to the attribute map, register the
transform at `configure()` time, then build a `Client(principal)` per request:

```java
import ai.shipeasy.Shipeasy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
class ShipeasyConfig {
    @PostConstruct
    void init() {
        Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
            .poll(true)
            .attributes((Object u) -> {
                MyPrincipal p = (MyPrincipal) u;
                return Map.of("user_id", p.id(), "plan", p.plan());
            }));
    }
}
```

```java
// In a controller / service — build a Client per request:
import ai.shipeasy.Client;

@GetMapping("/checkout")
String checkout(@AuthenticationPrincipal MyPrincipal principal) {
    Client c = new Client(principal);  // construct once per callsite
    return c.getFlag("new_checkout") ? "v2" : "v1";
}
```

For logged-out traffic, register `AnonIdFilter` so anonymous bucketing is shared
with the browser SDK (see Servlet/Jakarta below).

### Servlet / Jakarta — `ServletContextListener` + `AnonIdFilter`

Configure on context startup, and register `AnonIdFilter` so every request
without a `__se_anon_id` cookie gets one minted (the shared first-party cookie
every Shipeasy SDK buckets on):

```java
import ai.shipeasy.Shipeasy;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class ShipeasyBootstrap implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
            .poll(true));
    }
}
```

```java
// AnonIdFilter mints the shared __se_anon_id cookie for any request without it;
// evaluations then default to it as anonymous_id — no per-call wiring.
import ai.shipeasy.AnonIdFilter;
import jakarta.servlet.annotation.WebFilter;

@WebFilter("/*")
public class ShipeasyAnonId extends AnonIdFilter {}
```

In a Spring Boot servlet stack, register the same filter as a bean:

```java
import ai.shipeasy.AnonIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@Bean
FilterRegistrationBean<AnonIdFilter> shipeasyAnonId() {
    var reg = new FilterRegistrationBean<>(new AnonIdFilter());
    reg.addUrlPatterns("/*");
    return reg;
}
```

```java
// A logged-out request now buckets on the __se_anon_id cookie automatically:
new Client(Map.of()).getFlag("new_checkout");
```

`jakarta.servlet-api` is a `provided` dependency — your container already
supplies it, so this adds nothing to your deployment. Non-servlet stacks (Ktor,
http4k, Javalin) can use the `AnonId` primitives directly.

### Plain `main()`

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        // Configure once; poll(true) keeps a long-running app fresh.
        Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
            .poll(true));

        Client c = new Client(Map.of("user_id", "u_123"));
        System.out.println(c.getFlag("new_checkout"));
    }
}
```
