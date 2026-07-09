package me.cference.hephaestus.media

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Total-mapping tests for [[MediaType.from]]: the declared token wins when recognized, the MIME
 * content type decides otherwise, `image/gif` is animated, and anything unknown is a `Left`.
 */
final class MediaTypeSpec extends AnyFunSuite with Matchers:

  test("content type image/png maps to Image") {
    MediaType.from("", "image/png") shouldBe Right(MediaType.Image)
  }

  test("content type image/jpeg maps to Image") {
    MediaType.from("", "image/jpeg") shouldBe Right(MediaType.Image)
  }

  test("content type image/gif maps to Animated (gifs are animated)") {
    MediaType.from("", "image/gif") shouldBe Right(MediaType.Animated)
  }

  test("content type video/mp4 maps to Video") {
    MediaType.from("", "video/mp4") shouldBe Right(MediaType.Video)
  }

  test("declared token wins over an absent/mismatched content type") {
    MediaType.from("video", "") shouldBe Right(MediaType.Video)
    MediaType.from("animated", "image/gif") shouldBe Right(MediaType.Animated)
    MediaType.from("image", "application/octet-stream") shouldBe Right(MediaType.Image)
  }

  test("declared tokens are case- and whitespace-insensitive") {
    MediaType.from("  VIDEO ", "") shouldBe Right(MediaType.Video)
    MediaType.from("Image", "") shouldBe Right(MediaType.Image)
  }

  test("edge — an unsupported content type with no declared type is Left(Unsupported)") {
    MediaType.from("", "application/pdf") match
      case Left(MediaType.Unsupported(_)) => succeed
      case other => fail(s"expected Unsupported, got $other")
  }

  test("edge — both empty is Left(Unsupported)") {
    MediaType.from("", "").isLeft shouldBe true
  }

  test("edge — a garbage declared type falls back to the content type") {
    MediaType.from("banana", "image/webp") shouldBe Right(MediaType.Image)
    MediaType.from("banana", "text/plain").isLeft shouldBe true
  }
