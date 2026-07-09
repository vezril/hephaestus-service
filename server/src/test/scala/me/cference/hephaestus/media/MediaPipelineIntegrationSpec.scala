package me.cference.hephaestus.media

import apollostorage.grpc.*
import com.google.protobuf.ByteString as ProtoBytes
import com.typesafe.config.ConfigFactory
import io.grpc.Status
import me.cference.hephaestus.apollo.{ApolloClient, LexiconApolloClient}
import me.cference.hephaestus.storage.Md5
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.grpc.{GrpcClientSettings, GrpcServiceException}
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * End-to-end integration over the REAL media tools (vips/ffmpeg/ffprobe) and the real §1
 * [[LexiconApolloClient]] against an in-process `ObjectApi` gRPC stub (mirroring the §1
 * `LexiconApolloClientSpec` stub) — so reads (md5-verified) and content-addressed writes are
 * exercised for real. Fixtures are generated at test time with ffmpeg's `lavfi` sources (no binary
 * blobs committed). Runs only where the toolchain is installed (CI installs ffmpeg +
 * libvips-tools); otherwise the cases self-cancel.
 */
final class MediaPipelineIntegrationSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("pekko.http.server.preview.enable-http2 = on")
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(1, Seconds))

  private given ec: scala.concurrent.ExecutionContext = system.executionContext

  private val bucket = "media"
  private val spec = DerivativeSpec(250, 850, 850, "v1")
  private val allWant = DerivativeKind.values.toSet

  private var stub: StubObjectApi = scala.compiletime.uninitialized
  private var apollo: ApolloClient = scala.compiletime.uninitialized
  private var pipeline: MediaPipeline = scala.compiletime.uninitialized
  private val toolsOk = hasTool(Seq("ffmpeg", "-version")) &&
    hasTool(Seq("ffprobe", "-version")) && hasTool(Seq("vips", "--version"))

  override protected def beforeAll(): Unit =
    super.beforeAll()
    stub = new StubObjectApi
    val handler: HttpRequest => Future[HttpResponse] = ObjectApiPowerApiHandler(stub)
    val binding = Http()(system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
    val client = ObjectApiClient(
      GrpcClientSettings
        .connectToServiceAt("127.0.0.1", binding.localAddress.getPort)(system)
        .withTls(false)
    )
    apollo = new LexiconApolloClient(client)
    pipeline = new MediaPipeline(
      apollo,
      new RealMediaTools(ProcessCommandRunner.run),
      Files.createTempDirectory("heph-it-")
    )

  // --- helpers ---------------------------------------------------------------

  private def hasTool(argv: Seq[String]): Boolean =
    try ProcessCommandRunner.run(argv).exitCode == 0
    catch { case _: Throwable => false }

  private def source(bytes: Array[Byte]): Source[ByteString, NotUsed] =
    Source(bytes.grouped(4096).map(ByteString.fromArrayUnsafe).toList)

  private def generate(argv: Seq[String], suffix: String): Array[Byte] =
    val out = Files.createTempFile("heph-fixture-", suffix)
    Files.deleteIfExists(out)
    val result = ProcessCommandRunner.run(argv :+ out.toString)
    withClue(s"ffmpeg exit ${result.exitCode}: ${result.stderr.takeRight(600)}") {
      result.exitCode shouldBe 0
    }
    val bytes = Files.readAllBytes(out)
    Files.deleteIfExists(out)
    bytes

  private def seed(key: String, bytes: Array[Byte], contentType: String): String =
    apollo.writeDerivative(bucket, key, contentType, source(bytes)).futureValue
    Md5.hex(bytes)

  private def storedBytes(key: String): Array[Byte] =
    stub.bytesAt(bucket, key).getOrElse(fail(s"nothing stored at $key"))

  private def probeStoredWidth(key: String, suffix: String): Int =
    val tmp = Files.createTempFile("heph-probe-", suffix)
    Files.write(tmp, storedBytes(key))
    val res = ProcessCommandRunner.run(ToolArgs.vipsHeaderField(tmp.toString, "width"))
    Files.deleteIfExists(tmp)
    res.stdout.trim.toInt

  private def isWebp(bytes: Array[Byte]): Boolean =
    bytes.length > 12 &&
      new String(bytes, 0, 4, "US-ASCII") == "RIFF" &&
      new String(bytes, 8, 4, "US-ASCII") == "WEBP"

  private def descriptor(contentType: String, key: String) =
    MediaDescriptor(bucket, key, "", contentType, allWant, spec)

  // --- image -----------------------------------------------------------------

  "a real jpeg/png image" should {
    "yield a thumb + sample webp at content-addressed keys with correct dims and a stable phash" in {
      assume(toolsOk, "media toolchain not installed")
      val bytes = generate(
        Seq(
          "ffmpeg",
          "-y",
          "-f",
          "lavfi",
          "-i",
          "testsrc=size=1000x750:duration=1",
          "-frames:v",
          "1"
        ),
        ".png"
      )
      val md5 = seed("originals/img.png", bytes, "image/png")

      val ok = pipeline
        .process(descriptor("image/png", "originals/img.png"))
        .futureValue
        .getOrElse(fail("expected success"))

      ok.metadata.width shouldBe 1000
      ok.metadata.height shouldBe 750
      ok.metadata.md5 shouldBe md5
      ok.metadata.filesize shouldBe bytes.length.toLong
      ok.metadata.duration shouldBe None
      ok.phash should fullyMatch regex "[0-9a-f]{16}"
      ok.derivatives.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail, DerivativeKind.Sample)

      val thumbKey = s"derivatives/${md5.take(2)}/$md5/thumb.webp"
      val sampleKey = s"derivatives/${md5.take(2)}/$md5/sample.webp"
      isWebp(storedBytes(thumbKey)) shouldBe true
      isWebp(storedBytes(sampleKey)) shouldBe true
      probeStoredWidth(thumbKey, ".webp") shouldBe 250
      probeStoredWidth(sampleKey, ".webp") shouldBe 850

      // Determinism: reprocessing the identical original yields the identical phash.
      val again = pipeline
        .process(descriptor("image/png", "originals/img.png"))
        .futureValue
        .getOrElse(fail("expected success"))
      again.phash shouldBe ok.phash
    }
  }

  // --- video -----------------------------------------------------------------

  "a real short mp4 with audio" should {
    "yield a poster thumb/sample + a 720p transcode with duration/fps/hasAudio" in {
      assume(toolsOk, "media toolchain not installed")
      val bytes = generate(
        Seq(
          "ffmpeg",
          "-y",
          "-f",
          "lavfi",
          "-i",
          "testsrc=size=1280x720:rate=30:duration=2",
          "-f",
          "lavfi",
          "-i",
          "sine=frequency=440:duration=2",
          "-c:v",
          "libx264",
          "-pix_fmt",
          "yuv420p",
          "-c:a",
          "aac",
          "-shortest"
        ),
        ".mp4"
      )
      val md5 = seed("originals/withaudio.mp4", bytes, "video/mp4")

      val ok = pipeline
        .process(descriptor("video/mp4", "originals/withaudio.mp4"))
        .futureValue
        .getOrElse(fail("expected success"))

      ok.metadata.width shouldBe 1280
      ok.metadata.height shouldBe 720
      ok.metadata.hasAudio shouldBe Some(true)
      ok.metadata.duration.getOrElse(0.0) should (be >= 1.5 and be <= 2.5)
      ok.metadata.fps.getOrElse(0.0) should (be >= 28.0 and be <= 31.0)
      ok.derivatives.map(_.kind) should contain allOf (
        DerivativeKind.Thumbnail,
        DerivativeKind.Sample,
        DerivativeKind.Transcode
      )

      val transcodeKey = s"derivatives/${md5.take(2)}/$md5/720p.mp4"
      val transcoded = storedBytes(transcodeKey)
      transcoded.length should be > 0
      // ffprobe the produced transcode: it must be a valid video no taller than 720.
      val probe = probeTranscode(transcoded)
      probe.dimensions.height should be <= 720
      isWebp(storedBytes(s"derivatives/${md5.take(2)}/$md5/thumb.webp")) shouldBe true
    }
  }

  "a real short mp4 without audio" should {
    "report hasAudio=false" in {
      assume(toolsOk, "media toolchain not installed")
      val bytes = generate(
        Seq(
          "ffmpeg",
          "-y",
          "-f",
          "lavfi",
          "-i",
          "testsrc=size=640x480:rate=24:duration=1",
          "-c:v",
          "libx264",
          "-pix_fmt",
          "yuv420p"
        ),
        ".mp4"
      )
      seed("originals/noaudio.mp4", bytes, "video/mp4")
      val ok = pipeline
        .process(descriptor("video/mp4", "originals/noaudio.mp4"))
        .futureValue
        .getOrElse(fail("expected success"))
      ok.metadata.hasAudio shouldBe Some(false)
    }
  }

  private def probeTranscode(bytes: Array[Byte]): VideoInfo =
    val tmp = Files.createTempFile("heph-probe-", ".mp4")
    Files.write(tmp, bytes)
    val real = new RealMediaTools(ProcessCommandRunner.run)
    val info = real.probeVideo(tmp).futureValue
    Files.deleteIfExists(tmp)
    info

  /**
   * Minimal in-memory `ObjectApiPowerApi` stub (a trimmed mirror of the §1 spec's stub): stores
   * objects with an honest computed md5/size so §1's read verification passes, and exposes the raw
   * bytes so the test can assert the derivatives that were written.
   */
  final private class StubObjectApi extends ObjectApiPowerApi:
    private given scala.concurrent.ExecutionContext = system.executionContext

    final private case class Stored(bytes: Array[Byte], contentType: String, md5: String, gen: Long)
    private val store = TrieMap.empty[(String, String), Stored]

    def bytesAt(bucket: String, obj: String): Option[Array[Byte]] =
      store.get((bucket, obj)).map(_.bytes)

    private def metadataFor(bucket: String, obj: String, s: Stored): ObjectMetadata =
      ObjectMetadata(bucket, obj, s.contentType, s.bytes.length.toLong, "", s.md5, s.gen)

    def putObject(
        in: Source[PutObjectRequest, NotUsed],
        metadata: Metadata
    ): Future[PutObjectResponse] =
      in.runWith(Sink.seq).map { msgs =>
        val header = msgs
          .map(_.payload)
          .collectFirst { case PutObjectRequest.Payload.Header(h) => h }
          .getOrElse(throw new GrpcServiceException(Status.INVALID_ARGUMENT))
        val bytes = msgs
          .map(_.payload)
          .collect { case PutObjectRequest.Payload.Chunk(b) => b.toByteArray }
          .toArray
          .flatten
        val md5 = Md5.hex(bytes)
        val gen = store.get((header.bucket, header.`object`)).map(_.gen + 1L).getOrElse(1L)
        store.update((header.bucket, header.`object`), Stored(bytes, header.contentType, md5, gen))
        PutObjectResponse(generation = gen, crc32C = "", md5 = md5, size = bytes.length.toLong)
      }

    def getObject(in: GetObjectRequest, metadata: Metadata): Source[GetObjectResponse, NotUsed] =
      store.get((in.bucket, in.`object`)) match
        case None =>
          Source
            .failed(new GrpcServiceException(Status.NOT_FOUND))
            .mapMaterializedValue(_ => NotUsed)
        case Some(s) =>
          val header = GetObjectResponse(
            GetObjectResponse.Payload.Header(metadataFor(in.bucket, in.`object`, s))
          )
          val chunks = s.bytes
            .grouped(4096)
            .map(b => GetObjectResponse(GetObjectResponse.Payload.Chunk(ProtoBytes.copyFrom(b))))
            .toList
          Source(header :: chunks)

    def headObject(in: HeadObjectRequest, metadata: Metadata): Future[ObjectMetadata] =
      store.get((in.bucket, in.`object`)) match
        case None => Future.failed(new GrpcServiceException(Status.NOT_FOUND))
        case Some(s) => Future.successful(metadataFor(in.bucket, in.`object`, s))

    private def unimplemented: GrpcServiceException =
      new GrpcServiceException(Status.UNIMPLEMENTED)
    def createBucket(in: CreateBucketRequest, metadata: Metadata): Future[BucketResponse] =
      Future.successful(BucketResponse(in.bucket))
    def deleteBucket(in: DeleteBucketRequest, metadata: Metadata): Future[BucketResponse] =
      Future.successful(BucketResponse(in.bucket))
    def deleteObject(in: DeleteObjectRequest, metadata: Metadata): Future[DeleteObjectResponse] =
      Future.failed(unimplemented)
    def listBuckets(in: ListBucketsRequest, metadata: Metadata): Future[ListBucketsResponse] =
      Future.failed(unimplemented)
    def listObjects(in: ListObjectsRequest, metadata: Metadata): Future[ListObjectsResponse] =
      Future.failed(unimplemented)
