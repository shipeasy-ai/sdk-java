package ai.shipeasy;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Anonymous bucketing identity — the cross-SDK {@code __se_anon_id} cookie.
 *
 * <p>Gates and experiments bucket a unit with {@code murmur3(salt:unit)}. For a
 * logged-out visitor the unit is a stable anonymous id carried in a single
 * first-party cookie that EVERY Shipeasy SDK (server + browser) reads and
 * writes, so a server render and the browser bucket a fractional rollout
 * identically. The cookie name and format are frozen across every language; see
 * {@code experiment-platform/18-identity-bucketing.md}.
 *
 * <p>These primitives carry no servlet dependency. {@link AnonIdFilter} wires
 * them into a servlet container; non-servlet stacks (Ktor, http4k, Javalin) can
 * call them directly off whatever request/response types they use.
 */
public final class AnonId {
    /** The first-party cookie carrying the stable anonymous bucketing unit. */
    public static final String COOKIE = "__se_anon_id";
    /** One year, in seconds. */
    public static final int MAX_AGE = 31_536_000;

    // The cookie value is client-controllable and feeds bucketing, so a tampered
    // value is treated as absent and a fresh id is minted. UUIDs satisfy this.
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AnonId() {}

    /** A fresh opaque bucketing id (UUIDv4). */
    public static String mint() {
        return UUID.randomUUID().toString();
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /**
     * The anon id {@link AnonIdFilter} resolved for the current request, or
     * {@code null} when no filter ran. {@link Engine#getFlag}/{@code assignUniverse}
     * fall back to this as the default {@code anonymous_id}.
     */
    public static String current() {
        return CURRENT.get();
    }

    public static void setCurrent(String id) {
        CURRENT.set(id);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Build a {@code Set-Cookie} header value per the cross-SDK contract. Non
     * -HttpOnly by design — the browser SDK reads it via {@code document.cookie}
     * to bucket identically to the server.
     */
    public static String buildSetCookie(String id, boolean secure) {
        StringBuilder sb = new StringBuilder()
                .append(COOKIE).append('=').append(id)
                .append("; Path=/; Max-Age=").append(MAX_AGE)
                .append("; SameSite=Lax");
        if (secure) sb.append("; Secure");
        return sb.toString();
    }
}
