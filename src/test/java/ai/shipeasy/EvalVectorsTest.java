package ai.shipeasy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-language eval-parity golden vectors. The fixture
 * (src/test/resources/eval-vectors.json) is a byte-identical copy of the
 * canonical packages/core/src/eval/__fixtures__/eval-vectors.json. Every
 * Shipeasy SDK that reimplements bucketing MUST reproduce every vector — this
 * test locks the Java SDK to the platform's canonical implementation so
 * bucketing can never silently drift.
 *
 * Vectors are loaded from the resource (never hardcoded) and replayed against
 * the real {@link Murmur3} and {@link Eval} implementations.
 */
class EvalVectorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode loadFixture() throws Exception {
        try (InputStream in = EvalVectorsTest.class.getResourceAsStream("/eval-vectors.json")) {
            assertNotNull(in, "eval-vectors.json missing from test resources");
            return MAPPER.readTree(in);
        }
    }

    // ---- hash vectors ----------------------------------------------------

    @TestFactory
    Stream<DynamicTest> hashVectors() throws Exception {
        JsonNode root = loadFixture();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode v : root.get("hash")) {
            String input = v.get("input").asText();
            long expected = v.get("hash").asLong(); // canonical: unsigned uint32 decimal
            tests.add(DynamicTest.dynamicTest("hash[" + describe(input) + "]", () -> {
                long actual = Murmur3.hash32(input) & 0xffffffffL;
                assertEquals(expected, actual,
                        "hash32 diverged for input=" + describe(input));
            }));
        }
        return tests.stream();
    }

    // ---- gate vectors ----------------------------------------------------

    @TestFactory
    Stream<DynamicTest> gateVectors() throws Exception {
        JsonNode root = loadFixture();
        List<DynamicTest> tests = new ArrayList<>();
        int i = 0;
        for (JsonNode v : root.get("gate")) {
            final int idx = i++;
            Map<String, Object> gate = toMap(v.get("gate"));
            Map<String, Object> user = toMap(v.get("user"));
            boolean expected = v.get("pass").asBoolean();
            String note = v.has("note") ? v.get("note").asText() : "";
            tests.add(DynamicTest.dynamicTest("gate[" + idx + "] " + note, () -> {
                boolean actual = Eval.evalGate(gate, user);
                assertEquals(expected, actual, "gate eval diverged: " + note);
            }));
        }
        return tests.stream();
    }

    // ---- experiment vectors ---------------------------------------------

    @TestFactory
    Stream<DynamicTest> experimentVectors() throws Exception {
        JsonNode root = loadFixture();
        List<DynamicTest> tests = new ArrayList<>();
        int i = 0;
        for (JsonNode v : root.get("experiment")) {
            final int idx = i++;
            Map<String, Object> exp = toMap(v.get("experiment"));
            Map<String, Object> user = toMap(v.get("user"));
            JsonNode flagsNode = v.get("flags");
            JsonNode holdoutNode = v.get("holdoutRange");
            JsonNode result = v.get("result");
            boolean expectedIn = result.get("inExperiment").asBoolean();
            String expectedGroup = result.get("group").isNull() ? null : result.get("group").asText();
            String note = v.has("note") ? v.get("note").asText() : "";

            // The fixture expresses targeting via a flat gate-name -> bool map and
            // holdout via a [lo,hi] range. The SDK reads them out of the KV blob
            // shapes, so adapt: feed each flag boolean through a real gate that
            // deterministically yields that boolean (enabled, rollout 0/100%),
            // and place the holdout range on the experiment's universe.
            Map<String, Object> flagsBlob = buildFlagsBlob(flagsNode);
            Map<String, Object> expsBlob = buildExpsBlob(exp, holdoutNode);

            tests.add(DynamicTest.dynamicTest("experiment[" + idx + "] " + note, () -> {
                ExperimentResult r = Eval.evalExperiment(exp, flagsBlob, expsBlob, user);
                assertEquals(expectedIn, r.inExperiment, "inExperiment diverged: " + note);
                if (expectedIn) {
                    assertEquals(expectedGroup, r.group, "assigned group diverged: " + note);
                } else {
                    assertTrue(expectedGroup == null,
                            "fixture invariant: not-in vectors carry group=null");
                }
            }));
        }
        return tests.stream();
    }

    // ---- adapters --------------------------------------------------------

    /** flags: { gateName: bool } -> { "gates": { gateName: <gate yielding bool> } }. */
    private static Map<String, Object> buildFlagsBlob(JsonNode flagsNode) {
        Map<String, Object> gates = new HashMap<>();
        if (flagsNode != null && flagsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = flagsNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                boolean on = e.getValue().asBoolean();
                Map<String, Object> gate = new HashMap<>();
                gate.put("enabled", Boolean.TRUE);
                // rollout 100% -> true / 0% -> false, independent of any unit id,
                // so the real evalGate reproduces the fixture's flag boolean.
                gate.put("rolloutPct", on ? 10000 : 0);
                gate.put("salt", "fixture_" + e.getKey());
                gates.put(e.getKey(), gate);
            }
        }
        Map<String, Object> blob = new HashMap<>();
        blob.put("gates", gates);
        return blob;
    }

    /** holdoutRange [lo,hi] -> { "universes": { <exp.universe>: { "holdout_range": [lo,hi] } } }. */
    private static Map<String, Object> buildExpsBlob(Map<String, Object> exp, JsonNode holdoutNode) {
        Map<String, Object> universes = new HashMap<>();
        if (holdoutNode != null && holdoutNode.isArray() && holdoutNode.size() == 2) {
            Object universeName = exp.get("universe");
            if (universeName != null) {
                Map<String, Object> universe = new HashMap<>();
                List<Object> range = new ArrayList<>();
                range.add(holdoutNode.get(0).asInt());
                range.add(holdoutNode.get(1).asInt());
                universe.put("holdout_range", range);
                universes.put(universeName.toString(), universe);
            }
        }
        Map<String, Object> blob = new HashMap<>();
        blob.put("universes", universes);
        return blob;
    }

    // ---- JSON -> plain Java (Map / List / scalars) -----------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return (Map<String, Object>) toJava(node);
    }

    private static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> m = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                m.put(e.getKey(), toJava(e.getValue()));
            }
            return m;
        }
        if (node.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode child : node) l.add(toJava(child));
            return l;
        }
        if (node.isBoolean()) return node.asBoolean();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isNumber()) return node.asDouble();
        return node.asText();
    }

    private static String describe(String s) {
        return s.isEmpty() ? "<empty>" : s;
    }
}
