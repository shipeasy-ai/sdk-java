package ai.shipeasy.openfeature;

import ai.shipeasy.Engine;
import ai.shipeasy.FlagDetail;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenFeature <strong>server</strong> provider for Shipeasy.
 *
 * <p>Lets apps standardized on the CNCF OpenFeature API plug Shipeasy in as the
 * backing provider:
 *
 * <pre>{@code
 * import dev.openfeature.sdk.OpenFeatureAPI;
 * import ai.shipeasy.Engine;
 * import ai.shipeasy.openfeature.ShipeasyProvider;
 *
 * Engine engine = new Engine(System.getenv("SHIPEASY_SERVER_KEY"));
 * OpenFeatureAPI.getInstance().setProviderAndWait(new ShipeasyProvider(engine));
 *
 * var of = OpenFeatureAPI.getInstance().getClient();
 * boolean on = of.getBooleanValue("new_checkout", false,
 *     new MutableContext("u1"));
 * }</pre>
 *
 * <p>Pure adapter over {@link Engine} — no change to evaluation. Booleans route
 * to gate evaluation ({@link Engine#getFlagDetail}); strings, numbers and
 * objects route to dynamic configs ({@link Engine#getConfig}).
 *
 * <p>{@code dev.openfeature:sdk} is a {@code provided}-scope dependency: the
 * consuming app supplies it. Non-OpenFeature users never load this class.
 */
public final class ShipeasyProvider implements FeatureProvider {

    private static final Metadata METADATA = () -> "shipeasy";

    private final Engine engine;

    /**
     * Wrap an explicit {@link Engine} (advanced/back-compat use).
     */
    public ShipeasyProvider(Engine engine) {
        this.engine = engine;
    }

    /**
     * Global form: resolve the engine built by {@code Shipeasy.configure(...)}, so
     * OpenFeature is wired without naming the {@link Engine}:
     *
     * <pre>{@code
     * Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));
     * OpenFeatureAPI.getInstance().setProviderAndWait(new ShipeasyProvider());
     * }</pre>
     *
     * @throws IllegalStateException if {@code Shipeasy.configure(...)} has not run.
     */
    public ShipeasyProvider() {
        Engine e = ai.shipeasy.Shipeasy.engine();
        if (e == null) {
            throw new IllegalStateException(
                "new ShipeasyProvider() resolves the configured global engine — "
                    + "call Shipeasy.configure(...) first, or pass an Engine explicitly.");
        }
        this.engine = e;
    }

    @Override
    public Metadata getMetadata() {
        return METADATA;
    }

    /** Ensure the rules blob is loaded once. OpenFeature fires {@code Ready} on return. */
    @Override
    public void initialize(EvaluationContext context) throws Exception {
        engine.initOnce();
    }

    @Override
    public void shutdown() {
        engine.close();
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
            String key, Boolean defaultValue, EvaluationContext ctx) {
        FlagDetail detail = engine.getFlagDetail(key, toUser(ctx));
        Mapped m = mapReason(detail.reason());
        if (m.errorCode != null) {
            return ProviderEvaluation.<Boolean>builder()
                    .value(defaultValue)
                    .reason(m.reason)
                    .errorCode(m.errorCode)
                    .build();
        }
        return ProviderEvaluation.<Boolean>builder()
                .value(detail.value())
                .reason(m.reason)
                .build();
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
            String key, String defaultValue, EvaluationContext ctx) {
        return resolveConfig(key, defaultValue, ConfigType.STRING);
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
            String key, Integer defaultValue, EvaluationContext ctx) {
        return resolveConfig(key, defaultValue, ConfigType.INTEGER);
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
            String key, Double defaultValue, EvaluationContext ctx) {
        return resolveConfig(key, defaultValue, ConfigType.DOUBLE);
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
            String key, Value defaultValue, EvaluationContext ctx) {
        return resolveConfig(key, defaultValue, ConfigType.OBJECT);
    }

    // ---- config resolution (string/number/object) ----

    private enum ConfigType { STRING, INTEGER, DOUBLE, OBJECT }

    /**
     * Resolve a string/number/object flag from a Shipeasy config value:
     * absent → default with reason {@code DEFAULT}; wrong type → default with
     * errorCode {@code TYPE_MISMATCH}; present + right type → value with reason
     * {@code TARGETING_MATCH}.
     */
    @SuppressWarnings("unchecked")
    private <T> ProviderEvaluation<T> resolveConfig(String key, T defaultValue, ConfigType type) {
        Object raw = engine.getConfig(key);
        if (raw == null) {
            return ProviderEvaluation.<T>builder()
                    .value(defaultValue)
                    .reason(Reason.DEFAULT.toString())
                    .build();
        }
        T coerced = (T) coerce(raw, type);
        if (coerced == null) {
            return ProviderEvaluation.<T>builder()
                    .value(defaultValue)
                    .reason(Reason.ERROR.toString())
                    .errorCode(ErrorCode.TYPE_MISMATCH)
                    .errorMessage("config value " + raw + " is not of type " + type)
                    .build();
        }
        return ProviderEvaluation.<T>builder()
                .value(coerced)
                .reason(Reason.TARGETING_MATCH.toString())
                .build();
    }

    /** Returns the coerced value, or {@code null} on a type mismatch. */
    private Object coerce(Object raw, ConfigType type) {
        switch (type) {
            case STRING:
                return raw instanceof String ? raw : null;
            case INTEGER:
                return raw instanceof Number ? ((Number) raw).intValue() : null;
            case DOUBLE:
                return raw instanceof Number ? ((Number) raw).doubleValue() : null;
            case OBJECT:
                try {
                    return Value.objectToValue(raw);
                } catch (Exception e) {
                    return null;
                }
            default:
                return null;
        }
    }

    // ---- reason mapping ----

    private static final class Mapped {
        final String reason;
        final ErrorCode errorCode;
        Mapped(String reason, ErrorCode errorCode) {
            this.reason = reason;
            this.errorCode = errorCode;
        }
    }

    /**
     * Shipeasy {@link FlagDetail} reason → OpenFeature reason (+ optional error):
     * <ul>
     *   <li>{@code RULE_MATCH}       → {@code TARGETING_MATCH}</li>
     *   <li>{@code DEFAULT}          → {@code DEFAULT}</li>
     *   <li>{@code OFF}              → {@code DISABLED}</li>
     *   <li>{@code OVERRIDE}         → {@code STATIC}</li>
     *   <li>{@code FLAG_NOT_FOUND}   → {@code ERROR} + errorCode FLAG_NOT_FOUND</li>
     *   <li>{@code CLIENT_NOT_READY} → {@code ERROR} + errorCode PROVIDER_NOT_READY</li>
     * </ul>
     */
    private static Mapped mapReason(String reason) {
        if (FlagDetail.RULE_MATCH.equals(reason)) return new Mapped(Reason.TARGETING_MATCH.toString(), null);
        if (FlagDetail.DEFAULT.equals(reason)) return new Mapped(Reason.DEFAULT.toString(), null);
        if (FlagDetail.OFF.equals(reason)) return new Mapped(Reason.DISABLED.toString(), null);
        if (FlagDetail.OVERRIDE.equals(reason)) return new Mapped(Reason.STATIC.toString(), null);
        if (FlagDetail.FLAG_NOT_FOUND.equals(reason)) {
            return new Mapped(Reason.ERROR.toString(), ErrorCode.FLAG_NOT_FOUND);
        }
        if (FlagDetail.CLIENT_NOT_READY.equals(reason)) {
            return new Mapped(Reason.ERROR.toString(), ErrorCode.PROVIDER_NOT_READY);
        }
        return new Mapped(Reason.UNKNOWN.toString(), null);
    }

    // ---- context → user ----

    /**
     * Convert an OpenFeature {@link EvaluationContext} to a Shipeasy user map.
     * {@code targetingKey} becomes {@code user_id}; every other attribute is
     * carried through verbatim for targeting.
     */
    private static Map<String, Object> toUser(EvaluationContext ctx) {
        Map<String, Object> user = new HashMap<>();
        if (ctx == null) return user;
        Map<String, Object> attrs = ctx.asObjectMap();
        if (attrs != null) user.putAll(attrs);
        String key = ctx.getTargetingKey();
        if (key != null && !key.isEmpty()) {
            user.put("user_id", key);
        }
        return user;
    }
}
