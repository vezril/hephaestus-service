package me.cference.hephaestus.job

import me.cference.hephaestus.media.{DerivativeKind, DerivativeSpec, MediaDescriptor}

/**
 * Pure validation + mapping of a [[RawJob]] into a [[DecodedJob]]. Total: every required field must
 * be present and non-blank, otherwise a terminal [[DecodeError]] naming the offending field. The
 * `want` tokens are mapped leniently to [[DerivativeKind]]s — unrecognized tokens are ignored (so a
 * producer can request a future kind without breaking older workers); the per-type §2 plan further
 * constrains what is actually produced. The derivative [[DerivativeSpec]] is injected from config
 * (it is not carried on the wire message).
 */
object JobDecoder:

  def decode(raw: RawJob, spec: DerivativeSpec): Either[DecodeError, DecodedJob] =
    for
      jobId <- required(raw.jobId, "jobId")
      postId <- required(raw.postId, "postId")
      bucket <- required(raw.bucket, "source.bucket")
      key <- required(raw.key, "source.object")
      mediaType <- required(raw.mediaType, "mediaType")
      contentType <- required(raw.contentType, "contentType")
    yield DecodedJob(
      jobId,
      postId,
      MediaDescriptor(
        bucket = bucket,
        key = key,
        mediaType = mediaType,
        contentType = contentType,
        want = wantKinds(raw.want),
        spec = spec
      )
    )

  /** Map the on-wire `want` tokens to derivative kinds, ignoring unrecognized ones. */
  def wantKinds(want: Seq[String]): Set[DerivativeKind] =
    want.iterator.flatMap(kindOf).toSet

  private def kindOf(token: String): Option[DerivativeKind] =
    token.trim.toLowerCase match
      case "thumb" | "thumbnail" => Some(DerivativeKind.Thumbnail)
      case "sample" => Some(DerivativeKind.Sample)
      case "transcode" | "720p" | "preview" => Some(DerivativeKind.Transcode)
      case _ => None

  private def required(value: String, field: String): Either[DecodeError, String] =
    if Option(value).forall(_.trim.isEmpty) then
      Left(DecodeError(s"missing or blank required field: $field"))
    else Right(value)
