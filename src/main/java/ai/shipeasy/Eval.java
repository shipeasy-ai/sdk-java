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

        // Modern gatekeepers ship an ordered {@code stack}; evaluate it
        // top-to-bottom and pass on the first entry whose rules match AND whose
        // bucket hits. This is the canonical model — the flat {@code rules} /
        // {@code rolloutPct} below are a lossy approximation (a whitelist
        // condition at 100% collapses to {@code rolloutPct: 0} once the public
        // rollout is 0%, which the flat path would wrongly read as "never").
        // Mirrors {@code @shipeasy/core} evalGatekeeper — keep the two in sync.
        Object stackObj = gate.get("stack");
        if (stackObj instanceof List) {
            List<Object> stack = (List<Object>) stackObj;
            if (!stack.isEmpty()) {
                String fallbackSalt = (String) gate.getOrDefault("salt", "");
                long now = System.currentTimeMillis();
                for (Object e : stack) {
                    if (e instanceof Map && evalStackEntry((Map<String, Object>) e, user, fallbackSalt, now)) {
                        return true;
                    }
                }
                return false;
            }
        }

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

    // ── Gatekeeper (stacked) evaluation ─────────────────────────────────────
    //
    // One entry in a gate's ordered gatekeeper stack. A {@code condition} gates
    // on its rules then buckets at its own rollout (absent rolloutPct means
    // 100%: every match passes); a {@code rollout} buckets everyone who reached
    // it (absent means 0%). A rollout % may RAMP linearly over time. Mirrors
    // {@code @shipeasy/core} evalStackEntry/effectivePct/bucketHit — keep in sync
    // (see experiment-platform/04-evaluation.md).

    private static int clampPct(int n) {
        if (n < 0) return 0;
        if (n > 10000) return 10000;
        return n;
    }

    /**
     * Effective rollout % (basis points) for a stack entry at time {@code now}.
     * A condition with no explicit rolloutPct defaults to 100%; a rollout to 0%.
     * A {@code ramp} linearly interpolates {@code from}..{@code to} over
     * [{@code startAt}, {@code startAt+durationMs}] against the wall-clock
     * {@code now} (epoch ms). Integer division truncates toward zero with a
     * 64-bit intermediate for {@code delta*elapsed} — the cross-SDK contract.
     */
    @SuppressWarnings("unchecked")
    static int effectivePct(Map<String, Object> entry, long now) {
        boolean isCondition = "condition".equals(entry.get("type"));
        Number rp = (Number) entry.get("rolloutPct");
        int base = rp != null ? rp.intValue() : (isCondition ? 10000 : 0);

        Object rampObj = entry.get("ramp");
        if (!(rampObj instanceof Map)) return base;
        Map<String, Object> ramp = (Map<String, Object>) rampObj;
        int from = ((Number) ramp.getOrDefault("from", 0)).intValue();
        int to = ((Number) ramp.getOrDefault("to", 0)).intValue();
        long startAt = ((Number) ramp.getOrDefault("startAt", 0)).longValue();
        long durationMs = ((Number) ramp.getOrDefault("durationMs", 0)).longValue();

        if (now <= startAt) return from;
        if (durationMs <= 0 || now >= startAt + durationMs) return to;
        long delta = (long) to - from; // signed
        long elapsed = now - startAt;
        int pct = from + (int) (delta * elapsed / durationMs);
        return clampPct(pct);
    }

    /**
     * Hash the caller into [0,10000) and test against {@code pct}. No-unit
     * contract (experiment-platform/18): a fully-rolled bucket is answerable
     * without a unit id (on for everyone); a fractional one needs a stable unit,
     * so it is off. A pct of 0 or less is off for everyone; a pct of 10000 or
     * more is on for everyone with a unit.
     */
    static boolean bucketHit(int pct, String uid, String salt) {
        if (pct <= 0) return false;
        if (uid == null || uid.isEmpty()) return pct >= 10000;
        if (pct >= 10000) return true;
        return Murmur3.bucket(salt + ":" + uid, 10000) < pct;
    }

    /**
     * Evaluate one stack entry. A {@code condition} passes when its rules match
     * (all, or any when {@code pass=="any"}) AND the caller's bucket hits its
     * effective rollout; a {@code rollout} passes when the bucket hits. The
     * condition's default salt is its own {@code id} (so each step buckets
     * independently); a rollout falls back to the gate salt. Mirrors
     * {@code @shipeasy/core} evalStackEntry.
     */
    @SuppressWarnings("unchecked")
    static boolean evalStackEntry(Map<String, Object> entry, Map<String, Object> user, String fallbackSalt, long now) {
        String entrySalt = (String) entry.get("salt");
        String bucketBy = (String) entry.get("bucketBy");
        String id = (String) entry.get("id");
        if ("condition".equals(entry.get("type"))) {
            List<Map<String, Object>> rules = (List<Map<String, Object>>) entry.get("rules");
            if (rules == null || rules.isEmpty()) return false;
            boolean any = "any".equals(entry.get("pass"));
            boolean matched;
            if (any) {
                matched = false;
                for (Map<String, Object> r : rules) if (matchRule(r, user)) { matched = true; break; }
            } else {
                matched = true;
                for (Map<String, Object> r : rules) if (!matchRule(r, user)) { matched = false; break; }
            }
            if (!matched) return false;
            // Rules matched — bucket at the per-condition rollout. A distinct
            // default salt (the entry id) keeps each step's bucket independent.
            String salt = entrySalt != null && !entrySalt.isEmpty()
                    ? entrySalt : (id != null && !id.isEmpty() ? id : fallbackSalt);
            return bucketHit(effectivePct(entry, now), pickIdentifier(user, bucketBy), salt);
        }
        // rollout — salt fallback is the gate salt so existing entries don't re-bucket.
        String salt = entrySalt != null && !entrySalt.isEmpty() ? entrySalt : fallbackSalt;
        return bucketHit(effectivePct(entry, now), pickIdentifier(user, bucketBy), salt);
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
     *
     * <p>This legacy entry point does <em>not</em> merge universe param defaults
     * (it returns the group's raw params) — the universe-first
     * {@link #classifyExperiment} path does. It stays for the eval-vectors parity
     * test and the sticky-store tests.
     */
    @SuppressWarnings("unchecked")
    static ExperimentResult evalExperiment(
            Map<String, Object> exp,
            Map<String, Object> flagsBlob,
            Map<String, Object> expsBlob,
            Map<String, Object> user,
            StickyBucketStore store,
            String expName) {
        List<Number> holdoutRange = holdoutRangeFor(exp, expsBlob);
        ExpStanding s = classifyExperiment(exp, flagsBlob, user, holdoutRange, null, store, expName);
        if (s.state != State.GROUP) return NOT_IN;
        return new ExperimentResult(true, s.group, s.params);
    }

    /** The universe's holdout range ({@code [lo,hi]}) for {@code exp}, or {@code null}. */
    @SuppressWarnings("unchecked")
    static List<Number> holdoutRangeFor(Map<String, Object> exp, Map<String, Object> expsBlob) {
        if (exp == null || expsBlob == null) return null;
        String universeName = (String) exp.get("universe");
        if (universeName == null) return null;
        Map<String, Object> universes = (Map<String, Object>) expsBlob.get("universes");
        Map<String, Object> universe = universes == null ? null : (Map<String, Object>) universes.get(universeName);
        if (universe == null) return null;
        List<Number> holdout = (List<Number>) universe.get("holdout_range");
        return (holdout != null && holdout.size() == 2) ? holdout : null;
    }

    /** Flatten a universe {@code param_schema} array to a {@code name -> default}
     *  map — the defaults {@code assign()} layers under a variant's override map.
     *  Returns {@code null} for a null/empty schema so the merge short-circuits.
     *  Mirrors {@code paramDefaultsFromSchema} in the canonical TS SDK. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> paramDefaultsFromSchema(Object schema) {
        if (!(schema instanceof List)) return null;
        List<Object> items = (List<Object>) schema;
        if (items.isEmpty()) return null;
        Map<String, Object> out = new java.util.HashMap<>();
        for (Object it : items) {
            if (!(it instanceof Map)) continue;
            Map<String, Object> p = (Map<String, Object>) it;
            Object name = p.get("name");
            if (name != null) out.put(name.toString(), p.get("default"));
        }
        return out;
    }

    /** {@code universeDefaults ⊕ variantOverride} — a variant inherits every
     *  universe default it doesn't explicitly override. Mirrors {@code mergeParams}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeParams(Map<String, Object> paramDefaults, Object groupParams) {
        Map<String, Object> out = new java.util.HashMap<>();
        if (paramDefaults != null) out.putAll(paramDefaults);
        if (groupParams instanceof Map) out.putAll((Map<String, Object>) groupParams);
        return out;
    }

    /** A unit's standing in one experiment: an assigned {@link State#GROUP} (with
     *  merged params), {@link State#HOLDOUT} (universe carve-out or holdout gate —
     *  never assigned), or {@link State#OUT}. */
    enum State { GROUP, HOLDOUT, OUT }

    static final class ExpStanding {
        final State state;
        final String group;
        final Map<String, Object> params;

        private ExpStanding(State state, String group, Map<String, Object> params) {
            this.state = state;
            this.group = group;
            this.params = params;
        }

        static final ExpStanding OUT = new ExpStanding(State.OUT, null, null);
        static final ExpStanding HOLDOUT = new ExpStanding(State.HOLDOUT, null, null);

        static ExpStanding group(String name, Map<String, Object> params) {
            return new ExpStanding(State.GROUP, name, params);
        }
    }

    /**
     * Resolve a forced override group for {@code uid} (spec step 1): ID overrides
     * (tier 1) beat cohort/GK overrides (tier 2); within cohort overrides the first
     * (pre-sorted by priority) gate that passes wins. Returns the forced group name
     * or {@code null}. The caller applies eligibility + group-existence
     * (forced-but-gated). Mirrors {@code @shipeasy/core} resolveForcedGroup.
     */
    @SuppressWarnings("unchecked")
    static String resolveForcedGroup(
            Map<String, Object> exp, String uid, Map<String, Object> flagsBlob, Map<String, Object> user) {
        Object idov = exp.get("idOverrides");
        if (idov instanceof Map) {
            Object byId = ((Map<String, Object>) idov).get(uid);
            if (byId instanceof String && !((String) byId).isEmpty()) return (String) byId;
        }
        Object cohorts = exp.get("cohortOverrides");
        if (cohorts instanceof List) {
            for (Object o : (List<Object>) cohorts) {
                if (o instanceof Map) {
                    Map<String, Object> co = (Map<String, Object>) o;
                    String gate = (String) co.get("gate");
                    if (gate != null && evalGateByName(flagsBlob, gate, user)) {
                        Object grp = co.get("group");
                        return grp == null ? null : (String) grp;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Targeting → universe holdout → holdout gate → sticky → allocation (pooled or
     * legacy) → weighted group split. The single local mirror of @shipeasy/core's
     * {@code classifyExperiment} (mirror {@code classifyExperimentLocal} in the TS
     * SDK — keep the two in sync).
     *
     * <p>Steps in order: (1) targeting gate; (2) resolve the bucketing unit;
     * (3) compute the universe segment shared by holdout and every pool slice;
     * (4) universe holdout carve-out; (5) holdout gate; (6) sticky short-circuit;
     * (7) allocation — POOLED when {@code hashVersion>=2} with a pool slice, else
     * the legacy per-experiment alloc hash; (8) group split over {@code [0,usable)}
     * where {@code usable = 10000 - reservedHeadroomBp}, with universe defaults
     * merged under the assigned variant.
     *
     * @param paramDefaults the universe param defaults to merge under a variant's
     *        override map, or {@code null} to return raw group params (legacy path)
     */
    @SuppressWarnings("unchecked")
    static ExpStanding classifyExperiment(
            Map<String, Object> exp,
            Map<String, Object> flagsBlob,
            Map<String, Object> user,
            List<Number> holdoutRange,
            Map<String, Object> paramDefaults,
            StickyBucketStore store,
            String expName) {
        if (exp == null || !"running".equals(exp.get("status"))) return ExpStanding.OUT;

        String targetingGate = (String) exp.get("targetingGate");
        if (targetingGate != null && !targetingGate.isEmpty() && !evalGateByName(flagsBlob, targetingGate, user)) {
            return ExpStanding.OUT;
        }

        // Bucket on exp.bucketBy (e.g. company_id) when set, else user_id/anonymous_id.
        // Holdout, allocation, and group all hash on the SAME unit so a whole org
        // moves together. No resolvable unit -> not enrolled.
        String uid = pickIdentifier(user, (String) exp.get("bucketBy"));
        if (uid == null) return ExpStanding.OUT;

        // One segment in the universe's shared [0, 10000) hash space. The holdout
        // carve-out AND every experiment's pool slice are disjoint ranges of THIS
        // segment — that's what makes "held out / taken / free" a real partition.
        String universeName = (String) exp.get("universe");
        int universeSeg = Murmur3.bucket(universeName + ":" + uid, 10000);

        if (holdoutRange != null && holdoutRange.size() == 2) {
            int lo = holdoutRange.get(0).intValue();
            int hi = holdoutRange.get(1).intValue();
            if (universeSeg >= lo && universeSeg <= hi) return ExpStanding.HOLDOUT;
        }

        String holdoutGate = (String) exp.get("holdoutGate");
        if (holdoutGate != null && !holdoutGate.isEmpty() && evalGateByName(flagsBlob, holdoutGate, user)) {
            return ExpStanding.HOLDOUT;
        }

        String salt = (String) exp.getOrDefault("salt", "");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) exp.getOrDefault("groups", List.of());

        String salt8 = salt.length() >= 8 ? salt.substring(0, 8) : salt;

        // Durable overrides (spec step 1, forced-but-gated). Reached only after the
        // unit passes targeting and is not held out, so an override may now pin the
        // group — bypassing allocation + the weighted pick but NOT the gates above.
        // ID overrides (tier 1) beat cohort/GK overrides (tier 2); a forced group
        // that no longer exists falls through to normal allocation. No-op when
        // unconfigured, so v1/v2 stay byte-identical. Mirrors @shipeasy/core.
        String forced = resolveForcedGroup(exp, uid, flagsBlob, user);
        if (forced != null) {
            for (Map<String, Object> g : groups) {
                if (forced.equals(g.get("name"))) {
                    if (store != null && expName != null) {
                        store.set(uid, expName, new StickyEntry(forced, salt8));
                    }
                    return ExpStanding.group(forced, mergeParams(paramDefaults, g.get("params")));
                }
            }
        }

        // Sticky short-circuit (doc 20 §2): after the holdout, before allocation.
        // An enrolled unit whose stored salt prefix still matches skips the
        // allocation gate (a shrinking allocation keeps it in) and returns the
        // stored group without re-running the pick.
        if (store != null && expName != null) {
            Map<String, StickyEntry> unitEntries = store.get(uid);
            StickyEntry entry = unitEntries == null ? null : unitEntries.get(expName);
            if (entry != null && salt8.equals(entry.salt8)) {
                for (Map<String, Object> g : groups) {
                    if (entry.group != null && entry.group.equals(g.get("name"))) {
                        return ExpStanding.group(entry.group, mergeParams(paramDefaults, g.get("params")));
                    }
                }
                // Stored group gone -> fall through to re-bucket + overwrite.
            }
        }

        // Allocation. Pooled (hashVersion >= 2 with a slice) gives real mutual
        // exclusion: the unit's universe segment must fall in the claimed range.
        // Legacy falls back to an independent per-experiment salt so siblings
        // overlap freely.
        int hashVersion = ((Number) exp.getOrDefault("hashVersion", 1)).intValue();
        Number poolOffset = (Number) exp.get("poolOffsetBp");
        Number poolSize = (Number) exp.get("poolSizeBp");
        boolean pooled = hashVersion >= 2 && poolOffset != null && poolSize != null && poolSize.intValue() > 0;
        if (pooled) {
            int lo = poolOffset.intValue();
            int hi = lo + poolSize.intValue();
            if (universeSeg < lo || universeSeg >= hi) return ExpStanding.OUT;
        } else {
            int allocPct = ((Number) exp.getOrDefault("allocationPct", 0)).intValue();
            if (Murmur3.bucket(salt + ":alloc:" + uid, 10000) >= allocPct) return ExpStanding.OUT;
        }

        // Group split over [0, usable) where usable = 10000 - reserved; a unit in
        // the reserved tail is left unassigned so an appended variant can absorb it.
        int reserved = Math.max(0, Math.min(10000, ((Number) exp.getOrDefault("reservedHeadroomBp", 0)).intValue()));
        int usable = 10000 - reserved;
        int groupHash = Murmur3.bucket(salt + ":group:" + uid, 10000);
        if (groupHash >= usable) return ExpStanding.OUT;
        int cumulative = 0;
        for (int i = 0; i < groups.size(); i++) {
            Map<String, Object> g = groups.get(i);
            cumulative += ((Number) g.getOrDefault("weight", 0)).intValue();
            if (groupHash < cumulative || i == groups.size() - 1) {
                String groupName = (String) g.get("name");
                if (store != null && expName != null) {
                    store.set(uid, expName, new StickyEntry(groupName, salt8));
                }
                return ExpStanding.group(groupName, mergeParams(paramDefaults, g.get("params")));
            }
        }
        return ExpStanding.OUT;
    }

    /** Evaluate a gate by name out of the flags blob ({@code false} when absent). */
    @SuppressWarnings("unchecked")
    private static boolean evalGateByName(Map<String, Object> flagsBlob, String name, Map<String, Object> user) {
        Map<String, Object> gates = flagsBlob == null ? null : (Map<String, Object>) flagsBlob.get("gates");
        Map<String, Object> gate = gates == null ? null : (Map<String, Object>) gates.get(name);
        return gate != null && evalGate(gate, user);
    }
}
