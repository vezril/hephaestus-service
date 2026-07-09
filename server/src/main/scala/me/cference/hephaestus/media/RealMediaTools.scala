package me.cference.hephaestus.media

import spray.json.*

import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.control.NonFatal

/**
 * The production [[MediaTools]]: shells to `vips`/`ffmpeg`/`ffprobe` via the injected
 * [[CommandRunner]], using the pure argv from `core`'s [[ToolArgs]]. Blocking process execs are
 * dispatched on the provided `ExecutionContext` (Main supplies a blocking-suitable one). Every
 * nonzero exit or unparseable output fails the `Future` with a typed terminal [[MediaError]].
 */
final class RealMediaTools(runner: CommandRunner)(using ec: ExecutionContext) extends MediaTools:

  def probeImage(input: Path): Future[ImageInfo] =
    for
      width <- headerInt(input, "width")
      height <- headerInt(input, "height")
    yield ImageInfo(Dimensions(width, height))

  def probeVideo(input: Path): Future[VideoInfo] =
    exec("ffprobe", ToolArgs.ffprobeJson(input.toString)).flatMap { stdout =>
      Future.fromTry(scala.util.Try(parseVideoInfo(stdout)).recover { case NonFatal(e) =>
        throw MediaError.CorruptInput(s"ffprobe output unparseable: ${e.getMessage}")
      })
    }

  def thumbnail(input: Path, output: Path, longEdge: Int): Future[Unit] =
    exec("vips", ToolArgs.vipsThumbnail(input.toString, output.toString, longEdge)).map(_ => ())

  def posterFrame(input: Path, output: Path): Future[Unit] =
    exec("ffmpeg", ToolArgs.ffmpegPoster(input.toString, output.toString)).map(_ => ())

  def transcode720p(input: Path, output: Path): Future[Unit] =
    exec("ffmpeg", ToolArgs.ffmpegTranscode720p(input.toString, output.toString)).map(_ => ())

  def grayscaleRaster(input: Path, size: Int): Future[GrayscaleRaster] =
    val forced = Files.createTempFile("heph-phash-", ".png")
    val gray = Files.createTempFile("heph-phash-", ".pgm")
    val work =
      for
        _ <- exec("vips", ToolArgs.vipsForceSize(input.toString, forced.toString, size))
        _ <- exec("vips", ToolArgs.vipsColourspaceBW(forced.toString, gray.toString))
        raster <- Future.fromTry(scala.util.Try(readPgm(gray, size)))
      yield raster
    work.andThen { case _ =>
      Files.deleteIfExists(forced)
      Files.deleteIfExists(gray)
    }

  // --- process exec ----------------------------------------------------------

  private def exec(tool: String, argv: Seq[String]): Future[String] =
    Future(blocking(runner(argv))).flatMap { result =>
      if result.exitCode == 0 then Future.successful(result.stdout)
      else
        Future.failed(
          MediaError.ToolFailed(tool, s"exit ${result.exitCode}: ${firstLine(result.stderr)}")
        )
    }

  private def headerInt(input: Path, field: String): Future[Int] =
    exec("vipsheader", ToolArgs.vipsHeaderField(input.toString, field)).flatMap { out =>
      out.trim.toIntOption match
        case Some(v) => Future.successful(v)
        case None =>
          Future.failed(MediaError.CorruptInput(s"vipsheader $field not an int: '${out.trim}'"))
    }

  private def firstLine(s: String): String =
    s.linesIterator.find(_.trim.nonEmpty).getOrElse("").trim

  // --- ffprobe JSON ----------------------------------------------------------

  private def parseVideoInfo(stdout: String): VideoInfo =
    val obj = stdout.parseJson.asJsObject
    val streams = obj.fields.get("streams").collect { case JsArray(a) => a }.getOrElse(Vector.empty)
    val video = streams.find(s => str(s, "codec_type").contains("video"))
    val audio = streams.exists(s => str(s, "codec_type").contains("audio"))

    val width = video.flatMap(int(_, "width")).getOrElse(0)
    val height = video.flatMap(int(_, "height")).getOrElse(0)
    val fps = video.flatMap(str(_, "r_frame_rate")).flatMap(parseRational)
    val duration = obj.fields
      .get("format")
      .flatMap(f => str(f, "duration"))
      .orElse(video.flatMap(str(_, "duration")))
      .flatMap(_.toDoubleOption)
      .filter(d => !d.isNaN && d >= 0)

    VideoInfo(Dimensions(width, height), duration, fps, audio)

  private def parseRational(s: String): Option[Double] =
    s.split('/') match
      case Array(n, d) =>
        for
          num <- n.trim.toDoubleOption
          den <- d.trim.toDoubleOption
          if den != 0.0
        yield num / den
      case Array(single) => single.trim.toDoubleOption
      case _ => None

  private def str(v: JsValue, field: String): Option[String] =
    v.asJsObject.fields.get(field).collect { case JsString(s) => s }

  private def int(v: JsValue, field: String): Option[Int] =
    v.asJsObject.fields.get(field).collect {
      case JsNumber(n) => n.toInt
      case JsString(s) if s.toIntOption.isDefined => s.toInt
    }

  // --- PGM (binary P5) raster ------------------------------------------------

  /**
   * Parse a binary P5 PGM (`P5 <w> <h> <maxval>` header, then one byte per pixel) into a row-major
   * grayscale raster. libvips emits an 8-bit single-band PGM from the forced-size b-w step, so the
   * pixel plane is exactly `size`×`size` bytes.
   */
  private def readPgm(path: Path, size: Int): GrayscaleRaster =
    val bytes = Files.readAllBytes(path)
    var pos = 0
    def failCorrupt(why: String): Nothing = throw MediaError.CorruptInput(s"pgm: $why")

    def skipWhitespaceAndComments(): Unit =
      var progressing = true
      while progressing do
        progressing = false
        while pos < bytes.length && isWhitespace(bytes(pos)) do { pos += 1; progressing = true }
        if pos < bytes.length && bytes(pos) == '#'.toByte then
          while pos < bytes.length && bytes(pos) != '\n'.toByte do pos += 1
          progressing = true

    def nextToken(): String =
      skipWhitespaceAndComments()
      val start = pos
      while pos < bytes.length && !isWhitespace(bytes(pos)) do pos += 1
      if pos == start then failCorrupt("unexpected end of header")
      new String(bytes, start, pos - start, java.nio.charset.StandardCharsets.US_ASCII)

    val magic = nextToken()
    if magic != "P5" then failCorrupt(s"unsupported magic '$magic' (expected P5)")
    val width = nextToken().toIntOption.getOrElse(failCorrupt("bad width"))
    val height = nextToken().toIntOption.getOrElse(failCorrupt("bad height"))
    val maxval = nextToken().toIntOption.getOrElse(failCorrupt("bad maxval"))
    if maxval > 255 then failCorrupt(s"16-bit pgm unsupported (maxval $maxval)")
    if width != size || height != size then
      failCorrupt(s"expected ${size}x$size, got ${width}x$height")
    // Exactly one whitespace byte separates the header from the binary plane.
    pos += 1
    val expected = width * height
    if bytes.length - pos < expected then failCorrupt("truncated pixel plane")
    val pixels = IArray.tabulate(expected)(i => bytes(pos + i) & 0xff)
    GrayscaleRaster(size, pixels)

  private def isWhitespace(b: Byte): Boolean =
    b == ' '.toByte || b == '\n'.toByte || b == '\r'.toByte || b == '\t'.toByte
