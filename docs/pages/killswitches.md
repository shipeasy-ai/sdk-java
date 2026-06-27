# Kill switches

`getKillswitch` reads an operational kill switch from the cached rules blob.
A kill switch is the panic lever: `true` means "killed" (the protected path
should be disabled).

## Whole kill switch

```java
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
Unknown switches return `false`. A `null` `switchKey` reads the whole-kill-switch
killed state.

## Low-level `Engine` form

Kill switches are not user-scoped, so the engine form takes no user argument:

```java
Engine engine = Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
boolean killed       = engine.getKillswitch("panic_button");
boolean checkoutOff  = engine.getKillswitch("panic_button", "checkout");
```
