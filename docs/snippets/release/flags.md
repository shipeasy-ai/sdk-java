Configure once, then read `{{RESOURCE_NAME}}` off a user-bound `Client`.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY")); // once, at startup

boolean enabled = new Client(Map.of("user_id", "u_123")).getFlag("{{RESOURCE_NAME}}");
```
