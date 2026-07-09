package me.cference.hephaestus.job

import me.cference.hephaestus.media.{MediaError, MediaResult}
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
 * The §4 result-reporting seam. §3 hands the *domain* outcome (a [[MediaResult]] or a terminal
 * [[MediaError]]) to this interface **before acking**; `add-result-reporting` fills it with a real
 * publisher that emits `MediaProcessed`/`MediaFailed`. Shipping the seam now makes the ack
 * invariant ("publish before ack") complete and testable without building the wire messages here.
 */
trait ResultPublisher:
  def publish(job: DecodedJob, outcome: Either[MediaError, MediaResult]): Future[Unit]

object ResultPublisher:

  /** The §3 default: log the outcome and succeed. Real publication lands in §4. */
  def logging: ResultPublisher = new ResultPublisher:
    private val log = LoggerFactory.getLogger("me.cference.hephaestus.job.ResultPublisher")

    def publish(job: DecodedJob, outcome: Either[MediaError, MediaResult]): Future[Unit] =
      outcome match
        case Right(result) =>
          log.info(
            "job {} (post {}) processed — {} derivative(s), phash {}, spec {}",
            job.jobId,
            job.postId,
            Integer.valueOf(result.derivatives.size),
            result.phash,
            result.derivativeSpecVersion
          )
        case Left(error) =>
          log.warn(
            "job {} (post {}) failed terminally — {} (retriable={})",
            job.jobId,
            job.postId,
            error.message,
            java.lang.Boolean.valueOf(error.retriable)
          )
      Future.unit
