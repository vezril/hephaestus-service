# Tasks: design-hephaestus

TDD throughout, matching the sibling services. A stateless Pekko streaming worker.

## 0. Scaffold

- [ ] 0.1 Scala 3 + Pekko project; CI/CD (semver, main/development); README + MIT license
- [ ] 0.2 Runnable Pekko HTTP health/readiness; Docker image bundling **ffmpeg + libvips**
- [ ] 0.3 Config (env): Hermes endpoint + lanes, Apollo endpoint, derivative dimensions, specVersion, thresholds

## 1. Apollo I/O

- [ ] 1.1 (test/impl) gRPC client: stream-read an original; stream-write a derivative (atomic, content-addressed)
- [ ] 1.2 (test) md5 verification vs Apollo's ingest checksum → terminal fail on mismatch

## 2. Media processing

- [ ] 2.1 (test) image: libvips thumb + sample (sample only if larger) + dimensions
- [ ] 2.2 (test) video: ffmpeg poster frame + eager 720p h264; ffprobe duration/fps/hasAudio
- [ ] 2.3 (test) animated: poster + sample (+ opt transcode)
- [ ] 2.4 (test) perceptual hash; content-addressed derivative paths
- [ ] 2.5 (impl) the Pekko Streams pipeline (backpressured)

## 3. Job consumption

- [ ] 3.1 (impl) HermesMQ Scala client: two lanes (ingest before reprocess), small-batch pull
- [ ] 3.2 (test) lease extend for long transcode; ack only after durable write + publish
- [ ] 3.3 (test) idempotent per jobId (redelivery re-produces identical derivatives)

## 4. Result reporting

- [ ] 4.1 (test) publish MediaProcessed (metadata, phash, derivatives, derivativeSpecVersion)
- [ ] 4.2 (test) MediaFailed classification (retriable transient vs terminal corrupt); no lane wedge

## 5. Integration + deploy

- [ ] 5.1 (test) end-to-end (mock Apollo/Hermes or testcontainers): job → derivatives written → result published
- [ ] 5.2 deploy via Codex: pin to the beefiest node; ffmpeg/libvips in the image; one+ replica
- [ ] 5.3 (later) adopt Lexicon-generated message types (replace inline contract shapes)
