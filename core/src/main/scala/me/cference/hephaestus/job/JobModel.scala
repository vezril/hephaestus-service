package me.cference.hephaestus.job

import me.cference.hephaestus.media.MediaDescriptor

/**
 * The primitive, transport-free fields of a `ProcessMediaJob`, extracted from the wire message in
 * the effectful `server` shell (the scalapb/JSON codec lives there so `core` stays free of the
 * message artifact) and validated + mapped here in `core`. Every field is a raw `String`/`Seq`
 * exactly as it arrived; validation is this module's job, not the extractor's.
 */
final case class RawJob(
    jobId: String,
    postId: String,
    bucket: String,
    key: String,
    mediaType: String,
    contentType: String,
    want: Seq[String]
)

/**
 * A decoded, validated job: its identity (`jobId`/`postId`) plus the §2 [[MediaDescriptor]] the
 * pipeline runs. `jobId` is the idempotency key (a redelivery re-produces byte-identical output).
 */
final case class DecodedJob(jobId: String, postId: String, descriptor: MediaDescriptor)

/**
 * A terminal decoding/validation failure: the payload was not a usable `ProcessMediaJob`
 * (unparseable JSON, or a missing/blank required field). Terminal — a malformed message is reported
 * + acked, never redelivered (redelivering poison would wedge the lane forever).
 */
final case class DecodeError(message: String)
