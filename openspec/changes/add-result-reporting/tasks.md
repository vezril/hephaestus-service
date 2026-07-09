# Tasks — add-result-reporting

TDD throughout. The domain→proto mapping is pure but references Lexicon `codex.messages.v1` types,
so it lives in `server` (CI-tested via a canonical-JSON round-trip); keep any transport-free helpers
(the `MediaError → code` token, spec-version parse) in `core` where they're locally testable. The
publish (HermesClient + serialize) is the effectful shell.

All deps released — no prerequisite. `MediaProcessed`/`MediaFailed` are in `io.codex %%
lexicon-messages % 0.4.0`; `hermesmq-client 1.4.0` provides `publish`. Server compiles only in CI
(no local packages token) — **core local; server + mapping via green CI** (same as §1–§3).

Before starting: consult `/Users/cference/Code/claude-toolkit`. Read the §3 seam
(`server/.../job/ResultPublisher.scala`, `DecodedJob`), the §2 shapes
(`core/.../media/{MediaResult,MediaError}.scala`), and the proto at
`the-lexicon/messages/src/main/protobuf/codex/messages/v1/media.proto`.

## 1. Pure helpers (`core`)

- [x] 1.1 **Red/Green**: `MediaError → code` token — a total mapping (`unsupported_type`,
      `corrupt_input`, `tool_failed`, `plan_failed`, `upstream`, `unexpected`); exhaustive over the ADT.
- [x] 1.2 **Red/Green**: spec-version parse — `derivativeSpecVersion: String → Int` (total; a
      non-numeric value is a clear error, not a silent 0) (edge cases).

## 2. Domain → wire mapping (`server`, CI-tested)

- [x] 2.1 **Red**: `MediaResult → MediaProcessed` — job/post ids, `status = "ok"`, metadata
      (width/height/md5/filetype + optional duration/fps/hasAudio; **filesize dropped**), phash,
      derivatives (each `DerivativeRef` → `Derivative` with `ref = ObjectRef(mediaBucket, key)`,
      dims, variant/codec), `spec_version` (parsed). Assert via a **canonical-JSON round-trip**
      (`JsonFormat.toJsonString` → `fromJsonString` → fields equal) (edge cases: no-sample image,
      video with variant/codec, absent optionals).
- [x] 2.2 **Green**: the success mapping.
- [x] 2.3 **Red**: terminal `MediaError → MediaFailed` — job/post ids, `JobError(code, message)`,
      `retriable = false`; each `MediaError` case maps to its code (edge cases).
- [x] 2.4 **Green**: the failure mapping.

## 3. The publisher (`server`)

- [x] 3.1 **Red** (fake `HermesClient`/publish seam): success ⇒ publishes `MediaProcessed` JSON to
      the `media.processed` topic with `jobId`/`postId` attributes; terminal failure ⇒ publishes
      `MediaFailed` to `media.failed`; **retriable failure ⇒ does NOT publish** and returns a failed
      `Future` (so §3 leaves it unacked) (edge cases).
- [x] 3.2 **Green**: `HermesResultPublisher` implementing `ResultPublisher.publish` — match on the
      outcome, map, serialize (scalapb-json4s), publish; a Hermes publish failure surfaces as a
      failed `Future` (no ack).
- [x] 3.3 **Red/Green**: idempotent re-publish — a redelivered job re-publishes an equivalent
      `MediaProcessed` (byte-identical JSON) — safe (Artemis dedups by jobId).

## 4. Config + wiring

- [x] 4.1 **Red/Green**: config — `media.processed` / `media.failed` topic names load with defaults
      + env overrides; reuse the media bucket + Hermes endpoint.
- [x] 4.2 Wire `HermesResultPublisher` into `Main` (replace `ResultPublisher.logging`), built from
      the existing `HermesClient`; readiness unaffected (a Hermes publish outage is a retriable
      per-job failure, per §1–§3 precedent).

## 5. Integration

- [x] 5.1 **Test** (CI): drive the §3 consumer with the **real** `HermesResultPublisher` over a fake
      publish seam (or in-process capture) — a successful job publishes a well-formed `MediaProcessed`
      to `media.processed` before ack; a terminal job publishes `MediaFailed` to `media.failed`; a
      retriable job publishes nothing and is left unacked. Confirms the end-to-end loop closes.
- [x] 5.2 Refactor: extract the report module; core local-green, server + mapping green in CI;
      scalafmt/scalafix clean.
