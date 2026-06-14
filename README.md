# shipeasy (Java)

Server SDK for [Shipeasy](https://shipeasy.dev).

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

try (Client c = new Client(System.getenv("SHIPEASY_SERVER_KEY"))) {
    c.init();

    boolean enabled = c.getFlag("new_checkout", Map.of("user_id", "u_123"));
    Object cfg = c.getConfig("billing_copy");
    ExperimentResult r = c.getExperiment("checkout_button",
        Map.of("user_id", "u_123"),
        Map.of("color", "blue"));
    c.track("u_123", "purchase", Map.of("amount", 49));
}
```

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
c.getFlag("new_checkout", Map.of());
```

`jakarta.servlet-api` is a `provided` dependency — your container already
supplies it, so this adds nothing to your deployment. Non-servlet stacks (Ktor,
http4k, Javalin) can use the `AnonId` primitives directly. An explicit
`user_id`/`anonymous_id` always wins. The cookie is non-`HttpOnly` by design so
the browser SDK buckets identically; a request with **no** unit still resolves a
fully-rolled (100%) gate as on. Cookie name + format are a cross-SDK contract —
see `18-identity-bucketing.md`.
