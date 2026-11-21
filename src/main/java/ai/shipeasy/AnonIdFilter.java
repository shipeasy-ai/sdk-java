package ai.shipeasy;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Servlet {@link Filter} that mints the shared {@code __se_anon_id} bucketing
 * cookie. For any request without a valid {@code __se_anon_id} cookie it mints a
 * UUIDv4, exposes it for the request, and {@code Set-Cookie}s it on the
 * response. Once installed, gate/experiment evaluations with no explicit
 * {@code user_id}/{@code anonymous_id} automatically bucket on the cookie id —
 * anonymous visitors get stable, SSR/browser-consistent bucketing with no
 * per-call wiring.
 *
 * <p>Register it like any filter. With Spring Boot:
 *
 * <pre>{@code
 * @Bean
 * FilterRegistrationBean<AnonIdFilter> shipeasyAnonId() {
 *     var reg = new FilterRegistrationBean<>(new AnonIdFilter());
 *     reg.addUrlPatterns("/*");
 *     return reg;
 * }
 * }</pre>
 *
 * <p>The {@code jakarta.servlet-api} dependency is {@code provided} scope — your
 * servlet container already supplies it at runtime, so this adds nothing to your
 * deployment. Non-servlet stacks can use {@link AnonId} directly instead.
 *
 * <p>It also verifies the signed DevTools override cookie ({@code se_ov}) once
 * per request and scopes the result to the request thread, so {@link Client}
 * reads honor overrides a signed-in DevTools overlay set (fail-closed; needs the
 * project's override key served by Shipeasy). Equivalent to wrapping the request
 * in {@link Shipeasy#withRequestOverrides(String, Runnable)}.
 *
 * <p>It also reads the browser SDK's {@code X-SE-Correlation} request header
 * into the request-local {@code see()} context ({@link SeeExtras}), so every
 * {@code see()} fired while serving the request carries that
 * {@code correlation_id} — the token the platform joins on to link the
 * browser's error to the server exception behind it.
 */
public final class AnonIdFilter implements Filter {

    /** Header carrying the override cookies for APIs on a sibling subdomain (cookie-header syntax). */
    public static final String OVERRIDES_HEADER = "X-Shipeasy-Overrides";

    /** Request header the browser SDK sends on every same-origin request. */
    public static final String CORRELATION_HEADER = "X-SE-Correlation";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String id = readValidCookie(req);
        if (id == null) {
            id = AnonId.mint();
            res.addHeader("Set-Cookie", AnonId.buildSetCookie(id, isSecure(req)));
        }
        AnonId.setCurrent(id);
        SeeExtras.setCorrelationId(req.getHeader(CORRELATION_HEADER));
        // Signed DevTools overrides (se_ov cookie): verified once here, scoped to this request.
        OverrideCookie.Overrides prevOverrides = RequestOverrides.enter(
            req.getHeader("Cookie"), req.getHeader(OVERRIDES_HEADER));
        try {
            chain.doFilter(request, response);
        } finally {
            // Don't leak the id — or any ambient see() extras / correlation
            // token — onto the next request handled by this pooled thread.
            AnonId.clear();
            SeeExtras.clear();
            RequestOverrides.restore(prevOverrides);
        }
    }

    private static String readValidCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (AnonId.COOKIE.equals(c.getName()) && AnonId.isValid(c.getValue())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private static boolean isSecure(HttpServletRequest req) {
        if (req.isSecure()) return true;
        String proto = req.getHeader("X-Forwarded-Proto");
        if (proto == null) return false;
        int comma = proto.indexOf(',');
        if (comma >= 0) proto = proto.substring(0, comma);
        return "https".equalsIgnoreCase(proto.trim());
    }
}
