package me.cference.hephaestus.media

/** A pixel size. `longEdge` is the larger of the two dimensions (the axis derivatives scale by). */
final case class Dimensions(width: Int, height: Int):
  def longEdge: Int = math.max(width, height)

object Dimensions:

  /**
   * Scale `src` to fit within a `longEdge`×`longEdge` box, preserving aspect ratio and never
   * upscaling. A source already inside the box (or degenerate) is returned unchanged.
   */
  def fitLongEdge(src: Dimensions, longEdge: Int): Dimensions =
    val srcLong = src.longEdge
    if srcLong <= longEdge || srcLong <= 0 then src
    else
      val scale = longEdge.toDouble / srcLong
      Dimensions(scaled(src.width, scale), scaled(src.height, scale))

  /**
   * Scale `src` so its height is at most `targetHeight`, preserving aspect ratio (width rounded to
   * an even number, as h264 requires) and never upscaling. Used for the 720p transcode plan.
   */
  def fitHeight(src: Dimensions, targetHeight: Int): Dimensions =
    if src.height <= targetHeight || src.height <= 0 then src
    else
      val scale = targetHeight.toDouble / src.height
      val w = scaled(src.width, scale)
      Dimensions(if w % 2 == 0 then w else w + 1, targetHeight)

  private def scaled(edge: Int, scale: Double): Int =
    math.max(1, math.round(edge * scale).toInt)

/**
 * The derivative kinds Hephaestus can produce. A job descriptor's `want` set scopes which are
 * generated; the per-type plan further constrains them (e.g. only video yields a transcode, and a
 * small image skips the sample).
 */
enum DerivativeKind:
  case Thumbnail, Sample, Transcode

/**
 * The pure derivative parameters: output dimensions and the stamped spec version. The `server`
 * `DerivativeConfig`/`ThresholdConfig` map into this so `core` stays free of the config machinery.
 */
final case class DerivativeSpec(
    thumbnailPx: Int,
    samplePx: Int,
    sampleMinLongEdgePx: Int,
    specVersion: String
)

/**
 * An in-memory processing job: the Apollo `bucket`/`key` of the original, its declared
 * `mediaType`/`contentType`, the `want` set scoping outputs, and the derivative `spec`. This is the
 * §2 stand-in for the HermesMQ `ProcessMediaJob` that §3 will decode into it.
 */
final case class MediaDescriptor(
    bucket: String,
    key: String,
    mediaType: String,
    contentType: String,
    want: Set[DerivativeKind],
    spec: DerivativeSpec
)
