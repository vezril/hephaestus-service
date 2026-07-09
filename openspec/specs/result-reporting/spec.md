# result-reporting Specification

## Purpose

Define how Hephaestus reports outcomes back to the constellation: it maps the domain result of a
completed job to the Lexicon wire messages and publishes exactly one terminal result per job —
`MediaProcessed` on success or `MediaFailed` on terminal failure — to HermesMQ, before the job is
acknowledged. Transient failures are not reported (they are left for redelivery).

## Requirements

### Requirement: Publish exactly one terminal result per completed job

Hephaestus SHALL publish exactly one terminal result per job it completes: **`MediaProcessed`** on
success or **`MediaFailed`** on terminal failure (the Lexicon `codex.messages.v1` contracts,
serialized as protobuf canonical JSON), to the `media.processed` / `media.failed` topics via the
HermesMQ client. A **transient** (retriable) failure SHALL NOT be published — it is left for
redelivery — so a `MediaFailed` always carries `retriable = false`.

#### Scenario: Success publishes MediaProcessed
- **Given** a job whose pipeline produced a `MediaResult`
- **When** the result is reported
- **Then** a `MediaProcessed` is published to `media.processed` carrying the job/post ids, metadata, phash, derivative refs, and spec version

#### Scenario: Terminal failure publishes MediaFailed
- **Given** a job that failed terminally (corrupt/unsupported input, tool failure)
- **When** the result is reported
- **Then** a `MediaFailed` is published to `media.failed` with a `JobError` code+message and `retriable = false`

#### Scenario: Edge case — a transient failure publishes nothing
- **Given** a job that failed transiently (Apollo/Hermes outage)
- **When** the outcome is handed to the publisher
- **Then** nothing is published and the publish returns a failure, so the message is left unacknowledged for redelivery

### Requirement: Faithful domain-to-wire mapping

`MediaProcessed` SHALL carry the job's metadata (`width`, `height`, `md5`, `filetype`, and the
optional `duration`/`fps`/`hasAudio` when present), the perceptual `phash`, the produced derivatives
(each with its `kind`, the Apollo `ref` as `bucket`+`object`, `width`/`height`, and — for a
transcode — `variant`/`codec`), the parsed `spec_version`, and `status = "ok"`. `MediaFailed` SHALL
map each terminal `MediaError` case to a stable `JobError.code`. A non-numeric or `< 1`
`spec_version` SHALL be rejected at configuration load (fail-fast), not per job.

#### Scenario: Video result maps duration, transcode, and codec
- **Given** a completed video job with a 720p h264 transcode
- **When** the `MediaProcessed` is built
- **Then** its metadata includes duration/fps/hasAudio and its derivatives include the transcode with `variant`/`codec` set, and the JSON round-trips losslessly

#### Scenario: Image result maps thumb (and sample when present)
- **Given** a completed image job (thumb only, no sample)
- **When** the `MediaProcessed` is built
- **Then** it carries width/height/md5/filetype, the phash, the spec version, and a single `thumb` derivative ref

#### Scenario: Edge case — each error case has a stable code
- **Given** a terminal `MediaError` (e.g. unsupported type, corrupt input, tool failure)
- **When** it is mapped
- **Then** it yields the corresponding stable `JobError.code` and the human-readable message

#### Scenario: Edge case — a bad spec-version fails fast at startup
- **Given** a configured `spec-version` that is non-numeric or `< 1`
- **When** the service loads its configuration
- **Then** startup fails with an error naming the key (not a per-job publish failure)

### Requirement: Publish before ack; redelivery is safe

The publisher SHALL be invoked before the job is acknowledged (the job-consumption invariant), and
re-publishing an equivalent result on redelivery SHALL be safe — a `MediaProcessed` for a given
`jobId` is byte-identical across redeliveries and Artemis applies it at most once.

#### Scenario: Redelivery re-publishes an equivalent result
- **Given** a job already processed and reported
- **When** it is redelivered and reprocessed
- **Then** an equivalent (byte-identical JSON) `MediaProcessed` is published again — a harmless repeat Artemis dedups by `jobId`
