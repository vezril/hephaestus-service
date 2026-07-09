package me.cference.hephaestus.job

import me.cference.hephaestus.media.MediaPipeline
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * The two-lane priority consumer loop — the effectful heart of §3. Each cycle pulls a small batch
 * from the higher-priority lane ([[LanePriority]] drains `Ingest` before `Reprocess`), decodes each
 * message, runs the §2 [[MediaPipeline]], and executes the pure [[AckPolicy]] decision (publish
 * where required, **then** ack). Invariants:
 *
 *   - **Ack after publish.** A message is acked only after its derivatives are durable in Apollo
 *     AND its result was published — never before ([[AckPolicy.decide]] + the
 *     `publish.flatMap(ack)` order in [[interpret]]).
 *   - **Terminal ⇒ report + ack.** A non-retriable [[MediaError]] is published (reported) then
 *     acked; an undecodable message routes through [[AckPolicy.onDecodeFailure]] to an ack (nothing
 *     to publish). Poison is never redelivered forever.
 *   - **Transient ⇒ leave unacked.** A retriable failure (Apollo/Hermes outage) is not published
 *     and not acked; HermesMQ redelivers it. Idempotency (content addressing) makes the retry safe.
 *   - **One bad message never wedges a lane.** Every seam call is [[safely]]-lifted (a
 *     *synchronous* throw becomes a failed `Future`) and every per-message handler recovers to
 *     `unit`, so no single message — however malformed — can break the batch or the loop.
 *   - **Graceful drain.** [[drain]] stops further pulls and completes once in-flight work has
 *     finished and been acked — no job is acked without a durable result.
 *
 * Media processing AND the loop's own async plumbing run on the injected `processingEc` (a
 * dedicated dispatcher in `Main`); isolating them from Pekko's default dispatcher keeps the HTTP
 * health endpoint (which runs on the default dispatcher) responsive under a burst of CPU-heavy
 * transcodes. Per-batch concurrency is bounded by `settings.concurrency`.
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
  private val drained = new AtomicBoolean(false)
  @volatile private var pump: Future[Unit] = Future.unit
  private given mat: Materializer = Materializer.matFromSystem(using system)

  /**
   * Begin pulling. Idempotent while running; a no-op once [[drain]] has been called (a drained
   * consumer does not restart — build a fresh one).
   */
  def start(): Unit =
    if drained.get() then log.warn("Job consumer already drained — start() ignored")
    else if running.compareAndSet(false, true) then
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
    drained.set(true)
    if running.compareAndSet(true, false) then
      log.info("Job consumer draining — no new pulls; finishing in-flight work")
    pump

  // --- the loop --------------------------------------------------------------

  private def loop(ingestDrained: Boolean): Future[Unit] =
    // A batch already in flight when `drain` flips this flag still finishes (durable result + ack,
    // no half-work) — drain is "stop pulling, finish the current batch," not "abort mid-batch."
    if !running.get() then Future.unit
    else
      val lane = LanePriority.next(ingestDrained)
      safely(source.pull(lane, settings.batchSize)).transformWith {
        case Failure(error) =>
          // A pull failure (Hermes unreachable, or a synchronous throw from the seam) is transient:
          // nothing acked, back off, retry from ingest. Readiness is unaffected — not service-down.
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

  /**
   * Process a batch with a bounded, *sliding-window* concurrency (`mapAsync`) — as one job
   * finishes, the next starts, rather than waiting for a whole fixed group. `handle` never fails
   * (it recovers to `unit`), so the stream cannot fail; the returned `Future` completes when the
   * batch is done.
   */
  private def processBatch(lane: Lane, batch: List[Envelope]): Future[Unit] =
    Source(batch)
      .mapAsync(math.max(1, settings.concurrency))(env => handle(lane, env))
      .runWith(Sink.ignore)
      .map(_ => ())

  private def handle(lane: Lane, env: Envelope): Future[Unit] =
    val work =
      decode(env.payload) match
        case Left(error) =>
          // Terminal: nothing to publish (no job). Route through the SAME pure policy the rest of
          // the loop obeys — onDecodeFailure ⇒ AckOnly ⇒ ack (no redelivery of poison).
          log.warn(
            "Terminal decode failure on {} — acking (no redelivery): {}",
            lane,
            error.message
          )
          interpret(lane, env, AckPolicy.onDecodeFailure, () => Future.unit)
        case Right(job) =>
          safely(pipeline.process(job.descriptor)).flatMap { outcome =>
            val action = AckPolicy.decide(outcome)
            if action == AckAction.LeaveUnacked then
              log.warn("Transient failure for job {} — leaving unacked for redelivery", job.jobId)
            interpret(lane, env, action, () => publisher.publish(job, outcome))
          }
    work.recover { case NonFatal(e) =>
      // Any failure (publish/ack error, or a synchronous seam throw) leaves the message unacked so
      // it is redelivered — and, crucially, never propagates out to break the batch or the lane.
      log.error(s"Handler error on $lane (ackId ${env.ackId}) — leaving unacked", e)
      ()
    }

  /** Execute a pure [[AckAction]]: publish-then-ack, ack-only, or leave for redelivery. */
  private def interpret(
      lane: Lane,
      env: Envelope,
      action: AckAction,
      publish: () => Future[Unit]
  ): Future[Unit] =
    action match
      case AckAction.PublishThenAck =>
        // Publish (durable result / terminal report) STRICTLY before the ack.
        safely(publish()).flatMap(_ => safely(source.ack(lane, List(env.ackId))))
      case AckAction.AckOnly =>
        safely(source.ack(lane, List(env.ackId)))
      case AckAction.LeaveUnacked =>
        Future.unit

  /** Lift a `Future`-returning seam call so a *synchronous* throw becomes a failed `Future`. */
  private def safely[A](f: => Future[A]): Future[A] =
    try f
    catch { case NonFatal(e) => Future.failed(e) }

object JobConsumer:

  /**
   * Consumer tuning: `batchSize` messages per pull (small — backpressured), `concurrency` in-flight
   * jobs per batch, and the idle `pollInterval` back-off between empty pulls.
   */
  final case class Settings(batchSize: Int, concurrency: Int, pollInterval: FiniteDuration)
