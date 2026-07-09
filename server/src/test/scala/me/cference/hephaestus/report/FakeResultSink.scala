package me.cference.hephaestus.report

import scala.collection.mutable
import scala.concurrent.Future

/** One captured publish: the topic, the canonical-JSON payload, and the message attributes. */
final case class Captured(topic: String, payload: String, attributes: Map[String, String])

/**
 * A capturing [[ResultSink]] for the publisher tests: records every `(topic, payload, attributes)`.
 * `failWith` makes a publish fail (a Hermes outage) so the "publish failure ⇒ failed Future / no
 * ack" path is exercisable. An optional `events` log records a `publish:<topic>` marker so the
 * publish-before-ack ordering is assertable against a shared events buffer (the integration test).
 */
final class FakeResultSink(
    failWith: Option[Throwable] = None,
    events: Option[mutable.Buffer[String]] = None
) extends ResultSink:
  val captured: mutable.Buffer[Captured] = mutable.Buffer.empty

  def publish(topic: String, payload: String, attributes: Map[String, String]): Future[Unit] =
    failWith match
      case Some(t) => Future.failed(t)
      case None =>
        synchronized {
          captured += Captured(topic, payload, attributes)
          events.foreach(_ += s"publish:$topic")
        }
        Future.unit
