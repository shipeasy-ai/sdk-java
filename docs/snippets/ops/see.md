Report a caught, handled error (or a non-exception "violation") to Shipeasy with
`see()` — fire-and-forget, never re-throws. The static form reports against the
engine from `Shipeasy.configure(...)`. Assumes `Shipeasy.configure(...)` ran at
startup — see Installation.

### Report a handled exception

```java
import static ai.shipeasy.See.see;
import java.util.Map;

try {
    charge(order);
} catch (Exception e) {
    // .causesThe(subject)  what the error affects (e.g. "checkout")
    // .to(outcome)         the terminal — what you do about it; builds + fires once
    see(e).causesThe("checkout").to("use the backup processor");
    fallbackCharge(order);
}
```

### Attach context with `.extras(...)`

```java
import static ai.shipeasy.See.see;

try {
    charge(order);
} catch (Exception e) {
    // .extras(map)         structured fields attached to the report
    see(e).causesThe("checkout").extras(Map.of("order_id", oid)).to("use cached prices");
}
```

### Report a non-exception violation

```java
import static ai.shipeasy.See.violation;

// a bad state that isn't an exception — the name is a STABLE fingerprint; put
// variable data in .extras, never the name. .to() is the terminal.
violation("missing_invoice").causesThe("billing").to("skip the dunning email");
```

### Mark an expected exception — report NOTHING

```java
import static ai.shipeasy.See.controlFlowException;

try {
    parse(token);
} catch (NoSuchElementException e) {
    // transmits nothing; .because(...) is local-debug only
    controlFlowException(e).because("end of stream is expected");
}
```
