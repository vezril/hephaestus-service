# Change: design-hephaestus

> **Design capture (explore mode).** The internals of **Hephaestus**, the media-worker
> service. Implements the worker side of the cross-service contract (`design-hephaestus-contract`
> in codex). No code implemented by this change.

## Why

Hephaestus turns an uploaded original into everything the UI needs — thumbnails, samples,
video posters + a playable transcode, a perceptual hash, and extracted metadata. It's the
CPU-heavy forge of the constellation. The shared message + storage contract is already
captured; this pins down the worker itself: how it consumes jobs, runs the media pipeline,
and reports results.

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Shape | **Stateless Scala 3 + Pekko worker** — NOT event-sourced (no aggregate state / journal); idempotency from **content-addressing + jobId**, durability from **HermesMQ** |
| Tools | **ffmpeg/ffprobe** (video transcode, poster frame, metadata) + **libvips** (fast, low-memory image thumb/sample) + a perceptual-hash routine; bundled in the image |
| Derivatives | per media type (image · animated · video), content-addressed to Apollo, **eager 720p h264** for video (per the contract) |
| Lanes | drains `media.ingest` first, then `media.reprocess` (lower priority) — see `design-artemis-reprocessing` |
| Versioning | stamps a **derivativeSpecVersion** in `MediaProcessed` so reprocessing can target stale posts |
| Placement | **CPU-headroom node** (the beefiest laptop) — software transcode, no GPU; slow-but-fine at personal ingest rates |

## What Changes

- **job-consumption** (new): consume `ProcessMediaJob` from HermesMQ (ingest + lower-priority
  reprocess lanes), lease-aware for slow processing, ack only after derivatives are durable,
  idempotent per `jobId`, backpressured.
- **media-processing** (new): the pipeline — read the original from Apollo, generate the
  per-type derivative set (libvips/ffmpeg), compute the perceptual hash, extract metadata, and
  write derivatives content-addressed to Apollo.
- **result-reporting** (new): publish `MediaProcessed` (metadata, derivatives, phash,
  specVersion) or `MediaFailed` (with retriable classification) per the contract.

## Impact

- Affected specs: `job-consumption`, `media-processing`, `result-reporting` are **ADDED**.
- Implements: `design-hephaestus-contract` (messages + `derivatives/<md5[0:2]>/<md5>/…` layout).
- Depends on: HermesMQ (Scala client — `media.ingest` / `media.reprocess`), Apollo (gRPC read
  original / write derivatives), and later **Lexicon** for the message types.
- New repo `hephaestus-service`: Scala 3 + Pekko + Pekko Streams; image bundles ffmpeg + libvips;
  → Docker Hub; deployed by Codex pinned to the beefiest node.
- Out of scope: auto-tagging (that's Argus — Hephaestus stays tag-agnostic), event-sourcing
  (stateless worker), GPU transcode.
