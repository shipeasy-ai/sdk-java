The Java server SDK has no `t()` — emit the i18n loader `<script>` tag (with the
**public client key**) so the browser SDK loads the `{{PROFILE}}` profile.
Assumes `configure()` ran at startup — see Installation.

```java
import ai.shipeasy.Shipeasy;

// Package-level static — it resolves the engine configure() built. EVERY
// argument is optional: the PUBLIC client key, profile and CDN origin come from
// configure(options(serverKey).clientKey(...).profile("{{PROFILE}}").projectId(...)).
String head = Shipeasy.i18nScriptTag();
// → render `head` inside the document <head>
// Overloads take (clientKey, profile) and (clientKey, profile, baseUrl) to
// override a single tag — the client key is PUBLIC, never the server key.

// Devtools overlay (Shift+Alt+S or ?se=1) — opens only for a signed-in
// Shipeasy session, so gating it on staff/env is optional.
String devtools = Shipeasy.devtoolsScriptTag();
```
