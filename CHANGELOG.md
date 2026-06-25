# Changelog

## 0.8.0

- **BREAKING — `Client` is now the lightweight, user-bound handle; the
  heavyweight client is renamed to `Engine`.** The class that owns the API key,
  HTTP, the blob cache, the poll timer, overrides, `track`, `see()` and the
  `forTesting`/`fromFile`/`fromSnapshot` factories is now `ai.shipeasy.Engine`
  (was `ai.shipeasy.Client`). Its public surface is otherwise unchanged — only
  the name. The `see()` default-engine wiring (last-constructed wins) now hooks
  off `Engine` construction via `See.setDefaultEngine(Engine)` (was
  `setDefaultClient`). Migration: replace `new Client(...)`/`Client.forTesting()`
  /`Client.fromSnapshot(...)` with the `Engine` equivalents, and
  `See.setDefaultClient` with `See.setDefaultEngine`.

- **New global `Shipeasy.configure(...)` + user-bound `new Client(user)`.** The
  primary flow is now two calls:

  ```java
  Shipeasy.configure(System.getenv("SHIPEASY_SERVER_KEY"));   // once, at startup
  boolean on = new Client(user).getFlag("new_checkout");      // per user/request
  ```

  - `Shipeasy.configure(String apiKey)` / `Shipeasy.configure(Shipeasy.Options)`
    builds **one** global `Engine` (first-config-wins idempotent), registers it
    as the `see()` default, and kicks off the one-shot fetch fire-and-forget.
    `Shipeasy.options(apiKey)` is a small builder (`.baseUrl`, `.env`,
    `.disableTelemetry`, `.attributes`).
  - `Shipeasy.Options.attributes(Function<Object, Map<String,Object>>)` is an
    optional transform from your own user object (any shape) to the Shipeasy
    attribute map. Default = identity (the user object IS the attribute map). The
    transform runs once, in the `Client` constructor.
  - The new lightweight `ai.shipeasy.Client` (`public Client(Object user)`)
    reads the global engine (throws `IllegalStateException` if `configure` was
    not called), applies the attributes transform once, binds the resulting
    attribute map, and exposes **user-argument-free** `getFlag(name)` /
    `getFlag(name, default)` / `getFlagDetail(name)` / `getConfig(name[,
    default])` / `getExperiment(name, defaultParams)` / `getKillswitch(name[,
    switchKey])`. It owns no HTTP/cache/timer — it forwards to the global engine
    with the bound attrs. Request-scoped `anonymous_id` (from `AnonIdFilter`/
    `AnonId`) is still merged by the engine at evaluation time.

- **New `Engine.getKillswitch(name[, switchKey])`** reading the `killswitches`
  blob (`{killed, switches}`) — whole-killswitch killed state, or a named
  per-key switch. `Engine.evaluate(user)` now emits the real `killswitches` map
  (boolean, or per-switch map) instead of an empty map, matching the browser
  bootstrap shape. The `VERSION` constant is corrected to track the published
  version.

## 0.7.0

- **SSR bootstrap script-tag helpers.** New `Client.evaluate(user)`
  batch-evaluate (every gate/config/experiment → a `{flags, configs,
  experiments, killswitches}` payload) plus `bootstrapScriptTag` and
  `i18nScriptTag` overloads, which emit the cross-platform declarative `<script>`
  tags carrying the SSR payload as `data-*` attributes. The static
  `se-bootstrap.js` loader hydrates `window.__SE_BOOTSTRAP` and writes the
  `__se_anon_id` cookie so the browser buckets identically to the server. **No
  SDK key is embedded** in the bootstrap tag.

## 0.6.0

- **`see()` structured error reporting.** Added the `see()` grammar mirroring
  `@shipeasy/sdk` (TS) and the Python SDK. Every handled exception documents its
  product *consequence*, not just its stack. Instance methods on `Client`:
  `see(Object problem)`, `seeViolation(String name)` and
  `controlFlowException(Throwable e)`; a package-level static facade `ai.shipeasy.See`
  (`See.see`, `See.violation`/`See.seeViolation`, `See.controlFlowException`)
  dispatches against the last-constructed `Client` (registered automatically in
  its constructor; `See.setDefaultClient(Client)` is the explicit setter). A
  global call before any client logs a warning and no-ops — it never throws.
  The fluent chain `see(e).causesThe("checkout").extras(Map.of(...)).to("use cached prices")`
  uses `to(outcome)` as the terminal (builds the wire event and fire-and-forgets
  the POST to `/collect` on the same scheduler as `track()`); `causesThe()` and
  `extras()` may be called in any order before `to()`, `extras()` merges, and
  `to()` is idempotent. `controlFlowException(e).because(reason)` marks the
  throwable expected (queryable via `ControlFlowChain.isExpected(e)`) and reports
  nothing. Events are `{type:"error", kind, error_type, message, stack?, subject,
  outcome, extras?, side:"server", env?, sdk_version, ts}` with the documented
  sanitization (≤20 keys, 200-char string values, drop null, keep
  String/finite-Number/Boolean), a per-process spam limiter (30s dedup, 25 cap),
  and private-attribute stripping. No-op in test/local mode like `track()`. Added
  `Client.VERSION` (`"0.6.0"`) as the single runtime source of `sdk_version`.

## Unreleased

- **OpenFeature provider.** Added `ai.shipeasy.openfeature.ShipeasyProvider`, an
  implementation of the OpenFeature server `dev.openfeature.sdk.FeatureProvider`
  contract that wraps a `Client`. Metadata name is `shipeasy`;
  `initialize()` calls `client.initOnce()`. Boolean evaluation routes to
  `getFlagDetail` and maps the Shipeasy reason onto OpenFeature
  (`RULE_MATCH→TARGETING_MATCH`, `DEFAULT→DEFAULT`, `OFF→DISABLED`,
  `OVERRIDE→STATIC`, `FLAG_NOT_FOUND→ERROR`+`FLAG_NOT_FOUND`,
  `CLIENT_NOT_READY→ERROR`+`PROVIDER_NOT_READY`); on an error code the default
  is returned. String/integer/double/object evaluation route to `getConfig`:
  absent → default with reason `DEFAULT`, wrong type → default with errorCode
  `TYPE_MISMATCH`, present + right type → value with reason `TARGETING_MATCH`.
  `targetingKey` becomes `user_id` and other context attributes are carried
  through for targeting. `dev.openfeature:sdk` is a `provided`-scope dependency
  — the consuming OpenFeature app supplies it, so it adds nothing to
  non-OpenFeature consumers. Mirrors the canonical
  `@shipeasy/sdk/openfeature-server` provider.
- **Private attributes.** Added `Client.privateAttributes(List<String>)` — a
  fluent setter naming attributes usable for targeting but never persisted in
  analytics (LD/Statsig `privateAttributes`). The server evaluates locally so
  private attrs never leave for evaluation at all; the only egress is
  `/collect`, and the listed keys are stripped from every outbound `track()`
  payload before it is POSTed. Mirrors `stripPrivate` in the canonical TS SDK.
- **Manual exposure (server).** Added `logExposure(String userId, String
  experimentName)` and an overload `logExposure(Map<String,Object> user, String
  experimentName)`. The server never auto-logs: this re-evaluates the experiment
  and, only when the user is enrolled, POSTs one
  `{type:"exposure", experiment, group, user_id, ts}` event to `/collect`. No-op
  in test mode or when the user isn't enrolled. Parity with the browser's
  auto-exposure.
- **Sticky bucketing (server).** Added a pluggable `StickyBucketStore`
  (`Map<String,StickyEntry> get(String unit)` / `void set(String unit, String
  exp, StickyEntry entry)`), the value type `StickyEntry(group, salt8)`, and an
  in-memory built-in `InMemoryStickyStore`. Supply one via
  `Client.stickyStore(store)`; absent ⇒ deterministic (fully backward
  compatible). Threaded into experiment eval after the holdout, before
  allocation: a stored entry for `(unit, exp)` whose `salt8` matches the
  experiment salt's 8-char prefix skips the allocation gate and returns the
  stored group with no re-pick (so a shrinking allocation keeps enrolled units
  in); a fresh pick is persisted; a salt mismatch or a missing stored group
  re-buckets and overwrites. Bucketing unit is the `pickIdentifier`-resolved
  identifier (honors `bucketBy`). Mirrors `StickyBucketStore` /
  `createInMemoryStickyStore` in the canonical TS SDK.
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
