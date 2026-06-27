Read the dynamic config `{{RESOURCE_NAME}}` (with a fallback when absent).
Assumes `configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Client;
import java.util.Map;

// construct once per callsite (cheap; binds the user)
Client client = new Client(Map.of("user_id", "u_123"));

Object cfg = client.getConfig(
    "{{RESOURCE_NAME}}",          // config name
    Map.of("title", "Default")); // fallback returned when the config is absent
// one-arg overload returns null when absent: client.getConfig("{{RESOURCE_NAME}}")
```
