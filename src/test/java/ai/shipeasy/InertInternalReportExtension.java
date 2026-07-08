package ai.shipeasy;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Suite-wide guard that keeps the {@link InternalReport} self-monitoring channel
 * INERT for the entire test run.
 *
 * <p>The published SDK bakes a REAL production ingest key into
 * {@link InternalReport} so shipped builds can self-report SDK-internal bugs to
 * Shipeasy's own project. That makes the key live during OUR test run too — any
 * test that constructs a reporting-enabled engine and trips the {@link Client}
 * last-resort guard could otherwise fire a real
 * {@code POST https://api.shipeasy.ai/collect} and pollute our own errors
 * dashboard from CI.
 *
 * <p>This extension is auto-registered globally (see
 * {@code src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension}
 * + {@code junit-platform.properties} with
 * {@code junit.jupiter.extensions.autodetection.enabled=true}), so it runs before
 * EVERY test method in EVERY class — in any ordering and when a single test is run
 * in isolation. Before each test it resets the ingest key to the inert
 * {@link InternalReport#PLACEHOLDER_KEY} sentinel, so {@code keyConfigured()} gates
 * (blocks) every internal-report send by default.
 *
 * <p>{@link InternalReportTest} deliberately swaps in its own fake key + a loopback
 * {@code /collect} server AFTER this hook runs (in its own {@code @BeforeEach}), so
 * it still exercises the send path against a local recorder — never real network —
 * and resets afterwards. This guard only sets the DEFAULT state, so it does not
 * interfere with that.
 */
public final class InertInternalReportExtension implements BeforeEachCallback {

    static {
        // Declare the whole suite PRODUCTION-EQUIVALENT for egress. As of 0.15.0 the
        // SDK is quiet outside production (no network by default in dev/CI), so
        // without this every test that exercises a real network path — /collect
        // POSTs to a loopback, see()/track/exposure sends, poll fetch — would be
        // silently suppressed and fail. The `shipeasy.env` system property is the
        // highest-precedence signal in Env#isProductionEnv, so forcing it here makes
        // the production decision deterministic regardless of the CI machine's own
        // SHIPEASY_ENV/APP_ENV/ENV vars. Dedicated egress-default tests
        // (EnvDefaultsTest) clear it locally to assert the dev/prod branching.
        System.setProperty("shipeasy.env", "production");
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // Force the baked (now REAL) key back to the inert placeholder so no test
        // can reach a real send. keyConfigured() returns false for the placeholder,
        // which short-circuits InternalReport.report() before any network call.
        InternalReport.setIngestKeyForTest(InternalReport.PLACEHOLDER_KEY);
        // Re-assert the production-equivalent egress signal before every test, in
        // case a test cleared/changed it (EnvDefaultsTest does, and restores it,
        // but this is a belt-and-braces guard against ordering).
        System.setProperty("shipeasy.env", "production");
    }
}
