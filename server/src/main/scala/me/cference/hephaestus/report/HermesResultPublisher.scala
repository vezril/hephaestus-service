package me.cference.hephaestus.report

import me.cference.hephaestus.job.{DecodedJob, ResultPublisher}
import me.cference.hephaestus.media.{MediaError, MediaResult}
import scalapb.json4s.JsonFormat

import scala.concurrent.{ExecutionContext, Future}

/**
 * The real §4 [[ResultPublisher]]: it fills the §3 seam, mapping the domain outcome to the Lexicon
 * wire messages, serializing to protobuf canonical JSON (scalapb-json4s), and publishing via the
 * injected [[ResultSink]]. It reports exactly one terminal message per completed job and carries
 * `jobId`/`postId` as message attributes (routing/observability; Artemis dedups by `jobId`).
 *
 * Outcome routing:
 *   - `Right(result)` ⇒ `MediaProcessed` → `processedTopic`.
 *   - `Left(err)` with `err.retriable = false` ⇒ `MediaFailed` → `failedTopic`.
 *   - `Left(err)` with `err.retriable = true` ⇒ **do NOT publish**; return a failed `Future` so §3
 *     leaves the message unacked for redelivery (a transient failure is never reported).
 *
 * A [[ResultSink]] publish failure surfaces as a failed `Future` too (no ack) — the message is
 * redelivered and re-produced byte-identically (content addressing), so re-publishing is safe.
 */
final class HermesResultPublisher(
    sink: ResultSink,
    processedTopic: String,
    failedTopic: String,
    mediaBucket: String
)(using ec: ExecutionContext)
    extends ResultPublisher:

  def publish(job: DecodedJob, outcome: Either[MediaError, MediaResult]): Future[Unit] =
    outcome match
      case Right(result) =>
        ResultMapper.toProcessed(job, result, mediaBucket) match
          case Right(msg) =>
            sink.publish(processedTopic, JsonFormat.toJsonString(msg), attributes(job))
          case Left(err) =>
            // A non-numeric spec-version is an operator misconfiguration: fail loudly (no ack)
            // rather than report a MediaProcessed with a corrupt spec_version.
            Future.failed(new IllegalStateException(err.message))

      case Left(error) if error.retriable =>
        // Transient failure: publish NOTHING, fail the Future so §3 leaves it for redelivery.
        Future.failed(error)

      case Left(error) =>
        val msg = ResultMapper.toFailed(job, error)
        sink.publish(failedTopic, JsonFormat.toJsonString(msg), attributes(job))

  private def attributes(job: DecodedJob): Map[String, String] =
    Map("jobId" -> job.jobId, "postId" -> job.postId)
