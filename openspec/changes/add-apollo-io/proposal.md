# Change: add-apollo-io

## Why

The §0 scaffold gave Hephaestus a runtime shell, but it cannot yet touch media: it has no way to
read an original from Apollo or write a derivative back. This change adds the **Apollo I/O**
capability — a gRPC client for Apollo's object store — which every later milestone depends on
(the media pipeline reads the original here and writes its thumbnails/samples/transcodes here).

Apollo's object API (`ObjectApi`) is the constellation's single source of truth in **Lexicon**,
published as generated Scala gRPC stubs (`io.codex %% lexicon-grpc`). Consuming that client pins
Hephaestus to the same wire contract as Apollo — no drift.

## Decisions carried in from the §1 propose gate

| Decision | Choice |
|----------|--------|
| Pekko line | **Converge on Pekko 1.2.0** (Apollo + Lexicon are there). §0 was 1.1.3; this change **bumps Hephaestus to 1.2.0 / pekko-http 1.2.0**. See [[hephaestus-pekko-convergence]]. |
| gRPC client source | **Consume the pinned Lexicon artifact** `io.codex %% lexicon-grpc % 0.1.0` from GitHub Packages (resolver + `LEXICON_TOKEN`/`GITHUB_TOKEN` read:packages), exactly like Apollo's `adopt-lexicon-grpc-contracts`. No local proto vendoring. |
| Downstream consequence | hermesmq (still 1.1.3) **must be aligned to 1.2.0 before §3** (`add-job-consumption`) consumes its client — Pekko forbids a mixed-version classpath. Tracked, not done here. |
| Read framing | `GetObject` server-streaming: an `ObjectMetadata` header (md5/crc32c/size) then chunks → surfaced as a Pekko Streams `Source[ByteString]`. |
| Write framing | `PutObject` client-streaming: a `PutHeader` then chunks; Apollo returns the computed md5/crc32c/size. Writes are **atomic** (committed on stream completion) and **content-addressed**, so reprocessing overwrites byte-identically (idempotent). |
| Integrity | md5 computed while streaming the original, verified against the metadata header's md5 → **terminal fail on mismatch** (corrupt/tampered). Optional `expected_md5` sent on writes. |
| Failure classification | Apollo unreachable / timeout / transient gRPC status → **retriable**; `NOT_FOUND`, checksum mismatch, malformed → **terminal**. (Consumed by result-reporting in §4; here the client surfaces the distinction.) |

## What Changes

- **apollo-io** (new capability): a gRPC `ObjectApi` client wrapper exposing, to the rest of the
  service, two streaming operations plus a head/exists check:
  - **read original** `(bucket, object) → (ObjectMetadata, Source[ByteString])`, verifying md5
    end-to-end and failing terminally on mismatch;
  - **write derivative** `(bucket, key, contentType, Source[ByteString]) → PutResult(md5, size)`,
    atomic + content-addressed to `derivatives/<md5[0:2]>/<md5>/<name>`;
  - **head/exists** to support idempotent skip-if-present and reprocess overwrite semantics;
  - a typed error surface classifying transient (retriable) vs terminal failures.

- **Build**: bump Pekko `1.1.3 → 1.2.0` and pekko-http `1.1.0 → 1.2.0` in `build.sbt`; add the
  GitHub Packages resolver for `the-lexicon` + credentials (`LEXICON_TOKEN`/`GITHUB_TOKEN`);
  depend on `io.codex %% lexicon-grpc % 0.1.0` and `pekko-grpc-runtime`; align `pekko-discovery`
  to 1.2.0 (the generated client uses `GrpcClientSettings`). CI gains a `read:packages` token.

- **Config**: Apollo endpoint (host/port) already stubbed in §0 config is now wired to
  `GrpcClientSettings`; add the media bucket name and gRPC deadline/retry settings.

## Impact

- Affected specs: `apollo-io` is **ADDED**. Implements the storage half of
  `design-hephaestus-contract` (`derivative-storage`: `originals/<md5[0:2]>/<md5>.<ext>`,
  `derivatives/<md5[0:2]>/<md5>/<name>`).
- Affected code: `build.sbt` (Pekko 1.2.0, Lexicon dep + resolver + creds), new `apollo-io`
  client + typed errors in `server` (talks gRPC — belongs in the effectful shell, not `core`),
  config wiring, integration tests.
- Dependencies: **Lexicon `v0.1.0`** (GitHub Packages, needs a read:packages token locally + in
  CI); a running **Apollo** for integration tests (testcontainers or a mock `ObjectApi` server).
- Out of scope: the media pipeline itself (libvips/ffmpeg/phash — `add-media-pipeline`); HermesMQ
  job consumption + the hermes 1.2.0 alignment (`add-job-consumption`); `MediaProcessed`/
  `MediaFailed` publishing (`add-result-reporting`); bucket/object *listing*, range/partial reads,
  TLS/auth to Apollo (added when Apollo requires it).
