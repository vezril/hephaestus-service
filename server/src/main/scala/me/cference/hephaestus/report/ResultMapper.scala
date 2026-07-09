package me.cference.hephaestus.report

import codex.messages.v1 as pb
import me.cference.hephaestus.job.DecodedJob
import me.cference.hephaestus.media.{
  DerivativeRef,
  MediaError,
  MediaMetadata,
  MediaResult,
  SpecVersion,
  SpecVersionError
}

/**
 * The pure domain→wire mapping: the §2 [[MediaResult]]/[[MediaError]] into the Lexicon
 * `codex.messages.v1` `MediaProcessed`/`MediaFailed`. It references ScalaPB types, so it lives in
 * `server` (CI-tested via a canonical-JSON round-trip) rather than `core`. Only the *shape* mapping
 * lives here; serialization + publication is the effectful [[HermesResultPublisher]].
 *
 * Mapping notes (authoritative in the proposal's table):
 *   - `filesize` is dropped — the Lexicon `MediaMetadata` has no field for it (Artemis computes
 *     object size on upload).
 *   - `Derivative.ref` is an `ObjectRef(bucket, object)`; §2's flat content-addressed key becomes
 *     the `object`, paired with the configured media `bucket`.
 *   - `spec_version` is an int32: the stamped string is parsed ([[SpecVersion.parse]]); a
 *     non-numeric value is a typed [[SpecVersionError]] the caller must handle.
 */
object ResultMapper:

  /**
   * Map a successful [[MediaResult]] to a `MediaProcessed`. `Left(SpecVersionError)` if the stamped
   * spec-version is not numeric (an operator misconfiguration surfaced rather than silently sent as
   * a corrupt/zero `spec_version`).
   */
  def toProcessed(
      job: DecodedJob,
      result: MediaResult,
      mediaBucket: String
  ): Either[SpecVersionError, pb.MediaProcessed] =
    SpecVersion.parse(result.derivativeSpecVersion).map { specVersion =>
      pb.MediaProcessed(
        jobId = job.jobId,
        postId = job.postId,
        status = "ok",
        metadata = Some(toMetadata(result.metadata)),
        phash = result.phash,
        derivatives = result.derivatives.map(toDerivative(_, mediaBucket)),
        specVersion = specVersion
      )
    }

  /**
   * Map a terminal [[MediaError]] to a `MediaFailed`: the stable error `code` + human-readable
   * `message`, `retriable = false` (only terminal failures are ever published — a transient one is
   * left for redelivery, never reported).
   */
  def toFailed(job: DecodedJob, error: MediaError): pb.MediaFailed =
    pb.MediaFailed(
      jobId = job.jobId,
      postId = job.postId,
      error = Some(pb.JobError(code = MediaError.code(error), message = error.message)),
      retriable = false
    )

  private def toMetadata(m: MediaMetadata): pb.MediaMetadata =
    pb.MediaMetadata(
      width = m.width,
      height = m.height,
      durationSeconds = m.duration,
      fps = m.fps,
      md5 = m.md5,
      filetype = m.filetype,
      hasAudio = m.hasAudio
    )

  private def toDerivative(d: DerivativeRef, mediaBucket: String): pb.Derivative =
    pb.Derivative(
      kind = d.kind.wireToken,
      ref = Some(pb.ObjectRef.of(mediaBucket, d.ref)),
      width = d.width,
      height = d.height,
      variant = d.variant,
      codec = d.codec
    )
