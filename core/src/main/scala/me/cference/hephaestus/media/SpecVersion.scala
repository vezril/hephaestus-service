package me.cference.hephaestus.media

/** A rejected spec-version parse, naming the offending value. */
final case class SpecVersionError(message: String)

/**
 * Parse the stamped `derivativeSpecVersion` string into the proto `spec_version` int32. Total: a
 * missing/non-numeric value — or a `< 1` value (a zero/negative version stamp is semantically
 * nonsensical) — yields a typed [[SpecVersionError]] rather than a silent 0, so a misconfiguration
 * surfaces loudly instead of corrupting the published `MediaProcessed`. Transport-free, so it lives
 * in `core` alongside the other pure helpers.
 */
object SpecVersion:

  def parse(raw: String): Either[SpecVersionError, Int] =
    val trimmed = if raw == null then "" else raw.trim
    trimmed.toIntOption match
      case Some(n) if n >= 1 => Right(n)
      case Some(_) => Left(SpecVersionError(s"derivative spec-version must be >= 1: '$raw'"))
      case None => Left(SpecVersionError(s"derivative spec-version is not numeric: '$raw'"))
