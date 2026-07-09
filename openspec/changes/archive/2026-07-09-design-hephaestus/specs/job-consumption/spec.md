# job-consumption

Consuming `ProcessMediaJob` from HermesMQ correctly — ingest and lower-priority reprocess
lanes, lease-aware for slow processing, idempotent, backpressured.

## ADDED Requirements

### Requirement: Prioritized two-lane consumption

Hephaestus SHALL consume `ProcessMediaJob` from HermesMQ, draining the `media.ingest` lane
**before** the lower-priority `media.reprocess` lane, and SHALL pull small batches so a slow
worker applies backpressure (Hermes buffers the rest) rather than leasing more than it can
process.

#### Scenario: New uploads are handled before a backfill
- **GIVEN** jobs pending on both `media.ingest` and `media.reprocess`
- **WHEN** Hephaestus pulls
- **THEN** it processes the ingest job(s) before the reprocess backlog

#### Scenario: Edge case — an empty ingest lane lets reprocess drain
- **GIVEN** no ingest jobs and a reprocess backlog
- **WHEN** Hephaestus pulls
- **THEN** it processes the reprocess jobs until the lane is empty

### Requirement: Lease-aware, ack-after-durable processing

Hephaestus SHALL pull with a generous ack deadline, **extend the lease** when a transcode runs
long (so the job is not redelivered mid-flight), and **acknowledge only after** the derivatives
are durably written to Apollo and the result is published.

#### Scenario: A long transcode extends its lease
- **GIVEN** a leased video job whose transcode approaches the ack deadline
- **WHEN** it is still processing
- **THEN** the lease is extended and the job is not redelivered while in flight

#### Scenario: Edge case — a crash before ack causes safe redelivery
- **GIVEN** a job that crashed after writing some derivatives but before ack
- **WHEN** HermesMQ redelivers it
- **THEN** Hephaestus re-processes it, overwriting the identical content-addressed derivatives (idempotent), then acks

### Requirement: Idempotent per job

Processing SHALL be idempotent per `jobId` — because derivatives are content-addressed, a
redelivered job re-produces byte-identical outputs at the same paths, so at-least-once delivery
never corrupts or duplicates.

#### Scenario: Duplicate delivery is harmless
- **GIVEN** a job already processed with its derivatives in Apollo
- **WHEN** the same job is delivered again
- **THEN** the derivatives are overwritten with identical bytes and an equivalent result is published
