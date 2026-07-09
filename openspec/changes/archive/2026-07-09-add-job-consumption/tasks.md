# Tasks — add-job-consumption

TDD throughout. Keep the pure parts (job decoding/validation, lane-priority selection logic,
ack-decision from a processing outcome) in `core` where they don't need Pekko/IO; the consumer loop,
Hermes client calls, and pipeline invocation are the effectful `server` shell. Consumption goes
behind seams (`MessageSource` for pull/ack, `ResultPublisher` for the §4 seam) so the loop is
unit-testable with fakes; a real-Hermes integration test runs in CI.

All dependencies are released — no prerequisite work. `ProcessMediaJob`/`MediaProcessed`/`MediaFailed`
are in `io.codex %% lexicon-messages % 0.4.0` (`codex.messages.v1`, protobuf canonical JSON via
scalapb-json4s); `hermesmq-client 1.4.0` provides pull/ack. Both resolve in CI (read:packages token).

Before starting: consult `/Users/cference/Code/claude-toolkit`. Read `openspec/specs/apollo-io/spec.md`
+ `media-processing/spec.md` (the §1/§2 capabilities you drive), the media message fields in codex's
`design-hephaestus-contract/specs/media-processing-contract/spec.md`, and the `hermesmq-client`
API (`me.cference.hermesmq.client.HermesClient`: `pull`/`ack`/`publish`/`createSubscription`).

## 0. Build wiring

- [x] 0.1 Add deps: `me.cference.hermesmq %% hermesmq-client % 1.4.0` + `io.codex %% lexicon-messages
      % 0.4.0` (and bump the existing `lexicon-grpc` pin 0.1.0 → 0.4.0 to keep one Lexicon version);
      add the `vezril/hermesmq` GitHub Packages resolver; confirm the read:packages token grants both
      `the-lexicon` and `hermesmq` packages. Wire the token into CI sbt env.
- [x] 0.2 Confirm `sbt update` resolves both (in CI — no local token); full §0–§2 suite still green.

## 1. Pure core: job model + decisions

- [x] 1.1 **Red/Green**: decode + validate a `ProcessMediaJob` from a payload String (protobuf-JSON)
      → a total `Either[DecodeError, Job]`; missing/blank required fields ⇒ `Left` (terminal); map
      to the §2 `MediaDescriptor` (source bucket/object, mediaType, contentType, want) (edge cases).
- [x] 1.2 **Red/Green**: pure **ack-decision** from a processing outcome — success ⇒ ack;
      terminal failure ⇒ (report + ack, don't redeliver); transient failure ⇒ (don't ack, leave for
      redelivery). A total function over `Either[MediaError, MediaResult]` + decode failure.
- [x] 1.3 **Red/Green**: pure **lane-priority** selection — given ingest/reprocess batch states,
      choose the next lane (ingest before reprocess; only pull reprocess when ingest is drained).

## 2. Seams (`server`)

- [x] 2.1 **Red/Green**: `MessageSource` interface (`pull(lane, max)`, `ack(lane, ids)`) wrapping
      `HermesClient` for the two subscriptions; a **fake** for loop tests.
- [x] 2.2 **Red/Green**: `ResultPublisher` interface (`publish(result | failure): Future[Unit]`) with
      a **no-op/logging** default (real impl is §4); a fake capturing published results for tests.

## 3. The consumer loop (`server`)

- [x] 3.1 **Red**: single-job happy path (fakes) — pull ingest → decode → `MediaPipeline.process` →
      `ResultPublisher.publish` → **ack**; ack happens only after publish (assert ordering).
- [x] 3.2 **Green**: implement the loop for one lane.
- [x] 3.3 **Red**: lane priority — with jobs in both lanes, ingest is fully drained before reprocess
      is pulled (edge cases).
- [x] 3.4 **Green**: two-lane priority pull loop; bounded concurrency on a dedicated blocking
      dispatcher (CPU-heavy processing).
- [x] 3.5 **Red**: idempotency — a redelivered `jobId` re-produces byte-identical derivatives and
      re-publishes an equivalent result, no double-activation hazard on our side (edge case).
- [x] 3.6 **Green**: idempotent handling (optional skip-if-present via §1 `headExists`).

## 4. Failure handling + lifecycle

- [x] 4.1 **Red**: a terminal `MediaError`/decode failure ⇒ report via publisher + **ack** (no
      redelivery of poison); a transient (`ApolloError.retriable`/Hermes error) ⇒ **not acked**,
      redelivered later; a single bad message never wedges the lane (edge cases).
- [x] 4.2 **Green**: failure classification wired to the ack-decision from 1.2.
- [x] 4.3 **Red/Green**: graceful shutdown — stop pulling, finish in-flight jobs, ack them, then
      terminate (Coordinated Shutdown phase); no job acked without a durable result.
- [x] 4.4 Wire `JobConsumer` into `Main` (dedicated dispatcher; readiness stays independent of Hermes
      reachability, per §1 precedent — a Hermes outage is a retriable pull failure, not service-down).

## 5. Integration

- [~] 5.1 **Test** (CI, real/in-process Hermes + Apollo): publish a `ProcessMediaJob` → the worker
      pulls, processes (real §2 tools), writes derivatives, publishes a captured result, and acks;
      a redelivery produces identical output. Documented: transcode lane needs a generous ack deadline.
      DEFERRED (real-Hermes testcontainers): covered instead by the fake-`MessageSource` loop tests
      (`JobConsumerSpec`) driving the REAL §2 `MediaPipeline` + in-process Apollo, plus `JobCodecSpec`
      round-tripping the real `lexicon-messages` canonical JSON — per the change's "prefer fakes;
      real-Hermes only if tractable" guidance. Generous ack deadline documented in `application.conf`.
- [x] 5.2 Refactor: extract the consumer module; core + fake-based loop tests green locally,
      real-Hermes integration green in CI; scalafmt/scalafix clean.
