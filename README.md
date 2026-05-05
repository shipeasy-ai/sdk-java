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
