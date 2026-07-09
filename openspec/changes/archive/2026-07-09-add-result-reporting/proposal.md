# Change: add-result-reporting

## Why

§3 consumes jobs and runs the pipeline, but its `ResultPublisher` seam is a no-op logger — nothing
tells Artemis a post is ready or quarantined. This change fills that seam: it maps the domain
outcome to the Lexicon wire messages and **publishes `MediaProcessed` / `MediaFailed` to HermesMQ**,
closing the loop (consume → read → process → write → **report**). After it, Hephaestus is
functionally complete end-to-end.

The seam already exists (`ResultPublisher.publish(job, Either[MediaError, MediaResult])`) and §3
already guarantees **publish-before-ack**, so this change is contained: a real publisher + the
domain→proto mapping + Main wiring.

## Decisions / mapping

| Concern | Choice |
|---------|--------|
| Messages | Emit the Lexicon `codex.messages.v1` **`MediaProcessed`** (success) and **`MediaFailed`** (terminal failure), serialized as protobuf **canonical JSON** (scalapb-json4s), published via `HermesClient.publish(topic, json, attributes)`. |
| Topics | `media.processed` / `media.failed`, env-overridable config. Carry `jobId`/`postId` as message **attributes** (routing/observability; Artemis dedups by `jobId`). |
| Only terminal outcomes are published | A **transient** failure is *not* published (it's left unacked for redelivery by §3); only success (`MediaProcessed`) or a **terminal** `MediaError` (`MediaFailed`) is reported. The publisher inspects `retriable`: retriable ⇒ no publish (surface as a failed `Future` so §3 leaves it unacked). |
| `MediaError` → `JobError.code` | Stable tokens per case: `unsupported_type`, `corrupt_input`, `tool_failed`, `plan_failed`, `upstream`, `unexpected`; `message` ← `error.message`; `retriable` ← `error.retriable` (always false on a published `MediaFailed`). |
| Derivative `ref` | §2 `DerivativeRef.ref` is the flat content-addressed key; the proto `Derivative.ref` is `ObjectRef{bucket, object}` → split with the configured **media bucket** + the key. |
| `spec_version` | proto `spec_version` is `int32`; §2 `derivativeSpecVersion` is a String → parse to int (the config value is numeric). |
| `filesize` | §2 `MediaMetadata.filesize` has **no field** in the Lexicon `MediaMetadata` → dropped (Artemis already computes size on upload). *Noted for a possible future Lexicon field if Artemis ever needs it from the worker.* |
| Idempotency | Re-publishing on redelivery is safe — Artemis applies a `MediaProcessed` at most once per `jobId`; §4 needs no dedup of its own. |

## What Changes

- **result-reporting** (new capability): a `HermesResultPublisher` implementing the §3
  `ResultPublisher` seam — maps `MediaResult → MediaProcessed` and terminal `MediaError → MediaFailed`
  (`codex.messages.v1`), serializes to canonical JSON, and publishes to the `media.processed` /
  `media.failed` topics; a retriable outcome is not published (a failed `Future` so §3 leaves it
  unacked). Exactly one terminal message per completed job.
- **Config**: `media.processed` / `media.failed` topic names (env-overridable); reuse the media
  bucket + Hermes endpoint already in config.
- **Main**: replace `ResultPublisher.logging` with the real `HermesResultPublisher` (built from the
  existing `HermesClient`).

## Impact

- Affected specs: `result-reporting` is **ADDED**. Implements `design-hephaestus` (result-reporting)
  and the reporting half of `design-hephaestus-contract` (`media-processing-contract`).
- Affected code: new `report` package in `server` (the publisher + the pure domain→proto mapping,
  CI-tested via a JSON round-trip); small pure helpers (error→code) may live in `core`; config +
  Main wiring.
- Dependencies (all released): `lexicon-messages 0.4.0` (`MediaProcessed`/`MediaFailed`),
  `hermesmq-client 1.4.0` (`publish`), the §2 `MediaResult`/`MediaError`, the §3 seam.
- Out of scope: publishing on *transient* failure (by design — redelivery handles it); a dead-letter
  policy (Hermes-side); the `filesize` proto field (future Lexicon change if needed); §5
  integration/deploy (Codex).
