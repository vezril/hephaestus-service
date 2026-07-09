package me.cference.hephaestus.job

import codex.messages.v1.ProcessMediaJob
import scalapb.json4s.JsonFormat

import scala.util.control.NonFatal

/**
 * The wire-format boundary: parse a HermesMQ message payload (protobuf **canonical JSON**, per the
 * Lexicon contract) into the pure [[RawJob]] the `core` decoder validates. This is the one place
 * the `lexicon-messages` artifact + scalapb-json4s codec are touched; everything downstream is
 * pure. The parse itself is effect-free (a `String => Either`), but it lives in `server` because
 * `core` must not depend on the message artifact (which cannot resolve without the packages token).
 *
 * A malformed payload — invalid JSON, wrong shape, or a missing `source` object — is a terminal
 * [[DecodeError]] (never a thrown exception): a poison message is reported + acked, not
 * redelivered.
 */
object JobCodec:

  def parse(payload: String): Either[DecodeError, RawJob] =
    try
      val job = JsonFormat.fromJsonString[ProcessMediaJob](payload)
      job.source match
        case None =>
          Left(DecodeError("missing or blank required field: source"))
        case Some(ref) =>
          Right(
            RawJob(
              jobId = job.jobId,
              postId = job.postId,
              bucket = ref.bucket,
              key = ref.`object`,
              mediaType = job.mediaType,
              contentType = job.contentType,
              want = job.want
            )
          )
    catch case NonFatal(e) => Left(DecodeError(s"unparseable ProcessMediaJob: ${e.getMessage}"))

  /** Parse the payload and validate/map it in one step (the codec + the pure `core` decoder). */
  def decode(
      payload: String,
      spec: me.cference.hephaestus.media.DerivativeSpec
  ): Either[DecodeError, DecodedJob] =
    parse(payload).flatMap(JobDecoder.decode(_, spec))
