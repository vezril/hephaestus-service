package me.cference.hephaestus.media

import apollostorage.grpc.ObjectMetadata
import me.cference.hephaestus.apollo.{ApolloClient, ApolloError}
import me.cference.hephaestus.storage.StorageKey
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try, Using}

/**
 * The forge. `process(descriptor)` turns an original into a [[MediaResult]] (or a terminal
 * [[MediaError]]): detect the media type → read + md5-verify the original from Apollo (§1) → stage
 * it to a temp scratch file → run the per-type media tools → compute the perceptual hash → write
 * each derivative back content-addressed → assemble the result. The scratch area is removed on both
 * success and failure. Failure classification: a bad type / corrupt input / tool crash is terminal;
 * an Apollo transient carries through its own `retriable` flag.
 */
final class MediaPipeline(
    apollo: ApolloClient,
    tools: MediaTools,
    scratchRoot: Path
)(using system: ActorSystem[?]):

  private given ec: ExecutionContext = system.executionContext
  private given mat: Materializer = Materializer.matFromSystem(using system)

  def process(descriptor: MediaDescriptor): Future[Either[MediaError, MediaResult]] =
    MediaType.from(descriptor.mediaType, descriptor.contentType) match
      case Left(unsupported) =>
        // Terminal before any tool (or even any Apollo read) runs.
        Future.successful(Left(MediaError.UnsupportedType(unsupported.detail)))
      case Right(mediaType) =>
        // Create the scratch dir inside the effect boundary so a scratch-root failure yields a
        // Left rather than throwing synchronously out of `process`.
        Future.fromTry(Try(Files.createTempDirectory(scratchRoot, "heph-"))).transformWith {
          case Failure(error) => Future.successful(Left(toMediaError(error)))
          case Success(scratch) =>
            val work =
              try
                stageOriginal(descriptor, scratch).flatMap { case (meta, staged) =>
                  forge(mediaType, descriptor, meta, staged, scratch)
                }
              catch { case NonFatal(e) => Future.failed(e) }
            work.transformWith { outcome =>
              deleteRecursively(scratch)
              outcome match
                case Success(result) => Future.successful(Right(result))
                case Failure(error) => Future.successful(Left(toMediaError(error)))
            }
        }

  // --- staging ---------------------------------------------------------------

  /**
   * Read the original from Apollo and stream it to a temp file. The read Source md5-verifies
   * against the metadata header while streaming, so a mismatch/truncation fails this stage
   * terminally (via §1's typed error) before any derivative is produced.
   */
  private def stageOriginal(
      descriptor: MediaDescriptor,
      scratch: Path
  ): Future[(ObjectMetadata, Path)] =
    apollo.readOriginal(descriptor.bucket, descriptor.key).flatMap { case (meta, bytes) =>
      val target = scratch.resolve("original")
      bytes.runWith(FileIO.toPath(target)).map(_ => (meta, target))
    }

  // --- per-type forging ------------------------------------------------------

  private def forge(
      mediaType: MediaType,
      descriptor: MediaDescriptor,
      meta: ObjectMetadata,
      staged: Path,
      scratch: Path
  ): Future[MediaResult] =
    mediaType match
      case MediaType.Image => forgeImage(descriptor, meta, staged, scratch)
      case MediaType.Animated => forgeFramed(MediaType.Animated, descriptor, meta, staged, scratch)
      case MediaType.Video => forgeFramed(MediaType.Video, descriptor, meta, staged, scratch)

  /** Image path: vips thumb/sample straight off the staged raster; phash from the same raster. */
  private def forgeImage(
      descriptor: MediaDescriptor,
      meta: ObjectMetadata,
      staged: Path,
      scratch: Path
  ): Future[MediaResult] =
    for
      info <- tools.probeImage(staged)
      outputs <- planOrFail(MediaType.Image, descriptor, info.dimensions, meta.md5)
      _ <- produceAll(outputs, rasterSource = staged, videoSource = staged, scratch)
      phash <- tools.grayscaleRaster(staged, PerceptualHash.RasterSize).map(PerceptualHash.compute)
      refs <- writeAll(descriptor.bucket, outputs, scratch)
    yield MediaResult(
      MediaMetadata(
        width = info.dimensions.width,
        height = info.dimensions.height,
        duration = None,
        fps = None,
        filesize = meta.size,
        md5 = meta.md5,
        filetype = filetype(descriptor),
        hasAudio = None
      ),
      phash.hex,
      refs,
      descriptor.spec.specVersion
    )

  /**
   * Video/animated path: ffprobe metadata, an ffmpeg poster frame used as the vips raster source
   * for thumb/sample (and for the phash), plus — for video only — the eager 720p transcode off the
   * staged original.
   */
  private def forgeFramed(
      mediaType: MediaType,
      descriptor: MediaDescriptor,
      meta: ObjectMetadata,
      staged: Path,
      scratch: Path
  ): Future[MediaResult] =
    val poster = scratch.resolve("poster.png")
    for
      info <- tools.probeVideo(staged)
      _ <- tools.posterFrame(staged, poster)
      outputs <- planOrFail(mediaType, descriptor, info.dimensions, meta.md5)
      _ <- produceAll(outputs, rasterSource = poster, videoSource = staged, scratch)
      phash <- tools.grayscaleRaster(poster, PerceptualHash.RasterSize).map(PerceptualHash.compute)
      refs <- writeAll(descriptor.bucket, outputs, scratch)
    yield MediaResult(
      MediaMetadata(
        width = info.dimensions.width,
        height = info.dimensions.height,
        duration = info.duration,
        fps = info.fps,
        filesize = meta.size,
        md5 = meta.md5,
        filetype = filetype(descriptor),
        hasAudio = Some(info.hasAudio)
      ),
      phash.hex,
      refs,
      descriptor.spec.specVersion
    )

  // --- producing + writing ---------------------------------------------------

  private def planOrFail(
      mediaType: MediaType,
      descriptor: MediaDescriptor,
      source: Dimensions,
      md5: String
  ): Future[Seq[DerivativeOutput]] =
    DerivativePlan.plan(mediaType, descriptor.want, source, md5, descriptor.spec) match
      case Right(outputs) => Future.successful(outputs)
      case Left(StorageKey.KeyError(message)) => Future.failed(MediaError.PlanFailed(message))

  /** Produce every planned derivative FILE into the scratch dir (in declared order). */
  private def produceAll(
      outputs: Seq[DerivativeOutput],
      rasterSource: Path,
      videoSource: Path,
      scratch: Path
  ): Future[Unit] =
    sequentially(outputs) { output =>
      val out = scratch.resolve(output.name)
      output.kind match
        case DerivativeKind.Thumbnail | DerivativeKind.Sample =>
          tools.thumbnail(rasterSource, out, output.dimensions.longEdge)
        case DerivativeKind.Transcode =>
          tools.transcode720p(videoSource, out)
    }.map(_ => ())

  /** Write every produced derivative to Apollo, content-addressed, and collect the refs. */
  private def writeAll(
      bucket: String,
      outputs: Seq[DerivativeOutput],
      scratch: Path
  ): Future[Seq[DerivativeRef]] =
    sequentially(outputs) { output =>
      val file = scratch.resolve(output.name)
      val data: Source[ByteString, NotUsed] =
        FileIO.fromPath(file).mapMaterializedValue(_ => NotUsed)
      apollo.writeDerivative(bucket, output.key, output.contentType, data).map { _ =>
        DerivativeRef(
          output.kind,
          output.key,
          output.dimensions.width,
          output.dimensions.height,
          output.variant,
          output.codec
        )
      }
    }

  // --- helpers ---------------------------------------------------------------

  private def filetype(descriptor: MediaDescriptor): String =
    val ct = descriptor.contentType.trim.toLowerCase
    val slash = ct.indexOf('/')
    if slash >= 0 && slash < ct.length - 1 then ct.substring(slash + 1) else ct

  /** Run `f` over `xs` strictly in order, collecting results (no interleaving of side effects). */
  private def sequentially[A, B](xs: Seq[A])(f: A => Future[B]): Future[Seq[B]] =
    xs.foldLeft(Future.successful(Vector.empty[B])) { (acc, a) =>
      acc.flatMap(bs => f(a).map(bs :+ _))
    }

  private def toMediaError(t: Throwable): MediaError =
    // A stream failure (e.g. a terminal md5 mismatch on the verified read) is wrapped by Pekko's
    // IOOperationIncompleteException before it reaches us, so classify by walking the cause chain
    // for the first typed error rather than only the outermost throwable.
    causeChain(t)
      .collectFirst {
        case e: MediaError => e
        case e: ApolloError => MediaError.Upstream("apollo", e.getMessage, e.retriable)
      }
      .getOrElse {
        t match
          case NonFatal(e) =>
            MediaError.Unexpected(Option(e.getMessage).getOrElse(e.getClass.getName))
          case fatal => throw fatal
      }

  private def causeChain(t: Throwable): List[Throwable] =
    val seen = scala.collection.mutable.ListBuffer.empty[Throwable]
    var current: Throwable | Null = t
    while current != null && !seen.contains(current) do
      seen += current
      current = current.getCause
    seen.toList

  private def deleteRecursively(dir: Path): Unit =
    try
      if Files.exists(dir) then
        // Using.resource closes the walk's directory handle (a lazy Stream over an open fd) — it
        // would otherwise leak one fd per job.
        Using.resource(Files.walk(dir)) { walk =>
          walk
            .sorted(java.util.Comparator.reverseOrder())
            .iterator()
            .asScala
            .foreach(p => Files.deleteIfExists(p))
        }
    catch { case NonFatal(_) => () }

object MediaPipeline:

  /** Construct with the JVM's default temp dir as the scratch root. */
  def apply(apollo: ApolloClient, tools: MediaTools)(using
      system: ActorSystem[?]
  ): MediaPipeline =
    new MediaPipeline(apollo, tools, Paths.get(System.getProperty("java.io.tmpdir")))
