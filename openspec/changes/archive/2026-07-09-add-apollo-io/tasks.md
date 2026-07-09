# Tasks — add-apollo-io

TDD throughout. Every implementation task is preceded by a failing test and followed by refactor +
run-tests. The Apollo client is effectful (gRPC IO) → it lives in the `server` module's shell, not
in pure `core`; keep byte→md5 and key-derivation helpers pure and unit-tested in `core`.

Before starting: consult `/Users/cference/Code/claude-toolkit` (scala-fp-reviewer, tdd-coach,
git-and-ci-reviewer). Read the contract: `the-lexicon/src/main/protobuf/apollostorage/grpc/
object_api.proto` and Apollo's consumer pattern in `apollo-storage/build.sbt` +
`openspec/changes/archive/2026-07-09-adopt-lexicon-grpc-contracts/`.

## 1. Build: converge on Pekko 1.2.0 + consume the Lexicon gRPC client

- [x] 1.1 Bump `build.sbt` Pekko `1.1.3 → 1.2.0`, pekko-http `1.1.0 → 1.2.0`; add `pekko-discovery`
      1.2.0 and `pekko-grpc-runtime` (1.1.1). §0 core suite verified GREEN locally on 1.2.0; the §0
      server suite (health/config/probe) verified GREEN via CI (no 1.1→1.2 breakage).
- [x] 1.2 Added the GitHub Packages resolver for `vezril/the-lexicon` + credentials
      (`LEXICON_TOKEN` → `GITHUB_TOKEN` → `~/.sbt/.credentials`); depend on
      `io.codex %% lexicon-grpc % 0.1.0`. NOTE: cannot resolve locally (no read:packages token on
      this machine) — **resolution + server compile confirmed GREEN in CI**.
- [x] 1.3 CI: `LEXICON_TOKEN` secret wired into the sbt env of `ci.yml`/`release.yml`/`dev.yml`.
      Clean CI resolve confirmed (server main + tests compiled against the artifact).

## 2. Pure helpers (`core`)

- [x] 2.1 **Red**: md5 streaming digest — folding a `Source[ByteString]` yields the correct
      lowercase-hex md5 for known vectors; empty input; multi-chunk equals single-chunk (edge cases).
      (`core/storage/Md5Spec` — verified GREEN locally with `sbt core/test`.)
- [x] 2.2 **Green**: a pure md5 accumulator usable as a Pekko Streams stage/sink helper.
      (`core/storage/Md5.scala`: pure `Md5.hex` + `Md5State` fold accumulator.)
- [x] 2.3 **Red**: derivative key derivation — `(md5, name) → derivatives/<md5[0:2]>/<md5>/<name>`;
      original key `(md5, ext) → originals/<md5[0:2]>/<md5>.<ext>`; shard is first 2 md5 chars;
      reject empty/invalid md5 (edge cases). (`core/storage/StorageKeySpec` — GREEN locally.)
- [x] 2.4 **Green**: pure key-derivation functions (total, `Either` on invalid input).
      (`core/storage/StorageKey.scala`.)

## 3. Apollo client — read original (`server`)

- [x] 3.1 **Red** (in-process stub `ObjectApiPowerApi` server, mirroring apollo-storage's
      `ObjectApiSpec`): `GetObject` returns header then `Source[ByteString]`; bytes reassemble; md5
      equals header md5. (`LexiconApolloClientSpec` — GREEN in CI.)
- [x] 3.2 **Green**: `readOriginal(bucket, object)` — `prefixAndTail(1)` splits header from chunks,
      exposes `(ObjectMetadata, Source[ByteString])`, backpressured. (`LexiconApolloClient`.)
- [x] 3.3 **Red**: md5 mismatch ⇒ terminal `ApolloError.Md5Mismatch`; truncated stream ⇒ terminal
      `ApolloError.Truncated` (both `retriable = false`). (GREEN in CI.)
- [x] 3.4 **Green**: in-stream md5+size verification via a pass-through `Md5VerifyingStage`
      `GraphStage`; fails the byte source terminally on mismatch/short-count.

## 4. Apollo client — write derivative (`server`)

- [x] 4.1 **Red**: `PutObject` client-stream — `PutHeader` then chunks; returned md5/size match; a
      repeated write is byte-identical (idempotent); a mid-stream failure commits nothing (atomic).
      (`LexiconApolloClientSpec` — GREEN in CI.)
- [x] 4.2 **Green**: `writeDerivative(bucket, key, contentType, Source[ByteString], expectedMd5) →
      PutResult`, atomic (single header-then-chunks source, commit on completion), optional
      `expected_md5`. (`LexiconApolloClient`.)
- [x] 4.3 **Red**: `headExists` present ⇒ `Some(metadata)`; absent ⇒ `None` (typed not-found, no
      leak). (GREEN in CI.)
- [x] 4.4 **Green**: `HeadObject` wrapper mapping `NOT_FOUND` → `None`.

## 5. Error classification + client wiring

- [x] 5.1 **Red**: gRPC status mapping — `UNAVAILABLE`/`DEADLINE_EXCEEDED` ⇒ retriable;
      `NOT_FOUND`/`FAILED_PRECONDITION`/`INVALID_ARGUMENT`/unclassified ⇒ terminal. Typed
      `ApolloError` ADT with `retriable: Boolean`. (`ApolloErrorSpec` — GREEN in CI.)
- [x] 5.2 **Green**: `ApolloError.classify` centralizes mapping of `StatusRuntimeException`/stream
      failures (passes an already-typed `ApolloError` through unchanged). (`ApolloError.scala`.)
- [x] 5.3 **Green**: `ApolloClient.fromConfig` builds `GrpcClientSettings` from config (host/port
      split from `apollo.endpoint`, `withDeadline`); single injectable `ApolloClient` interface
      wrapping the generated `ObjectApiClient`; wired into `Main` and released on Coordinated
      Shutdown. NOTE: readiness is deliberately NOT gated on Apollo reachability (a transient
      outage is a retriable per-job failure, not service-down) — documented in `Main`.

## 6. Integration

- [x] 6.1 **Test**: end-to-end round-trip through the in-process server — `writeDerivative` then
      `readOriginal`, md5 round-trips. Content-addressed key shape is unit-proven in
      `StorageKeySpec` (`derivatives/<md5[0:2]>/<md5>/<name>`). (GREEN in CI.)
- [~] 6.2 **Partial**: `UNAVAILABLE ⇒ retriable` is proven at the classification layer
      (`ApolloErrorSpec`); a live-unreachable-Apollo integration test and readiness gating are
      deferred (readiness gating intentionally omitted, see 5.3). No crash/lane-wedge path exists —
      failures are values (`ApolloError`), not thrown.
- [x] 6.3 Client kept in the `server` module's `apollo` package (per this change's design — the
      effectful shell, not a separate module). Unit + in-process suites GREEN in CI; `scalafmtCheckAll`
      clean. NOTE: `scalafix` not run locally (needs server compile, gated on the Lexicon token); not
      part of the CI format gate.
