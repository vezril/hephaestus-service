package me.cference.hephaestus.media

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.concurrent.Future

/**
 * A recording fake [[MediaTools]] for orchestration tests: it records every call (with its salient
 * argument) so the spec can assert the pipeline drove the right tools, writes a tiny placeholder
 * file for each produced derivative (so the write step has real bytes to stream), and can be told
 * to fail a named operation to exercise the cleanup-on-failure / terminal-error paths. No real
 * binaries are touched.
 */
final class FakeMediaTools(
    imageDims: Dimensions = Dimensions(4000, 3000),
    videoInfo: VideoInfo =
      VideoInfo(Dimensions(1920, 1080), Some(12.0), Some(30.0), hasAudio = true),
    raster: GrayscaleRaster = GrayscaleRaster(
      PerceptualHash.RasterSize,
      IArray.fill(PerceptualHash.RasterSize * PerceptualHash.RasterSize)(120)
    ),
    failOn: Set[String] = Set.empty
) extends MediaTools:

  val calls: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

  private def record(call: String): Unit = synchronized { calls += call; () }

  private def guard[A](op: String)(value: => A): Future[A] =
    if failOn.contains(op) then Future.failed(MediaError.ToolFailed(op, "injected failure"))
    else Future.successful(value)

  def probeImage(input: Path): Future[ImageInfo] =
    record("probeImage")
    guard("probeImage")(ImageInfo(imageDims))

  def probeVideo(input: Path): Future[VideoInfo] =
    record("probeVideo")
    guard("probeVideo")(videoInfo)

  def thumbnail(input: Path, output: Path, longEdge: Int): Future[Unit] =
    record(s"thumbnail:$longEdge")
    guard("thumbnail")(writePlaceholder(output))

  def posterFrame(input: Path, output: Path): Future[Unit] =
    record("posterFrame")
    guard("posterFrame")(writePlaceholder(output))

  def transcode720p(input: Path, output: Path): Future[Unit] =
    record("transcode720p")
    guard("transcode720p")(writePlaceholder(output))

  def grayscaleRaster(input: Path, size: Int): Future[GrayscaleRaster] =
    record(s"grayscaleRaster:$size")
    guard("grayscaleRaster")(raster)

  private def writePlaceholder(output: Path): Unit =
    Files.write(output, s"fake:${output.getFileName}".getBytes("UTF-8"))
    ()
