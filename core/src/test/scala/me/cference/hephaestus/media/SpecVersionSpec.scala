package me.cference.hephaestus.media

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The spec-version parse §4 needs to turn the stamped `derivativeSpecVersion` string into the proto
 * `spec_version` int32. Total: a non-numeric value is a clear typed error (never a silent 0), so a
 * misconfiguration surfaces loudly rather than corrupting the wire message.
 */
final class SpecVersionSpec extends AnyFunSuite with Matchers:

  test("a numeric string parses to its int value") {
    SpecVersion.parse("1") shouldBe Right(1)
    SpecVersion.parse("7") shouldBe Right(7)
    SpecVersion.parse("42") shouldBe Right(42)
  }

  test("surrounding whitespace is tolerated") {
    SpecVersion.parse("  3  ") shouldBe Right(3)
  }

  test("a non-numeric value is a typed error, not a silent zero") {
    SpecVersion.parse("v1").isLeft shouldBe true
    SpecVersion.parse("").isLeft shouldBe true
    SpecVersion.parse("abc").isLeft shouldBe true
    SpecVersion.parse("1.0").isLeft shouldBe true
  }

  test("a null value is a typed error") {
    // Deliberately exercises the null-input defense (parse accepts a raw String that may arrive
    // null from Java/deserialization) — a literal null is the point of the test here.
    SpecVersion.parse(null).isLeft shouldBe true // scalafix:ok DisableSyntax.null
  }

  test("a zero or negative version is rejected (must be >= 1)") {
    SpecVersion.parse("0").isLeft shouldBe true
    SpecVersion.parse("-1").isLeft shouldBe true
    SpecVersion.parse("-42").isLeft shouldBe true
  }

  test("the error names the offending value") {
    SpecVersion.parse("v1") match
      case Left(SpecVersionError(message)) => message should include("v1")
      case Right(n) => fail(s"expected a parse error, got $n")
  }
