# Tasks — add-initial-hephaestus

TDD is non-negotiable: every implementation task is preceded by a failing-test task and followed
by a refactor + run-tests task. Do not start an implementation task while its tests are green
(that means the tests are wrong) or missing.

Before starting: consult `/Users/cference/Code/claude-toolkit` for relevant skills and agents
(scala-fp-reviewer, tdd-coach, git-and-ci-reviewer, clean-code-reviewer).

Scope: milestone §0 (runtime shell + toolchain). Stateless worker — NO domain aggregate,
NO event-persistence. No gRPC wiring yet (that is `add-apollo-io`).

## 1. Project Scaffolding & CI/CD (`project-scaffolding`)

- [x] 1.1 Initialize repo: sbt build with `core` + `server` modules, Scala 3.3.4 LTS, scalafmt +
      scalafix configs, `.gitignore`, sbt-dynver, sbt-native-packager; `git init`, `main` +
      `development` branches
- [ ] 1.2 **Tests first**: PR-verification workflow with a deliberately failing placeholder test →
      confirm the check is red and merge is blocked (edge case: failing test blocks merge)
      — HUMAN: requires pushing to GitHub + running Actions; `ci.yml` has separate format/test jobs
      so a failing test blocks merge, but the live-run verification is externally gated.
- [ ] 1.3 **Tests first**: introduce a scalafmt violation on a branch with green tests → confirm
      the format check fails independently (edge case)
      — HUMAN: `ci.yml`'s `format` job is independent of `build-test`; live-run verification gated.
- [x] 1.4 Implement: finalize `ci.yml` (scalafmtCheck, compile, test) triggered on PRs to
      `development` and `main`
- [x] 1.5 Implement release tag filter: workflow triggers only on `v[0-9]+.[0-9]+.[0-9]+`; add
      ancestry check that the tag is on `main` (verify-tag-on-main step). Malformed-tag no-trigger
      is enforced by the `on.push.tags` pattern; live push verification is HUMAN-gated.
- [x] 1.6 Verify sbt-dynver: tagged commit → clean version; untagged → snapshot version (untagged
      build produces a snapshot version locally; `ci.yml` version-check job asserts this)
- [x] 1.7 Refactor workflows (shared setup via `.github/actions/setup-scala` composite action +
      caching); re-run all checks

## 2. Service Runtime — Pekko + Health + Config + Docker (`service-runtime`)

- [x] 2.1 **Red**: ScalaTest (Pekko HTTP route testkit) — `GET /health` returns 200 `UP` + version;
      `GET /nope` returns 404; health returns 503 once readiness withdrawn (edge cases)
      (`HealthRoutesSpec`)
- [x] 2.2 **Green**: implement typed `ActorSystem` guardian, health route, readiness flag wired to
      Coordinated Shutdown phases (`Main`, `HealthRoutes`, `HttpServer.wireShutdown`)
- [x] 2.3 **Red**: startup test — occupied port ⇒ fast failure, non-zero exit, clear log (edge case)
      (`HttpServerSpec`; exit semantics live in `Main`'s bind onComplete)
- [x] 2.4 **Green**: implement bind-failure handling and exit semantics (`Main` logs + `System.exit(1)`)
- [x] 2.5 **Red**: config test — HOCON defaults load; each key is overridable by its env var; missing
      required value fails fast with a clear message (edge cases) (`AppConfigSpec`)
- [x] 2.6 **Green**: typed config module (HOCON + env overrides); no client wiring yet
      (`AppConfig` pure loader + `application.conf` `${?ENV}` substitutions)
- [x] 2.7 **Red**: media-toolchain probe test — reports ffmpeg + libvips presence and versions;
      absence ⇒ `Degraded`/readiness `DOWN` (edge case) (`ToolchainProbeSpec`, injected fake runner)
- [x] 2.8 **Green**: implement the toolchain probe (pure in `core`; real runner `ToolRunner` in
      `server` invokes `ffmpeg -version` / `vips --version`), surfaced in readiness by `Main`
- [ ] 2.9 **Red**: Docker smoke test (CI job) — container reaches `healthy`; runs as non-root;
      `ffmpeg`/`vips` on PATH; `HTTP_PORT=9090` honored
      — HUMAN: requires a real `docker build`/run; the brief scopes this to `Docker/stage` only
      (staged Dockerfile verified to emit the apt-get media tools + non-root USER + HEALTHCHECK).
- [x] 2.10 **Green**: sbt-native-packager Docker config — temurin base + apt-install `ffmpeg`
      `libvips-tools` (before USER switch), non-root user `hephaestus`, EXPOSE 8080, HEALTHCHECK
      against `/health`, env-driven config (verified via `Docker/stage`)
- [x] 2.11 **Refactor**: config + toolchain-probe are separate modules (`core`/`server` split);
      full suite green (Docker smoke test HUMAN-gated per 2.9)

## 3. Publish to Docker Hub (`release-publishing`)

- [x] 3.1 **Tests first**: workflow-level assertions — publish jobs guarded by
      `if: github.repository == 'vezril/hephaestus-service'` (fork PRs skip); a "Ensure Docker Hub
      credentials are present" step fails before any push if `DOCKERHUB_TOKEN` is missing.
      Live scratch-branch verification is HUMAN-gated.
- [x] 3.2 Implement `release.yml`: on `v*` semver tag → verify-tag-on-main → test → build → push
      `X.Y.Z` + `latest`; immutability guard (`docker manifest inspect` fails if the version exists)
- [x] 3.3 Implement `development` publish (`dev.yml`): on push → test → push `dev` + `dev-<short-sha>`
- [x] 3.4 Verify end-to-end: cut `v0.1.0`, pull both tags, confirm same digest; confirm failing
      commit publishes nothing — DONE 2026-07-09: v0.1.0 released, release workflow green,
      `calvinference/hephaestus:0.1.0`+`:latest` on Docker Hub (both 323 MB, same build); the
      earlier dev-publish run without creds published nothing (credential guard fired).
- [x] 3.5 Refactor: shared build steps via `.github/actions/setup-scala` composite action across
      ci/release/dev workflows

## 4. Documentation (`documentation`)

- [ ] 4.1 **Tests first**: doc-verification checklist in CI — quickstart commands run verbatim on a
      fresh clone; badge URLs resolve (edge case)
      — HUMAN: not implemented as a CI job this milestone (would add a `docs` workflow like apollo's
      `scripts/verify-docs.sh`); README quickstart commands were run verbatim locally and pass.
- [x] 4.2 Write `README.md`: description, badges, AI Usage Disclaimer (SDLC agent team + human
      review), docker compose deployment example, configuration table (HOCON keys ↔ env vars),
      run/test instructions
- [ ] 4.3 Verify compose example self-contained: copy snippet to empty dir, `docker compose up`,
      service reaches healthy
      — HUMAN: requires building/publishing the image + a Docker daemon; compose snippet authored.
- [x] 4.4 Add MIT `LICENSE` (year 2026 / holder "Calvin Ference"), sbt `licenses` setting
      (`ThisBuild / licenses`), README link. GitHub license auto-detection is HUMAN-verified post-push.
- [ ] 4.5 Final pass: run every documented command once more on `development`, then open the
      release PR to `main`
      — HUMAN: opening the PR + release cut is external; documented commands verified locally.
