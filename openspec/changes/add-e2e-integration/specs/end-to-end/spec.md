# end-to-end — Spec Delta

## ADDED Requirements

### Requirement: Verified end-to-end against real Apollo and Hermes

The worker SHALL be verified end-to-end against the real ApolloStorage and HermesMQ services (their
published images, via testcontainers): a `ProcessMediaJob` published to a real `media.ingest`
subscription, referencing a real original stored in Apollo, SHALL result in the derivative set
written content-addressed to Apollo and a `MediaProcessed` published to `media.processed`. This
verification is an **opt-in** tier (excluded from the default PR test run) run on demand in CI.

#### Scenario: Happy-path loop closes end-to-end
- **Given** real Apollo + Hermes running, an original uploaded to Apollo, and a `ProcessMediaJob` published to `media.ingest`
- **When** the real worker consumes and processes it
- **Then** the thumbnail (and sample, if applicable) exist in Apollo at `derivatives/<md5[0:2]>/<md5>/…` and a well-formed `MediaProcessed` (ids, metadata, phash, derivative refs, spec version) is pulled from `media.processed`

#### Scenario: Terminal failure reports MediaFailed end-to-end
- **Given** a corrupt/unsupported original uploaded to Apollo and its `ProcessMediaJob` published
- **When** the real worker processes it
- **Then** a `MediaFailed` (`retriable = false`) is published to `media.failed` and no derivative is written for it

#### Scenario: The E2E tier does not gate fast PR CI
- **Given** the default `sbt test` run (no `-De2e=true`)
- **When** it executes
- **Then** the `E2E`-tagged suite is skipped and the fast PR-verification CI remains unchanged
