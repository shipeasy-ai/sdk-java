The Java server SDK has no `t()` — emit the i18n loader `<script>` tag (with the
**public client key**) so the browser SDK loads the `{{PROFILE}}` profile.

```java
import ai.shipeasy.Shipeasy;
import ai.shipeasy.Engine;

Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));

String clientKey = System.getenv("SHIPEASY_CLIENT_KEY"); // PUBLIC key
String head = engine.i18nScriptTag(clientKey, "{{PROFILE}}");
// → render `head` inside the document <head>
```
