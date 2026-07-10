package me.cference.hephaestus.report

import codex.messages.v1 as pb
import me.cference.hephaestus.apollo.ApolloError
import me.cference.hephaestus.job.*
import me.cference.hephaestus.media.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.OptionValues
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import scalapb.json4s.JsonFormat

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
 * End-to-end closure of the reporting loop: the REAL §3 [[JobConsumer]] driving the REAL §2
 * [[MediaPipeline]] (over an in-process Apollo + fake tools) with the REAL §4
 * [[HermesResultPublisher]] over a fake [[ResultSink]]. Asserts the loop routes outcomes to the
 * right topics AND respects publish-before-ack / leave-unacked-on-transient.
 */
final class ResultReportingIntegrationSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with Eventually:

  private given ec: ExecutionContext = system.executionContext

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(25, Millis))

  private val bucket = "media"
  private val spec = DerivativeSpec(250, 850, 850, "1")
  private val settings =
    JobConsumer.Settings(batchSize = 1, concurrency = 1, pollInterval = 40.millis)

  private def imageJob(jobId: String, key: String): DecodedJob =
    DecodedJob(
      jobId,
      s"post-$jobId",
      MediaDescriptor(bucket, key, "image", "image/png", DerivativeKind.values.toSet, spec)
    )

  private def seedImage(apollo: InMemoryApollo, key: String): Unit =
    apollo.seedOriginal(bucket, key, s"orig-$key".getBytes("UTF-8"), "image/png")

  private def publisherOver(sink: ResultSink): HermesResultPublisher =
    new HermesResultPublisher(sink, "media.processed", "media.failed", bucket)

  private def decoderFor(
      table: Map[String, Either[DecodeError, DecodedJob]]
  ): String => Either[DecodeError, DecodedJob] =
    payload => table.getOrElse(payload, Left(DecodeError(s"unknown payload: $payload")))

  "a successful job" should {
    "publish a well-formed MediaProcessed to media.processed BEFORE the ack" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      seedImage(apollo, "k1")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val sink = new FakeResultSink(events = Some(events))
      val decode = decoderFor(Map("job-1" -> Right(imageJob("job-1", "k1"))))
      source.offer(Lane.Ingest, Envelope("ack-job-1", "job-1"))

      val consumer =
        new JobConsumer(source, pipeline, publisherOver(sink), decode, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain("ack-job-1"))
      consumer.drain().futureValue

      sink.captured should have size 1
      val c = sink.captured.head
      c.topic shouldBe "media.processed"
      c.attributes shouldBe Map("jobId" -> "job-1", "postId" -> "post-job-1")
      val processed = JsonFormat.fromJsonString[pb.MediaProcessed](c.payload)
      processed.status shouldBe "ok"
      processed.jobId shouldBe "job-1"
      processed.derivatives should not be empty
      // Publish strictly before ack.
      events.indexOf("publish:media.processed") should be < events.indexOf("ack:ack-job-1")
    }
  }

  "a terminally-failing job" should {
    "publish a MediaFailed to media.failed then ack (no redelivery)" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val sink = new FakeResultSink(events = Some(events))
      // An unsupported content type fails terminally before any Apollo read.
      val badJob = DecodedJob(
        "job-x",
        "post-x",
        MediaDescriptor(bucket, "kx", "", "application/pdf", Set.empty, spec)
      )
      val decode = decoderFor(Map("job-x" -> Right(badJob)))
      source.offer(Lane.Ingest, Envelope("ack-job-x", "job-x"))

      val consumer =
        new JobConsumer(source, pipeline, publisherOver(sink), decode, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain("ack-job-x"))
      consumer.drain().futureValue

      sink.captured should have size 1
      sink.captured.head.topic shouldBe "media.failed"
      val failed = JsonFormat.fromJsonString[pb.MediaFailed](sink.captured.head.payload)
      failed.retriable shouldBe false
      failed.error.value.code shouldBe "unsupported_type"
      events.indexOf("publish:media.failed") should be < events.indexOf("ack:ack-job-x")
    }
  }

  "a transiently-failing job" should {
    "publish NOTHING and leave the message unacked for redelivery" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo(readFutureFailure =
        Some(ApolloError.Unavailable("readOriginal", "apollo down"))
      )
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val sink = new FakeResultSink(events = Some(events))
      // A "seal" (undecodable ⇒ AckOnly) is a deterministic marker the loop moved past the transient
      // job (batch=1, concurrency=1), so once acked we can prove job-t was neither published nor acked.
      val decode = decoderFor(Map("job-t" -> Right(imageJob("job-t", "kt"))))
      source.offer(Lane.Ingest, Envelope("ack-job-t", "job-t"), Envelope("ack-seal", "seal"))

      val consumer =
        new JobConsumer(source, pipeline, publisherOver(sink), decode, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain("ack-seal"))
      consumer.drain().futureValue

      source.acked shouldBe Seq("ack-seal") // job-t never acked
      sink.captured shouldBe empty // nothing published for the transient failure
    }
  }

  private def freshRoot(): java.nio.file.Path =
    java.nio.file.Files.createTempDirectory("heph-report-")
