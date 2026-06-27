Bucket a user into `{{RESOURCE_NAME}}`, then track the `{{SUCCESS_EVENT}}` conversion.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Engine;
import ai.shipeasy.Client;
import ai.shipeasy.ExperimentResult;
import java.util.Map;

Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

ExperimentResult r = new Client(Map.of("user_id", "u_123"))
    .getExperiment("{{RESOURCE_NAME}}", Map.of("color", "blue")); // default params

if (r.inExperiment && "treatment".equals(r.group)) {
    // render the treatment variant
}

// later, on conversion — track() lives on the Engine:
engine.track("u_123", "{{SUCCESS_EVENT}}", Map.of("amount", 49));
```
