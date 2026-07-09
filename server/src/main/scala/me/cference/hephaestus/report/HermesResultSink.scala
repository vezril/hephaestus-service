package me.cference.hephaestus.report

import me.cference.hermesmq.client.HermesClient
import me.cference.hermesmq.domain.TopicId

import scala.concurrent.{ExecutionContext, Future}

/**
 * The production [[ResultSink]]: a thin adapter over the [[HermesClient]] `publish`. A
 * blank/invalid topic name is parsed to a typed error and surfaced as a FAILED `Future` (never a
 * synchronous throw), which the consumer loop tolerates as a transient per-job failure — keeping
 * readiness independent of Hermes reachability, per the §1–§3 precedent.
 */
final class HermesResultSink(client: HermesClient)(using ec: ExecutionContext) extends ResultSink:

  def publish(topic: String, payload: String, attributes: Map[String, String]): Future[Unit] =
    TopicId.from(topic) match
      case Right(topicId) => client.publish(topicId, payload, attributes).map(_ => ())
      case Left(err) =>
        Future.failed(
          new IllegalArgumentException(s"invalid result topic '$topic': ${err.message}")
        )
