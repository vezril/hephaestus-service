# Tasks — add-e2e-integration

An opt-in end-to-end integration test — no production code changes. It wires the REAL worker
(`JobConsumer` + `MediaPipeline` + `ApolloClient` + `HermesResultPublisher`) to testcontainers
running the published Apollo + Hermes images. Everything is in the `server` test scope + CI; there
is no `core` work.

**Isolation:** this change lives in the `feat/add-e2e-integration` git worktree
(`/Users/cference/Code/hephaestus-e2e`) — a concurrent session shares the main tree. Do all git work
in the worktree; stage explicit paths (never `git add -A`).

**CI-verified:** the `server` module + testcontainers can't compile/run locally (no read:packages
token; and multi-container boot is heavy) — **the E2E CI job is the gate.** Iterate there.

Before starting: consult `/Users/cference/Code/claude-toolkit`. Read apollo-storage's testcontainers
IT setup (`com.dimafeng` usage, its `-Dit=true` gating) and both services' `docker-compose.yml` for
the exact env/ports; read the §1 `ApolloClient`, §3 `JobConsumer`/`HermesClient` usage, §4
`HermesResultPublisher`.

## 1. Build + tier gating

- [x] 1.1 Add `com.dimafeng %% testcontainers-scala-scalatest` (+ testcontainers `postgresql`/generic
      as needed) to the `server` Test scope, versioned to match apollo-storage.
- [x] 1.2 Add a ScalaTest tag `E2E`; exclude it from the default `Test / test` unless `-De2e=true`
      (mirror apollo's `-Dit` pattern). Confirm the default `sbt test` still skips it (fast PR CI unchanged).
      Verified via `show server/testOptions`: default carries `-l me.cference.hephaestus.e2e.E2E`; `-De2e=true` clears it.

## 2. Container harness (`server` test)

- [x] 2.1 Bring up `postgres:16-alpine` + `calvinference/apollostorage` on a shared network — env per
      compose (`POSTGRES_*`, `HTTP_PORT`, `GRPC_PORT`, single-node cluster: `CLUSTER_MIN_MEMBERS=1`),
      wait for `/health` UP with a generous timeout; expose the mapped gRPC port. (`ConstellationContainers`.)
- [x] 2.2 Bring up `postgres:16-alpine` + `calvinference/hermesmq` — env per compose, wait for
      `/health/ready`; expose the mapped HTTP port. NOTE: schema is NOT mounted — hermesmq self-migrates
      (`migrate-on-start=true` default), so the mount the spec suggested is unnecessary (documented deviation).
- [x] 2.3 A harness that builds the real `ApolloClient` + `HermesClient` from the mapped ports and a
      real `AppConfig`, and starts the real `JobConsumer` with the real `MediaPipeline` +
      `HermesResultPublisher`. Reuse ffmpeg/libvips (CI installs them). (`MediaWorkerE2ESpec.beforeAll`.)

## 3. The happy-path e2e (`E2E`-tagged)

- [x] 3.1 Generate a real original (ffmpeg lavfi → jpeg), compute its md5, create the media bucket,
      upload it to Apollo (`ApolloClient.writeDerivative`/PutObject at `originals/<md5[0:2]>/<md5>.jpg`).
- [x] 3.2 Create the `media.ingest` + `media.processed` topics + subscriptions; publish a
      `ProcessMediaJob` (canonical-JSON) referencing the uploaded original to `media.ingest`.
- [x] 3.3 Start the worker; **assert** (with an eventually/timeout): the thumbnail (and sample)
      derivatives exist in Apollo at `derivatives/<md5[0:2]>/<md5>/…` (read back via HEAD), AND
      a `MediaProcessed` is pulled from `media.processed` carrying the ids, metadata, phash, derivative
      refs, and spec version. PASSED in CI (run 29053440662).

## 4. The failure-path e2e (`E2E`-tagged)

- [x] 4.1 Upload a **corrupt/unsupported** original + publish its job; **assert** a `MediaFailed`
      (`retriable = false`) is published to `media.failed` and no derivative is written. PASSED in CI.

## 5. CI + docs

- [x] 5.1 `.github/workflows/e2e.yml`: push (this branch) + `workflow_dispatch` + nightly `schedule`;
      Docker available; `LEXICON_TOKEN` env; apt `ffmpeg`/`libvips-tools`; run
      `sbt -De2e=true server/testOnly ...MediaWorkerE2ESpec`. Green on first run (29053440662, 2m8s).
- [x] 5.2 (Optional) `docker-compose.e2e.yml` + README note for running the loop locally.
- [x] 5.3 Confirm the default PR `ci.yml` is unchanged and still fast/green (E2E excluded).
