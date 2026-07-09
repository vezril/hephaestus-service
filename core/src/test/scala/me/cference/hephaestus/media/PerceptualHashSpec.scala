package me.cference.hephaestus.media

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Tests the pure DCT pHash: determinism (the core promise — the same original hashes identically
 * every time), a pinned golden vector (a regression anchor), a 1-pixel shift perturbs only a few
 * bits (small Hamming distance), and the hex rendering is a stable 16-char lowercase string.
 */
final class PerceptualHashSpec extends AnyFunSuite with Matchers:

  private val n = PerceptualHash.RasterSize

  private def raster(f: (Int, Int) => Int): GrayscaleRaster =
    val px = IArray.tabulate(n * n)(i => f(i % n, i / n) & 0xff)
    GrayscaleRaster(n, px)

  private def hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a ^ b)

  private val patterned = raster((x, y) => x * 7 + y * 13)
  private val gradient = raster((x, y) => (x + y) * 4)

  /**
   * A smooth, low-frequency "natural" image (a sum of sinusoids): its 8×8 DCT block is
   * well-separated from the median, so bit assignments are robust to a 1-pixel shift.
   */
  private def smooth(x: Int, y: Int): Int =
    val v = 128.0 + 50 * math.sin(2 * math.Pi * x / 17.0) + 45 * math.cos(2 * math.Pi * y / 13.0) +
      35 * math.sin(2 * math.Pi * (x + y) / 23.0) + 25 * math.cos(2 * math.Pi * (x - y) / 11.0)
    math.max(0, math.min(255, math.round(v).toInt))
  private val natural = raster(smooth)

  test("identical rasters hash identically") {
    PerceptualHash.compute(patterned) shouldBe PerceptualHash.compute(patterned)
  }

  test("the hash is deterministic across independently constructed identical rasters") {
    val a = raster((x, y) => x * 7 + y * 13)
    val b = raster((x, y) => x * 7 + y * 13)
    PerceptualHash.compute(a).bits shouldBe PerceptualHash.compute(b).bits
  }

  test("golden — the patterned raster hashes to its pinned vector") {
    PerceptualHash.compute(patterned).hex shouldBe "8d3585376ae22d5a"
  }

  test("hex is always 16 lowercase hex chars (zero-padded, unsigned)") {
    val hex = PerceptualHash.compute(patterned).hex
    hex should have length 16
    hex shouldBe hex.toLowerCase
    hex should fullyMatch regex "[0-9a-f]{16}"
  }

  test("a 1-pixel shift perturbs only a few low-frequency bits (small Hamming distance)") {
    val shiftX = raster((x, y) => smooth(math.min(x + 1, n - 1), y))
    val shiftY = raster((x, y) => smooth(x, math.min(y + 1, n - 1)))
    hamming(
      PerceptualHash.compute(natural).bits,
      PerceptualHash.compute(shiftX).bits
    ) should be <= 12
    hamming(
      PerceptualHash.compute(natural).bits,
      PerceptualHash.compute(shiftY).bits
    ) should be <= 12
  }

  test("a very different (inverted) image is far in Hamming distance") {
    val inverted = raster((x, y) => 255 - smooth(x, y))
    val d = hamming(PerceptualHash.compute(natural).bits, PerceptualHash.compute(inverted).bits)
    d should be >= 24
  }

  test("the DC term is the most-significant bit (a bright-biased image sets it)") {
    // A ramp whose low-frequency energy makes the DC coefficient exceed the AC median.
    val hash = PerceptualHash.compute(gradient)
    (hash.bits & (1L << 63)) should not be 0L
  }
