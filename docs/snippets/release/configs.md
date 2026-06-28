Read the dynamic config `{{CONFIG_KEY}}` (with a fallback when absent).
Assumes `configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Client;
import java.util.Map;

// construct once per callsite (cheap; binds the user)
Client client = new Client(Map.of("user_id", "u_123"));

Object cfg = client.getConfig(
    "{{CONFIG_KEY}}",          // config name
    Map.of("title", "Default")); // fallback returned when the config is absent
// one-arg overload returns null when absent: client.getConfig("{{CONFIG_KEY}}")
```
