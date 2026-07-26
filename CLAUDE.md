# CLAUDE.md — ai.shipeasy:shipeasy (Java)

Guidance for AI agents (and humans) working in this repository.

## What this is

The **server** Java SDK for [Shipeasy](https://shipeasy.ai): feature flags,
dynamic configs, kill switches, A/B experiments, metric tracking, `see()` error
reporting, and SSR/i18n helpers. Server-key only; never embed in a client app.
Source under `src/main/java/ai/shipeasy/`; tests under `src/test/java/` (JUnit 5,
`mvn test`). The OpenFeature provider lives in `ai.shipeasy.openfeature`.

## The documented public surface (this is a contract)

Users are taught exactly **two** things, and the docs must never drift from them:

1. **`Shipeasy.configure()`** — and its siblings `Shipeasy.configureForTesting()` /
   `Shipeasy.configureForOffline()` — for setup.
2. **`new Client(user)`** — the cheap, user-bound handle for *all* reads
   (`getFlag` / `getFlagDetail` / `getConfig` / `getKillswitch` / `track`, plus
   universe assignment via `universe(name).assign()`).

Plus the package-level statics on `Shipeasy` that let users avoid the heavyweight
object: `overrideFlag` / `overrideConfig` / `overrideExperiment` /
`clearOverrides`, `onChange`, `bootstrapScriptTag` / `i18nScriptTag` /
`devtoolsScriptTag`, the
global-form `ShipeasyProvider()` (OpenFeature), and the `See.see()` family.

**The `Engine` class is an internal detail. Do NOT document it.** It stays public
for advanced/back-compat use, but no page, snippet, skill, or the README should
tell a user to construct or call an `Engine`. New user-facing capability should
get a `configure`-style or `Shipeasy.*` static affordance, then be documented
through that.

## HARD RULE: change the SDK → update the docs in the SAME change

`docs/` is the published, user-facing source of truth (rendered at
<https://shipeasy-ai.github.io/sdk-java/> and ingested by the Shipeasy CLI/MCP
`docs` tooling and the central docs portal). Any change to the SDK's **public API
or behaviour** updates the relevant `docs/pages/*.md`, the matching
`docs/snippets/**`, and `docs/skill/SKILL.md` in the same commit; new
page/snippet/placeholder → also `docs/manifest.json`. See
[`docs/CLAUDE.md`](docs/CLAUDE.md).

**`README.md` is generated — do not hand-edit it.** It is assembled from the docs
by the JDK-only single-file program `tools/GenReadme.java` (which also re-syncs the
embedded `src/main/resources/shipeasy-skill/SKILL.md`). After editing `docs/`, run:

```bash
java tools/GenReadme.java
```

CI (`.github/workflows/tests.yml`) re-runs it and fails if `README.md` or the
embedded skill drifts.

## Versioning & release

- Bump **both** the `<version>` in `pom.xml` and `Engine.VERSION`
  (`src/main/java/ai/shipeasy/Engine.java`, sent on every `see()` event), and add a
  `CHANGELOG.md` entry.
- Publishing is **push-to-`main`** (Maven Central via the publish workflow, which
  **gracefully skips** without the `CENTRAL_*`/`GPG_*` secrets — a green run there
  does not guarantee the artifact landed). A version-bumped push to `main` is the
  release trigger.

## Checks before you commit

- `mvn test` (the suite is hermetic — no network). CI runs JDK 11/17/21.
- New public behaviour ships with a test.
- Docs updated per the hard rule; `docs/manifest.json` stays valid JSON and every
  path it lists exists.
- `java tools/GenReadme.java` and commit the result (CI checks it's in sync).
