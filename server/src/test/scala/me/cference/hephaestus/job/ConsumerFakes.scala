package me.cference.hephaestus.job

import me.cference.hephaestus.media.{MediaError, MediaResult}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/**
 * An in-memory [[MessageSource]] for the consumer loop tests: messages are offered per lane, pulls
 * dequeue up to `max` (backpressure), and every ack is recorded — both in `acked` (the ack handles)
 * and in the shared `events` log (so ordering against publishes is assertable). `throwAckFor` makes
 * `ack` throw *synchronously* for the given ack handles, modeling a seam that throws eagerly — the
 * loop must survive it, not just a failed Future.
 */
final class FakeMessageSource(
    events: mutable.Buffer[String],
    throwAckFor: Set[String] = Set.empty
) extends MessageSource:

  private val queues =
    Map(Lane.Ingest -> mutable.Queue[Envelope](), Lane.Reprocess -> mutable.Queue[Envelope]())
  val acked: mutable.Buffer[String] = mutable.Buffer.empty

  def offer(lane: Lane, envelopes: Envelope*): Unit =
    synchronized(envelopes.foreach(queues(lane).enqueue(_)))

  def pull(lane: Lane, max: Int): Future[List[Envelope]] =
    synchronized {
      val q = queues(lane)
      val taken = List.newBuilder[Envelope]
      var n = 0
      while n < max && q.nonEmpty do
        taken += q.dequeue()
        n += 1
      Future.successful(taken.result())
    }

  def ack(lane: Lane, ackIds: List[String]): Future[Unit] =
    // Throw BEFORE recording — a synchronous escape, evaluated before any `.recover` attaches.
    ackIds.find(throwAckFor.contains).foreach { id =>
      throw new IllegalStateException(s"synchronous ack throw for $id")
    }
    synchronized {
      ackIds.foreach { id =>
        acked += id
        events += s"ack:$id"
      }
    }
    Future.unit

/**
 * A capturing [[ResultPublisher]]: records every published `(job, outcome)` and logs a `publish:`
 * event so the ack-after-publish ordering is assertable. An optional `gate` future lets a test hold
 * a publish "in flight" (for the graceful-drain test); `entries` counts publish entries
 * synchronously so a test can detect in-flight work before the gate is released.
 */
final class CapturingResultPublisher(
    events: mutable.Buffer[String],
    gate: Option[Future[Unit]] = None
)(using ec: ExecutionContext)
    extends ResultPublisher:

  val published: mutable.Buffer[(DecodedJob, Either[MediaError, MediaResult])] =
    mutable.Buffer.empty
  val entries: AtomicInteger = new AtomicInteger(0)

  def publish(job: DecodedJob, outcome: Either[MediaError, MediaResult]): Future[Unit] =
    entries.incrementAndGet()
    def record(): Unit = synchronized {
      published += ((job, outcome))
      events += s"publish:${job.jobId}"
      ()
    }
    gate match
      case Some(g) => g.map(_ => record())
      case None =>
        record()
        Future.unit
