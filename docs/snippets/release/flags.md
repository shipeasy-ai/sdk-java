Read `{{FLAG_KEY}}` off a user-bound `Client`. Assumes `configure()` ran at
startup — see Installation.

```java
import ai.shipeasy.Client;
import java.util.Map;

// construct once per callsite (cheap; binds the user)
Client client = new Client(Map.of("user_id", "u_123"));

boolean enabled = client.getFlag("{{FLAG_KEY}}"); // gate name
// optional default overload — returned ONLY when unresolvable (engine not
// ready / flag absent), never when the flag legitimately evaluates to false:
// boolean enabled = client.getFlag("{{FLAG_KEY}}", true /* default */);
```
