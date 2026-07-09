# media-processing Specification

## Purpose

Define the media pipeline: given a job descriptor, read and md5-verify the original from Apollo,
detect the media type, generate the per-type derivative set (libvips images / ffmpeg video),
compute a deterministic perceptual hash, extract metadata, write derivatives content-addressed and
atomically back to Apollo, and assemble a `MediaResult` — or a terminal failure for corrupt or
unsupported input.

## Requirements

### Requirement: Per-media-type derivative generation

Hephaestus SHALL generate the derivative set appropriate to the media type: for **images**, a
thumbnail (~250px webp) and a sample (~850px webp, only when the original exceeds the sample
dimension); for **animated**, a poster thumbnail and a sample; for **video**, a poster-frame
thumbnail, a sample poster, and — **eagerly** — a single 720p h264 mp4 transcode. Images SHALL use
libvips (`vips`); video SHALL use ffmpeg. The requested outputs are scoped by the job descriptor's
`want` list.

#### Scenario: A large image yields thumb and sample
- **Given** an image larger than the sample dimension
- **When** it is processed
- **Then** both a thumbnail and a downscaled sample are produced (libvips)

#### Scenario: A video yields a poster and an eager 720p transcode
- **Given** a video original
- **When** it is processed
- **Then** a poster-frame thumbnail and a 720p h264 mp4 transcode are produced (ffmpeg) on ingest

#### Scenario: Edge case — a small image skips the sample
- **Given** an image smaller than the sample dimension
- **When** it is processed
- **Then** only a thumbnail is produced (no redundant sample)

### Requirement: Media type detection

Hephaestus SHALL determine the media type from the job descriptor's declared `mediaType` and
`contentType`, mapping to image/animated/video. An unknown or unsupported type SHALL be a
**terminal** failure (no tools are run).

#### Scenario: Supported type is processed
- **Given** a descriptor with `contentType image/png`
- **When** the pipeline starts
- **Then** it selects the image pipeline

#### Scenario: Edge case — unsupported type is terminal
- **Given** a descriptor whose type maps to none of image/animated/video
- **When** the pipeline starts
- **Then** it fails terminally (retriable = false) and runs no media tool

### Requirement: Media tools invoked via an injectable interface, in a temp scratch area

Hephaestus SHALL invoke `vips`/`ffmpeg`/`ffprobe` through an injectable interface (so orchestration
is testable with fakes), operating in a temp scratch area: the original SHALL be staged to a temp
file, tools run against it, derivative files streamed back to Apollo, and the scratch files SHALL be
cleaned up on both success and failure. A tool's nonzero exit / unparseable output SHALL surface as
a typed processing error.

#### Scenario: Scratch is cleaned up after success
- **Given** a job that completes
- **When** the pipeline finishes
- **Then** all temp scratch files for that job are removed

#### Scenario: Edge case — scratch is cleaned up after a tool failure
- **Given** a job whose transcode tool exits nonzero
- **When** the pipeline aborts
- **Then** the temp scratch files are still removed (no leak) and the job fails with a typed error

### Requirement: Perceptual hash and metadata extraction

Hephaestus SHALL compute a perceptual hash (a deterministic DCT-based 64-bit pHash) and extract
metadata — width/height for all; for video/animated the duration, fps, and whether audio is present
(via ffprobe). It SHALL verify the original's md5 on read (per `apollo-io`).

#### Scenario: Video metadata is extracted
- **Given** a video original
- **When** it is processed
- **Then** the result includes its duration, fps, and hasAudio (from ffprobe) plus a perceptual hash

#### Scenario: pHash is deterministic
- **Given** the same original processed twice
- **When** the perceptual hash is computed
- **Then** both runs yield the identical hash

#### Scenario: Edge case — a checksum mismatch fails the job
- **Given** an original whose bytes do not match Apollo's recorded md5
- **When** Hephaestus reads it
- **Then** it fails the job (terminal) rather than producing derivatives from corrupt input

### Requirement: Content-addressed, atomic derivative writes

Derivatives SHALL be written to Apollo keyed by the original's md5
(`derivatives/<md5[0:2]>/<md5>/…`) using the atomic content-addressed write of `apollo-io`, so
reprocessing overwrites identically and a crash mid-write never leaves a torn derivative.

#### Scenario: Derivatives are grouped under the original's md5
- **Given** an original with md5 `ab34…f`
- **When** its thumbnail and sample are written
- **Then** they land at `derivatives/ab/ab34…f/thumb.webp` and `.../sample.webp`

#### Scenario: Edge case — reprocessing overwrites identically
- **Given** derivatives already produced for an original
- **When** the original is reprocessed with the same spec
- **Then** the same paths are overwritten with byte-identical content

### Requirement: Assemble a MediaResult (or terminal failure)

On success Hephaestus SHALL assemble a `MediaResult` carrying the extracted metadata
(`width`, `height`, `duration?`, `fps?`, `filesize`, `md5`, `filetype`, `hasAudio?`), the `phash`,
the produced `derivatives` (each with `kind`, Apollo `ref`, dimensions, and for transcodes the
`variant`/`codec`), and the stamped `derivativeSpecVersion` — the value the result-reporting
capability publishes as `MediaProcessed`. On corrupt/unsupported input it SHALL yield a **terminal**
failure instead.

#### Scenario: Image success assembles metadata + derivatives + phash
- **Given** a completed image job
- **When** the result is assembled
- **Then** it carries width/height/md5/filetype, a phash, `derivativeSpecVersion`, and derivative refs for `thumb` and (if downscaled) `sample`

#### Scenario: Video success includes duration and a transcode derivative
- **Given** a completed video job
- **When** the result is assembled
- **Then** metadata includes duration/fps/hasAudio and derivatives include a `poster` and a 720p mp4 transcode

#### Scenario: Edge case — corrupt input yields a terminal failure, no derivatives
- **Given** a corrupt or unsupported media file
- **When** processing fails
- **Then** the result is a terminal failure (retriable = false) and no derivative was written
