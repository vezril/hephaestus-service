package me.cference.hephaestus.job

import me.cference.hephaestus.media.{MediaError, MediaResult}

/**
 * What the consumer should do with a message once its processing outcome is known. The ack
 * invariant ("ack only after a durable result is published") is encoded here as a pure decision so
 * the effectful loop only has to execute it.
 */
enum AckAction:
  /** Hand the outcome (a success OR a terminal failure) to the result publisher, then ack. */
  case PublishThenAck

  /**
   * A terminal failure with nothing to publish (an undecodable message — no job to report on): ack
   * so the poison message is not redelivered forever.
   */
  case AckOnly

  /** A transient failure: do NOT publish and do NOT ack — leave the message for redelivery. */
  case LeaveUnacked

/**
 * The pure ack policy. A successful result and a terminal media failure are both published then
 * acked (§4 turns them into `MediaProcessed`/`MediaFailed`); a retriable failure is left unacked so
 * HermesMQ redelivers it; an undecodable message is acked without publishing (no job to report on).
 */
object AckPolicy:

  def decide(outcome: Either[MediaError, MediaResult]): AckAction =
    outcome match
      case Right(_) => AckAction.PublishThenAck
      case Left(error) if error.retriable => AckAction.LeaveUnacked
      case Left(_) => AckAction.PublishThenAck

  /**
   * An undecodable/invalid message is terminal — ack it (report is a log line; there is no job).
   */
  def onDecodeFailure: AckAction = AckAction.AckOnly
