package me.cference.hephaestus.media

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Tests the pure derivative plan: thumbnail always, sample only when the source exceeds the sample
 * threshold, transcode only for video, all keyed content-addressed under the original's md5, dims
 * scaled without upscaling.
 */
final class DerivativePlanSpec extends AnyFunSuite with Matchers:

  private val md5 = "ab34cf00112233445566778899aabbcc"
  private val spec =
    DerivativeSpec(thumbnailPx = 250, samplePx = 850, sampleMinLongEdgePx = 850, specVersion = "v1")
  private val all = DerivativeKind.values.toSet

  private def planOf(mt: MediaType, src: Dimensions, want: Set[DerivativeKind] = all) =
    DerivativePlan.plan(mt, want, src, md5, spec).getOrElse(fail("plan returned Left"))

  test("a large image yields thumb + sample with content-addressed keys") {
    val outs = planOf(MediaType.Image, Dimensions(4000, 3000))
    outs.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail, DerivativeKind.Sample)
    outs.map(_.key) shouldBe Seq(
      s"derivatives/ab/$md5/thumb.webp",
      s"derivatives/ab/$md5/sample.webp"
    )
    outs.foreach(_.contentType shouldBe "image/webp")
  }

  test("thumbnail long edge is capped at the thumbnail px, aspect preserved") {
    val thumb = planOf(MediaType.Image, Dimensions(4000, 3000)).head
    thumb.dimensions.longEdge shouldBe 250
    thumb.dimensions shouldBe Dimensions(250, 188) // 3000 * 250/4000 = 187.5 → 188
  }

  test("sample long edge is capped at the sample px") {
    val sample = planOf(MediaType.Image, Dimensions(4000, 3000))(1)
    sample.dimensions.longEdge shouldBe 850
  }

  test("edge — a small image (long edge <= sample threshold) skips the sample") {
    val outs = planOf(MediaType.Image, Dimensions(800, 600))
    outs.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail)
  }

  test("edge — an image exactly at the sample threshold skips the sample (strict >)") {
    val outs = planOf(MediaType.Image, Dimensions(850, 400))
    outs.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail)
  }

  test("edge — one px over the sample threshold gets a sample") {
    val outs = planOf(MediaType.Image, Dimensions(851, 400))
    outs.map(_.kind) should contain(DerivativeKind.Sample)
  }

  test("thumbnail never upscales a tiny image") {
    val thumb = planOf(MediaType.Image, Dimensions(100, 80)).head
    thumb.dimensions shouldBe Dimensions(100, 80)
  }

  test("video yields thumb + sample + a 720p h264 transcode") {
    val outs = planOf(MediaType.Video, Dimensions(1920, 1080))
    outs.map(_.kind) shouldBe Seq(
      DerivativeKind.Thumbnail,
      DerivativeKind.Sample,
      DerivativeKind.Transcode
    )
    val transcode = outs.last
    transcode.name shouldBe "720p.mp4"
    transcode.key shouldBe s"derivatives/ab/$md5/720p.mp4"
    transcode.contentType shouldBe "video/mp4"
    transcode.variant shouldBe Some("720p")
    transcode.codec shouldBe Some("h264")
    transcode.dimensions shouldBe Dimensions(1280, 720)
  }

  test("the transcode caps height at 720 with an even width, never upscaling") {
    planOf(MediaType.Video, Dimensions(1920, 1080)).last.dimensions shouldBe Dimensions(1280, 720)
    // a sub-720 source is left alone (no upscale)
    planOf(MediaType.Video, Dimensions(640, 480)).last.dimensions shouldBe Dimensions(640, 480)
  }

  test("animated yields thumb + sample but no transcode") {
    val outs = planOf(MediaType.Animated, Dimensions(1200, 900))
    outs.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail, DerivativeKind.Sample)
  }

  test("the want set scopes outputs — thumbnail only") {
    val outs = planOf(MediaType.Video, Dimensions(1920, 1080), Set(DerivativeKind.Thumbnail))
    outs.map(_.kind) shouldBe Seq(DerivativeKind.Thumbnail)
  }

  test("the want set can request only a transcode") {
    val outs = planOf(MediaType.Video, Dimensions(1920, 1080), Set(DerivativeKind.Transcode))
    outs.map(_.kind) shouldBe Seq(DerivativeKind.Transcode)
  }

  test("an invalid md5 is a Left, never a throw") {
    DerivativePlan
      .plan(MediaType.Image, all, Dimensions(4000, 3000), "not-an-md5", spec)
      .isLeft shouldBe
      true
  }
