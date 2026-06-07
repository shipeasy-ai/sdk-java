package ai.shipeasy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryTest {
    // 1) basic telemetry send works for each entity call, hitting the right URL.
    @Test
    void firesPerEntity() {
        List<String> sent = new ArrayList<>();
        Telemetry t = new Telemetry("https://e.x", "srv", "server", "prod", false, (Consumer<String>) sent::add);

        t.emit("gate", "g");
        t.emit("config", "c");
        t.emit("experiment", "e");
        t.emit("ks", "k");

        assertEquals(4, sent.size());
        assertTrue(sent.get(0).endsWith("/gate/g"));
        assertTrue(sent.get(1).endsWith("/config/c"));
        assertTrue(sent.get(2).endsWith("/experiment/e"));
        assertTrue(sent.get(3).endsWith("/ks/k"));
        for (String url : sent) {
            assertTrue(url.startsWith("https://e.x/t/"));
            assertFalse(url.contains("srv")); // raw key never appears in the URL
        }
    }

    // 2) telemetry is not sent when disabled in settings.
    @Test
    void disabledSendsNothing() {
        List<String> sent = new ArrayList<>();
        Telemetry t = new Telemetry("https://e.x", "srv", "server", "prod", true, (Consumer<String>) sent::add);

        t.emit("gate", "g");
        t.emit("config", "c");
        t.emit("experiment", "e");

        assertTrue(sent.isEmpty());
    }
}
