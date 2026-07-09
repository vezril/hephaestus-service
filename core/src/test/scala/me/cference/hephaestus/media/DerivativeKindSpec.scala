package me.cference.hephaestus.media

import me.cference.hephaestus.job.JobDecoder
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Each [[DerivativeKind]] has a stable wire token §4 stamps onto `Derivative.kind`. The token is a
 * wire contract, and it must round-trip through the §3 `want`-token decoder so a kind Hephaestus
 * reports is a kind a producer could have requested.
 */
final class DerivativeKindSpec extends AnyFunSuite with Matchers:

  test("each kind has its stable wire token") {
    DerivativeKind.Thumbnail.wireToken shouldBe "thumb"
    DerivativeKind.Sample.wireToken shouldBe "sample"
    DerivativeKind.Transcode.wireToken shouldBe "transcode"
  }

  test("wire tokens round-trip through the want-token decoder") {
    DerivativeKind.values.foreach { kind =>
      JobDecoder.wantKinds(Seq(kind.wireToken)) shouldBe Set(kind)
    }
  }
