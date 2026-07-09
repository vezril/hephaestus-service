package me.cference.hephaestus.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Total-function tests for content-addressed key derivation: the happy paths produce the exact
 * `originals/..`/`derivatives/..` shapes from the contract, and every invalid input is a `Left`
 * (never a throw).
 */
final class StorageKeySpec extends AnyFunSuite with Matchers:

  private val md5 = "ab34cf00112233445566778899aabbcc" // 32 lowercase hex

  test("derivative key is derivatives/<md5[0:2]>/<md5>/<name>") {
    StorageKey.derivative(md5, "thumb.webp") shouldBe
      Right(s"derivatives/ab/$md5/thumb.webp")
  }

  test("original key is originals/<md5[0:2]>/<md5>.<ext>") {
    StorageKey.original(md5, "jpg") shouldBe Right(s"originals/ab/$md5.jpg")
  }

  test("the shard is exactly the first two md5 chars") {
    val other = "ff00000000000000000000000000ffff"
    StorageKey.derivative(other, "s.webp") shouldBe Right(s"derivatives/ff/$other/s.webp")
  }

  test("a leading dot on the extension is normalized away") {
    StorageKey.original(md5, ".png") shouldBe Right(s"originals/ab/$md5.png")
  }

  test("empty md5 is rejected (Left, not a throw)") {
    StorageKey.derivative("", "x.webp").isLeft shouldBe true
    StorageKey.original("", "jpg").isLeft shouldBe true
  }

  test("an md5 of the wrong length is rejected") {
    StorageKey.derivative("abc", "x.webp").isLeft shouldBe true
  }

  test("uppercase hex is rejected (keys are canonical lowercase)") {
    StorageKey.derivative(md5.toUpperCase, "x.webp").isLeft shouldBe true
  }

  test("non-hex characters are rejected") {
    StorageKey.original("zz34cf00112233445566778899aabbcc", "jpg").isLeft shouldBe true
  }

  test("a blank extension is rejected") {
    StorageKey.original(md5, "").isLeft shouldBe true
    StorageKey.original(md5, "   ").isLeft shouldBe true
  }

  test("a blank derivative name is rejected") {
    StorageKey.derivative(md5, "").isLeft shouldBe true
    StorageKey.derivative(md5, "   ").isLeft shouldBe true
  }
