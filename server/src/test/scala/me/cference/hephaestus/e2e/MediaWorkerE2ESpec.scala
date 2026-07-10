package me.cference.hephaestus.e2e

import apollostorage.grpc.{CreateBucketRequest, ObjectApiClient}
import codex.messages.v1 as pb
import codex.messages.v1.{ObjectRef, ProcessMediaJob}
import com.typesafe.config.ConfigFactory
import me.cference.hephaestus.apollo.{ApolloClient, LexiconApolloClient}
import me.cference.hephaestus.config.ApolloConfig
import me.cference.hephaestus.job.{HermesMessageSource, JobCodec, JobConsumer}
import me.cference.hephaestus.media.{
  DerivativeSpec,
  MediaPipeline,
  ProcessCommandRunner,
  RealMediaTools
}
import me.cference.hephaestus.report.{HermesResultPublisher, HermesResultSink}
import me.cference.hephaestus.storage.StorageKey
import me.cference.hermesmq.client.{HermesClient, ReceivedMessage}
import me.cference.hermesmq.domain.{SubscriptionId, TopicId}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.stream.scaladsl.FileIO
import org.apache.pekko.NotUsed
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import scalapb.json4s.JsonFormat

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}

/**
 * The opt-in end-to-end verification (add-e2e-integration), `E2E`-tagged so the default `sbt test`
 * skips it. It boots the published Apollo + Hermes images (see [[ConstellationContainers]]) and
 * runs THIS repo's REAL worker in-process — `JobConsumer` → `MediaPipeline` (real ffmpeg/libvips) →
 * `ApolloClient` (real gRPC) → `HermesResultPublisher` (real REST) — asserting the whole loop
 * against the real brokers:
 *
 *   - happy path: a real jpeg uploaded to Apollo + a `ProcessMediaJob` on `media.ingest` yields the
 *     thumb + sample derivatives content-addressed in Apollo AND a well-formed `MediaProcessed` on
 *     `media.processed`;
 *   - failure path: a corrupt "jpeg" yields a `MediaFailed` (`retriable=false`) on `media.failed`
 *     and NO derivative written.
 */
final class MediaWorkerE2ESpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with Eventually
    with OptionValues:

  // Generous patience: real container round-trips + a full ffmpeg/vips pipeline per job.
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(90, Seconds), interval = Span(1, Seconds))

  private val bucket = "media"
  private val spec =
    DerivativeSpec(thumbnailPx = 250, samplePx = 850, sampleMinLongEdgePx = 850, specVersion = "1")

  // Topic + subscription ids provisioned in Hermes before the worker starts.
  private val ingestTopic = TopicId.from("media.ingest").toOption.get
  private val processedTopic = TopicId.from("media.processed").toOption.get
  private val failedTopic = TopicId.from("media.failed").toOption.get
  private val reprocessTopic = TopicId.from("media.reprocess").toOption.get
  private val ingestSubName = "sub.ingest"
  private val reprocessSubName = "sub.reprocess"
  private val processedSub = SubscriptionId.from("sub.processed").toOption.get
  private val failedSub = SubscriptionId.from("sub.failed").toOption.get

  private val containers = new ConstellationContainers()

  private var system: ActorSystem[Nothing] = scala.compiletime.uninitialized
  private var objectApi: ObjectApiClient = scala.compiletime.uninitialized
  private var apollo: ApolloClient = scala.compiletime.uninitialized
  private var hermes: HermesClient = scala.compiletime.uninitialized
  private var consumer: JobConsumer = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    containers.start()

    system = ActorSystem[Nothing](Behaviors.empty[Nothing], "heph-e2e", ConfigFactory.load())
    given ActorSystem[Nothing] = system
    given ExecutionContext = system.executionContext

    // --- Apollo: one gRPC channel, shared by setup (createBucket) + the worker + assertions ---
    val apolloConfig = ApolloConfig(containers.apolloGrpcEndpoint, bucket, 30.seconds)
    val settings = GrpcClientSettings
      .connectToServiceAt(apolloConfig.host, apolloConfig.port)(system)
      .withTls(false)
    objectApi = ObjectApiClient(settings)
    apollo = new LexiconApolloClient(objectApi)

    // --- Hermes: create the topics + subscriptions the worker consumes/publishes ---
    hermes = new HermesClient(containers.hermesHttpEndpoint)(using system)

    val mediaEc: ExecutionContext =
      system.dispatchers.lookup(DispatcherSelector.fromConfig("hephaestus-media-dispatcher"))

    val setup =
      for
        _ <- objectApi.createBucket(CreateBucketRequest(bucket))
        _ <- hermes.createTopic(ingestTopic)
        _ <- hermes.createTopic(reprocessTopic)
        _ <- hermes.createTopic(processedTopic)
        _ <- hermes.createTopic(failedTopic)
        _ <- hermes.createSubscription(SubscriptionId.from(ingestSubName).toOption.get, ingestTopic)
        _ <- hermes.createSubscription(
          SubscriptionId.from(reprocessSubName).toOption.get,
          reprocessTopic
        )
        // Result subscriptions MUST exist before the worker publishes, so they capture the results.
        _ <- hermes.createSubscription(processedSub, processedTopic)
        _ <- hermes.createSubscription(failedSub, failedTopic)
      yield ()
    Await.result(setup, 60.seconds)

    // --- Wire + start THIS repo's real worker, pointed at the containers ---
    val tools = new RealMediaTools(ProcessCommandRunner.run)(using mediaEc)
    val pipeline = MediaPipeline(apollo, tools)(using system)
    val publisher = new HermesResultPublisher(
      sink = new HermesResultSink(hermes)(using mediaEc),
      processedTopic = processedTopic.value,
      failedTopic = failedTopic.value,
      mediaBucket = bucket
    )
    val source = HermesMessageSource(hermes, ingestSubName, reprocessSubName)(using mediaEc) match
      case Right(s) => s
      case Left(err) => throw new IllegalStateException(s"bad lane config: $err")
    consumer = new JobConsumer(
      source = source,
      pipeline = pipeline,
      publisher = publisher,
      decode = JobCodec.decode(_, spec),
      settings = JobConsumer.Settings(batchSize = 4, concurrency = 2, pollInterval = 500.millis)
    )(using system, mediaEc)
    consumer.start()

  override def afterAll(): Unit =
    // The fields are `uninitialized` until beforeAll runs; Option(...) guards a teardown that
    // fires before setup completed (e.g. a container failed to start) without a null literal.
    Option(consumer).foreach(c => Await.ready(c.drain(), 30.seconds))
    Option(apollo).foreach(a => Await.ready(a.close(), 15.seconds))
    Option(system).foreach { s =>
      s.terminate()
      Await.ready(s.whenTerminated, 30.seconds)
    }
    containers.stop()

  // --- helpers ---------------------------------------------------------------

  /** Generate a real jpeg with ffmpeg's lavfi test source (no committed binaries). */
  private def generateJpeg(width: Int, height: Int): Path =
    val out = Files.createTempFile("heph-e2e-orig-", ".jpg")
    val pb = new ProcessBuilder(
      "ffmpeg",
      "-y",
      "-f",
      "lavfi",
      "-i",
      s"testsrc=size=${width}x$height:rate=1",
      "-frames:v",
      "1",
      out.toString
    ).redirectErrorStream(true)
    val proc = pb.start()
    val logOut = new String(proc.getInputStream.readAllBytes())
    val code = proc.waitFor()
    require(code == 0 && Files.size(out) > 0L, s"ffmpeg failed ($code): $logOut")
    out

  private def md5Of(path: Path): String =
    val md = MessageDigest.getInstance("MD5")
    md.digest(Files.readAllBytes(path)).map("%02x".format(_)).mkString

  /** Upload a local file to Apollo as the original at `originals/<md5[0:2]>/<md5>.jpg`. */
  private def uploadOriginal(path: Path, md5: String): String =
    val key = StorageKey.original(md5, "jpg").toOption.get
    val data = FileIO.fromPath(path).mapMaterializedValue(_ => NotUsed)
    Await.result(apollo.writeDerivative(bucket, key, "image/jpeg", data), 30.seconds)
    key

  private def publishJob(jobId: String, key: String, mediaType: String, contentType: String): Unit =
    val job = ProcessMediaJob(
      jobId = jobId,
      postId = s"post-$jobId",
      source = Some(ObjectRef(bucket = bucket, `object` = key)),
      mediaType = mediaType,
      contentType = contentType,
      want = Seq("thumb", "sample")
    )
    Await.result(
      hermes.publish(ingestTopic, JsonFormat.toJsonString(job), Map("jobId" -> jobId)),
      15.seconds
    )
    ()

  /** Poll a subscription until a message matching `jobId` (by attribute) appears, or time out. */
  private def awaitResult(
      sub: SubscriptionId,
      jobId: String,
      within: FiniteDuration
  ): ReceivedMessage =
    val deadline = within.fromNow
    var found: Option[ReceivedMessage] = None
    while found.isEmpty && deadline.hasTimeLeft() do
      val msgs = Await.result(hermes.pull(sub, 10), 15.seconds)
      found = msgs.find(_.attributes.get("jobId").contains(jobId))
      if found.isEmpty then Thread.sleep(1000)
    found.getOrElse(fail(s"no message for job $jobId on ${sub.value} within $within"))

  // --- the tests -------------------------------------------------------------

  "the media worker, end-to-end against real Apollo + Hermes" should {

    "process a real image job to derivatives-in-Apollo and a MediaProcessed" taggedAs E2E in {
      val jpeg = generateJpeg(1200, 800)
      val md5 = md5Of(jpeg)
      val key = uploadOriginal(jpeg, md5)

      publishJob("job-ok", key, mediaType = "image", contentType = "image/jpeg")

      // Derivatives land content-addressed in Apollo (read back via HEAD).
      val thumbKey = StorageKey.derivative(md5, "thumb.webp").toOption.get
      val sampleKey = StorageKey.derivative(md5, "sample.webp").toOption.get
      eventually {
        apollo.headExists(bucket, thumbKey).futureValue shouldBe defined
        apollo.headExists(bucket, sampleKey).futureValue shouldBe defined
      }

      // A well-formed MediaProcessed on media.processed.
      val msg = awaitResult(processedSub, "job-ok", 90.seconds)
      msg.attributes.get("postId").value shouldBe "post-job-ok"
      val processed = JsonFormat.fromJsonString[pb.MediaProcessed](msg.payload)
      processed.jobId shouldBe "job-ok"
      processed.postId shouldBe "post-job-ok"
      processed.status shouldBe "ok"
      processed.specVersion shouldBe 1
      processed.phash should not be empty
      processed.metadata.value.md5 shouldBe md5
      processed.metadata.value.width shouldBe 1200
      processed.metadata.value.height shouldBe 800
      processed.derivatives.map(_.kind).toSet shouldBe Set("thumb", "sample")
      processed.derivatives.foreach { d =>
        d.ref.value.bucket shouldBe bucket
        d.ref.value.`object` should startWith(s"derivatives/${md5.take(2)}/$md5/")
      }
    }

    "report MediaFailed (retriable=false) and write no derivative for a corrupt original" taggedAs E2E in {
      // A file with a .jpg key but non-image bytes: Apollo stores it fine; vips probe fails terminally.
      val corrupt = Files.createTempFile("heph-e2e-corrupt-", ".jpg")
      Files.write(corrupt, "this is definitely not a jpeg".getBytes("UTF-8"))
      val md5 = md5Of(corrupt)
      val key = uploadOriginal(corrupt, md5)

      publishJob("job-bad", key, mediaType = "image", contentType = "image/jpeg")

      val msg = awaitResult(failedSub, "job-bad", 90.seconds)
      val failed = JsonFormat.fromJsonString[pb.MediaFailed](msg.payload)
      failed.jobId shouldBe "job-bad"
      failed.retriable shouldBe false
      failed.error.value.code should not be empty

      // No derivative was written for this md5.
      val thumbKey = StorageKey.derivative(md5, "thumb.webp").toOption.get
      apollo.headExists(bucket, thumbKey).futureValue shouldBe None
    }
  }
