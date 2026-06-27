The Java server SDK has no `t()` — emit the i18n loader `<script>` tag (with the
**public client key**) so the browser SDK loads the `{{PROFILE}}` profile.
Assumes `configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Engine;
import ai.shipeasy.Shipeasy;

// grab the already-configured global Engine (the SSR/script-tag helpers live on
// the Engine, not the bound Client)
Engine engine = Shipeasy.engine();

String clientKey = System.getenv("SHIPEASY_CLIENT_KEY"); // PUBLIC key (never the server key)
String head = engine.i18nScriptTag(
    clientKey,      // public client key embedded in the loader tag
    "{{PROFILE}}"); // locale profile, e.g. en:prod
// → render `head` inside the document <head>
// 3-arg overload also takes a CDN baseUrl (defaults to https://cdn.shipeasy.ai).
```
