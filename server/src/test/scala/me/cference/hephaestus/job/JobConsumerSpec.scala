package me.cference.hephaestus.job

import me.cference.hephaestus.apollo.ApolloError
import me.cference.hephaestus.media.*
import me.cference.hephaestus.metrics.MetricsRecorder
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Promise}

/**
 * Loop tests for the two-lane priority consumer with the in-memory [[FakeMessageSource]] +
 * [[CapturingResultPublisher]], driving the REAL §2 [[MediaPipeline]] over an in-memory Apollo +
 * fake media tools. They assert the ack invariants end-to-end: ack-after-publish ordering,
 * ingest-before-reprocess priority, terminal→report+ack, transient→no-ack, idempotent redelivery,
 * one-bad-message-doesn't-wedge, and graceful drain.
 */
final class JobConsumerSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with Eventually:

  private given ec: ExecutionContext = system.executionContext

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(25, Millis))

  private val bucket = "media"
  private val spec = DerivativeSpec(250, 850, 850, "v1")
  private val settings =
    JobConsumer.Settings(batchSize = 4, concurrency = 2, pollInterval = 40.millis)

  private def imageJob(jobId: String, key: String): DecodedJob =
    DecodedJob(
      jobId,
      s"post-$jobId",
      MediaDescriptor(bucket, key, "image", "image/png", DerivativeKind.values.toSet, spec)
    )

  private def envelope(jobId: String): Envelope = Envelope(s"ack-$jobId", jobId)

  /**
   * A payload→outcome decode table + a call counter, driving the consumer without touching JSON.
   */
  final private class Decoder:
    val table: mutable.Map[String, Either[DecodeError, DecodedJob]] = mutable.Map.empty
    val calls: AtomicInteger = new AtomicInteger(0)
    def fn: String => Either[DecodeError, DecodedJob] = payload =>
      calls.incrementAndGet()
      table.getOrElse(payload, Left(DecodeError(s"unknown payload: $payload")))

  /** A [[MetricsRecorder]] that captures every recorded `(lane, outcome)` for assertion. */
  final private class RecordingRecorder extends MetricsRecorder:
    val outcomes: mutable.Buffer[(String, String)] = mutable.Buffer.empty
    def recordProcessed(lane: String, outcome: String): Unit =
      synchronized { outcomes += ((lane, outcome)); () }
    def observeSeconds(lane: String, seconds: Double): Unit = ()
    def inflightInc(): Unit = ()
    def inflightDec(): Unit = ()

  private def seedImage(apollo: InMemoryApollo, key: String): Unit =
    apollo.seedOriginal(bucket, key, s"orig-$key".getBytes("UTF-8"), "image/png")

  "the consumer — happy path" should {
    "ack strictly after publishing the result" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      seedImage(apollo, "k1")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val publisher = new CapturingResultPublisher(events)
      val decoder = new Decoder
      decoder.table("job-1") = Right(imageJob("job-1", "k1"))
      source.offer(Lane.Ingest, envelope("job-1"))

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain("ack-job-1"))
      consumer.drain().futureValue

      publisher.published.map(_._1.jobId) shouldBe Seq("job-1")
      publisher.published.head._2.isRight shouldBe true
      events.indexOf("publish:job-1") should be < events.indexOf("ack:ack-job-1")
    }
  }

  "the consumer — lane priority" should {
    "drain ingest fully before pulling reprocess" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      Seq("ka", "kb", "kc").foreach(seedImage(apollo, _))
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val publisher = new CapturingResultPublisher(events)
      val decoder = new Decoder
      decoder.table("job-a") = Right(imageJob("job-a", "ka"))
      decoder.table("job-b") = Right(imageJob("job-b", "kb"))
      decoder.table("job-c") = Right(imageJob("job-c", "kc"))
      source.offer(Lane.Ingest, envelope("job-a"), envelope("job-b"))
      source.offer(Lane.Reprocess, envelope("job-c"))

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain allOf ("ack-job-a", "ack-job-b", "ack-job-c"))
      consumer.drain().futureValue

      val reprocessAt = source.acked.indexOf("ack-job-c")
      source.acked.indexOf("ack-job-a") should be < reprocessAt
      source.acked.indexOf("ack-job-b") should be < reprocessAt
    }
  }

  "the consumer — terminal failure" should {
    "report the failure and ack (no poison redelivery)" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val publisher = new CapturingResultPublisher(events)
      val decoder = new Decoder
      // An unsupported content type fails terminally in the pipeline before any Apollo read.
      decoder.table("job-x") = Right(
        DecodedJob(
          "job-x",
          "post-x",
          MediaDescriptor(bucket, "kx", "", "application/pdf", Set.empty, spec)
        )
      )
      source.offer(Lane.Ingest, envelope("job-x"))

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain("ack-job-x"))
      consumer.drain().futureValue

      publisher.published.map(_._1.jobId) shouldBe Seq("job-x")
      publisher.published.head._2 match
        case Left(e: MediaError.UnsupportedType) => e.retriable shouldBe false
        case other => fail(s"expected terminal UnsupportedType, got $other")
    }

    "ack an undecodable message without publishing, and not wedge the lane" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      seedImage(apollo, "kg")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val publisher = new CapturingResultPublisher(events)
      val decoder = new Decoder
      decoder.table("good") = Right(imageJob("job-good", "kg"))
      // "bad" is absent from the table ⇒ Left(DecodeError) ⇒ terminal decode failure.
      source.offer(Lane.Ingest, Envelope("ack-bad", "bad"), Envelope("ack-good", "good"))

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain allOf ("ack-bad", "ack-good"))
      consumer.drain().futureValue

      // The bad message was acked (not redelivered) but never published; the good one processed fine.
      publisher.published.map(_._1.jobId) shouldBe Seq("job-good")
    }
  }

  "the consumer — transient failure" should {
    "leave the message unacked for redelivery (no publish, no ack)" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo(readFutureFailure =
        Some(ApolloError.Unavailable("readOriginal", "apollo down"))
      )
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val publisher = new CapturingResultPublisher(events)
      val decoder = new Decoder
      decoder.table("job-t") = Right(imageJob("job-t", "kt"))
      // A "seal" message (undecodable ⇒ AckOnly ⇒ acked, no Apollo read) is a DETERMINISTIC marker
      // that the loop has moved PAST the transient job. Processed strictly after it (batch=1,
      // concurrency=1), so once "ack-seal" appears, job-t is fully handled and provably unacked.
      source.offer(Lane.Ingest, envelope("job-t"), Envelope("ack-seal", "seal"))
      val sequential =
        JobConsumer.Settings(batchSize = 1, concurrency = 1, pollInterval = 40.millis)

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, sequential)(using system, ec)
      consumer.start()
      eventually(source.acked should contain("ack-seal"))
      consumer.drain().futureValue

      source.acked shouldBe Seq("ack-seal") // job-t never acked
      publisher.published shouldBe empty // neither published (transient + decode-fail)
    }

    "survive a MessageSource that throws synchronously on ack, and keep serving the lane" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      seedImage(apollo, "kboom")
      seedImage(apollo, "kok")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      // The seam throws synchronously when acking "ack-boom" (before any .recover attaches).
      val source = new FakeMessageSource(events, throwAckFor = Set("ack-boom"))
      val publisher = new CapturingResultPublisher(events)
      val decoder = new Decoder
      decoder.table("boom") = Right(imageJob("job-boom", "kboom"))
      decoder.table("ok") = Right(imageJob("job-ok", "kok"))
      source.offer(Lane.Ingest, Envelope("ack-boom", "boom"), Envelope("ack-ok", "ok"))

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, settings)(using system, ec)
      consumer.start()
      // Despite the synchronous throw on job-boom's ack, the loop must survive and still ack job-ok.
      eventually(source.acked should contain("ack-ok"))
      consumer.drain().futureValue

      source.acked should not contain "ack-boom" // its ack threw ⇒ never recorded
      publisher.published.map(_._1.jobId) should contain("job-ok")
    }
  }

  "the consumer — idempotency" should {
    "re-produce byte-identical output and re-publish an equivalent result on redelivery" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      seedImage(apollo, "ki")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val source = new FakeMessageSource(events)
      val publisher = new CapturingResultPublisher(events)
      val decoder = new Decoder
      decoder.table("job-i") = Right(imageJob("job-i", "ki"))
      // Same jobId delivered twice (distinct ack handles) — an at-least-once redelivery.
      source.offer(Lane.Ingest, Envelope("ack-i1", "job-i"), Envelope("ack-i2", "job-i"))

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, settings)(using system, ec)
      consumer.start()
      eventually(source.acked should contain allOf ("ack-i1", "ack-i2"))
      consumer.drain().futureValue

      publisher.published.size shouldBe 2
      val results = publisher.published.map(_._2).collect { case Right(r) => r }
      results.size shouldBe 2
      // Byte-identical derivatives ⇒ equal metadata/phash/derivative refs.
      results(0) shouldBe results(1)
    }
  }

  "the consumer — metrics recording" should {
    "record the right outcome per lane for success, terminal, and transient jobs" in {
      // A recorder shared across three single-message consumers; each is drained before the next so
      // the recorded outcomes are deterministic.
      val recorder = new RecordingRecorder

      // Success on the ingest lane.
      locally {
        val events = mutable.Buffer.empty[String]
        val apollo = new InMemoryApollo()
        seedImage(apollo, "ms")
        val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
        val source = new FakeMessageSource(events)
        val publisher = new CapturingResultPublisher(events)
        val decoder = new Decoder
        decoder.table("job-s") = Right(imageJob("job-s", "ms"))
        source.offer(Lane.Ingest, envelope("job-s"))
        val consumer =
          new JobConsumer(source, pipeline, publisher, decoder.fn, settings, recorder)(using
            system,
            ec
          )
        consumer.start()
        eventually(source.acked should contain("ack-job-s"))
        consumer.drain().futureValue
      }

      // Terminal failure on the ingest lane (unsupported content type fails in the pipeline).
      locally {
        val events = mutable.Buffer.empty[String]
        val apollo = new InMemoryApollo()
        val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
        val source = new FakeMessageSource(events)
        val publisher = new CapturingResultPublisher(events)
        val decoder = new Decoder
        decoder.table("job-term") = Right(
          DecodedJob(
            "job-term",
            "post-term",
            MediaDescriptor(bucket, "mt", "", "application/pdf", Set.empty, spec)
          )
        )
        source.offer(Lane.Ingest, envelope("job-term"))
        val consumer =
          new JobConsumer(source, pipeline, publisher, decoder.fn, settings, recorder)(using
            system,
            ec
          )
        consumer.start()
        eventually(source.acked should contain("ack-job-term"))
        consumer.drain().futureValue
      }

      // Transient failure on the ingest lane (Apollo unavailable ⇒ retriable, left unacked). A
      // "seal" (undecodable ⇒ acked) marks that the loop has moved past the transient job.
      locally {
        val events = mutable.Buffer.empty[String]
        val apollo = new InMemoryApollo(readFutureFailure =
          Some(ApolloError.Unavailable("readOriginal", "apollo down"))
        )
        val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
        val source = new FakeMessageSource(events)
        val publisher = new CapturingResultPublisher(events)
        val decoder = new Decoder
        decoder.table("job-tr") = Right(imageJob("job-tr", "mtr"))
        source.offer(Lane.Ingest, envelope("job-tr"), Envelope("ack-seal", "seal"))
        val sequential =
          JobConsumer.Settings(batchSize = 1, concurrency = 1, pollInterval = 40.millis)
        val consumer =
          new JobConsumer(source, pipeline, publisher, decoder.fn, sequential, recorder)(using
            system,
            ec
          )
        consumer.start()
        eventually(source.acked should contain("ack-seal"))
        consumer.drain().futureValue
      }

      recorder.outcomes should contain(("ingest", "success"))
      recorder.outcomes should contain(("ingest", "terminal"))
      recorder.outcomes should contain(("ingest", "retriable"))
    }
  }

  "the consumer — graceful drain" should {
    "finish in-flight work and ack it before terminating" in {
      val events = mutable.Buffer.empty[String]
      val apollo = new InMemoryApollo()
      seedImage(apollo, "kd")
      val pipeline = new MediaPipeline(apollo, new FakeMediaTools(), freshRoot())
      val gate = Promise[Unit]()
      val source = new FakeMessageSource(events)
      val publisher = new CapturingResultPublisher(events, gate = Some(gate.future))
      val decoder = new Decoder
      decoder.table("job-d") = Right(imageJob("job-d", "kd"))
      source.offer(Lane.Ingest, envelope("job-d"))

      val consumer =
        new JobConsumer(source, pipeline, publisher, decoder.fn, settings)(using system, ec)
      consumer.start()
      // Wait until a job is in-flight AT the publish step (blocked on the gate).
      eventually(publisher.entries.get shouldBe 1)

      val drained = consumer.drain()
      drained.value shouldBe None // in-flight work not yet finished
      source.acked shouldBe empty // nothing acked before publish completes

      gate.success(()) // release the in-flight publish
      drained.futureValue // drain completes only after in-flight finishes
      source.acked should contain("ack-job-d") // finished AND acked before terminate
    }
  }

  private def freshRoot(): java.nio.file.Path =
    java.nio.file.Files.createTempDirectory("heph-consumer-")
