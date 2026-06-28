Check whether the kill switch `{{KILLSWITCH_KEY}}` is killed. Assumes
`configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Client;
import java.util.Map;

// construct once per callsite (cheap; binds the user)
Client client = new Client(Map.of("user_id", "u_123"));

boolean killed = client.getKillswitch("{{KILLSWITCH_KEY}}"); // killswitch name
// optional second arg reads one named per-key switch (null = whole killswitch):
// boolean off = client.getKillswitch("{{KILLSWITCH_KEY}}", "eu_region" /* switchKey */);

if (killed) {
    // disable the protected path
}
```
