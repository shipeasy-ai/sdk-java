Read the dynamic config `{{RESOURCE_NAME}}` (with a fallback when absent).

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

Object cfg = new Client(Map.of("user_id", "u_123"))
    .getConfig("{{RESOURCE_NAME}}", Map.of("title", "Default"));
```
