# Change: add-job-consumption

## Why

§0–§2 gave Hephaestus a runtime, an Apollo client, and the media pipeline — but nothing drives it.
This change makes it a real worker: it **consumes `ProcessMediaJob` messages from HermesMQ**, runs
the §2 pipeline, and acknowledges a job only after its derivatives are durably written to Apollo and
its result is published. It wires the forge to the queue.

Two lanes: **`media.ingest` is drained before `media.reprocess`** (new uploads beat backfills, per
`design-artemis-reprocessing`). Consumption is idempotent per `jobId` — a redelivered job re-produces
byte-identical derivatives (content addressing), so at-least-once delivery is safe.

## Decisions carried in from the §3 propose gate

| Decision | Choice |
|----------|--------|
| Message contract source | **Lexicon** — consume the media message contracts (`ProcessMediaJob`/`MediaProcessed`/`MediaFailed` in `codex.messages.v1`) as the pinned artifact `io.codex %% lexicon-messages % 0.4.0`, consistent with §1's single-source-of-truth choice. **Already defined + released** (Lexicon v0.4.0, `messages/…/media.proto`; already consumed by artemis) — no Lexicon change needed. Payload is protobuf **canonical JSON** (scalapb-json4s), the HermesMQ wire format. |
| HermesMQ client | Consume the published **`me.cference.hermesmq %% hermesmq-client % 1.4.0`** (Pekko 1.2.0) from GitHub Packages — the alignment is already done; no cross-repo hermes work. The client is a REST wrapper: `pull(subscription, max) → List[ReceivedMessage]` (payload = protobuf-canonical-JSON String) + `ack(subscription, ackIds)`. |
| Lease handling | Client 1.4.0 has **no `modifyAckDeadline`/lease-extend**, so long transcodes are handled by **idempotency**: a slow job may be redelivered and re-processed to byte-identical output (harmless); the first completion acks. Pull small batches; **document that the transcode lane needs a generous server-side ack deadline**. (True lease-extend is a future hermes-client addition if needed.) |
| Lane priority | Two subscriptions; the pull loop drains `media.ingest` fully before pulling `media.reprocess`. |
| Ack ordering | Ack **only after** derivatives are durable in Apollo **and** the result is published. Publishing is a **seam** (`ResultPublisher` interface) filled by §4; §3 ships a no-op/logging default so the ack invariant is complete and testable now. |
| Idempotency | `jobId` (= `postId`) is the idempotency key; content-addressed writes make redelivery safe. Optional skip-if-present via the §1 `headExists`. |

## What Changes

- **job-consumption** (new capability): a `JobConsumer` that
  - subscribes to `media.ingest` and `media.reprocess` (topics/subscription names from config);
  - runs a **priority pull loop** — drain ingest before reprocess — pulling small batches,
    backpressured, on a bounded blocking dispatcher (processing is CPU-heavy);
  - **decodes** each `ProcessMediaJob` (Lexicon type) from the message payload;
  - invokes the §2 `MediaPipeline`, then the `ResultPublisher` seam, then **acks**;
  - is **idempotent per `jobId`** and never wedges a lane on a single bad message (terminal
    failures are reported via the publisher seam + acked; transient failures are left unacked for
    redelivery).

- **Build**: depend on `me.cference.hermesmq %% hermesmq-client % 1.4.0` and
  `io.codex %% lexicon-messages % <version>` (both GitHub Packages — the read:packages token must
  also grant `vezril/hermesmq`); wire `LEXICON_TOKEN`/a packages token into the sbt env in CI.
- **Config**: Hermes endpoint + the two lane subscription/topic names (already stubbed in §0
  config) wired to the client; pull batch size, bounded processing concurrency.
- **Main**: start the `JobConsumer` on a dedicated dispatcher; graceful drain on shutdown (stop
  pulling, finish in-flight, ack, then terminate).

## Impact

- Affected specs: `job-consumption` is **ADDED**. Implements `design-hephaestus` (job-consumption)
  and the consumption half of `design-hephaestus-contract` (`media-processing-contract`).
- Dependencies (all already released — **no prerequisite work**): `hermesmq-client 1.4.0`,
  `io.codex %% lexicon-messages % 0.4.0` (align the Lexicon pin — §1 currently uses `lexicon-grpc`
  0.1.0; bump both to 0.4.0), the §2 `MediaPipeline`, the §1 `ApolloClient`; a running Hermes for
  integration tests (testcontainers or an in-process fake).
- Out of scope: publishing `MediaProcessed`/`MediaFailed` (`add-result-reporting`, §4 — §3 provides
  the `ResultPublisher` seam only); lease-extension (idempotency handles slow jobs); the dead-letter
  policy (Hermes-side); adopting a real result publisher.
