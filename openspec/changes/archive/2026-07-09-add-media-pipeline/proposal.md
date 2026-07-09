# Change: add-media-pipeline

## Why

§1 gave Hephaestus the ability to read an original from Apollo and write derivatives back. This
change adds the **forge itself** — the media-processing pipeline that turns an original into the
derivative set the UI needs: thumbnails and samples (libvips), video posters + an eager 720p h264
transcode (ffmpeg), extracted metadata (ffprobe), and a perceptual hash. It is the heart of the
service; §3 (HermesMQ job consumption) and §4 (result publishing) are just the plumbing that feeds
it and reports its output.

This change is driven by an **in-memory job descriptor** — not the HermesMQ `ProcessMediaJob` yet
(that's §3). The pipeline is a pure-ish function `descriptor → MediaResult | terminal failure`,
made effectful only by staging bytes and shelling out to the media tools.

## Decisions carried into §2

| Decision | Choice |
|----------|--------|
| Tool invocation | Shell out to **`vips`** (images) and **`ffmpeg`/`ffprobe`** (video) via an injectable `MediaTools` interface (the §0 `ToolRunner` pattern), so orchestration is unit-testable with fakes and real-tool tests run where the binaries exist. |
| Staging | **Stage the original to a temp scratch file** (ffmpeg needs a seekable input), run the tools producing derivative files, stream each derivative to Apollo via the §1 client, then **clean up** — success or failure. Scratch dir + bounded concurrency from config. |
| phash | Compute a **DCT-based 64-bit pHash in the JVM** (a pure `core` routine) from a small grayscale raster libvips emits — dependency-light, deterministic, unit-testable; no extra native lib. |
| Media type | Derived from the descriptor's `mediaType`/`contentType`; the `want` list selects which outputs to produce. Unknown/unsupported type ⇒ **terminal**. |
| Pure vs shell | `core` holds the pure parts (media-type mapping, tool arg-list construction, pHash DCT, `MediaResult` assembly); `server` holds the effectful staging/exec/Apollo orchestration. |
| Integrity | Reuse §1's end-to-end md5 verify on read → a checksum mismatch fails **terminal** before any tool runs (no derivatives from corrupt input). |
| Versioning | Stamp the `derivativeSpecVersion` (from §0 config) on the `MediaResult`. |
| CI testing | The `Compile & test` CI job **installs `ffmpeg` + `libvips-tools`** (apt) so the real-tool integration tests run in CI, not just unit tests with fakes. |

## What Changes

- **media-processing** (new capability): given a job descriptor `(source ref, mediaType,
  contentType, want, specVersion)`, Hephaestus SHALL:
  - read + md5-verify the original (via the §1 Apollo client);
  - **detect the media type** and run the per-type pipeline — image → libvips thumbnail (~250px
    webp) + sample (~850px webp, only if larger) + dimensions; animated → poster + sample; video →
    ffmpeg poster-frame thumbnail + sample poster + **eager 720p h264 mp4**, ffprobe →
    duration/fps/hasAudio;
  - compute a **perceptual hash**;
  - write each derivative **content-addressed + atomic** to `derivatives/<md5[0:2]>/<md5>/<name>`
    (via §1);
  - assemble a **`MediaResult`** (metadata `{width,height,duration?,fps?,filesize,md5,filetype,
    hasAudio?}`, `phash`, `derivatives[]` with kind/ref/dimensions/variant, `derivativeSpecVersion`)
    — the value §4 will publish as `MediaProcessed` — or a **terminal failure** for
    corrupt/unsupported input.

- **Build**: Docker image already bundles ffmpeg + libvips (§0). Add an apt-install of
  `ffmpeg` + `libvips-tools` to the CI `Compile & test` job so real-tool integration tests run.
  A media-fixtures resource set (tiny sample image/video) for tests.

## Impact

- Affected specs: `media-processing` is **ADDED**. Implements `design-hephaestus` (media-processing)
  and the derivative-set half of `design-hephaestus-contract`.
- Affected code: new `media` package in `server` (pipeline orchestration, `MediaTools` interface +
  real impl shelling to vips/ffmpeg/ffprobe, temp staging); pure helpers in `core` (media-type
  mapping, tool arg construction, pHash DCT, `MediaResult` assembly); config gains scratch dir,
  derivative dimensions (already stubbed), transcode settings.
- Dependencies: the §1 `ApolloClient`; ffmpeg/libvips present at runtime (image) and in CI (apt).
- Out of scope: HermesMQ job consumption + the two-lane pull (`add-job-consumption`, §3 — needs the
  hermesmq Pekko-1.2.0 alignment first); `MediaProcessed`/`MediaFailed` publishing
  (`add-result-reporting`, §4); GPU transcode; multi-rung transcode ladders; frame-accurate scene
  detection; animated gif→webm transcode (poster+sample only for now, transcode optional/later).
