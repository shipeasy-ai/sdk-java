Track a metric/conversion event from the bound `Client`. Metrics in the dashboard
are computed from these events. Assumes `Shipeasy.configure(...)` ran at startup —
see Installation.

### Track an event

```java
import ai.shipeasy.Client;
import java.util.Map;

Client client = new Client(Map.of("user_id", "u_123")); // construct once per callsite

// track(eventName, props)
//   eventName — the event your metric is built on (required)
//   props     — optional payload; numeric/string fields you can sum/filter on in
//               a metric (private attributes are stripped before egress)
client.track("{{EVENT_NAME}}", Map.of("amount", 49, "currency", "usd"));
```

Fire-and-forget (never blocks your response) and a no-op under
`Shipeasy.configureForTesting` / `configureForOffline`. The unit is the bound user
(`user_id`, else `anonymous_id`); with no unit the call is a no-op.

### Track without properties

```java
Client client = new Client(Map.of("user_id", "u_123")); // construct once per callsite

client.track("{{EVENT_NAME}}", Map.of()); // props are optional (pass an empty map)
```
