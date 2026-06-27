Bucket a user into `{{RESOURCE_NAME}}`, then track the `{{SUCCESS_EVENT}}` conversion.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY")); // once, at startup

Client client = new Client(Map.of("user_id", "u_123"));

ExperimentResult r = client.getExperiment("{{RESOURCE_NAME}}", Map.of("color", "blue")); // default params

if (r.inExperiment && "treatment".equals(r.group)) {
    // render the treatment variant
}

// later, on conversion — track() lives right on the bound Client:
client.track("{{SUCCESS_EVENT}}", Map.of("amount", 49));
```
