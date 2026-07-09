package me.cference.hephaestus.job

import me.cference.hephaestus.media.MediaPipeline
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * The two-lane priority consumer loop — the effectful heart of §3. Each cycle pulls a small batch
 * from the higher-priority lane ([[LanePriority]] drains `Ingest` before `Reprocess`), decodes each
 * message, runs the §2 [[MediaPipeline]], hands the outcome to the [[ResultPublisher]], and
 * **then** acks. Invariants:
 *
 *   - **Ack after publish.** A message is acked only after its derivatives are durable in Apollo
 *     AND its result was published — never before ([[AckPolicy]] + the `publish.flatMap(ack)`
 *     order).
 *   - **Terminal ⇒ report + ack.** An undecodable message or a non-retriable [[MediaError]] is
 *     reported (where a job exists) and acked, so poison is not redelivered forever.
 *   - **Transient ⇒ leave unacked.** A retriable failure (Apollo/Hermes outage) is not published
 *     and not acked; HermesMQ redelivers it. Idempotency (content addressing) makes the retry safe.
 *   - **One bad message never wedges a lane.** Every per-message handler recovers to `unit`, so a
 *     failure never breaks the batch or the loop.
 *   - **Graceful drain.** [[drain]] stops further pulls and completes once in-flight work has
 *     finished and been acked — no job is acked without a durable result.
 *
 * Processing runs on the injected `processingEc` (a dedicated blocking dispatcher in `Main`, since
 * transcoding is CPU-heavy) with bounded per-batch concurrency.
 */
final class JobConsumer(
    source: MessageSource,
    pipeline: MediaPipeline,
    publisher: ResultPublisher,
    decode: String => Either[DecodeError, DecodedJob],
    settings: JobConsumer.Settings
)(using system: ActorSystem[?], processingEc: ExecutionContext):

  private val log = LoggerFactory.getLogger(classOf[JobConsumer])
  private val running = new AtomicBoolean(false)
  @volatile private var pump: Future[Unit] = Future.unit

  /** Begin pulling (idempotent — a second call while running is a no-op). */
  def start(): Unit =
    if running.compareAndSet(false, true) then
      log.info(
        "Job consumer started — batch={}, concurrency={}, poll={}",
        Integer.valueOf(settings.batchSize),
        Integer.valueOf(settings.concurrency),
        settings.pollInterval
      )
      pump = loop(ingestDrained = false)

  /**
   * Stop pulling and complete once in-flight work has finished + been acked. Safe to call before
   * `start` (returns an already-completed future).
   */
  def drain(): Future[Unit] =
    if running.compareAndSet(true, false) then
      log.info("Job consumer draining — no new pulls; finishing in-flight work")
    pump

  // --- the loop --------------------------------------------------------------

  private def loop(ingestDrained: Boolean): Future[Unit] =
    if !running.get() then Future.unit
    else
      val lane = LanePriority.next(ingestDrained)
      source.pull(lane, settings.batchSize).transformWith {
        case Failure(error) =>
          // A pull failure (e.g. Hermes unreachable) is transient: nothing acked, back off, retry
          // from ingest. Readiness is unaffected — an outage is not service-down.
          log.warn("Pull from {} failed ({}) — backing off", lane, error.getMessage)
          backoffThen(loop(ingestDrained = false))
        case Success(batch) if batch.isEmpty =>
          lane match
            case Lane.Ingest => loop(ingestDrained = true) // ingest drained → try reprocess
            case Lane.Reprocess => backoffThen(loop(ingestDrained = false)) // both idle → back off
        case Success(batch) =>
          processBatch(lane, batch).flatMap(_ => loop(ingestDrained = false))
      }

  private def backoffThen(next: => Future[Unit]): Future[Unit] =
    if !running.get() then Future.unit
    else
      val p = Promise[Unit]()
      // The typed Scheduler's FiniteDuration overload takes the ExecutionContext implicitly (the
      // 3-arg explicit overload wants a java.time.Duration); processingEc is the in-scope given.
      val runnable: Runnable = () => p.completeWith(next)
      system.scheduler.scheduleOnce(settings.pollInterval, runnable)(using processingEc)
      p.future

  // --- per-batch / per-message ----------------------------------------------

  private def processBatch(lane: Lane, batch: List[Envelope]): Future[Unit] =
    boundedForeach(batch, settings.concurrency)(env => handle(lane, env))

  private def handle(lane: Lane, env: Envelope): Future[Unit] =
    decode(env.payload) match
      case Left(error) =>
        // Terminal: nothing to publish (no job) — ack so the poison is not redelivered.
        log.warn("Terminal decode failure on {} — acking (no redelivery): {}", lane, error.message)
        ackQuietly(lane, env)
      case Right(job) =>
        pipeline
          .process(job.descriptor)
          .flatMap { outcome =>
            AckPolicy.decide(outcome) match
              case AckAction.LeaveUnacked =>
                log.warn(
                  "Transient failure for job {} — leaving unacked for redelivery",
                  job.jobId
                )
                Future.unit
              case AckAction.PublishThenAck =>
                // Publish (durable result / terminal report) STRICTLY before the ack.
                publisher.publish(job, outcome).flatMap(_ => source.ack(lane, List(env.ackId)))
              case AckAction.AckOnly =>
                source.ack(lane, List(env.ackId))
          }
          .recover { case NonFatal(e) =>
            // A publish/ack failure (or any unexpected error) leaves the message unacked → redelivery.
            log.error(s"Handler error for job ${job.jobId} — leaving unacked", e)
            ()
          }

  private def ackQuietly(lane: Lane, env: Envelope): Future[Unit] =
    source.ack(lane, List(env.ackId)).recover { case NonFatal(e) =>
      log.error(s"Ack failed for ${env.ackId} — will be redelivered", e)
      ()
    }

  /**
   * Run `f` over `xs` in groups of at most `parallelism`, groups sequenced (bounded concurrency).
   */
  private def boundedForeach[A](xs: List[A], parallelism: Int)(
      f: A => Future[Unit]
  ): Future[Unit] =
    xs.grouped(math.max(1, parallelism)).foldLeft(Future.unit) { (acc, group) =>
      acc.flatMap(_ => Future.sequence(group.map(f)).map(_ => ()))
    }

object JobConsumer:

  /**
   * Consumer tuning: `batchSize` messages per pull (small — backpressured), `concurrency` in-flight
   * jobs per batch, and the idle `pollInterval` back-off between empty pulls.
   */
  final case class Settings(batchSize: Int, concurrency: Int, pollInterval: FiniteDuration)
