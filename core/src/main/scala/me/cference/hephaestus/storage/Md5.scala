package me.cference.hephaestus.storage

import java.security.MessageDigest

/**
 * Pure md5 helpers, free of Pekko. `hex` folds a sequence of byte chunks into the lowercase-hex md5
 * of their concatenation (the tested, referentially-transparent surface). `Md5State` is an
 * incremental accumulator usable as the state of a Pekko Streams fold/sink in the `server` module,
 * so the payload never has to be buffered whole to be hashed.
 */
object Md5:

  /** Lowercase-hex md5 of the concatenation of `chunks` (order-significant, empty ⇒ md5 of ""). */
  def hex(chunks: IterableOnce[Array[Byte]]): String =
    val digest = MessageDigest.getInstance("MD5")
    chunks.iterator.foreach(digest.update)
    toHex(digest.digest())

  /** Lowercase-hex md5 of a single byte array. */
  def hex(bytes: Array[Byte]): String = hex(Iterator.single(bytes))

  /** Render raw digest bytes as lowercase hex (zero-padded, two chars per byte). */
  private[storage] def toHex(bytes: Array[Byte]): String =
    val sb = new StringBuilder(bytes.length * 2)
    bytes.foreach(b => sb.append(f"${b & 0xff}%02x"))
    sb.toString

/**
 * Incremental md5 accumulator. `update` mutates the backing `MessageDigest` and returns `this`, so
 * it threads cleanly through a sequential Pekko Streams fold
 * (`Source.fold(Md5State.empty)(_.update(_))`) without cloning per chunk. `hexDigest` snapshots the
 * digest (clone) so the state can still be read without consuming it.
 */
final class Md5State private (private val digest: MessageDigest):

  /**
   * Feed one chunk into the running digest. Mutates and returns `this` for fold-friendly chaining.
   */
  def update(bytes: Array[Byte]): Md5State =
    digest.update(bytes)
    this

  /** The lowercase-hex md5 of everything fed so far. Non-destructive (clones before finalizing). */
  def hexDigest: String =
    val snapshot = digest.clone().asInstanceOf[MessageDigest]
    Md5.toHex(snapshot.digest())

object Md5State:
  /** A fresh, empty accumulator (md5 of the empty input until anything is fed). */
  def empty: Md5State = new Md5State(MessageDigest.getInstance("MD5"))
