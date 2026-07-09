# Change: add-e2e-integration

## Why

§1–§4 are each tested against **in-process stubs** (a gRPC `ObjectApi` stub for Apollo, a fake
`MessageSource` for Hermes) plus real ffmpeg/libvips. That proves each seam, but nothing yet proves
the **whole worker against the real brokers** — that a `ProcessMediaJob` published to a real
HermesMQ, read from a real ApolloStorage, actually flows end-to-end to derivatives-in-Apollo and a
`MediaProcessed`-on-the-topic. This change adds that verification: the real `JobConsumer` +
`MediaPipeline` + `ApolloClient` + `HermesResultPublisher` wired to **testcontainers** running the
published Apollo + Hermes images.

## Decisions

| Concern | Choice |
|---------|--------|
| Harness | **testcontainers-scala** (`com.dimafeng %% testcontainers-scala-scalatest`, matching apollo-storage's IT deps) orchestrating 4 containers: `postgres:16-alpine` + `calvinference/apollostorage` and `postgres:16-alpine` + `calvinference/hermesmq` (schema-init mounted), on a shared Docker network. |
| Worker | Run the **real worker in-process** (not a 5th container) — pointed at the containers' mapped gRPC (Apollo) + HTTP (Hermes) ports — so the test exercises this repo's actual code, and real ffmpeg/libvips run locally/in-CI. |
| Tier | **Opt-in, not default CI** — tag these tests (e.g. `E2E`) and exclude them from the default `sbt test` (mirrors apollo's `-Dit=true` PostgresIT exclusion). They pull multi-image + boot a Pekko cluster, so they must not gate every PR on weight/flakiness. |
| CI | A **dedicated workflow/job** runs the E2E on demand (manual `workflow_dispatch` + optional nightly `schedule`), with Docker (GitHub runners have it), the `LEXICON_TOKEN`, and apt `ffmpeg`/`libvips-tools`. The fast PR CI is unchanged. |
| Apollo boot | Apollo forms a **cluster-of-one** via Pekko management self-discovery — configure it for reliable single-node boot (`CLUSTER_MIN_MEMBERS=1`, self contact-point) and gate readiness on `/health` UP with a generous startup timeout. If bootstrap proves flaky in testcontainers, document the limitation and fall back to a longer wait/retry. |
| Fixtures | Generate a tiny real original at test time (ffmpeg lavfi → jpeg/mp4), upload it to Apollo, so no binaries are committed. |

## What Changes

- **end-to-end** (new capability): an opt-in integration test that, against real Apollo + Hermes:
  creates the media bucket + uploads a real original to Apollo; creates the `media.ingest` /
  `media.processed` topics + subscriptions in Hermes; publishes a `ProcessMediaJob`; runs the real
  `JobConsumer`; and **asserts** the derivatives land at `derivatives/<md5[0:2]>/<md5>/…` in Apollo
  (read back) and a well-formed `MediaProcessed` is published to `media.processed` (pulled + decoded).
  A terminal-failure variant asserts `MediaFailed` on `media.failed`.

- **Build**: add `testcontainers-scala-scalatest` (+ a generic-container / network helper) to the
  `server` test scope; a ScalaTest tag to gate the E2E tier; a `docker-compose.e2e.yml` (optional)
  for local manual runs.
- **CI**: a new `e2e.yml` workflow (`workflow_dispatch` + nightly), Docker + `LEXICON_TOKEN` + apt
  media tools, running `sbt -De2e=true server/test` (or the tagged suite). No change to `ci.yml`.

## Impact

- Affected specs: `end-to-end` is **ADDED** (a verification capability — it asserts the composed
  behavior of `apollo-io` + `media-processing` + `job-consumption` + `result-reporting`).
- Affected code: new `server/src/test` E2E suite + testcontainers wiring + tag; `build.sbt` test
  deps + tag exclusion; `.github/workflows/e2e.yml`; optional `docker-compose.e2e.yml`.
- Dependencies (all released): the `calvinference/apollostorage` + `calvinference/hermesmq` images
  on Docker Hub; `hermesmq-client 1.4.0`; the §1–§4 code. Docker required to run (CI runners + local).
- Out of scope: the Codex k8s **deploy** (§5.2 — separate repo/track); load/soak testing; TLS/auth
  to Apollo (cleartext h2c in the test); multi-node Apollo/Hermes.
- Note: authored in an isolated git worktree (`feat/add-e2e-integration`) because a concurrent
  session (`add-structured-logging`) shares the main working tree.
