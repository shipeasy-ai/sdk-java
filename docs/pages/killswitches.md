# Kill switches

`getKillswitch` reads an operational kill switch from the cached rules blob.
A kill switch is the panic lever: `true` means "killed" (the protected path
should be disabled).

## Whole kill switch

```java
import ai.shipeasy.Client;
import java.util.Map;

Client c = new Client(Map.of("user_id", "u_123"));
boolean killed = c.getKillswitch("panic_button");
if (killed) {
    // disable the protected path
}
```

`getKillswitch(name)` returns `true` when the whole kill switch is killed.
Unknown kill switches return `false`.

## Named per-key switches

A kill switch can carry named per-key switches. Pass the switch key to read one:

```java
boolean checkoutOff = c.getKillswitch("panic_button", "checkout");
```

With `switchKey`, the call returns `true` when that specific named switch is on.
An **unconfigured** switch key falls back to the kill switch's top-level value,
so a switch you haven't explicitly set tracks whether the whole kill switch is
killed. A `null` `switchKey` reads the whole-kill-switch killed state directly.

Kill switches are not user-scoped — the bound user is irrelevant to the result,
but reading them off the same `Client` keeps everything one-stop.
