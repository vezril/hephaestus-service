# result-reporting

Publishing the processing outcome per the contract, with failure classification and a spec
version for reprocessing.

## ADDED Requirements

### Requirement: Publish MediaProcessed with derivatives, metadata, and spec version

On success, Hephaestus SHALL publish `MediaProcessed` carrying the `jobId`/`postId`, the
extracted `metadata`, the `phash`, the produced `derivatives` (each with kind + Apollo ref +
dimensions, and for the transcode its variant/codec), and a **`derivativeSpecVersion`** stamping
the generation logic that ran. It SHALL publish only after the derivatives are durable in Apollo.

#### Scenario: A successful result reports its outputs and version
- **GIVEN** a completed image job
- **WHEN** Hephaestus publishes the result
- **THEN** `MediaProcessed` carries the metadata, phash, derivative refs, and the current `derivativeSpecVersion`

#### Scenario: Edge case — the result stamps the version that ran
- **GIVEN** a reprocess job run under derivative spec `v4`
- **WHEN** the result is published
- **THEN** its `derivativeSpecVersion` is `v4` (so Artemis knows the post is now current)

### Requirement: Classified failure reporting

On failure, Hephaestus SHALL publish `MediaFailed` with an `error` and a `retriable` flag:
`retriable: true` for transient faults (Apollo unreachable, timeout) so the job can be
redelivered, and `retriable: false` for terminal faults (corrupt/unsupported media) so Artemis
quarantines the post rather than retrying forever. A failed job SHALL NOT wedge the lane.

#### Scenario: A corrupt file fails terminally
- **GIVEN** an unsupported or corrupt original
- **WHEN** processing fails
- **THEN** Hephaestus publishes `MediaFailed` with `retriable: false` and moves on

#### Scenario: Edge case — a transient fault is retriable
- **GIVEN** Apollo is briefly unreachable mid-job
- **WHEN** the write fails
- **THEN** Hephaestus reports `retriable: true` (or leaves the job unacked) so it is retried later
