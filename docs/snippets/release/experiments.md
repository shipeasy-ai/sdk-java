Bucket a user into `{{RESOURCE_NAME}}`, then track the `{{SUCCESS_EVENT}}`
conversion. Assumes `configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

// construct once per callsite (cheap; binds the user)
Client client = new Client(Map.of("user_id", "u_123"));

ExperimentResult r = client.getExperiment(
    "{{RESOURCE_NAME}}",        // experiment name
    Map.of("color", "blue"));   // defaultParams — filled in when not enrolled

if (r.inExperiment && "treatment".equals(r.group)) {
    // render the treatment variant; r.params carries the assigned parameters
}

// later, on conversion — track() lives on the bound Client (NOT the Engine);
// the unit id is derived from the bound user (user_id, else anonymous_id):
client.track(
    "{{SUCCESS_EVENT}}",        // event name
    Map.of("amount", 49));      // optional properties bag (track(name) omits it)
```
