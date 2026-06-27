Check whether the kill switch `{{RESOURCE_NAME}}` is killed.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import java.util.Map;

Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

boolean killed = new Client(Map.of("user_id", "u_123")).getKillswitch("{{RESOURCE_NAME}}");
if (killed) {
    // disable the protected path
}
```
