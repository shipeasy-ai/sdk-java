Check whether the kill switch `{{RESOURCE_NAME}}` is killed. Assumes
`configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Client;
import java.util.Map;

// construct once per callsite (cheap; binds the user)
Client client = new Client(Map.of("user_id", "u_123"));

boolean killed = client.getKillswitch("{{RESOURCE_NAME}}"); // killswitch name
// optional second arg reads one named per-key switch (null = whole killswitch):
// boolean off = client.getKillswitch("{{RESOURCE_NAME}}", "eu_region" /* switchKey */);

if (killed) {
    // disable the protected path
}
```
