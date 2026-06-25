package ai.shipeasy.openfeature;

import ai.shipeasy.Engine;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ShipeasyProvider} through the real OpenFeature SDK
 * ({@code OpenFeatureAPI.setProviderAndWait} + a {@code Engine}), seeding flags
 * and configs from an in-memory snapshot so no network I/O happens.
 */
class ShipeasyProviderTest {

    @AfterEach
    void tearDown() {
        OpenFeatureAPI.getInstance().shutdown();
    }

    /** A gate with the given enabled/rollout, plus the configs used across tests. */
    private static Engine seededClient() {
        Map<String, Object> gates = Map.of(
            "new_checkout", Map.of("enabled", true, "rolloutPct", 10000, "salt", "s"),
            "off_gate", Map.of("enabled", false, "rolloutPct", 10000, "salt", "s"),
            "zero_gate", Map.of("enabled", true, "rolloutPct", 0, "salt", "s"));
        Map<String, Object> configs = Map.of(
            "welcome_msg", Map.of("value", "hello"),
            "retry_count", Map.of("value", 3),
            "ratio", Map.of("value", 1.5),
            "theme", Map.of("value", Map.of("color", "blue")),
            // welcome_msg requested as a number -> type mismatch
            "not_a_number", Map.of("value", "abc"));
        Map<String, Object> flags = Map.of("gates", gates, "configs", configs);
        return Engine.fromSnapshot(flags, null);
    }

    private static dev.openfeature.sdk.Client setProvider(Engine sdk) {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait(new ShipeasyProvider(sdk));
        return api.getClient();
    }

    @Test
    void metadataName() {
        assertEquals("shipeasy", new ShipeasyProvider(Engine.forTesting()).getMetadata().getName());
    }

    @Test
    void resolveBooleanTargetingMatch() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<Boolean> d =
            of.getBooleanDetails("new_checkout", false, new MutableContext("u_1"));
        assertTrue(d.getValue());
        assertEquals(Reason.TARGETING_MATCH.toString(), d.getReason());
        assertNull(d.getErrorCode());
    }

    @Test
    void resolveBooleanDefaultReason() {
        var of = setProvider(seededClient());
        // gate on but 0% rollout -> evaluates false -> DEFAULT
        FlagEvaluationDetails<Boolean> d =
            of.getBooleanDetails("zero_gate", true, new MutableContext("u_1"));
        assertFalse(d.getValue());
        assertEquals(Reason.DEFAULT.toString(), d.getReason());
        assertNull(d.getErrorCode());
    }

    @Test
    void resolveBooleanDisabled() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<Boolean> d =
            of.getBooleanDetails("off_gate", true, new MutableContext("u_1"));
        assertFalse(d.getValue());
        assertEquals(Reason.DISABLED.toString(), d.getReason());
    }

    @Test
    void resolveBooleanFlagNotFound() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<Boolean> d =
            of.getBooleanDetails("missing", true, new MutableContext("u_1"));
        // error -> default value returned
        assertTrue(d.getValue());
        assertEquals(ErrorCode.FLAG_NOT_FOUND, d.getErrorCode());
        assertEquals(Reason.ERROR.toString(), d.getReason());
    }

    @Test
    void resolveString() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<String> d =
            of.getStringDetails("welcome_msg", "fallback", new MutableContext("u_1"));
        assertEquals("hello", d.getValue());
        assertEquals(Reason.TARGETING_MATCH.toString(), d.getReason());
    }

    @Test
    void resolveStringAbsentDefault() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<String> d =
            of.getStringDetails("absent", "fallback", new MutableContext("u_1"));
        assertEquals("fallback", d.getValue());
        assertEquals(Reason.DEFAULT.toString(), d.getReason());
    }

    @Test
    void resolveInteger() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<Integer> d =
            of.getIntegerDetails("retry_count", 1, new MutableContext("u_1"));
        assertEquals(3, d.getValue());
        assertEquals(Reason.TARGETING_MATCH.toString(), d.getReason());
    }

    @Test
    void resolveDouble() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<Double> d =
            of.getDoubleDetails("ratio", 0.0, new MutableContext("u_1"));
        assertEquals(1.5, d.getValue());
        assertEquals(Reason.TARGETING_MATCH.toString(), d.getReason());
    }

    @Test
    void resolveObject() {
        var of = setProvider(seededClient());
        FlagEvaluationDetails<Value> d =
            of.getObjectDetails("theme", new Value("default"), new MutableContext("u_1"));
        assertEquals(Reason.TARGETING_MATCH.toString(), d.getReason());
        assertTrue(d.getValue().isStructure());
        assertEquals("blue", d.getValue().asStructure().getValue("color").asString());
    }

    @Test
    void typeMismatch() {
        var of = setProvider(seededClient());
        // not_a_number holds "abc"; ask for an integer -> TYPE_MISMATCH -> default
        FlagEvaluationDetails<Integer> d =
            of.getIntegerDetails("not_a_number", 7, new MutableContext("u_1"));
        assertEquals(7, d.getValue());
        assertEquals(ErrorCode.TYPE_MISMATCH, d.getErrorCode());
        assertEquals(Reason.ERROR.toString(), d.getReason());
    }

    @Test
    void targetingKeyBecomesUserId() {
        // A 100%-rollout gate with a user-attribute rule confirms targetingKey
        // and other attrs reach the SDK.
        Map<String, Object> gate = Map.of(
            "enabled", true, "rolloutPct", 10000, "salt", "s",
            "rules", List.of(Map.of("attr", "plan", "op", "eq", "value", "pro")));
        Engine sdk = Engine.fromSnapshot(Map.of("gates", Map.of("g", gate)), null);
        var of = setProvider(sdk);
        assertTrue(of.getBooleanValue("g", false,
            new MutableContext("u_1").add("plan", "pro")));
        assertFalse(of.getBooleanValue("g", false,
            new MutableContext("u_1").add("plan", "free")));
    }
}
