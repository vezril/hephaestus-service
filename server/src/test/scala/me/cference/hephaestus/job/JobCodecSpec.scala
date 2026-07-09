package me.cference.hephaestus.job

import codex.messages.v1.{ObjectRef, ProcessMediaJob}
import me.cference.hephaestus.media.{DerivativeKind, DerivativeSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scalapb.json4s.JsonFormat

/**
 * Round-trip tests for the wire-format boundary: a `ProcessMediaJob` encoded to protobuf-canonical
 * JSON (the HermesMQ wire format) parses back into a [[RawJob]], and malformed/incomplete payloads
 * are terminal [[DecodeError]]s rather than thrown exceptions.
 */
final class JobCodecSpec extends AnyWordSpec with Matchers:

  private val spec = DerivativeSpec(250, 850, 850, "v1")

  private val job = ProcessMediaJob(
    jobId = "job-1",
    postId = "post-1",
    source = Some(ObjectRef(bucket = "media", `object` = "originals/ab/ab34f.png")),
    mediaType = "image",
    contentType = "image/png",
    want = Seq("thumb", "sample")
  )

  "JobCodec.parse" should {
    "parse canonical JSON into a RawJob" in {
      val payload = JsonFormat.toJsonString(job)
      JobCodec.parse(payload) match
        case Right(raw) =>
          raw.jobId shouldBe "job-1"
          raw.postId shouldBe "post-1"
          raw.bucket shouldBe "media"
          raw.key shouldBe "originals/ab/ab34f.png"
          raw.mediaType shouldBe "image"
          raw.contentType shouldBe "image/png"
          raw.want shouldBe Seq("thumb", "sample")
        case Left(err) => fail(s"expected parse success, got $err")
    }

    "return a terminal DecodeError on unparseable JSON (not a throw)" in {
      JobCodec.parse("{not json") match
        case Left(DecodeError(message)) => message should include("unparseable")
        case Right(ok) => fail(s"expected decode error, got $ok")
    }

    "return a terminal DecodeError when the source object is absent" in {
      val payload = JsonFormat.toJsonString(job.copy(source = None))
      JobCodec.parse(payload) match
        case Left(DecodeError(message)) => message should include("source")
        case Right(ok) => fail(s"expected decode error, got $ok")
    }
  }

  "JobCodec.decode" should {
    "parse AND validate/map to a decoded job with a descriptor" in {
      val payload = JsonFormat.toJsonString(job)
      JobCodec.decode(payload, spec) match
        case Right(decoded) =>
          decoded.jobId shouldBe "job-1"
          decoded.descriptor.bucket shouldBe "media"
          decoded.descriptor.want shouldBe Set(DerivativeKind.Thumbnail, DerivativeKind.Sample)
        case Left(err) => fail(s"expected decode success, got $err")
    }

    "surface a blank required field from the pure validator" in {
      val payload = JsonFormat.toJsonString(job.copy(jobId = ""))
      JobCodec.decode(payload, spec) match
        case Left(DecodeError(message)) => message should include("jobId")
        case Right(ok) => fail(s"expected validation failure, got $ok")
    }
  }
