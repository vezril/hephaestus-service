# job-consumption Specification

## Purpose

Define how Hephaestus consumes `ProcessMediaJob` messages from HermesMQ and drives the media
pipeline: a two-lane priority pull consumer (`media.ingest` before `media.reprocess`) that decodes
each job, runs the pipeline, publishes the outcome through a seam, and acknowledges only after
derivatives are durably written and the result is published — idempotent per `jobId`, robust to a
single bad message, and draining gracefully on shutdown.

## Requirements

### Requirement: Consume ProcessMediaJob from two priority lanes

Hephaestus SHALL consume `ProcessMediaJob` messages (the Lexicon `lexicon-messages` contract) from
two HermesMQ subscriptions via pull, draining **`media.ingest` before `media.reprocess`** so new
uploads beat backfills. It SHALL pull small batches (backpressured) and process on a bounded,
dedicated dispatcher (processing is CPU-heavy).

#### Scenario: Ingest is drained before reprocess
- **Given** jobs waiting in both `media.ingest` and `media.reprocess`
- **When** the worker pulls
- **Then** it processes all available ingest jobs before pulling any reprocess job

#### Scenario: A job maps to a pipeline descriptor
- **Given** a `ProcessMediaJob` with source `originals/ab/ab34…f.png`, mediaType image
- **When** it is decoded
- **Then** it yields a media descriptor the pipeline can run (source bucket/object, type, want)

#### Scenario: Edge case — an undecodable/invalid message is terminal
- **Given** a message whose payload is not a valid `ProcessMediaJob` (missing required fields)
- **When** the worker decodes it
- **Then** it is a terminal failure (reported + acked, not redelivered) and the lane is not wedged

### Requirement: Acknowledge only after durable write and publish

Hephaestus SHALL acknowledge a job **only after** its derivatives are durably written to Apollo
**and** its result has been handed to the result publisher. Publishing is a seam (`ResultPublisher`)
that result-reporting fills; this capability ships a no-op/logging default so the ack invariant
holds and is testable.

#### Scenario: Ack follows durable write and publish
- **Given** a job that processes successfully
- **When** the worker completes it
- **Then** the derivatives are durable in Apollo and the result is published **before** the ack is sent

#### Scenario: Edge case — a transient failure is not acked
- **Given** a job whose processing fails transiently (Apollo/Hermes unreachable, timeout)
- **When** the worker handles the outcome
- **Then** the message is left unacknowledged for redelivery (no lost work, no premature ack)

#### Scenario: Edge case — a terminal failure is reported and acked
- **Given** a job with corrupt/unsupported media (a terminal `MediaError`)
- **When** the worker handles the outcome
- **Then** it reports the failure via the publisher and acks the message (a poison message is not redelivered forever)

### Requirement: Idempotent per jobId over at-least-once delivery

Because HermesMQ delivers at least once, processing SHALL be idempotent per `jobId`: a redelivered
job SHALL re-produce byte-identical derivatives (guaranteed by content addressing) and re-publish an
equivalent result. The worker MAY skip regeneration when derivatives already exist (via `headExists`).

#### Scenario: Redelivery re-produces identical output
- **Given** a job already processed, whose derivatives exist in Apollo
- **When** HermesMQ redelivers the same `jobId`
- **Then** the worker re-writes byte-identical derivatives to the same keys and re-publishes an equivalent result (a harmless repeat)

### Requirement: Slow jobs handled by idempotency, not lease extension

Hephaestus SHALL rely on idempotency, not lease extension, for jobs that exceed the ack deadline:
because the HermesMQ client provides no lease extension, a job whose processing (e.g. a 720p
transcode on CPU) runs longer than the subscription's ack deadline MAY be redelivered and
re-processed safely. The transcode/`media.reprocess` path SHALL be operated with a generous
server-side ack deadline, documented for deployment.

#### Scenario: A slow transcode that times out is safely reprocessed
- **Given** a transcode that runs longer than the ack deadline and is redelivered
- **When** the redelivery is processed
- **Then** it produces byte-identical derivatives and the eventual completion acks — no corruption, no duplicate post activation on our side

### Requirement: Graceful drain on shutdown; a lane is never wedged

On shutdown the worker SHALL stop pulling, finish in-flight jobs, ack them, and then terminate (via
Coordinated Shutdown); no job SHALL be acked without a durable result. A single failing message
SHALL never block progress on its lane.

#### Scenario: Clean drain
- **Given** the worker is processing when a termination signal arrives
- **When** shutdown runs
- **Then** it stops pulling new jobs, completes in-flight work, acks it, and exits — no half-acked job

#### Scenario: Edge case — one bad message does not wedge the lane
- **Given** a message that fails terminally
- **When** the worker handles it
- **Then** it is acked/reported and the worker continues pulling subsequent jobs on that lane
