package ai.shipeasy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Precedence + case handling of {@link Env#isProductionEnv}. Only the
 * {@code shipeasy.env} system property is exercised as the native signal here
 * (env vars can't be set portably from a JUnit test); {@link EnvDefaultsTest}
 * covers the configured-env fallback and the egress wiring. The suite normally
 * pins {@code shipeasy.env=production} (see {@link InertInternalReportExtension});
 * these tests save/restore it around each case.
 */
class EnvTest {

    private String saved;

    @BeforeEach
    void save() {
        saved = System.getProperty("shipeasy.env");
        System.clearProperty("shipeasy.env");
    }

    @AfterEach
    void restore() {
        if (saved != null) System.setProperty("shipeasy.env", saved);
        else System.clearProperty("shipeasy.env");
    }

    @Test
    void systemPropertyProductionIsProd() {
        System.setProperty("shipeasy.env", "production");
        assertTrue(Env.isProductionEnv("dev")); // native signal wins over configured env
        System.setProperty("shipeasy.env", "prod");
        assertTrue(Env.isProductionEnv("dev"));
    }

    @Test
    void systemPropertyIsCaseInsensitiveAndTrimmed() {
        System.setProperty("shipeasy.env", "  PRODUCTION ");
        assertTrue(Env.isProductionEnv(null));
        System.setProperty("shipeasy.env", "Prod");
        assertTrue(Env.isProductionEnv(null));
    }

    @Test
    void anyOtherPresentNativeValueIsNotProd() {
        for (String v : new String[] {"development", "dev", "staging", "test", "ci", "qa"}) {
            System.setProperty("shipeasy.env", v);
            assertFalse(Env.isProductionEnv("prod"),
                v + " must read as non-production even when configured env is prod");
        }
    }

    @Test
    void fallsBackToConfiguredEnvWhenNoNativeSignal() {
        // no shipeasy.env set (cleared in @BeforeEach); rely on the configured env.
        assertTrue(Env.isProductionEnv("prod"));
        assertTrue(Env.isProductionEnv("production"));
        assertTrue(Env.isProductionEnv(null), "null configured env defaults to prod");
        assertTrue(Env.isProductionEnv("PROD"));
        assertFalse(Env.isProductionEnv("dev"));
        assertFalse(Env.isProductionEnv("staging"));
    }

    @Test
    void blankNativeSignalFallsThroughToConfiguredEnv() {
        System.setProperty("shipeasy.env", "   ");
        assertFalse(Env.isProductionEnv("dev"), "blank native signal is treated as unset");
        assertTrue(Env.isProductionEnv("prod"));
    }
}
