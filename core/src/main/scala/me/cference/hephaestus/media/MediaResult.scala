package me.cference.hephaestus.media

/**
 * The extracted metadata for a processed original: pixel `width`/`height` (all types), `duration`/
 * `fps`/`hasAudio` (video/animated, via ffprobe), the byte `filesize`, the `md5` (the verified
 * original checksum — the content-addressing anchor), and the `filetype` (the container/format).
 */
final case class MediaMetadata(
    width: Int,
    height: Int,
    duration: Option[Double],
    fps: Option[Double],
    filesize: Long,
    md5: String,
    filetype: String,
    hasAudio: Option[Boolean]
)

/**
 * A produced derivative as it will be reported to §4: its [[DerivativeKind]], the Apollo `ref` (the
 * content-addressed key it was written to), its `width`/`height`, and — for a transcode — the
 * `variant` (e.g. `720p`) and `codec` (e.g. `h264`).
 */
final case class DerivativeRef(
    kind: DerivativeKind,
    ref: String,
    width: Int,
    height: Int,
    variant: Option[String] = None,
    codec: Option[String] = None
)

/**
 * The successful output of the pipeline: the extracted [[MediaMetadata]], the perceptual `phash`
 * (lowercase hex), the produced `derivatives`, and the stamped `derivativeSpecVersion`. This is the
 * exact value §4 will publish as `MediaProcessed`; on corrupt/unsupported input the pipeline yields
 * a terminal [[MediaError]] instead.
 */
final case class MediaResult(
    metadata: MediaMetadata,
    phash: String,
    derivatives: Seq[DerivativeRef],
    derivativeSpecVersion: String
)
