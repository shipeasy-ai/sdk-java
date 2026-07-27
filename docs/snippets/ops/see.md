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
    // .to(outcome, map)    PREFERRED: fold the extras into the terminal. The
    //                      consequence sentence stays whole and there is no
    //                      ordering to remember.
    see(e).causesThe("checkout").to("use cached prices", Map.of("order_id", oid));

    // .to returns void, so extras CANNOT trail it — this does not compile:
    // see(e).causesThe("checkout").to("use cached prices").extras(Map.of("order_id", oid));

    // NEVER: extras wedged between the subject and the outcome — it splits the
    // consequence sentence in half and is hard to read.
    // see(e).causesThe("checkout").extras(Map.of("order_id", oid)).to("use cached prices");
}
```

### Attach context from anywhere with `See.addExtras(...)`

Prefer this over the inline form whenever the context already exists *above*
the catch — it keeps the catch site a clean one-liner.

```java
import static ai.shipeasy.See.see;
import ai.shipeasy.See;

// Buffer extras earlier in the request — from any layer, not just the catch.
// Every see() report that fires LATER on the same thread carries them, so you
// don't have to thread context down into the catch site. Thread-local, so
// concurrent requests never mix; AnonIdFilter clears it per request (register it
// like any servlet filter — outside a servlet request call See.clearExtras()).
See.addExtras(Map.of("order_id", order.id(), "tenant", tenant.slug()));

// ...deep in a service, later in the same request...
try {
    charge(order);
} catch (Exception e) {
    // report carries order_id + tenant automatically; a chained .extras / inline
    // .to extra of the same key wins over the ambient one.
    see(e).causesThe("checkout").to("use cached prices");
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
