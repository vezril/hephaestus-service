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

- [ ] 1.1 Add `com.dimafeng %% testcontainers-scala-scalatest` (+ testcontainers `postgresql`/generic
      as needed) to the `server` Test scope, versioned to match apollo-storage.
- [ ] 1.2 Add a ScalaTest tag `E2E`; exclude it from the default `Test / test` unless `-De2e=true`
      (mirror apollo's `-Dit` pattern). Confirm the default `sbt test` still skips it (fast PR CI unchanged).

## 2. Container harness (`server` test)

- [ ] 2.1 Bring up `postgres:16-alpine` + `calvinference/apollostorage` on a shared network — env per
      compose (`POSTGRES_*`, `HTTP_PORT`, `GRPC_PORT`, single-node cluster: `CLUSTER_MIN_MEMBERS=1`),
      wait for `/health` UP with a generous timeout; expose the mapped gRPC port.
- [ ] 2.2 Bring up `postgres:16-alpine` (schema-init mounted) + `calvinference/hermesmq` — env per
      compose, wait for health; expose the mapped HTTP port.
- [ ] 2.3 A harness that builds the real `ApolloClient` + `HermesClient` from the mapped ports and a
      real `AppConfig`, and starts the real `JobConsumer` with the real `MediaPipeline` +
      `HermesResultPublisher`. Reuse ffmpeg/libvips (CI installs them).

## 3. The happy-path e2e (`E2E`-tagged)

- [ ] 3.1 Generate a real original (ffmpeg lavfi → jpeg), compute its md5, create the media bucket,
      upload it to Apollo (`ApolloClient.writeDerivative`/PutObject at `originals/<md5[0:2]>/<md5>.jpg`).
- [ ] 3.2 Create the `media.ingest` + `media.processed` topics + subscriptions; publish a
      `ProcessMediaJob` (canonical-JSON) referencing the uploaded original to `media.ingest`.
- [ ] 3.3 Start the worker; **assert** (with an eventually/timeout): the thumbnail (and sample)
      derivatives exist in Apollo at `derivatives/<md5[0:2]>/<md5>/…` (read back + verify bytes), AND
      a `MediaProcessed` is pulled from `media.processed` carrying the ids, metadata, phash, derivative
      refs, and spec version.

## 4. The failure-path e2e (`E2E`-tagged)

- [ ] 4.1 Upload a **corrupt/unsupported** original + publish its job; **assert** a `MediaFailed`
      (`retriable = false`) is published to `media.failed` and no derivative is written.

## 5. CI + docs

- [ ] 5.1 `.github/workflows/e2e.yml`: `workflow_dispatch` (+ nightly `schedule`); Docker available;
      `LEXICON_TOKEN` env; apt `ffmpeg`/`libvips-tools`; run `sbt -De2e=true server/test` (or the
      `E2E`-tagged suite). Watch it green — **this is the gate.**
- [ ] 5.2 (Optional) `docker-compose.e2e.yml` + README note for running the loop locally.
- [ ] 5.3 Confirm the default PR `ci.yml` is unchanged and still fast/green (E2E excluded).
