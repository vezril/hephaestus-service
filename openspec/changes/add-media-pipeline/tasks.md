# Tasks — add-media-pipeline

TDD throughout. Keep the pipeline's pure parts (media-type mapping, tool arg-list construction,
pHash DCT, `MediaResult` assembly) in `core` (unit-tested, no Pekko/IO/process exec); keep staging,
process execution, and Apollo orchestration in the `server` shell. Tool execution goes behind an
injectable `MediaTools` interface (the §0 `ToolRunner` pattern) so orchestration unit tests use
fakes; real-tool tests run where ffmpeg/libvips exist (locally + CI installs them).

Before starting: consult `/Users/cference/Code/claude-toolkit` (scala-fp-reviewer, tdd-coach,
git-and-ci-reviewer). Read `openspec/specs/apollo-io/spec.md` (the §1 client you depend on) and the
design at `openspec/changes/design-hephaestus/{design.md,specs/media-processing/spec.md}`.

## 1. Pure core: types, mapping, tool args

- [ ] 1.1 **Red/Green**: `MediaType` ADT (`Image`|`Animated`|`Video`) + total mapping from
      `contentType`/declared `mediaType`; unknown ⇒ `Left(Unsupported)` (edge cases).
- [ ] 1.2 **Red/Green**: pure derivative plan — given `MediaType` + `want` + source dimensions,
      produce the list of outputs to generate (thumb always; sample only if larger than the sample
      dim; video adds poster + 720p) with their content-addressed names (edge: small image skips sample).
- [ ] 1.3 **Red/Green**: pure `vips`/`ffmpeg`/`ffprobe` **arg-list builders** (input path, output
      path, dimensions, transcode params) — assert exact argv for each output; no shell string interp.

## 2. Perceptual hash (`core`, pure)

- [ ] 2.1 **Red**: DCT-based 64-bit pHash over a small grayscale raster — known-vector hashes;
      identical rasters ⇒ identical hash; a 1px shift ⇒ small Hamming distance; hash is stable
      (deterministic) (edge cases).
- [ ] 2.2 **Green**: pure pHash (grayscale downscale → DCT → median threshold → 64-bit); a lowercase
      hex/`Long` representation matching what Artemis stores.

## 3. Media tools integration (`server` shell)

- [ ] 3.1 **Red**: `MediaTools` interface (probe/thumbnail/sample/poster/transcode/raster-for-phash)
      with a **fake** impl — orchestration tests assert the right calls + args without real binaries.
- [ ] 3.2 **Green**: real `MediaTools` shelling to `vips`/`ffmpeg`/`ffprobe` via the injectable
      runner; parse `ffprobe` JSON → `duration`/`fps`/`hasAudio`; capture nonzero-exit/stderr as a
      typed processing error (classify terminal vs retriable).
- [ ] 3.3 **Red/Green**: temp scratch staging — write the read `Source[ByteString]` to a temp file,
      run tools, read derivative files back as `Source[ByteString]`; **cleanup on success AND
      failure** (edge: cleanup runs even when a tool fails).

## 4. Pipeline orchestration (`server`)

- [ ] 4.1 **Red**: image pipeline — large image ⇒ thumb + sample + dimensions + phash; small image ⇒
      thumb only (no sample); output derivatives carry correct content-addressed keys (edge cases).
- [ ] 4.2 **Green**: image path (read+verify via §1 → stage → vips thumb/sample → phash → write via §1).
- [ ] 4.3 **Red**: video pipeline — poster-frame thumb + sample poster + **eager 720p h264 mp4**;
      metadata has duration/fps/hasAudio; phash from a poster raster (edge: no audio track ⇒ hasAudio=false).
- [ ] 4.4 **Green**: video path (ffmpeg poster + transcode, ffprobe metadata).
- [ ] 4.5 **Red/Green**: animated path — poster + sample (transcode deferred/optional).
- [ ] 4.6 **Red**: `MediaResult` assembly — metadata + phash + derivative refs (kind/ref/dims/variant)
      + `derivativeSpecVersion` stamped; matches the §4 `MediaProcessed` shape (edge cases).
- [ ] 4.7 **Green**: assemble and return `MediaResult`.

## 5. Failure classification

- [ ] 5.1 **Red**: corrupt/unsupported input ⇒ **terminal** `MediaError` (retriable=false), no
      derivatives written; md5 mismatch on read ⇒ terminal (reuses §1); a tool crash on valid input
      vs a transient Apollo error ⇒ correct terminal/retriable split (edge cases).
- [ ] 5.2 **Green**: typed `MediaError` ADT with `retriable`; map tool nonzero-exit/parse failures
      (terminal) vs Apollo `ApolloError.retriable` (passed through).

## 6. Integration + CI tooling

- [ ] 6.1 CI: add `apt-get install -y ffmpeg libvips-tools` to the `Compile & test` job so real-tool
      integration tests run in CI (not just fake-based unit tests). Cache/pin as needed.
- [ ] 6.2 **Test** (real tools): end-to-end with tiny fixtures through a mock/in-process Apollo —
      a real jpeg → thumb+sample webp written at `derivatives/<md5[0:2]>/<md5>/…`, dimensions+phash
      correct; a real short mp4 → poster + 720p transcode, duration/fps/hasAudio correct.
- [ ] 6.3 Refactor: extract the pipeline module; unit (core + fakes) green locally, real-tool suite
      green in CI; scalafmt/scalafix clean.
