# Tasks — add-apollo-io

TDD throughout. Every implementation task is preceded by a failing test and followed by refactor +
run-tests. The Apollo client is effectful (gRPC IO) → it lives in the `server` module's shell, not
in pure `core`; keep byte→md5 and key-derivation helpers pure and unit-tested in `core`.

Before starting: consult `/Users/cference/Code/claude-toolkit` (scala-fp-reviewer, tdd-coach,
git-and-ci-reviewer). Read the contract: `the-lexicon/src/main/protobuf/apollostorage/grpc/
object_api.proto` and Apollo's consumer pattern in `apollo-storage/build.sbt` +
`openspec/changes/archive/2026-07-09-adopt-lexicon-grpc-contracts/`.

## 1. Build: converge on Pekko 1.2.0 + consume the Lexicon gRPC client

- [ ] 1.1 Bump `build.sbt` Pekko `1.1.3 → 1.2.0`, pekko-http `1.1.0 → 1.2.0`; add `pekko-discovery`
      1.2.0 and `pekko-grpc-runtime`. Run the full §0 suite — confirm still green on 1.2.0 (catch
      any 1.1→1.2 breakage in the existing health/config/probe code).
- [ ] 1.2 Add the GitHub Packages resolver for `vezril/the-lexicon` + credentials
      (`LEXICON_TOKEN` then `GITHUB_TOKEN`); depend on `io.codex %% lexicon-grpc % 0.1.0`. Confirm
      `sbt update` resolves the artifact locally (needs a read:packages token).
- [ ] 1.3 CI: pass a `read:packages` token to `sbt` in `ci.yml`/`release.yml`/`dev.yml` (a
      `LEXICON_TOKEN` secret, or reuse the built-in `GITHUB_TOKEN` if it can read the-lexicon
      packages). Verify a clean CI resolve.

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

- [ ] 3.1 **Red** (against a mock/in-memory `ObjectApi` server or testcontainer Apollo): `GetObject`
      returns the header metadata then a `Source[ByteString]`; the streamed bytes reassemble to the
      original; the computed md5 equals the header md5.
- [ ] 3.2 **Green**: `readOriginal(bucket, object)` — open the server-stream, split header from
      chunks, expose `(ObjectMetadata, Source[ByteString])`, backpressured.
- [ ] 3.3 **Red**: md5 mismatch (header says X, bytes hash to Y) ⇒ **terminal** failure, typed and
      classified non-retriable; truncated stream ⇒ terminal (edge cases).
- [ ] 3.4 **Green**: in-stream md5 verification wired into the read; terminal error on mismatch.

## 4. Apollo client — write derivative (`server`)

- [ ] 4.1 **Red**: `PutObject` client-stream — send `PutHeader` then chunks from a
      `Source[ByteString]`; the returned `PutObjectResponse` md5/size match; a re-write at the same
      content-addressed key returns byte-identical result (idempotent) (edge cases).
- [ ] 4.2 **Green**: `writeDerivative(bucket, key, contentType, Source[ByteString]) → PutResult`,
      atomic (commit on stream completion), optional `expected_md5`.
- [ ] 4.3 **Red**: `headExists(bucket, key)` — present ⇒ metadata; absent ⇒ typed not-found; used
      for skip-if-present / reprocess overwrite (edge case).
- [ ] 4.4 **Green**: `HeadObject` wrapper.

## 5. Error classification + client wiring

- [ ] 5.1 **Red**: gRPC status mapping — `UNAVAILABLE`/`DEADLINE_EXCEEDED`/connection refused ⇒
      **retriable**; `NOT_FOUND`/`FAILED_PRECONDITION` (checksum)/`INVALID_ARGUMENT` ⇒ **terminal**
      (edge cases). A typed `ApolloError` ADT with a `retriable: Boolean`.
- [ ] 5.2 **Green**: map `StatusRuntimeException`/stream failures to `ApolloError`; centralize.
- [ ] 5.3 **Green**: build `GrpcClientSettings` from config (Apollo host/port, deadline); a single
      injectable `ApolloClient` interface (so the pipeline + tests depend on the interface, not the
      generated stub). Wire lifecycle into `Main` (create on startup, part of readiness).

## 6. Integration

- [ ] 6.1 **Test**: end-to-end against a real/mock Apollo — write a derivative, read it back,
      verify md5 round-trips and the content-addressed key is exactly
      `derivatives/<md5[0:2]>/<md5>/<name>`.
- [ ] 6.2 **Test**: Apollo unreachable ⇒ retriable `ApolloError` (no crash, no lane wedge); readiness
      reflects Apollo connectivity if we choose to gate on it.
- [ ] 6.3 Refactor: extract the client module; run unit + integration suites; scalafmt/scalafix clean.
