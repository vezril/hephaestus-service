package me.cference.hephaestus.apollo

import io.grpc.{Status, StatusRuntimeException}

import scala.util.control.NonFatal

/**
 * Typed failure surface for Apollo object I/O. Every case carries a `retriable` flag so downstream
 * result-reporting can leave retriable jobs for redelivery and quarantine terminal ones:
 *
 *   - **retriable**: Apollo unreachable (`UNAVAILABLE`, connection refused) or `DEADLINE_EXCEEDED`;
 *   - **terminal**: `NOT_FOUND`, `FAILED_PRECONDITION` (checksum), `INVALID_ARGUMENT`, an md5
 *     mismatch, a truncated payload, a malformed response, or any unclassified status.
 *
 * `ApolloError` extends `Exception` so it can fail a `Future`/stream directly; `classify` maps a
 * raw gRPC/stream failure to the right case (and passes an already-typed `ApolloError` through
 * unchanged). The originating throwable, when there is one, is chained as the cause so a
 * quarantined failure keeps its stack trace.
 */
sealed abstract class ApolloError(
    message: String,
    val retriable: Boolean,
    cause: Throwable | Null = null
) extends RuntimeException(message):
  if cause != null then initCause(cause)

object ApolloError:

  /** Apollo is unreachable or returned a transient status — safe to retry. */
  final case class Unavailable(op: String, detail: String, source: Throwable | Null = null)
      extends ApolloError(s"apollo $op: unavailable — $detail", retriable = true, source)

  /** The per-call deadline elapsed — safe to retry. */
  final case class DeadlineExceeded(op: String, source: Throwable | Null = null)
      extends ApolloError(s"apollo $op: deadline exceeded", retriable = true, source)

  /** Object or bucket does not exist — terminal. */
  final case class NotFound(op: String, detail: String, source: Throwable | Null = null)
      extends ApolloError(s"apollo $op: not found — $detail", retriable = false, source)

  /** A precondition (e.g. checksum) failed — terminal. */
  final case class FailedPrecondition(op: String, detail: String, source: Throwable | Null = null)
      extends ApolloError(s"apollo $op: failed precondition — $detail", retriable = false, source)

  /** A malformed request argument — terminal. */
  final case class InvalidArgument(op: String, detail: String, source: Throwable | Null = null)
      extends ApolloError(s"apollo $op: invalid argument — $detail", retriable = false, source)

  /**
   * The streamed original did not hash to the metadata header's md5 — terminal (corrupt/tampered).
   */
  final case class Md5Mismatch(bucket: String, key: String, expected: String, actual: String)
      extends ApolloError(
        s"apollo readOriginal: md5 mismatch for $bucket/$key (header $expected, computed $actual)",
        retriable = false
      )

  /** The stream ended before the declared size — terminal (never silently truncate). */
  final case class Truncated(bucket: String, key: String, expected: Long, actual: Long)
      extends ApolloError(
        s"apollo readOriginal: truncated $bucket/$key (declared $expected bytes, received $actual)",
        retriable = false
      )

  /** The server response violated the header-then-chunks framing — terminal. */
  final case class Protocol(op: String, detail: String)
      extends ApolloError(s"apollo $op: protocol error — $detail", retriable = false)

  /** An unclassified failure — conservatively terminal (do not hammer on unknown errors). */
  final case class Unexpected(op: String, detail: String, source: Throwable | Null = null)
      extends ApolloError(s"apollo $op: unexpected — $detail", retriable = false, source)

  /** Map any failure raised by a client call/stream to a typed `ApolloError` for the given op. */
  def classify(op: String, t: Throwable): ApolloError = t match
    case e: ApolloError => e
    case e: StatusRuntimeException => fromStatus(op, e)
    case NonFatal(e) => Unexpected(op, Option(e.getMessage).getOrElse(e.getClass.getName), e)
    case fatal => throw fatal

  private def fromStatus(op: String, e: StatusRuntimeException): ApolloError =
    val status = e.getStatus
    val detail = Option(status.getDescription).filter(_.nonEmpty).getOrElse(status.getCode.toString)
    status.getCode match
      case Status.Code.UNAVAILABLE => Unavailable(op, detail, e)
      case Status.Code.DEADLINE_EXCEEDED => DeadlineExceeded(op, e)
      case Status.Code.NOT_FOUND => NotFound(op, detail, e)
      case Status.Code.FAILED_PRECONDITION => FailedPrecondition(op, detail, e)
      case Status.Code.INVALID_ARGUMENT => InvalidArgument(op, detail, e)
      case other => Unexpected(op, s"$other: $detail", e)
