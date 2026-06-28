# Dynamic configs

`getConfig` fetches a typed JSON value (a "dynamic config" / remote config) by
name from the cached rules blob. Configs are **not user-scoped** — they resolve
to a single value per key.

## Reading a config

```java
import ai.shipeasy.Client;
import java.util.Map;

Client c = new Client(Map.of("user_id", "u_123"));
Object copy = c.getConfig("billing_copy");
```

The returned `Object` is the parsed JSON value — a `Map`, `List`, `String`,
`Number`, `Boolean`, or `null` depending on what was published. Cast as needed:

```java
@SuppressWarnings("unchecked")
Map<String, Object> copy = (Map<String, Object>) c.getConfig("billing_copy");
String title = (String) copy.get("title");
```

## Defaults

The default-value overload returns the fallback when the config key is absent
(no override and not present in the blob). The one-argument overload returns
`null` when the key is absent or the engine isn't initialized:

```java
Object copy = c.getConfig("billing_copy", Map.of("title", "Default"));
```
