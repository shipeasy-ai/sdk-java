# Changelog

## Unreleased

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
