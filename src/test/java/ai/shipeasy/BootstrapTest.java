package ai.shipeasy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapTest {

    @SuppressWarnings("unchecked")
    private static Engine client() {
        Map<String, Object> flags = Map.of(
            "gates", Map.of(
                "new_ui", Map.of("enabled", true, "rolloutPct", 10000, "salt", "s"),
                "off_gate", Map.of("enabled", false, "rolloutPct", 10000, "salt", "s")),
            "configs", Map.of("theme", Map.of("value", Map.of("color", "blue"))));
        return Engine.fromSnapshot(flags, Map.of("experiments", Map.of(), "universes", Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluateBuildsPayload() {
        Map<String, Object> p = client().evaluate(Map.of("user_id", "u1"));
        Map<String, Object> flags = (Map<String, Object>) p.get("flags");
        assertEquals(Boolean.TRUE, flags.get("new_ui"));
        assertEquals(Boolean.FALSE, flags.get("off_gate"));
        assertEquals(Map.of("color", "blue"), ((Map<String, Object>) p.get("configs")).get("theme"));
        assertTrue(((Map<String, Object>) p.get("killswitches")).isEmpty());
    }

    @Test
    void bootstrapScriptTagAttrs() throws Exception {
        String tag = client().bootstrapScriptTag(Map.of("user_id", "u1"), "anon-1");
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/sdk/runtime.js\""));
        assertTrue(tag.contains("data-se-bootstrap"));
        assertTrue(tag.contains("data-anon-id=\"anon-1\""));
        assertTrue(tag.contains("data-i18n-profile=\"en:prod\""));
        assertFalse(tag.contains("data-key"), "bootstrap tag must not carry a key");

        Matcher m = Pattern.compile("data-flags=\"([^\"]*)\"").matcher(tag);
        assertTrue(m.find());
        String decoded = m.group(1)
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        Map<String, Object> flags = new ObjectMapper().readValue(decoded, Map.class);
        assertEquals(Boolean.TRUE, flags.get("new_ui"));
    }

    @Test
    void bootstrapScriptTagOmitsAnonWhenUnset() {
        String tag = client().bootstrapScriptTag(Map.of("user_id", "u1"));
        assertFalse(tag.contains("data-anon-id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bootstrapScriptTagCarriesIdentifiedUser() throws Exception {
        Map<String, Object> user = Map.of(
            "user_id", "u1", "email", "a@b.co", "anonymous_id", "anon-1");
        String tag = client().bootstrapScriptTag(user, "anon-1");
        assertTrue(tag.contains("data-anon-id=\"anon-1\""));

        Matcher m = Pattern.compile("data-user=\"([^\"]*)\"").matcher(tag);
        assertTrue(m.find(), "expected data-user attribute");
        String decoded = m.group(1)
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&#39;", "'");
        // Sorted keys, no anonymous_id.
        assertEquals("{\"email\":\"a@b.co\",\"user_id\":\"u1\"}", decoded);
        Map<String, Object> identity = new ObjectMapper().readValue(decoded, Map.class);
        assertFalse(identity.containsKey("anonymous_id"));
        assertEquals("u1", identity.get("user_id"));
        assertEquals("a@b.co", identity.get("email"));
    }

    @Test
    void bootstrapScriptTagOmitsUserWhenAnonymous() {
        // Only an anonymous_id → no identified trait remains → no data-user.
        String anonOnly = client().bootstrapScriptTag(Map.of("anonymous_id", "anon-1"), "anon-1");
        assertFalse(anonOnly.contains("data-user"), "anon-only request must not carry data-user");
        assertTrue(anonOnly.contains("data-anon-id=\"anon-1\""));

        // Empty user → no data-user.
        String empty = client().bootstrapScriptTag(Map.of());
        assertFalse(empty.contains("data-user"), "empty user must not carry data-user");
    }

    @Test
    void i18nScriptTag() {
        String tag = client().i18nScriptTag("client_pub", "fr:prod");
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/sdk/i18n/loader.js\""));
        assertTrue(tag.contains("data-key=\"client_pub\""));
        assertTrue(tag.contains("data-profile=\"fr:prod\""));
    }

    // --- every argument is optional: the tags read what configure() set ------

    /** The same engine, carrying the SSR tag defaults the configure options set. */
    private static Engine configured() {
        return client().tagDefaults(
            "sdk_client_cfg", "fr:prod", "proj_cfg", "https://cdn.example.test");
    }

    @Test
    void i18nScriptTagDefaultsFromConfigure() {
        String tag = configured().i18nScriptTag();
        assertTrue(tag.contains("src=\"https://cdn.example.test/sdk/i18n/loader.js\""));
        assertTrue(tag.contains("data-key=\"sdk_client_cfg\""));
        assertTrue(tag.contains("data-profile=\"fr:prod\""));
    }

    @Test
    void bootstrapScriptTagNeedsNoUser() {
        String tag = configured().bootstrapScriptTag();
        assertTrue(tag.contains("src=\"https://cdn.example.test/sdk/runtime.js\""));
        assertTrue(tag.contains("data-i18n-profile=\"fr:prod\""));
        assertFalse(tag.contains("data-user"));
    }

    @Test
    void devtoolsScriptTagDefaultsFromConfigure() {
        String tag = configured().devtoolsScriptTag();
        assertTrue(tag.contains("src=\"https://cdn.example.test/se-devtools.js\""));
        assertTrue(tag.contains("data-project-id=\"proj_cfg\""));
        assertTrue(tag.contains("data-client-api-key=\"sdk_client_cfg\""));
        assertTrue(tag.contains("defer"));
    }

    @Test
    void explicitArgumentsStillWin() {
        Engine engine = configured();
        String i18n = engine.i18nScriptTag("other_key", "de:prod");
        assertTrue(i18n.contains("data-key=\"other_key\""));
        assertTrue(i18n.contains("data-profile=\"de:prod\""));

        String boot = engine.bootstrapScriptTag(Map.of("user_id", "u1"), null, "de:prod", null);
        assertTrue(boot.contains("data-i18n-profile=\"de:prod\""));

        String dev = engine.devtoolsScriptTag("proj_other", "other_key", null, false);
        assertTrue(dev.contains("data-project-id=\"proj_other\""));
        assertTrue(dev.contains("data-client-api-key=\"other_key\""));
        assertFalse(dev.contains("defer"));
    }

    @Test
    void devtoolsTagRendersWhenUnconfigured() {
        // A missing project id / client key renders anyway (the browser bundle
        // reports what it needs) — a tag helper must never break a template.
        String tag = client().devtoolsScriptTag();
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/se-devtools.js\""));
        assertTrue(tag.contains("data-project-id=\"\""));
    }
}
