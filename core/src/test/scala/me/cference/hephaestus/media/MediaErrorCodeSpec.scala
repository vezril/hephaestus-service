package me.cference.hephaestus.media

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The `MediaError → code` token mapping §4 stamps onto a published `MediaFailed.JobError.code`. It
 * is total (exhaustive over the ADT) and stable — the tokens are a wire contract Artemis reads, so
 * they must not drift.
 */
final class MediaErrorCodeSpec extends AnyFunSuite with Matchers:

  test("every MediaError case maps to its stable code token") {
    MediaError.code(MediaError.UnsupportedType("x")) shouldBe "unsupported_type"
    MediaError.code(MediaError.CorruptInput("x")) shouldBe "corrupt_input"
    MediaError.code(MediaError.ToolFailed("vips", "x")) shouldBe "tool_failed"
    MediaError.code(MediaError.PlanFailed("x")) shouldBe "plan_failed"
    MediaError.code(
      MediaError.Upstream("readOriginal", "x", isRetriable = false)
    ) shouldBe "upstream"
    MediaError.code(
      MediaError.Upstream("readOriginal", "x", isRetriable = true)
    ) shouldBe "upstream"
    MediaError.code(MediaError.Unexpected("x")) shouldBe "unexpected"
  }

  test("codes are lowercase snake_case and non-blank") {
    val all = Seq(
      MediaError.UnsupportedType("x"),
      MediaError.CorruptInput("x"),
      MediaError.ToolFailed("t", "x"),
      MediaError.PlanFailed("x"),
      MediaError.Upstream("op", "x", isRetriable = false),
      MediaError.Unexpected("x")
    )
    all.map(MediaError.code).foreach { c =>
      c should fullyMatch regex "[a-z_]+"
    }
    all.map(MediaError.code).distinct.size shouldBe all.size
  }
