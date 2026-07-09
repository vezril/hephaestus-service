package me.cference.hephaestus.job

import me.cference.hermesmq.client.HermesClient
import me.cference.hermesmq.domain.{AckId, SubscriptionId}

import scala.concurrent.{ExecutionContext, Future}

/** A message pulled from a lane: its opaque ack handle and the raw canonical-JSON payload. */
final case class Envelope(ackId: String, payload: String)

/**
 * The pull/ack seam over HermesMQ, parameterized by [[Lane]] so the consumer loop is oblivious to
 * subscription plumbing (and is unit-testable with an in-memory fake). `pull` is backpressured by
 * `max`; `ack` finalizes messages by their ack handles.
 */
trait MessageSource:
  def pull(lane: Lane, max: Int): Future[List[Envelope]]
  def ack(lane: Lane, ackIds: List[String]): Future[Unit]

/**
 * The production [[MessageSource]]: wraps a [[HermesClient]] and routes each [[Lane]] to its
 * subscription. A pull/ack failure (e.g. Hermes unreachable) surfaces as a failed `Future` — the
 * consumer treats that as transient (back off, retry; nothing is acked), keeping readiness
 * independent of Hermes reachability.
 */
final class HermesMessageSource(
    client: HermesClient,
    ingestSub: SubscriptionId,
    reprocessSub: SubscriptionId
)(using ec: ExecutionContext)
    extends MessageSource:

  private def subscription(lane: Lane): SubscriptionId =
    lane match
      case Lane.Ingest => ingestSub
      case Lane.Reprocess => reprocessSub

  def pull(lane: Lane, max: Int): Future[List[Envelope]] =
    client.pull(subscription(lane), max).map(_.map(m => Envelope(m.ackId.value, m.payload)))

  def ack(lane: Lane, ackIds: List[String]): Future[Unit] =
    if ackIds.isEmpty then Future.unit
    else client.ack(subscription(lane), ackIds.map(unsafeAckId))

  private def unsafeAckId(raw: String): AckId =
    AckId
      .from(raw)
      .getOrElse(throw new IllegalArgumentException(s"blank ackId from Hermes: '$raw'"))

object HermesMessageSource:

  /**
   * Build from a client and the two lane subscription names. Blank names are a configuration error
   * surfaced eagerly (the `Left` is mapped to a thrown `IllegalArgumentException` by the caller in
   * `Main`, which validates config before wiring).
   */
  def apply(client: HermesClient, ingestSub: String, reprocessSub: String)(using
      ec: ExecutionContext
  ): Either[String, HermesMessageSource] =
    for
      ingest <- SubscriptionId.from(ingestSub).left.map(_.message)
      reprocess <- SubscriptionId.from(reprocessSub).left.map(_.message)
    yield new HermesMessageSource(client, ingest, reprocess)
