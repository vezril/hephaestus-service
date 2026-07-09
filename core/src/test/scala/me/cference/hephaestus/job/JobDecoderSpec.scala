package me.cference.hephaestus.job

import me.cference.hephaestus.media.{DerivativeKind, DerivativeSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit tests for the pure job validation + mapping. A well-formed [[RawJob]] maps to a §2
 * [[me.cference.hephaestus.media.MediaDescriptor]]; every required field is guarded (missing/blank
 * ⇒ a terminal [[DecodeError]] naming it); `want` tokens map leniently to derivative kinds.
 */
final class JobDecoderSpec extends AnyWordSpec with Matchers:

  private val spec = DerivativeSpec(250, 850, 850, "v1")

  private def raw(
      jobId: String = "job-1",
      postId: String = "post-1",
      bucket: String = "media",
      key: String = "originals/ab/ab34f.png",
      mediaType: String = "image",
      contentType: String = "image/png",
      want: Seq[String] = Seq("thumb", "sample")
  ): RawJob = RawJob(jobId, postId, bucket, key, mediaType, contentType, want)

  "JobDecoder.decode" should {

    "map a well-formed job to a pipeline descriptor" in {
      val result = JobDecoder.decode(raw(), spec)
      result match
        case Right(job) =>
          job.jobId shouldBe "job-1"
          job.postId shouldBe "post-1"
          job.descriptor.bucket shouldBe "media"
          job.descriptor.key shouldBe "originals/ab/ab34f.png"
          job.descriptor.mediaType shouldBe "image"
          job.descriptor.contentType shouldBe "image/png"
          job.descriptor.want shouldBe Set(DerivativeKind.Thumbnail, DerivativeKind.Sample)
          job.descriptor.spec shouldBe spec
        case Left(err) => fail(s"expected success, got $err")
    }

    "map want tokens leniently, ignoring unrecognized kinds" in {
      JobDecoder.wantKinds(Seq("thumbnail", "SAMPLE", "transcode", "720p", "bogus")) shouldBe
        Set(DerivativeKind.Thumbnail, DerivativeKind.Sample, DerivativeKind.Transcode)
      JobDecoder.wantKinds(Seq.empty) shouldBe empty
      JobDecoder.wantKinds(Seq("nope")) shouldBe empty
    }

    "reject each missing/blank required field, naming it" in {
      val cases = Map(
        "jobId" -> raw(jobId = ""),
        "postId" -> raw(postId = "   "),
        "source.bucket" -> raw(bucket = ""),
        "source.object" -> raw(key = " "),
        "mediaType" -> raw(mediaType = ""),
        "contentType" -> raw(contentType = "")
      )
      cases.foreach { case (field, badJob) =>
        JobDecoder.decode(badJob, spec) match
          case Left(DecodeError(message)) => message should include(field)
          case Right(ok) => fail(s"expected $field to fail, got $ok")
      }
    }

    "succeed with an empty want set (produces metadata/phash, no derivatives)" in {
      JobDecoder.decode(raw(want = Seq.empty), spec) match
        case Right(job) => job.descriptor.want shouldBe empty
        case Left(err) => fail(s"expected success, got $err")
    }
  }
