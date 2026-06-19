package ai.shipeasy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class Eval {
    static final ExperimentResult NOT_IN = new ExperimentResult(false, "control", null);

    private Eval() {}

    static boolean enabled(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        return false;
    }

    static Double toNum(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static String userId(Map<String, Object> user) {
        Object v = user.get("user_id");
        if (v == null) v = user.get("anonymous_id");
        return v == null ? null : v.toString();
    }

    /**
     * Resolve the bucketing identifier. With {@code bucketBy} set (e.g.
     * {@code company_id}) the corresponding user attribute drives bucketing so a
     * whole org moves together; a non-empty String is used verbatim and a Number
     * is stringified ({@code 42 -> "42"}). Otherwise (unset, or the attribute is
     * absent/empty/an unsupported type) fall back to {@code user_id ?? anonymous_id}.
     * Mirrors {@code pickIdentifier} in packages/core/src/eval/gate.ts.
     */
    static String pickIdentifier(Map<String, Object> user, String bucketBy) {
        if (bucketBy != null && !bucketBy.isEmpty()) {
            Object v = user.get(bucketBy);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            if (v instanceof Number) return String.valueOf(v);
        }
        return userId(user);
    }

    @SuppressWarnings("unchecked")
    static boolean matchRule(Map<String, Object> rule, Map<String, Object> user) {
        String attr = (String) rule.get("attr");
        String op = (String) rule.get("op");
        Object value = rule.get("value");
        Object actual = attr == null ? null : user.get(attr);

        switch (op == null ? "" : op) {
            case "eq": return java.util.Objects.equals(actual, value);
            case "neq": return !java.util.Objects.equals(actual, value);
            case "in":
                return value instanceof List && ((List<Object>) value).contains(actual);
            case "not_in":
                return !(value instanceof List) || !((List<Object>) value).contains(actual);
            case "contains":
                if (actual instanceof String && value instanceof String) return ((String) actual).contains((String) value);
                if (actual instanceof List) return ((List<Object>) actual).contains(value);
                return false;
            case "regex":
                if (!(actual instanceof String) || !(value instanceof String)) return false;
                try { return Pattern.compile((String) value).matcher((String) actual).find(); }
                catch (PatternSyntaxException e) { return false; }
            case "gt": case "gte": case "lt": case "lte": {
                Double a = toNum(actual), b = toNum(value);
                if (a == null || b == null) return false;
                switch (op) {
                    case "gt": return a > b;
                    case "gte": return a >= b;
                    case "lt": return a < b;
                    case "lte": return a <= b;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static boolean evalGate(Map<String, Object> gate, Map<String, Object> user) {
        if (gate == null) return false;
        if (enabled(gate.get("killswitch"))) return false;
        if (!enabled(gate.get("enabled"))) return false;
        List<Map<String, Object>> rules = (List<Map<String, Object>>) gate.get("rules");
        if (rules != null) for (Map<String, Object> r : rules) if (!matchRule(r, user)) return false;
        String uid = userId(user);
        Number rolloutPct = (Number) gate.getOrDefault("rolloutPct", 0);
        if (uid == null) {
            // No unit id (an unidentified request before any anon id is minted):
            // a fully-rolled gate is on for everyone, so it can be answered
            // without bucketing; a fractional rollout needs a stable unit, so
            // deny until one exists. Rules above still apply, so targeting wins.
            // See experiment-platform/18-identity-bucketing.md.
            return rolloutPct.intValue() >= 10000;
        }
        String salt = (String) gate.getOrDefault("salt", "");
        return Murmur3.bucket(salt + ":" + uid, 10000) < rolloutPct.intValue();
    }

    static ExperimentResult evalExperiment(
            Map<String, Object> exp,
            Map<String, Object> flagsBlob,
            Map<String, Object> expsBlob,
            Map<String, Object> user) {
        return evalExperiment(exp, flagsBlob, expsBlob, user, null, null);
    }

    /**
     * As {@link #evalExperiment(Map, Map, Map, Map)} but with optional sticky
     * bucketing (doc 20 §2). When {@code store} (and {@code expName}) are
     * supplied, an enrolled unit whose stored salt prefix still matches the
     * experiment's current salt prefix skips the allocation gate and returns the
     * stored group without re-running the pick. A fresh pick is persisted to the
     * store. A salt mismatch or a missing/stale stored group falls through to a
     * re-bucket + overwrite. {@code null store} ⇒ deterministic (no stickiness).
     */
    @SuppressWarnings("unchecked")
    static ExperimentResult evalExperiment(
            Map<String, Object> exp,
            Map<String, Object> flagsBlob,
            Map<String, Object> expsBlob,
            Map<String, Object> user,
            StickyBucketStore store,
            String expName) {
        if (exp == null || !"running".equals(exp.get("status"))) return NOT_IN;

        String targetingGate = (String) exp.get("targetingGate");
        if (targetingGate != null && !targetingGate.isEmpty()) {
            Map<String, Object> gates = flagsBlob == null ? null : (Map<String, Object>) flagsBlob.get("gates");
            Map<String, Object> gate = gates == null ? null : (Map<String, Object>) gates.get(targetingGate);
            if (gate == null || !evalGate(gate, user)) return NOT_IN;
        }

        // Bucket on exp.bucketBy (e.g. company_id) when set, else user_id/anonymous_id.
        // Holdout, allocation, and group all hash on the SAME unit so a whole org
        // moves together. No resolvable unit -> not enrolled.
        String uid = pickIdentifier(user, (String) exp.get("bucketBy"));
        if (uid == null) return NOT_IN;

        String universeName = (String) exp.get("universe");
        if (universeName != null && expsBlob != null) {
            Map<String, Object> universes = (Map<String, Object>) expsBlob.get("universes");
            Map<String, Object> universe = universes == null ? null : (Map<String, Object>) universes.get(universeName);
            if (universe != null) {
                List<Number> holdout = (List<Number>) universe.get("holdout_range");
                if (holdout != null && holdout.size() == 2) {
                    int seg = Murmur3.bucket(universeName + ":" + uid, 10000);
                    if (seg >= holdout.get(0).intValue() && seg <= holdout.get(1).intValue()) return NOT_IN;
                }
            }
        }

        String salt = (String) exp.getOrDefault("salt", "");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) exp.getOrDefault("groups", List.of());

        // Sticky short-circuit (doc 20 §2): after the holdout, before allocation.
        // An enrolled unit whose stored salt prefix still matches skips the
        // allocation gate (a shrinking allocation keeps it in) and returns the
        // stored group without re-running the pick.
        String salt8 = salt.length() >= 8 ? salt.substring(0, 8) : salt;
        if (store != null && expName != null) {
            Map<String, StickyEntry> unitEntries = store.get(uid);
            StickyEntry entry = unitEntries == null ? null : unitEntries.get(expName);
            if (entry != null && salt8.equals(entry.salt8)) {
                for (Map<String, Object> g : groups) {
                    if (entry.group != null && entry.group.equals(g.get("name"))) {
                        return new ExperimentResult(true, entry.group, g.get("params"));
                    }
                }
                // Stored group gone -> fall through to re-bucket + overwrite.
            }
        }

        Number allocPct = (Number) exp.getOrDefault("allocationPct", 0);
        if (Murmur3.bucket(salt + ":alloc:" + uid, 10000) >= allocPct.intValue()) return NOT_IN;

        int groupHash = Murmur3.bucket(salt + ":group:" + uid, 10000);
        int cumulative = 0;
        for (int i = 0; i < groups.size(); i++) {
            Map<String, Object> g = groups.get(i);
            cumulative += ((Number) g.getOrDefault("weight", 0)).intValue();
            if (groupHash < cumulative || i == groups.size() - 1) {
                String groupName = (String) g.get("name");
                if (store != null && expName != null) {
                    store.set(uid, expName, new StickyEntry(groupName, salt8));
                }
                return new ExperimentResult(true, groupName, g.get("params"));
            }
        }
        return NOT_IN;
    }
}
