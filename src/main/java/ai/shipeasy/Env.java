package ai.shipeasy;

/**
 * Native runtime-environment detection.
 *
 * <p>Used ONLY to pick the DEFAULT for outbound egress when the caller does not
 * set it explicitly:
 * <ul>
 *   <li>is the SDK allowed to make network requests at all
 *       ({@code isNetworkEnabled})?</li>
 *   <li>is per-evaluation usage telemetry / tracking allowed
 *       ({@code isTrackingEnabled} / the inverse {@code disableTelemetry})?</li>
 * </ul>
 *
 * <p>Both default to ON in production and OFF everywhere else, so a local/dev/CI
 * run of an app that embeds the SDK never phones home unless it explicitly opts
 * in.
 *
 * <p>Precedence for the production decision:
 * <ol>
 *   <li>A native runtime signal, checked in order: the {@code shipeasy.env}
 *       <em>system property</em>, then the env vars {@code SHIPEASY_ENV},
 *       {@code APP_ENV}, {@code ENV}. A value of {@code "production"}/{@code "prod"}
 *       (case-insensitive) ⇒ prod; any other present value
 *       ({@code "development"}/{@code "staging"}/{@code "test"}/…) ⇒ not prod.</li>
 *   <li>When no native signal is set (common on serverless / mobile), fall back
 *       to the SDK's own configured {@code env} option, which the caller sets and
 *       which itself defaults to {@code "prod"}. This keeps a real production
 *       deploy "on" by default while an {@code env("dev")} config stays quiet.</li>
 * </ol>
 *
 * <p>The {@code env} option is always present (it defaults to {@code "prod"}), so
 * the production decision is always inferrable — the SDK never has to make the
 * fields required.
 */
final class Env {

    private Env() {}

    /**
     * True when the host runtime looks like a production deployment.
     *
     * @param configuredEnv the SDK's own {@code env} option (dev/staging/prod);
     *                       consulted only when no native runtime signal is set.
     */
    static boolean isProductionEnv(String configuredEnv) {
        String native_ = readNativeEnv();
        if (native_ != null) {
            return native_.equals("production") || native_.equals("prod");
        }
        String env = configuredEnv == null ? "prod" : configuredEnv.trim().toLowerCase();
        return env.equals("prod") || env.equals("production");
    }

    /**
     * Read the native runtime environment string, lowercased, or {@code null}
     * when no signal is present. System property {@code shipeasy.env} wins over
     * the environment variables so a JVM flag can force the decision (used by the
     * test suite to declare itself production-equivalent for egress).
     */
    private static String readNativeEnv() {
        String raw = firstNonBlank(
            System.getProperty("shipeasy.env"),
            System.getenv("SHIPEASY_ENV"),
            System.getenv("APP_ENV"),
            System.getenv("ENV"));
        if (raw == null) return null;
        String v = raw.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }
}
