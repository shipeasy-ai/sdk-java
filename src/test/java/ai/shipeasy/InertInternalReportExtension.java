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
    @Override
    public void beforeEach(ExtensionContext context) {
        // Force the baked (now REAL) key back to the inert placeholder so no test
        // can reach a real send. keyConfigured() returns false for the placeholder,
        // which short-circuits InternalReport.report() before any network call.
        InternalReport.setIngestKeyForTest(InternalReport.PLACEHOLDER_KEY);
    }
}
