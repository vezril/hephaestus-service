package me.cference.hephaestus.report

import codex.messages.v1 as pb
import me.cference.hephaestus.job.DecodedJob
import me.cference.hephaestus.media.*
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import scalapb.json4s.JsonFormat

/**
 * Behavioural tests for the real publisher over a fake [[ResultSink]]: a success publishes a
 * well-formed `MediaProcessed` to the processed topic with `jobId`/`postId` attributes, a terminal
 * failure publishes `MediaFailed` to the failed topic, a RETRIABLE failure publishes NOTHING and
 * fails the `Future` (so §3 leaves it unacked), a sink failure surfaces as a failed `Future`, and a
 * redelivery re-publishes byte-identical JSON.
 */
final class HermesResultPublisherSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with OptionValues:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  private val processedTopic = "media.processed"
  private val failedTopic = "media.failed"
  private val bucket = "media"
  private val spec = DerivativeSpec(250, 850, 850, "1")

  private def job(id: String) =
    DecodedJob(
      id,
      s"post-$id",
      MediaDescriptor(bucket, "k", "image", "image/png", Set.empty, spec)
    )

  private def imageResult =
    MediaResult(
      MediaMetadata(800, 600, None, None, 2048L, "a" * 32, "png", None),
      "d8b1861e78e199e7",
      Seq(DerivativeRef(DerivativeKind.Thumbnail, "derivatives/aa/x/thumb.webp", 250, 188)),
      "1"
    )

  private def publisher(sink: ResultSink) =
    new HermesResultPublisher(sink, processedTopic, failedTopic, bucket)

  "the publisher — success" should {
    "publish a MediaProcessed to the processed topic with jobId/postId attributes" in {
      val sink = new FakeResultSink()
      publisher(sink).publish(job("j1"), Right(imageResult)).futureValue

      sink.captured should have size 1
      val c = sink.captured.head
      c.topic shouldBe processedTopic
      c.attributes shouldBe Map("jobId" -> "j1", "postId" -> "post-j1")
      // The payload is a MediaProcessed in canonical JSON (parses back to the expected message).
      val decoded = JsonFormat.fromJsonString[pb.MediaProcessed](c.payload)
      decoded.status shouldBe "ok"
      decoded.jobId shouldBe "j1"
      decoded.derivatives.head.kind shouldBe "thumb"
      decoded.derivatives.head.ref.value.bucket shouldBe bucket
    }
  }

  "the publisher — terminal failure" should {
    "publish a MediaFailed to the failed topic with retriable=false" in {
      val sink = new FakeResultSink()
      val err = MediaError.CorruptInput("truncated")
      publisher(sink).publish(job("j2"), Left(err)).futureValue

      sink.captured should have size 1
      val c = sink.captured.head
      c.topic shouldBe failedTopic
      c.attributes shouldBe Map("jobId" -> "j2", "postId" -> "post-j2")
      val decoded = JsonFormat.fromJsonString[pb.MediaFailed](c.payload)
      decoded.retriable shouldBe false
      decoded.error.value.code shouldBe "corrupt_input"
      decoded.error.value.message shouldBe err.message
    }
  }

  "the publisher — retriable failure" should {
    "publish NOTHING and return a failed Future (left unacked for redelivery)" in {
      val sink = new FakeResultSink()
      val err = MediaError.Upstream("readOriginal", "apollo down", isRetriable = true)

      val f = publisher(sink).publish(job("j3"), Left(err))
      f.failed.futureValue shouldBe err
      sink.captured shouldBe empty
    }
  }

  "the publisher — sink failure" should {
    "surface a Hermes publish failure as a failed Future (no ack)" in {
      val boom = new RuntimeException("hermes 503")
      val sink = new FakeResultSink(failWith = Some(boom))
      publisher(sink).publish(job("j4"), Right(imageResult)).failed.futureValue shouldBe boom
    }
  }

  "the publisher — non-numeric spec-version" should {
    "fail the Future rather than publish a corrupt MediaProcessed" in {
      val sink = new FakeResultSink()
      val bad = imageResult.copy(derivativeSpecVersion = "v1")
      publisher(sink).publish(job("j5"), Right(bad)).failed.futureValue
      sink.captured shouldBe empty
    }
  }

  "the publisher — idempotent redelivery" should {
    "re-publish byte-identical JSON for the same result" in {
      val sink = new FakeResultSink()
      val p = publisher(sink)
      p.publish(job("j6"), Right(imageResult)).futureValue
      p.publish(job("j6"), Right(imageResult)).futureValue

      sink.captured should have size 2
      sink.captured(0).payload shouldBe sink.captured(1).payload
      sink.captured.map(_.topic).distinct shouldBe Seq(processedTopic)
    }
  }
