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
 */
public final class AnonIdFilter implements Filter {

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
        try {
            chain.doFilter(request, response);
        } finally {
            AnonId.clear();
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
