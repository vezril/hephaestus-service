package me.cference.hephaestus.media

/**
 * A small square grayscale raster: `size`×`size` pixels in row-major order, each in `0..255`. This
 * is the dependency-light input libvips emits (a forced-size b-w PGM) that the pure pHash consumes,
 * so the perceptual-hash math has no native-library dependency and is unit-testable in `core`.
 */
final case class GrayscaleRaster(size: Int, pixels: IArray[Int]):
  require(pixels.length == size * size, s"raster is ${pixels.length} px, expected ${size * size}")

/** A 64-bit perceptual hash: the raw `bits` and a stable zero-padded lowercase-hex rendering. */
final case class PHash(bits: Long):
  /** 16 lowercase hex chars (unsigned two's-complement) — the form Artemis stores. */
  def hex: String = f"$bits%016x"

/**
 * A deterministic DCT-based 64-bit perceptual hash (the Zauner pHash construction):
 *
 *   1. take a `size`×`size` grayscale raster (libvips forces the source to e.g. 32×32, aspect
 *      deliberately broken); 2. compute its 2-D DCT-II (orthonormal scaling); 3. keep the top-left
 *      8×8 low-frequency block (the coarse structure); 4. take the median of the 63 AC coefficients
 *      (excluding the DC term `(0,0)`, which dwarfs the rest); 5. emit 64 bits — bit `= 1` where
 *      the coefficient exceeds that median.
 *
 * Bit layout: the 8×8 block is walked row-major (`row = i/8`, `col = i%8`) for `i` in `0..63`, and
 * bit `i` is placed at position `63 - i`, so `i = 0` (the DC term) is the most-significant bit. The
 * routine is pure and float-deterministic: the same raster always yields the same hash, and a
 * 1-pixel shift perturbs only a few low-frequency bits (a small Hamming distance).
 */
object PerceptualHash:

  /** Standard raster edge for the pHash (32×32 → 8×8 low-frequency block). */
  val RasterSize = 32

  def compute(raster: GrayscaleRaster): PHash =
    val n = raster.size
    val dct = dct2d(raster.pixels, n)

    // Row-major 8×8 low-frequency block (guard against a raster smaller than 8 on an edge).
    val block: IndexedSeq[Double] =
      for
        row <- 0 until 8
        col <- 0 until 8
      yield if row < n && col < n then dct(row * n + col) else 0.0

    val median = medianOf(block.tail) // exclude DC (index 0)

    var bits = 0L
    var i = 0
    while i < 64 do
      if block(i) > median then bits |= (1L << (63 - i))
      i += 1
    PHash(bits)

  /** Orthonormal 2-D DCT-II of an `n`×`n` row-major raster, returned row-major. */
  private def dct2d(pixels: IArray[Int], n: Int): Array[Double] =
    val cos = Array.tabulate(n, n)((k, x) => math.cos(((2 * x + 1) * k * math.Pi) / (2 * n)))
    val c = Array.tabulate(n)(k => if k == 0 then math.sqrt(1.0 / n) else math.sqrt(2.0 / n))
    val out = new Array[Double](n * n)
    var u = 0
    while u < n do
      var v = 0
      while v < n do
        var sum = 0.0
        var x = 0
        while x < n do
          val cux = cos(u)(x)
          var y = 0
          while y < n do
            sum += cux * cos(v)(y) * pixels(x * n + y)
            y += 1
          x += 1
        out(u * n + v) = c(u) * c(v) * sum
        v += 1
      u += 1
    out

  private def medianOf(values: Seq[Double]): Double =
    val sorted = values.sorted
    val len = sorted.length
    if len == 0 then 0.0
    else if len % 2 == 1 then sorted(len / 2)
    else (sorted(len / 2 - 1) + sorted(len / 2)) / 2.0
