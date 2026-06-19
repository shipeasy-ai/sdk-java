# Changelog

## Unreleased

- **Per-experiment `bucketBy`.** Experiment evaluation now honors an optional
  `bucketBy` attribute (JSON `bucketBy`), bucketing on that user attribute (e.g.
  `company_id` to keep a whole org on one variant) instead of the individual.
  When set and the attribute is a non-empty string (or a number, stringified)
  it drives the holdout, allocation, and group hashes so all three agree;
  otherwise it falls back to `user_id ?? anonymous_id`. Mirrors `pickIdentifier`
  in the canonical core implementation.
- **Default values on `getFlag`/`getConfig`.** Added overloads
  `getFlag(name, user, defaultValue)` and `getConfig(name, defaultValue)`. The
  default is returned only when the value cannot be resolved (client not
  initialized, or the key is absent) — never when a flag evaluates to `false`.
- **Evaluation detail.** Added `FlagDetail` (`value()`/`reason()`) with reason
  constants (`OVERRIDE`, `CLIENT_NOT_READY`, `FLAG_NOT_FOUND`, `OFF`,
  `RULE_MATCH`, `DEFAULT`) and `getFlagDetail(name, user)`. The reason is
  computed at the SDK boundary without modifying the canonical `Eval.evalGate`;
  the "gate" usage beacon fires exactly once per call and never on `OVERRIDE`.
  `getFlag(name, user)` now delegates to `getFlagDetail(...).value()`.
- **Change listeners.** Added `onChange(Runnable)` returning a cancel
  `Runnable`. Listeners fire after a background poll applies new data (200, not
  304); never in local/test/snapshot mode. Each listener is invoked in a
  try/catch (logged on throw) and stored in a `CopyOnWriteArrayList`.
- **Offline file data source.** Added `Client.fromFile(path)` and
  `Client.fromSnapshot(flags, experiments)` — no-network clients backed by a
  captured `{ "flags": ..., "experiments": ... }` snapshot. Evaluations run the
  real eval against the snapshot; `override*` values apply on top.
- **Local-override test utility.** Added `Client.forTesting()` — a no-network,
  immediately-usable client (telemetry disabled, `init()`/`initOnce()`/`track()`
  are no-ops, no API key required). New override setters (also usable on a normal
  client) seed values for tests: `overrideFlag(name, value)`,
  `overrideConfig(name, value)`, `overrideExperiment(name, group, params)`, and
  `clearOverrides()`. An override always wins in `getFlag`/`getConfig`/
  `getExperiment`; `overrideExperiment` makes `getExperiment` return
  `new ExperimentResult(true, group, params)`. Overrides are stored in
  `ConcurrentHashMap`s to match the volatile-blob concurrency model.

## 0.3.0

- **Anonymous bucketing (`__se_anon_id`).** Added `AnonIdFilter`, a servlet
  `Filter` that mints the shared `__se_anon_id` first-party cookie for any
  request without one, and `AnonId` framework-agnostic primitives. Gate/
  experiment evaluations now default to the cookie id as `anonymous_id` (via a
  request `ThreadLocal`), so anonymous visitors bucket consistently across
  server renders and the browser with no per-call wiring. `jakarta.servlet-api`
  is a `provided` dependency (your container supplies it at runtime). Implements
  the cross-SDK contract in `18-identity-bucketing.md`.
- **Eval fix (no-unit gate rule).** A request with no `user_id`/`anonymous_id`
  now resolves a fully-rolled (100%) gate as **on** instead of always off; a
  fractional gate is still off until a stable unit exists. Matches the
  TypeScript reference SDK. Targeting rules are still evaluated first.

## 0.2.0

- Per-evaluation usage telemetry (fire-and-forget, on by default).

## 0.1.0

- Initial release: feature flags, configs, experiments, metric tracking.
