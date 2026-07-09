# media-processing

The pipeline: read the original from Apollo, generate the per-type derivative set (libvips/
ffmpeg), compute a perceptual hash, extract metadata, and write derivatives content-addressed.

## ADDED Requirements

### Requirement: Per-media-type derivative generation

Hephaestus SHALL generate the derivative set appropriate to the media type: for **images**, a
thumbnail (~250px webp) and a sample (~850px webp, only when the original exceeds the sample
dimension); for **animated**, a poster thumbnail and a sample; for **video**, a poster-frame
thumbnail, a sample poster, and — **eagerly** — a single 720p h264 mp4 transcode. Images SHALL
use libvips; video SHALL use ffmpeg.

#### Scenario: A large image yields thumb and sample
- **GIVEN** an image larger than the sample dimension
- **WHEN** it is processed
- **THEN** both a thumbnail and a downscaled sample are produced (libvips)

#### Scenario: A video yields a poster and an eager 720p transcode
- **GIVEN** a video original
- **WHEN** it is processed
- **THEN** a poster-frame thumbnail and a 720p h264 mp4 transcode are produced (ffmpeg) on ingest

#### Scenario: Edge case — a small image skips the sample
- **GIVEN** an image smaller than the sample dimension
- **WHEN** it is processed
- **THEN** only a thumbnail is produced (no redundant sample)

### Requirement: Perceptual hash and metadata extraction

Hephaestus SHALL compute a perceptual hash (for dedup / find-similar) and extract metadata —
dimensions for all, and for video/animated the duration, fps, and whether audio is present
(via ffprobe). It SHALL verify the original's md5 against Apollo's ingest checksum.

#### Scenario: Video metadata is extracted
- **GIVEN** a video original
- **WHEN** it is processed
- **THEN** the result includes its duration, fps, and hasAudio (from ffprobe) plus a perceptual hash

#### Scenario: Edge case — a checksum mismatch fails the job
- **GIVEN** an original whose bytes do not match Apollo's recorded md5
- **WHEN** Hephaestus reads it
- **THEN** it fails the job (terminal) rather than producing derivatives from corrupt input

### Requirement: Content-addressed, atomic derivative writes

Derivatives SHALL be written to Apollo keyed by the original's md5
(`derivatives/<md5[0:2]>/<md5>/…`) using atomic writes, so reprocessing overwrites identically
and a crash mid-write never leaves a torn derivative.

#### Scenario: Derivatives are grouped under the original's md5
- **GIVEN** an original with md5 `ab34…f`
- **WHEN** its thumbnail and sample are written
- **THEN** they land at `derivatives/ab/ab34…f/thumb.webp` and `.../sample.webp`

#### Scenario: Edge case — reprocessing overwrites identically
- **GIVEN** derivatives already produced for an original
- **WHEN** the original is reprocessed with the same spec
- **THEN** the same paths are overwritten with byte-identical content
