package me.cference.hephaestus.report

import codex.messages.v1 as pb
import me.cference.hephaestus.job.DecodedJob
import me.cference.hephaestus.media.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import scalapb.json4s.JsonFormat

/**
 * Round-trip tests for the pure domain→wire mapping. The invariant is a lossless canonical-JSON
 * round-trip (`toJsonString` → `fromJsonString` → equal), plus assertions on the specific field
 * mapping (status, spec_version parse, ObjectRef bucket/object, dropped filesize, optionals).
 */
final class ResultMapperSpec extends AnyFunSuite with Matchers with OptionValues with EitherValues:

  private val bucket = "media"
  private val spec = DerivativeSpec(250, 850, 850, "3")

  private def job(id: String) =
    DecodedJob(
      id,
      s"post-$id",
      MediaDescriptor(bucket, "k", "image", "image/png", Set.empty, spec)
    )

  private def roundTrips(msg: pb.MediaProcessed): Unit =
    JsonFormat.fromJsonString[pb.MediaProcessed](JsonFormat.toJsonString(msg)) shouldBe msg

  private def roundTrips(msg: pb.MediaFailed): Unit =
    JsonFormat.fromJsonString[pb.MediaFailed](JsonFormat.toJsonString(msg)) shouldBe msg

  test("image thumb-only: absent optionals, single thumb derivative, status ok") {
    val meta = MediaMetadata(800, 600, None, None, 2048L, "a" * 32, "png", None)
    val result = MediaResult(
      meta,
      "d8b1861e78e199e7",
      Seq(DerivativeRef(DerivativeKind.Thumbnail, "derivatives/aa/x/thumb.webp", 250, 188)),
      "3"
    )

    val processed = ResultMapper.toProcessed(job("j1"), result, bucket).value

    processed.jobId shouldBe "j1"
    processed.postId shouldBe "post-j1"
    processed.status shouldBe "ok"
    processed.phash shouldBe "d8b1861e78e199e7"
    processed.specVersion shouldBe 3
    processed.metadata.value.width shouldBe 800
    processed.metadata.value.height shouldBe 600
    processed.metadata.value.md5 shouldBe "a" * 32
    processed.metadata.value.filetype shouldBe "png"
    // Optionals absent (not zero) for an image, and filesize has no proto field at all.
    processed.metadata.value.durationSeconds shouldBe None
    processed.metadata.value.fps shouldBe None
    processed.metadata.value.hasAudio shouldBe None
    processed.derivatives should have size 1
    val d = processed.derivatives.head
    d.kind shouldBe "thumb"
    d.ref.value.bucket shouldBe bucket
    d.ref.value.`object` shouldBe "derivatives/aa/x/thumb.webp"
    d.width shouldBe 250
    d.height shouldBe 188
    d.variant shouldBe None
    d.codec shouldBe None
    roundTrips(processed)
  }

  test("image thumb + sample: two derivatives, both round-trip") {
    val meta = MediaMetadata(1600, 1200, None, None, 4096L, "b" * 32, "jpg", None)
    val result = MediaResult(
      meta,
      "0011223344556677",
      Seq(
        DerivativeRef(DerivativeKind.Thumbnail, "derivatives/bb/x/thumb.webp", 250, 188),
        DerivativeRef(DerivativeKind.Sample, "derivatives/bb/x/sample.webp", 850, 638)
      ),
      "3"
    )

    val processed = ResultMapper.toProcessed(job("j2"), result, bucket).value

    processed.derivatives.map(_.kind) shouldBe Seq("thumb", "sample")
    processed.derivatives.map(_.ref.value.`object`) shouldBe Seq(
      "derivatives/bb/x/thumb.webp",
      "derivatives/bb/x/sample.webp"
    )
    processed.derivatives.foreach(_.ref.value.bucket shouldBe bucket)
    roundTrips(processed)
  }

  test("video with duration/fps/hasAudio and a transcode carrying variant + codec") {
    val meta = MediaMetadata(1920, 1080, Some(12.5), Some(30.0), 8192L, "c" * 32, "mp4", Some(true))
    val result = MediaResult(
      meta,
      "aabbccddeeff0011",
      Seq(
        DerivativeRef(DerivativeKind.Thumbnail, "derivatives/cc/x/thumb.webp", 250, 141),
        DerivativeRef(
          DerivativeKind.Transcode,
          "derivatives/cc/x/720p.mp4",
          1280,
          720,
          Some("720p"),
          Some("h264")
        )
      ),
      "3"
    )

    val processed = ResultMapper.toProcessed(job("j3"), result, bucket).value

    processed.metadata.value.durationSeconds shouldBe Some(12.5)
    processed.metadata.value.fps shouldBe Some(30.0)
    processed.metadata.value.hasAudio shouldBe Some(true)
    val transcode = processed.derivatives.find(_.kind == "transcode").value
    transcode.variant shouldBe Some("720p")
    transcode.codec shouldBe Some("h264")
    transcode.ref.value shouldBe pb.ObjectRef.of(bucket, "derivatives/cc/x/720p.mp4")
    roundTrips(processed)
  }

  test("a non-numeric spec-version is a typed Left, not a corrupt zero") {
    val meta = MediaMetadata(10, 10, None, None, 1L, "d" * 32, "png", None)
    val result = MediaResult(meta, "0", Seq.empty, "v1")
    ResultMapper.toProcessed(job("j4"), result, bucket).isLeft shouldBe true
  }

  test("terminal MediaError maps to MediaFailed with its stable code, message, retriable=false") {
    val cases: Seq[(MediaError, String)] = Seq(
      MediaError.UnsupportedType("x") -> "unsupported_type",
      MediaError.CorruptInput("x") -> "corrupt_input",
      MediaError.ToolFailed("ffmpeg", "exit 1") -> "tool_failed",
      MediaError.PlanFailed("bad md5") -> "plan_failed",
      MediaError.Upstream("readOriginal", "md5 mismatch", isRetriable = false) -> "upstream",
      MediaError.Unexpected("boom") -> "unexpected"
    )
    cases.foreach { (error, expectedCode) =>
      val failed = ResultMapper.toFailed(job("jf"), error)
      failed.jobId shouldBe "jf"
      failed.postId shouldBe "post-jf"
      failed.retriable shouldBe false
      failed.error.value.code shouldBe expectedCode
      failed.error.value.message shouldBe error.message
      roundTrips(failed)
    }
  }
