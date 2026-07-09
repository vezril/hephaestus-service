package me.cference.hephaestus.media

import me.cference.hephaestus.apollo.ApolloError
import me.cference.hephaestus.storage.Md5
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * Orchestration tests over the pipeline with a recording fake [[MediaTools]] and an in-memory
 * [[ApolloClient]]: they assert the right tools are driven with the right args, derivatives land at
 * content-addressed keys, the scratch area is cleaned up on success AND failure, and failures are
 * classified terminal vs retriable. No real media binaries are involved (that is the integration
 * spec's job).
 */
final class MediaPipelineSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(1, Seconds))

  private val bucket = "media"
  private val spec = DerivativeSpec(250, 850, 850, "v1")
  private val allWant = DerivativeKind.values.toSet

  private def freshScratchRoot(): Path = Files.createTempDirectory("heph-root-")

  private def hephDirsUnder(root: Path): Seq[Path] =
    Files
      .list(root)
      .iterator()
      .asScala
      .filter(p => Files.isDirectory(p) && p.getFileName.toString.startsWith("heph-"))
      .toSeq

  private def descriptor(mediaType: String, contentType: String, key: String) =
    MediaDescriptor(bucket, key, mediaType, contentType, allWant, spec)

  "process — media type detection" should {
    "fail terminally on an unsupported type, running no tool" in {
      val apollo = new InMemoryApollo()
      val tools = new FakeMediaTools()
      val pipeline = new MediaPipeline(apollo, tools, freshScratchRoot())
      val result =
        pipeline.process(descriptor("", "application/pdf", "orig")).futureValue
      result match
        case Left(e: MediaError.UnsupportedType) => e.retriable shouldBe false
        case other => fail(s"expected UnsupportedType, got $other")
      tools.calls shouldBe empty
      apollo.writes shouldBe empty
    }
  }

  "process — image path" should {
    "produce thumb + sample, phash, and content-addressed keys for a large image" in {
      val apollo = new InMemoryApollo()
      val bytes = "a-large-original".getBytes("UTF-8")
      val md5 = Md5.hex(bytes)
      apollo.seedOriginal(bucket, "img", bytes, "image/png")
      val tools = new FakeMediaTools(imageDims = Dimensions(4000, 3000))
      val pipeline = new MediaPipeline(apollo, tools, freshScratchRoot())

      val result = pipeline.process(descriptor("", "image/png", "img")).futureValue
      val ok = result.getOrElse(fail(s"expected success, got $result"))

      ok.metadata.width shouldBe 4000
      ok.metadata.height shouldBe 3000
      ok.metadata.duration shouldBe None
      ok.metadata.hasAudio shouldBe None
      ok.metadata.md5 shouldBe md5
      ok.metadata.filetype shouldBe "png"
      ok.phash should fullyMatch regex "[0-9a-f]{16}"
      ok.derivativeSpecVersion shouldBe "v1"
      ok.derivatives.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail, DerivativeKind.Sample)

      apollo.writes.keySet should contain((bucket, s"derivatives/${md5.take(2)}/$md5/thumb.webp"))
      apollo.writes.keySet should contain((bucket, s"derivatives/${md5.take(2)}/$md5/sample.webp"))
      tools.calls should contain allOf (
        "thumbnail:250",
        "thumbnail:850",
        s"grayscaleRaster:${PerceptualHash.RasterSize}"
      )
    }

    "produce only a thumbnail for a small image (no sample)" in {
      val apollo = new InMemoryApollo()
      apollo.seedOriginal(bucket, "small", "tiny".getBytes("UTF-8"), "image/jpeg")
      val tools = new FakeMediaTools(imageDims = Dimensions(800, 600))
      val pipeline = new MediaPipeline(apollo, tools, freshScratchRoot())

      val ok = pipeline
        .process(descriptor("", "image/jpeg", "small"))
        .futureValue
        .getOrElse(fail("expected success"))

      ok.derivatives.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail)
      tools.calls should contain("thumbnail:250")
      tools.calls should not contain "thumbnail:850"
      apollo.writes.keySet.map(_._2).exists(_.endsWith("sample.webp")) shouldBe false
    }
  }

  "process — video path" should {
    "produce poster thumb/sample + a 720p transcode with duration/fps/hasAudio" in {
      val apollo = new InMemoryApollo()
      apollo.seedOriginal(bucket, "vid", "a-video".getBytes("UTF-8"), "video/mp4")
      val tools = new FakeMediaTools(
        videoInfo = VideoInfo(Dimensions(1920, 1080), Some(12.0), Some(30.0), hasAudio = true)
      )
      val pipeline = new MediaPipeline(apollo, tools, freshScratchRoot())

      val ok = pipeline
        .process(descriptor("", "video/mp4", "vid"))
        .futureValue
        .getOrElse(fail("expected success"))

      ok.metadata.duration shouldBe Some(12.0)
      ok.metadata.fps shouldBe Some(30.0)
      ok.metadata.hasAudio shouldBe Some(true)
      ok.derivatives.map(_.kind) shouldBe Seq(
        DerivativeKind.Thumbnail,
        DerivativeKind.Sample,
        DerivativeKind.Transcode
      )
      val transcode = ok.derivatives.last
      transcode.variant shouldBe Some("720p")
      transcode.codec shouldBe Some("h264")
      transcode.width shouldBe 1280
      transcode.height shouldBe 720
      tools.calls should contain allOf ("probeVideo", "posterFrame", "transcode720p")
    }

    "report hasAudio=false when the video has no audio track" in {
      val apollo = new InMemoryApollo()
      apollo.seedOriginal(bucket, "mute", "silent".getBytes("UTF-8"), "video/mp4")
      val tools = new FakeMediaTools(
        videoInfo = VideoInfo(Dimensions(1280, 720), Some(5.0), Some(24.0), hasAudio = false)
      )
      val pipeline = new MediaPipeline(apollo, tools, freshScratchRoot())

      val ok = pipeline
        .process(descriptor("", "video/mp4", "mute"))
        .futureValue
        .getOrElse(fail("expected success"))
      ok.metadata.hasAudio shouldBe Some(false)
    }
  }

  "process — animated path" should {
    "produce poster thumb + sample and no transcode" in {
      val apollo = new InMemoryApollo()
      apollo.seedOriginal(bucket, "gif", "anim".getBytes("UTF-8"), "image/gif")
      val tools = new FakeMediaTools(
        videoInfo = VideoInfo(Dimensions(1200, 900), Some(3.0), Some(15.0), hasAudio = false)
      )
      val pipeline = new MediaPipeline(apollo, tools, freshScratchRoot())

      val ok = pipeline
        .process(descriptor("", "image/gif", "gif"))
        .futureValue
        .getOrElse(fail("expected success"))
      ok.derivatives.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail, DerivativeKind.Sample)
      tools.calls should not contain "transcode720p"
    }
  }

  "process — cleanup" should {
    "remove the scratch area after a successful job" in {
      val root = freshScratchRoot()
      val apollo = new InMemoryApollo()
      apollo.seedOriginal(bucket, "img", "bytes".getBytes("UTF-8"), "image/png")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), root)
      pipeline.process(descriptor("", "image/png", "img")).futureValue.isRight shouldBe true
      hephDirsUnder(root) shouldBe empty
    }

    "remove the scratch area even when a tool fails mid-job" in {
      val root = freshScratchRoot()
      val apollo = new InMemoryApollo()
      apollo.seedOriginal(bucket, "vid", "bytes".getBytes("UTF-8"), "video/mp4")
      val tools = new FakeMediaTools(failOn = Set("transcode720p"))
      val pipeline = new MediaPipeline(apollo, tools, root)

      val result = pipeline.process(descriptor("", "video/mp4", "vid")).futureValue
      result match
        case Left(e: MediaError.ToolFailed) => e.retriable shouldBe false
        case other => fail(s"expected terminal ToolFailed, got $other")
      hephDirsUnder(root) shouldBe empty
    }
  }

  "process — failure classification" should {
    "pass through a retriable Apollo outage on read" in {
      val apollo = new InMemoryApollo(readFutureFailure =
        Some(ApolloError.Unavailable("readOriginal", "apollo down"))
      )
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshScratchRoot())
      val result = pipeline.process(descriptor("", "image/png", "img")).futureValue
      result match
        case Left(e: MediaError.Upstream) => e.retriable shouldBe true
        case other => fail(s"expected retriable Upstream, got $other")
      apollo.writes shouldBe empty
    }

    "treat a terminal md5 mismatch on read as terminal, writing nothing" in {
      val apollo = new InMemoryApollo(readStreamFailure =
        Some(ApolloError.Md5Mismatch(bucket, "img", "aa" * 16, "bb" * 16))
      )
      apollo.seedOriginal(bucket, "img", "corrupt".getBytes("UTF-8"), "image/png")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshScratchRoot())
      val result = pipeline.process(descriptor("", "image/png", "img")).futureValue
      result match
        case Left(e: MediaError.Upstream) => e.retriable shouldBe false
        case other => fail(s"expected terminal Upstream, got $other")
      apollo.writes shouldBe empty
    }
  }
