# Installation

## Requirements

- **Java 17+** (the SDK uses `java.net.http.HttpClient` and modern language features).
- A Shipeasy **server key** (`SHIPEASY_SERVER_KEY`).

## Maven

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy</artifactId>
  <version>0.8.0</version>
</dependency>
```

## Gradle

```kotlin
implementation("ai.shipeasy:shipeasy:0.8.0")
```

## Optional, `provided`-scope dependencies

These are **not** pulled into your deployment unless you already supply them:

- `jakarta.servlet-api` — only needed for the [`AnonIdFilter`](advanced.md)
  servlet filter that mints the shared `__se_anon_id` cookie. Your container
  already supplies it.
- `dev.openfeature:sdk` — only needed for the [OpenFeature provider](openfeature.md).

## Imports

```java
import ai.shipeasy.Shipeasy;          // configure() entry point
import ai.shipeasy.Client;            // user-bound handle
import ai.shipeasy.Engine;            // heavyweight handle (advanced)
import ai.shipeasy.ExperimentResult;  // experiment return type
import ai.shipeasy.FlagDetail;        // value + reason
```
