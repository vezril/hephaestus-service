package me.cference.hephaestus.media

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Assembly + error-classification tests for the value objects §4 publishes: a `MediaResult` carries
 * metadata + phash + derivative refs + the stamped spec version, and every `MediaError` reports the
 * right `retriable` verdict (only an upstream transient is retriable).
 */
final class MediaResultSpec extends AnyFunSuite with Matchers:

  test("a MediaResult carries metadata, phash, derivatives and the stamped spec version") {
    val meta = MediaMetadata(
      1920,
      1080,
      Some(12.5),
      Some(30.0),
      4096L,
      "ab34cf00112233445566778899aabbcc",
      "mp4",
      Some(true)
    )
    val derivs = Seq(
      DerivativeRef(DerivativeKind.Thumbnail, "derivatives/ab/x/thumb.webp", 250, 141),
      DerivativeRef(
        DerivativeKind.Transcode,
        "derivatives/ab/x/720p.mp4",
        1280,
        720,
        Some("720p"),
        Some("h264")
      )
    )
    val result = MediaResult(meta, "d8b1861e78e199e7", derivs, "v1")

    result.metadata.hasAudio shouldBe Some(true)
    result.derivativeSpecVersion shouldBe "v1"
    result.derivatives.map(_.kind) should contain allOf (
      DerivativeKind.Thumbnail,
      DerivativeKind.Transcode
    )
    result.derivatives.last.variant shouldBe Some("720p")
  }

  test("image metadata leaves the video-only fields empty") {
    val meta =
      MediaMetadata(800, 600, None, None, 2048L, "ab34cf00112233445566778899aabbcc", "png", None)
    meta.duration shouldBe None
    meta.fps shouldBe None
    meta.hasAudio shouldBe None
  }

  test("terminal errors are not retriable") {
    Seq(
      MediaError.UnsupportedType("x"),
      MediaError.CorruptInput("x"),
      MediaError.ToolFailed("vips", "x"),
      MediaError.PlanFailed("x"),
      MediaError.Unexpected("x"),
      MediaError.Upstream("readOriginal", "md5 mismatch", isRetriable = false)
    ).foreach(_.retriable shouldBe false)
  }

  test("an upstream transient is retriable (passed through from Apollo)") {
    MediaError.Upstream("readOriginal", "unavailable", isRetriable = true).retriable shouldBe true
  }

  test("MediaError is a Throwable carrying its message (so it can fail a Future)") {
    val e: Throwable = MediaError.ToolFailed("ffmpeg", "exit 1")
    e.getMessage shouldBe "ffmpeg failed: exit 1"
  }
