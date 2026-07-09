# Design: Hephaestus (media worker)

The forge. Captured in explore mode; no implementation. Implements the worker side of
`design-hephaestus-contract`.

## Shape: a stateless streaming worker

```
   HermesMQ (media.ingest / media.reprocess)
        │  pull ProcessMediaJob (lease-aware, backpressured)
        ▼
   read ORIGINAL from Apollo (gRPC stream) ──▶ PROCESS (ffmpeg / libvips / phash / ffprobe)
        │                                            │
        │                          write DERIVATIVES to Apollo (content-addressed)
        ▼                                            │
   publish MediaProcessed / MediaFailed  ◀───────────┘   then ACK the job
```

Hephaestus holds **no durable state** — no aggregate, no journal. It's a Pekko (Streams)
worker: durability is HermesMQ's (redelivery), idempotency is content-addressing's (derivatives
overwrite deterministic paths; a redelivered job re-produces identical bytes). Simpler than the
event-sourced services on purpose.

## The pipeline, per media type

```
   image      libvips → thumbnail (~250px webp) · sample (~850px webp, only if larger)
              + dimensions
   animated   poster thumbnail · sample · (opt) gif→webm/mp4
   video      ffmpeg → poster-frame thumbnail · sample poster · EAGER 720p h264 mp4
              ffprobe → duration · fps · hasAudio
   ALL        perceptual hash (phash) · md5 (verify vs Apollo's ingest checksum)
   write      derivatives/<md5[0:2]>/<md5>/{thumb.webp, sample.webp, poster.webp, 720p.mp4}
```

- **libvips** for images (fast, low memory — kind to old laptops); **ffmpeg/ffprobe** for video.
- **Software transcode, no GPU** → slow on old CPUs but async + personal-scale = fine; runs on
  the beefiest node.
- Writes are **atomic + content-addressed** → reprocessing overwrites identically (idempotent).

## Consuming HermesMQ (Scala client)

Unlike Argus (its own Python client), Hephaestus uses HermesMQ's **Scala client**. Processing
is slow, so lease handling matters:

```
   pull small batches (backpressure) · generous ack deadline · extend the lease for long
   transcodes · ACK only after derivatives are durable in Apollo AND the result is published
   drain media.ingest BEFORE media.reprocess (new uploads beat backfills)
   idempotent per jobId — redelivery re-produces identical derivatives (safe)
```

## Reporting results + failure classification

```
   MediaProcessed  metadata{w,h,duration?,fps?,md5,filetype,hasAudio?} · phash · derivatives[] ·
                   derivativeSpecVersion
   MediaFailed     error{code,message} · retriable
       retriable=true   transient (Apollo unreachable, timeout) → leave for redelivery
       retriable=false  terminal (corrupt/unsupported media) → Artemis quarantines the post
```

## Versioning for reprocessing

Each result stamps a **`derivativeSpecVersion`** (bumped when the derivative outputs change —
new sizes/formats/rungs). Artemis stores it; a `stale` reprocess re-runs only out-of-date posts
(`design-artemis-reprocessing`).

## Not event-sourced (and why that's right)

The event-sourced services (Apollo, Hermes, Artemis) own durable state with invariants.
Hephaestus owns none — it's a pure function `job → derivatives + result`, made reliable by the
queue + content-addressing. Adding a journal would be ceremony with no payoff.

## Out of scope

Auto-tagging (Argus) · event-sourcing · GPU transcode · multi-rung transcode ladders (eager
720p only) · frame-accurate scene detection.
