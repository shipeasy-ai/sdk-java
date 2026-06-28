package ai.shipeasy.examples.guide;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Serves the single guide page at {@code /}.
 *
 * <h2>SDK not wired yet</h2>
 * Every value below is a hardcoded placeholder. The {@code // TODO} blocks show
 * the exact live SDK call to drop in once {@code ai.shipeasy:shipeasy} is on the
 * classpath. After installing the SDK you would construct one client and reuse it:
 *
 * <pre>{@code
 * // TODO: once ai.shipeasy:shipeasy is installed
 * // import ai.shipeasy.Shipeasy;
 * // import ai.shipeasy.ShipeasyClient;
 * // ShipeasyClient c = Shipeasy.init(System.getenv("SHIPEASY_SERVER_KEY"));
 * }</pre>
 */
@Controller
public class GuideController {

    @GetMapping("/")
    public String guide(Model model) {

        // ----------------------------------------------------------------
        // 1. FEATURE FLAG — a boolean on/off switch with targeting + rollout.
        // ----------------------------------------------------------------
        // TODO: once ai.shipeasy:shipeasy is installed
        // boolean on = c.getFlag("new_checkout", Map.of("user_id", "u_123"));
        Entity featureFlag = new Entity(
                "FEATURE FLAG",
                "new_checkout",
                "true",
                "reason: RULE_MATCH",
                "A boolean on/off switch with targeting rules + percentage rollout.",
                "boolean on = c.getFlag(\"new_checkout\", Map.of(\"user_id\", \"u_123\"));",
                "#34d399"
        );

        // ----------------------------------------------------------------
        // 2. DYNAMIC CONFIG — a typed JSON blob you change without deploying.
        // ----------------------------------------------------------------
        // TODO: once ai.shipeasy:shipeasy is installed
        // Object cfg = c.getConfig("billing_copy");
        Entity dynamicConfig = new Entity(
                "DYNAMIC CONFIG",
                "billing_copy",
                "{\"headline\":\"Welcome back 👋\",\"cta\":\"Upgrade to Pro\"}",
                "typed JSON · 2 keys",
                "A typed JSON blob you change without deploying.",
                "Object cfg = c.getConfig(\"billing_copy\");",
                "#60a5fa"
        );

        // ----------------------------------------------------------------
        // 3. A/B EXPERIMENT — splits users into variants and measures a metric.
        // ----------------------------------------------------------------
        // TODO: once ai.shipeasy:shipeasy is installed
        // ExperimentResult r = c.getExperiment(
        //         "checkout_button",
        //         Map.of("user_id", "u_123"),
        //         Map.of("color", "#888", "label", "Buy"));
        Entity experiment = new Entity(
                "A/B EXPERIMENT",
                "checkout_button",
                "treatment",
                "inExperiment: true · params {\"color\":\"#34d399\",\"label\":\"Buy now\"}",
                "Splits users into variants and measures a metric.",
                "ExperimentResult r = c.getExperiment(\"checkout_button\", "
                        + "Map.of(\"user_id\",\"u_123\"), Map.of(\"color\",\"#888\",\"label\",\"Buy\"));",
                "#c084fc"
        );

        // ----------------------------------------------------------------
        // 4. KILL SWITCH — an operational off-switch shipped alongside flags.
        // ----------------------------------------------------------------
        // TODO: once ai.shipeasy:shipeasy is installed
        // Map<String,Object> boot = c.evaluate(Map.of("user_id", "u_123"));
        // boolean paused = Boolean.TRUE.equals(
        //         ((Map<?,?>) boot.get("killswitches")).get("payments_paused"));
        Entity killSwitch = new Entity(
                "KILL SWITCH",
                "payments_paused",
                "false",
                "payments live",
                "An operational off-switch shipped alongside flags — flip it to "
                        + "disable a subsystem during an incident.",
                "Map<String,Object> boot = c.evaluate(Map.of(\"user_id\",\"u_123\")); "
                        + "boolean paused = ...boot.get(\"killswitches\")...;",
                "#f87171"
        );

        // ----------------------------------------------------------------
        // 5. EVENT / METRIC — fire-and-forget events that power metrics.
        // ----------------------------------------------------------------
        // TODO: once ai.shipeasy:shipeasy is installed
        // c.track("u_123", "checkout_completed", Map.of("revenue", 49.99, "plan", "pro"));
        Entity event = new Entity(
                "EVENT / METRIC",
                "checkout_completed",
                "last event queued",
                "props {\"revenue\":49.99,\"plan\":\"pro\"}",
                "Fire-and-forget events that power experiment metrics + dashboards.",
                "c.track(\"u_123\", \"checkout_completed\", "
                        + "Map.of(\"revenue\", 49.99, \"plan\", \"pro\"));",
                "#22d3ee"
        );

        // ----------------------------------------------------------------
        // 6. I18N LABEL — server-managed copy you translate + publish.
        //    (i18n for the Java SDK ships as a follow-up; shown for completeness.)
        // ----------------------------------------------------------------
        // TODO: once ai.shipeasy:shipeasy is installed (illustrative)
        // String hero = t("hero.title", Map.of("name", "Sam"));
        Entity i18nLabel = new Entity(
                "I18N LABEL",
                "hero.title",
                "Ship features, not stress",
                "i18n for the Java SDK ships as a follow-up",
                "Server-managed copy you translate + publish from the dashboard — "
                        + "no redeploy.",
                "t(\"hero.title\", Map.of(\"name\",\"Sam\"))",
                "#fbbf24"
        );

        // ----------------------------------------------------------------
        // 7. ERROR REPORTING — see(): structured reports of the product
        //    consequence, not just a stack trace.
        // ----------------------------------------------------------------
        // TODO: once ai.shipeasy:shipeasy is installed
        // try {
        //     submitOrder(o);
        // } catch (Exception e) {
        //     Shipeasy.see(e).causesThe("checkout").to("use cached prices")
        //             .extras(Map.of("order_id", o.id));
        // }
        Entity errorReporting = new Entity(
                "ERROR REPORTING",
                "see()",
                "0 issues reported this session",
                "structured consequence reports",
                "Structured error reports that document the product consequence, "
                        + "not just a stack trace.",
                "try { submitOrder(o); } catch (Exception e) { "
                        + "Shipeasy.see(e).causesThe(\"checkout\").to(\"use cached prices\")"
                        + ".extras(Map.of(\"order_id\", o.id)); }",
                "#f87171"
        );

        List<Entity> entities = List.of(
                featureFlag,
                dynamicConfig,
                experiment,
                killSwitch,
                event,
                i18nLabel,
                errorReporting
        );

        model.addAttribute("entities", entities);
        return "guide";
    }
}
