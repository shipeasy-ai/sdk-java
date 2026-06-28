package ai.shipeasy.examples.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ai.shipeasy.Engine;
import ai.shipeasy.ExperimentResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the Spring context, hits the guide route ("/") in-process via MockMvc,
 * and asserts the returned HTML contains the values that the Shipeasy SDK is
 * mocked to return.
 *
 * <h2>How the mock is set up</h2>
 * Per {@code docs/pages/testing.md}, the SDK's deterministic, no-network test
 * setup is {@link Engine#forTesting()} plus the {@code override*} setters. We
 * seed the three live entities the guide page shows a value for:
 * <ul>
 *   <li>feature flag {@code new_checkout}</li>
 *   <li>dynamic config {@code billing_copy}</li>
 *   <li>A/B experiment {@code checkout_button}</li>
 * </ul>
 * with the exact key names read from {@code GuideController}, but with
 * <strong>distinctive sentinel values</strong> that do NOT appear in the
 * controller's current hardcoded placeholders — so the page assertions genuinely
 * exercise the SDK&rarr;page path instead of tautologically matching placeholder
 * text.
 *
 * <h2>EXPECTED TO FAIL (page value assertions)</h2>
 * The example controller renders <em>hardcoded placeholders</em> and is NOT
 * wired to the SDK — and the global-install seam
 * ({@code Shipeasy.useEngineForTest}) is package-private in {@code ai.shipeasy},
 * so it is not reachable from this test package. We therefore set up the mock
 * with {@link Engine#forTesting()} and assert the rendered HTML contains the
 * sentinel values that mock returns. The <em>infrastructure</em> is correct —
 * the context boots, the controller route is hit in-process, real HTML comes
 * back, and the assertions run against the mocked values — but the page
 * value-matching assertions are expected to FAIL until the controller is
 * actually wired to the SDK (the sentinels are absent from the placeholders).
 * The pure-SDK self-checks on {@link Engine#forTesting()} pass.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GuidePageTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void guidePageRendersMockedShipeasyValues() throws Exception {
        // ---- Arrange: mock EVERY value Shipeasy returns via the testing API. ----
        // Engine.forTesting() is no-network; override* always wins over any fetch.
        try (Engine c = Engine.forTesting()) {

            // 1. FEATURE FLAG — new_checkout = true
            c.overrideFlag("new_checkout", true);
            boolean newCheckout = c.getFlag("new_checkout", Map.of());

            // 2. DYNAMIC CONFIG — billing_copy = {headline, cta}
            //    DISTINCTIVE sentinels that do NOT appear in the controller's
            //    current placeholders, so the page assertion genuinely tests the
            //    SDK->page path (and currently fails — the app isn't wired).
            c.overrideConfig(
                    "billing_copy",
                    Map.of("headline", "Welcome aboard 🚀", "cta", "Start free trial"));
            @SuppressWarnings("unchecked")
            Map<String, Object> billingCopy = (Map<String, Object>) c.getConfig("billing_copy");

            // 3. A/B EXPERIMENT — checkout_button -> treatment, params {color,label}
            //    Distinctive sentinel params, again not present in placeholders.
            c.overrideExperiment(
                    "checkout_button",
                    "treatment",
                    Map.of("color", "#0ea5e9", "label", "Checkout now"));
            ExperimentResult checkoutButton =
                    c.getExperiment("checkout_button", Map.of(), Map.of("color", "#888", "label", "Buy"));

            // Sanity-check the mock itself (these are pure-SDK assertions and pass).
            assertThat(newCheckout).isTrue();
            assertThat(billingCopy).containsEntry("headline", "Welcome aboard 🚀");
            assertThat(checkoutButton.inExperiment).isTrue();
            assertThat(checkoutButton.group).isEqualTo("treatment");

            // ---- Act: fetch the page IN-PROCESS through the controller. ----
            String html = mockMvc.perform(get("/"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // ---- Assert: the HTML contains each mocked value. ----
            // EXPECTED TO FAIL until the controller is wired to the SDK — the page
            // currently renders placeholders, not these sentinel mock values.

            // 1. feature flag value
            assertThat(html).contains("new_checkout");
            assertThat(html).contains(String.valueOf(newCheckout)); // "true"

            // 2. dynamic config values (distinctive sentinels)
            assertThat(html).contains("billing_copy");
            assertThat(html).contains((String) billingCopy.get("headline")); // "Welcome aboard 🚀"
            assertThat(html).contains((String) billingCopy.get("cta"));      // "Start free trial"

            // 3. experiment group + params (distinctive sentinels)
            assertThat(html).contains("checkout_button");
            assertThat(html).contains(checkoutButton.group); // "treatment"
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) checkoutButton.params;
            assertThat(html).contains((String) params.get("color")); // "#0ea5e9"
            assertThat(html).contains((String) params.get("label")); // "Checkout now"
        }
    }
}
