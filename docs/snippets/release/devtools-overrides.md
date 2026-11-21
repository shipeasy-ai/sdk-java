Honor signed DevTools overrides (`se_ov` cookie) on server reads. Assumes
`Shipeasy.configure()` ran at startup — see Installation. Servlet apps get this
from `AnonIdFilter`; other stacks wrap the request.

```java
import ai.shipeasy.Client;
import ai.shipeasy.Shipeasy;
import java.util.Map;

String cookieHeader = request.getHeader("Cookie"); // raw Cookie header (or the bare se_ov value)

// cookieHeader: verified once at scope entry, fail-closed; body: Runnable or Supplier
Shipeasy.withRequestOverrides(cookieHeader, () -> {
    // construct once per callsite (cheap; binds the user)
    Client client = new Client(Map.of("user_id", "u_123"));
    boolean enabled = client.getFlag("{{FLAG_KEY}}"); // honors a signed override
});
```
