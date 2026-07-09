package me.cference.hephaestus.media

/**
 * Pure argv builders for the media tools. Each returns the exact command as a `Seq[String]` (argv),
 * NEVER a shell string — the `server` runner execs the argv directly, so there is no shell to quote
 * against and no interpolation-injection surface. Paths flow through untouched; the pieces the
 * pipeline varies (dimensions, transcode params) are the tested, asserted parts.
 */
object ToolArgs:

  /** libvips webp save quality for the lossy thumbnail/sample outputs. */
  val WebpQuality = 80

  /**
   * Read a single header field (`width`/`height`) of an image via `vipsheader`. Prints just the
   * field's value, so the pipeline can size the plan without decoding the whole raster.
   */
  def vipsHeaderField(input: String, field: String): Seq[String] =
    Seq("vipsheader", "-f", field, input)

  /**
   * Downscale `input` to fit within a `longEdge`×`longEdge` box (never upscaling — `--size down`),
   * writing a webp at `output`. Serves both the thumbnail and the sample (the caller varies
   * `longEdge`).
   */
  def vipsThumbnail(input: String, output: String, longEdge: Int): Seq[String] =
    Seq(
      "vips",
      "thumbnail",
      input,
      s"$output[Q=$WebpQuality]",
      longEdge.toString,
      "--height",
      longEdge.toString,
      "--size",
      "down"
    )

  /**
   * Force `input` to an exact `size`×`size` raster (aspect ratio deliberately broken — the pHash
   * wants a fixed grid), writing `output`. Paired with [[vipsColourspaceBW]] to yield a grayscale
   * raster.
   */
  def vipsForceSize(input: String, output: String, size: Int): Seq[String] =
    Seq(
      "vips",
      "thumbnail",
      input,
      output,
      size.toString,
      "--height",
      size.toString,
      "--size",
      "force"
    )

  /**
   * Convert `input` to greyscale, writing `output`. On an RGBA source this yields a two-band
   * grey+alpha image, so the pHash raster path follows it with [[vipsExtractBand]] to drop the
   * alpha before the single-band PGM save (which rejects multi-band input).
   */
  def vipsColourspaceBW(input: String, output: String): Seq[String] =
    Seq("vips", "colourspace", input, output, "b-w")

  /**
   * Extract `n` band(s) starting at `band` from `input`, writing `output`. Used in the pHash raster
   * path as `extract_band 0 --n 1` to keep only the grey plane of a (possibly grey+alpha) b-w image
   * — so a transparent PNG hashes deterministically instead of failing the single-band PGM save.
   */
  def vipsExtractBand(input: String, output: String, band: Int, n: Int): Seq[String] =
    Seq("vips", "extract_band", input, output, band.toString, "--n", n.toString)

  /** Probe container/streams as JSON on stdout only (`-v quiet`) for metadata extraction. */
  def ffprobeJson(input: String): Seq[String] =
    Seq(
      "ffprobe",
      "-v",
      "quiet",
      "-print_format",
      "json",
      "-show_format",
      "-show_streams",
      input
    )

  /** Extract the first video frame as a still image `output` (poster source for thumb/sample). */
  def ffmpegPoster(input: String, output: String): Seq[String] =
    Seq("ffmpeg", "-y", "-i", input, "-frames:v", "1", "-update", "1", output)

  /**
   * Eagerly transcode `input` to a single 720p h264 mp4 at `output`: cap the height at 720 without
   * upscaling (`h=min(720\,ih)`, the comma escaped for the filtergraph — no shell involved), even
   * width (`w=-2`), `yuv420p` for broad playback, AAC audio, and `+faststart` for progressive play.
   */
  def ffmpegTranscode720p(input: String, output: String): Seq[String] =
    Seq(
      "ffmpeg",
      "-y",
      "-i",
      input,
      "-vf",
      "scale=w=-2:h=min(720\\,ih)",
      "-c:v",
      "libx264",
      "-preset",
      "veryfast",
      "-crf",
      "23",
      "-pix_fmt",
      "yuv420p",
      "-c:a",
      "aac",
      "-movflags",
      "+faststart",
      output
    )
