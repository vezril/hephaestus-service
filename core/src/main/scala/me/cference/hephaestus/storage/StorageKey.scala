package me.cference.hephaestus.storage

/**
 * Pure, total content-addressed key derivation for Apollo objects (implements the
 * `derivative-storage` half of the Hephaestus contract). An object's key is derived from the
 * ORIGINAL's md5:
 *
 *   - original: `originals/<md5[0:2]>/<md5>.<ext>`
 *   - derivative: `derivatives/<md5[0:2]>/<md5>/<name>`
 *
 * The two-character shard is the first two hex chars of the md5. Both functions validate their
 * inputs and return `Left(KeyError)` rather than throwing, so an invalid md5/name is a value the
 * caller must handle.
 */
object StorageKey:

  /** A rejected key derivation, naming why the input was invalid. */
  final case class KeyError(message: String)

  private val Md5Pattern = "^[0-9a-f]{32}$".r

  /**
   * `originals/<md5[0:2]>/<md5>.<ext>`. Rejects a non-lowercase-hex-32 md5 or a blank extension.
   */
  def original(md5: String, ext: String): Either[KeyError, String] =
    for
      validMd5 <- validateMd5(md5)
      cleanExt <- normalizedExt(ext)
    yield s"originals/${shard(validMd5)}/$validMd5.$cleanExt"

  /** `derivatives/<md5[0:2]>/<md5>/<name>`. Rejects an invalid md5 or a blank name. */
  def derivative(md5: String, name: String): Either[KeyError, String] =
    for
      validMd5 <- validateMd5(md5)
      cleanName <- nonBlank(name, "derivative name")
    yield s"derivatives/${shard(validMd5)}/$validMd5/$cleanName"

  private def shard(md5: String): String = md5.substring(0, 2)

  private def validateMd5(md5: String): Either[KeyError, String] =
    if md5 == null || md5.isEmpty then Left(KeyError("md5 is empty"))
    else if Md5Pattern.matches(md5) then Right(md5)
    else Left(KeyError(s"md5 is not 32 lowercase hex chars: '$md5'"))

  private def normalizedExt(ext: String): Either[KeyError, String] =
    val trimmed = if ext == null then "" else ext.stripPrefix(".").trim
    if trimmed.isEmpty then Left(KeyError("extension is blank")) else Right(trimmed)

  private def nonBlank(value: String, label: String): Either[KeyError, String] =
    if value == null || value.trim.isEmpty then Left(KeyError(s"$label is blank")) else Right(value)
