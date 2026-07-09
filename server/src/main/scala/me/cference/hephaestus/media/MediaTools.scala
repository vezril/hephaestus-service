package me.cference.hephaestus.media

import java.nio.file.Path
import scala.concurrent.Future

/** An image original's probed pixel size. */
final case class ImageInfo(dimensions: Dimensions)

/**
 * A video/animated original's probed metadata: pixel size, optional `duration` (seconds) and `fps`,
 * and whether an audio track is present (all extracted from ffprobe's JSON).
 */
final case class VideoInfo(
    dimensions: Dimensions,
    duration: Option[Double],
    fps: Option[Double],
    hasAudio: Boolean
)

/**
 * The result of running an external command: its exit code and the (separately captured) stdout and
 * stderr. Keeping stdout clean matters — ffprobe's JSON is read from stdout while diagnostics go to
 * stderr.
 */
final case class CommandResult(exitCode: Int, stdout: String, stderr: String)

/**
 * The injectable command runner (the §0 `ToolRunner` pattern, enriched to separate stdout/stderr
 * and surface the exit code rather than throwing). Orchestration tests inject a fake `MediaTools`
 * and never touch this; the real `MediaTools` execs argv through it.
 */
type CommandRunner = Seq[String] => CommandResult

/**
 * The media-tooling seam: every shell-out the pipeline needs, behind one injectable interface so
 * orchestration is unit-testable with a fake (asserting the calls + args, with no real binaries)
 * and the real impl shells to `vips`/`ffmpeg`/`ffprobe`. All operations are asynchronous and
 * total-ish: a tool's nonzero exit / unparseable output fails the `Future` with a typed
 * [[MediaError]].
 */
trait MediaTools:

  /** Probe an image's pixel dimensions (via `vipsheader`). */
  def probeImage(input: Path): Future[ImageInfo]

  /** Probe a video/animated original's metadata (via `ffprobe` JSON). */
  def probeVideo(input: Path): Future[VideoInfo]

  /**
   * Downscale `input` to fit within a `longEdge` box, writing a webp at `output` (via `vips`).
   * Serves both the thumbnail and the sample — the caller varies `longEdge`.
   */
  def thumbnail(input: Path, output: Path, longEdge: Int): Future[Unit]

  /** Extract the first video/animated frame as a still image at `output` (via `ffmpeg`). */
  def posterFrame(input: Path, output: Path): Future[Unit]

  /** Eagerly transcode `input` to a 720p h264 mp4 at `output` (via `ffmpeg`). */
  def transcode720p(input: Path, output: Path): Future[Unit]

  /**
   * Emit a small `size`×`size` grayscale raster for the perceptual hash (via `vips`) — the
   * dependency-light input the pure `PerceptualHash` consumes.
   */
  def grayscaleRaster(input: Path, size: Int): Future[GrayscaleRaster]
