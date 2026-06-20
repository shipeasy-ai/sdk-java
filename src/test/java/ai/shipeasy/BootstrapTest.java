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
    private static Client client() {
        Map<String, Object> flags = Map.of(
            "gates", Map.of(
                "new_ui", Map.of("enabled", true, "rolloutPct", 10000, "salt", "s"),
                "off_gate", Map.of("enabled", false, "rolloutPct", 10000, "salt", "s")),
            "configs", Map.of("theme", Map.of("value", Map.of("color", "blue"))));
        return Client.fromSnapshot(flags, Map.of("experiments", Map.of(), "universes", Map.of()));
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
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/sdk/bootstrap.js\""));
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
    void i18nScriptTag() {
        String tag = client().i18nScriptTag("client_pub", "fr:prod");
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/sdk/i18n/loader.js\""));
        assertTrue(tag.contains("data-key=\"client_pub\""));
        assertTrue(tag.contains("data-profile=\"fr:prod\""));
    }
}
