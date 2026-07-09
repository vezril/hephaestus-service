package me.cference.hephaestus.storage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Known-answer + edge-case tests for the pure md5 helpers. Vectors are the canonical RFC 1321 /
 * de-facto md5 test strings so a regression in encoding (hex casing, padding) is caught
 * immediately.
 */
final class Md5Spec extends AnyFunSuite with Matchers:

  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  test("known vectors hash to their canonical lowercase-hex md5") {
    Md5.hex(bytes("")) shouldBe "d41d8cd98f00b204e9800998ecf8427e"
    Md5.hex(bytes("abc")) shouldBe "900150983cd24fb0d6963f7d28e17f72"
    Md5.hex(bytes("The quick brown fox jumps over the lazy dog")) shouldBe
      "9e107d9d372bb6826bd81d3542a419d6"
  }

  test("empty input (no chunks) hashes to the md5 of the empty string") {
    Md5.hex(Iterator.empty[Array[Byte]]) shouldBe "d41d8cd98f00b204e9800998ecf8427e"
  }

  test("multi-chunk equals single-chunk for the same concatenated bytes") {
    val whole = "the-lexicon single source of truth"
    val single = Md5.hex(bytes(whole))
    val multi = Md5.hex(List("the-lexicon ", "single source ", "of truth").map(bytes))
    multi shouldBe single
  }

  test("chunk boundaries do not change the digest (empty chunks interspersed)") {
    val a = Md5.hex(List(bytes("hello"), bytes("world")))
    val b = Md5.hex(List(bytes("hel"), bytes(""), bytes("low"), bytes("orld")))
    b shouldBe a
  }

  test("output is always 32 lowercase hex chars") {
    val out = Md5.hex(bytes("anything"))
    out should fullyMatch regex "^[0-9a-f]{32}$"
  }

  test("Md5State folds incrementally to the same digest as hex") {
    val chunks = List("chunk-one|", "chunk-two|", "chunk-three").map(bytes)
    val folded = chunks.foldLeft(Md5State.empty)((st, c) => st.update(c)).hexDigest
    folded shouldBe Md5.hex(chunks)
  }

  test("Md5State.hexDigest is non-destructive (readable, then continue feeding)") {
    val st = Md5State.empty.update(bytes("ab"))
    val mid = st.hexDigest
    mid shouldBe Md5.hex(bytes("ab"))
    st.update(bytes("c"))
    st.hexDigest shouldBe Md5.hex(bytes("abc"))
  }
