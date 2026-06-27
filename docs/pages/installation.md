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
  <version>0.8.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("ai.shipeasy:shipeasy:0.8.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'ai.shipeasy:shipeasy:0.8.0'
```

## Optional, `provided`-scope dependencies

These are **not** pulled into your deployment unless you already supply them:

- `jakarta.servlet-api` — only needed for the [`AnonIdFilter`](advanced.md)
  servlet filter that mints the shared `__se_anon_id` cookie. Your container
  already supplies it.
- `dev.openfeature:sdk` — only needed for the [OpenFeature provider](openfeature.md).

## Imports

```java
import ai.shipeasy.Shipeasy;          // configure() entry point
import ai.shipeasy.Client;            // user-bound handle
import ai.shipeasy.Engine;            // heavyweight handle (advanced)
import ai.shipeasy.ExperimentResult;  // experiment return type
import ai.shipeasy.FlagDetail;        // value + reason
```

---

## Configure once, then bind a `Client` per request

Configuration happens **once per process**. After it returns, construct a
cheap, user-bound `Client` per request — every evaluation call then takes **no
user argument** because the user is bound at construction.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

// Once, at startup. Builds the single process-wide Engine, registers it as the
// default see() engine, and kicks off the initial rules fetch fire-and-forget.
// First-config-wins idempotent — later calls return the already-built engine.
Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

// Per user / per request. The user is bound at construction (cheap — forwards
// to the global Engine; owns no HTTP, cache or timer).
Client c = new Client(Map.of("user_id", "u_123", "plan", "pro"));

boolean enabled = c.getFlag("new_checkout");
```

### The `attributes` transform

If your domain user object is not already a Shipeasy attribute map, register a
transform **once** at `configure` time. It runs once, in the `Client`
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

`configure` performs a single fire-and-forget fetch. For a long-running server
that should pick up rule changes, call `init()` on the returned `Engine` to
start the background poll (interval driven by the `X-Poll-Interval` response
header):

```java
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
engine.init(); // starts the background poll loop
```

### `Options` knobs

Build `Options` with `Shipeasy.options(apiKey)` and chain:

| Method | Default | Meaning |
| --- | --- | --- |
| `.baseUrl(String)` | `https://edge.shipeasy.dev` | Override the edge API base URL. |
| `.env(String)` | `"prod"` | Deployment env tagged on usage telemetry and `see()` events. |
| `.disableTelemetry(boolean)` | `false` | Turn off per-evaluation usage beacons. |
| `.attributes(Function)` | identity | Map your user object → attribute map. |

### Environment variables

The SDK reads no env vars itself — you pass the key explicitly. The convention
is `SHIPEASY_SERVER_KEY` for the server key (and a `*_CLIENT_KEY` public key for
the browser SDK / SSR i18n tag — never the server key in the browser).

---

## Framework wiring

Configure exactly **once**; the location is the only thing that differs per
framework. Build a `Client` per request thereafter.

### Spring Boot — `@PostConstruct`

Run `configure` from a bean's `@PostConstruct`:

```java
import ai.shipeasy.Shipeasy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
class ShipeasyConfig {
    @PostConstruct
    void init() {
        Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
        // For a long-running server, also start the background poll:
        // Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY")).init();
    }
}
```

### Spring Boot — `@Bean` (with the `attributes` transform)

A `@Bean` factory is the natural home when you map your own principal type:

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Engine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Configuration
class ShipeasyConfiguration {
    @Bean
    Engine shipeasyEngine() {
        return Shipeasy.configure(Shipeasy.options(System.getenv("SHIPEASY_SERVER_KEY"))
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
    boolean on = new Client(principal).getFlag("new_checkout");
    return on ? "v2" : "v1";
}
```

For logged-out traffic, register the `AnonIdFilter` so anonymous bucketing is
shared with the browser SDK (see Servlet/Jakarta below).

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
        Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
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
    public static void main(String[] args) throws Exception {
        // Configure once; init() starts the background poll for a long-running app.
        Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY")).init();

        Client c = new Client(Map.of("user_id", "u_123"));
        System.out.println(c.getFlag("new_checkout"));
    }
}
```

### Using the `Engine` directly (advanced)

You can construct and drive an `Engine` yourself instead of the global
`configure` path — useful for tests or multiple independent keys. It is
`AutoCloseable`:

```java
import ai.shipeasy.Engine;
import java.util.Map;

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
