package ai.shipeasy;

// Signed DevTools override cookie (`se_ov`) -- verify only. This is a VENDORED
// port of the canonical implementation in the shipeasy monorepo
// (packages/core/src/overrides/cookie.ts) and MUST stay byte-compatible with it:
// the derivation formulas and the body signing are shared across every SDK and
// guarded by a cross-impl fixture test (OverrideCookieTest).
//
//   Kp = base64url-decoded `overrideKey` served on the /sdk/flags payload
//   Ks = HMAC-SHA256(Kp, "se-ov-sess:v1:<nonce>:<exp>")
//   sig = HMAC-SHA256(Ks, <body as-is>)       cookie = body "." sig

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Verifier for the signed {@code se_ov} DevTools override cookie. Fails closed. */
final class OverrideCookie {

    /** Cookie name carrying the signed override payload. */
    static final String COOKIE_NAME = "se_ov";
    /** Format version -- must match core's {@code OVERRIDE_FORMAT_VERSION}. */
    static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OverrideCookie() {}

    /** A verified override set: gates (flags + kill switches), configs, experiment groups. */
    static final class Overrides {
        static final Overrides EMPTY =
            new Overrides(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());

        final Map<String, Boolean> gates;
        final Map<String, Object> configs;
        final Map<String, String> exps;

        Overrides(Map<String, Boolean> gates, Map<String, Object> configs, Map<String, String> exps) {
            this.gates = gates;
            this.configs = configs;
            this.exps = exps;
        }

        boolean isEmpty() {
            return gates.isEmpty() && configs.isEmpty() && exps.isEmpty();
        }
    }

    /**
     * Verify a bare cookie value against the project key {@code Kp} (base64url,
     * as received in the flags payload). Returns {@code null} on ANY failure
     * (never throws).
     */
    static Overrides verify(String cookieValue, String encodedProjectKey, String keyId, long nowSeconds) {
        try {
            return verifyImpl(cookieValue, encodedProjectKey, keyId, nowSeconds);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Overrides verifyImpl(String cookieValue, String encodedProjectKey, String keyId, long now)
            throws Exception {
        if (cookieValue == null || encodedProjectKey == null || keyId == null) return null;
        byte[] projectKey = b64uDecode(encodedProjectKey);
        int dot = cookieValue.indexOf('.');
        if (dot <= 0 || dot != cookieValue.lastIndexOf('.')) return null;
        String body = cookieValue.substring(0, dot);
        String sig = cookieValue.substring(dot + 1);

        Object parsed = MAPPER.readValue(new String(b64uDecode(body), StandardCharsets.UTF_8), Object.class);
        if (!(parsed instanceof Map)) return null;
        Map<String, Object> p = (Map<String, Object>) parsed;
        Object v = p.get("v");
        if (!(v instanceof Number) || ((Number) v).doubleValue() != FORMAT_VERSION) return null;
        if (!(p.get("pid") instanceof String)) return null;
        if (!(p.get("keyId") instanceof String)) return null;
        if (!(p.get("nonce") instanceof String)) return null;
        if (!(p.get("exp") instanceof Number)) return null;
        Object ov = p.get("ov");
        if (ov == null || Boolean.FALSE.equals(ov)) return null; // JS falsy check (`!payload.ov`)
        if (!keyId.equals(p.get("keyId"))) return null;

        Number expN = (Number) p.get("exp");
        if (!(expN.doubleValue() > now)) return null;

        // exp is rendered as a plain integer in the signed message.
        String expStr = (expN instanceof Double || expN instanceof Float) && expN.doubleValue() == Math.rint(expN.doubleValue())
            ? Long.toString(expN.longValue())
            : expN.toString();
        byte[] sessionKey = hmac(projectKey, "se-ov-sess:v1:" + p.get("nonce") + ":" + expStr);
        byte[] expected = hmac(sessionKey, body);
        if (!MessageDigest.isEqual(expected, b64uDecode(sig))) return null;

        Map<String, Object> ovMap = ov instanceof Map ? (Map<String, Object>) ov : Collections.emptyMap();
        Map<String, Boolean> gates = new HashMap<>();
        Object g = ovMap.get("gates");
        if (g instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) g).entrySet()) {
                if (e.getValue() instanceof Boolean) gates.put(e.getKey(), (Boolean) e.getValue());
            }
        }
        Map<String, Object> configs = new HashMap<>();
        Object c = ovMap.get("configs");
        if (c instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) c).entrySet()) {
                if (e.getValue() != null) configs.put(e.getKey(), e.getValue());
            }
        }
        Map<String, String> exps = new HashMap<>();
        Object x = ovMap.get("exps");
        if (x instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) x).entrySet()) {
                if (e.getValue() instanceof String) exps.put(e.getKey(), (String) e.getValue());
            }
        }
        return new Overrides(gates, configs, exps);
    }

    /**
     * Extract the raw {@code se_ov} value from either a bare cookie value or a
     * full {@code Cookie:} header ({@code a=1; se_ov=...; b=2}), URL-decoding
     * the value. Returns {@code null} if absent.
     */
    static String extract(String input) {
        if (input == null || input.isEmpty()) return null;
        if (!input.contains(";") && !input.contains("se_ov=")) {
            return input.contains(".") ? input : null;
        }
        for (String part : input.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if (part.substring(0, eq).trim().equals(COOKIE_NAME)) {
                try {
                    // URLDecoder maps '+' to space; cookie values are percent-encoded
                    // only (decodeURIComponent semantics), so protect '+'.
                    return URLDecoder.decode(part.substring(eq + 1).trim().replace("+", "%2B"), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static byte[] hmac(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    /** base64url, padding optional. */
    private static byte[] b64uDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
