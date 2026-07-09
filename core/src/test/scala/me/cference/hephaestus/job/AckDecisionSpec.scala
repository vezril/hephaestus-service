package me.cference.hephaestus.job

import me.cference.hephaestus.media.{MediaError, MediaMetadata, MediaResult}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit tests for the pure ack policy: success and terminal failures are published then acked; a
 * retriable failure is left unacked for redelivery; an undecodable message is acked without a
 * publish.
 */
final class AckDecisionSpec extends AnyWordSpec with Matchers:

  private val result =
    MediaResult(
      MediaMetadata(10, 10, None, None, 1L, "abc", "png", None),
      "0" * 16,
      Seq.empty,
      "v1"
    )

  "AckPolicy.decide" should {
    "publish then ack a successful result" in {
      AckPolicy.decide(Right(result)) shouldBe AckAction.PublishThenAck
    }

    "publish then ack a terminal media failure" in {
      AckPolicy.decide(Left(MediaError.UnsupportedType("x"))) shouldBe AckAction.PublishThenAck
      AckPolicy.decide(Left(MediaError.CorruptInput("x"))) shouldBe AckAction.PublishThenAck
      AckPolicy.decide(
        Left(MediaError.Upstream("apollo", "checksum", isRetriable = false))
      ) shouldBe
        AckAction.PublishThenAck
    }

    "leave a retriable failure unacked for redelivery" in {
      AckPolicy.decide(Left(MediaError.Upstream("apollo", "down", isRetriable = true))) shouldBe
        AckAction.LeaveUnacked
    }
  }

  "AckPolicy.onDecodeFailure" should {
    "ack an undecodable message without publishing" in {
      AckPolicy.onDecodeFailure shouldBe AckAction.AckOnly
    }
  }
