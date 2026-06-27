Rendering a translated label is **client-side only** — the Java server SDK does
not expose `t()`. Once the loader tag (see setup) has hydrated the browser, the
client SDK renders keys:

```js
// browser, @shipeasy/sdk client
import { t } from "@shipeasy/sdk/client";
t("checkout.cta"); // → translated string for the active {{PROFILE}} profile
```
