package me.cference.hephaestus.media

/** A rejected spec-version parse, naming the offending value. */
final case class SpecVersionError(message: String)

/**
 * Parse the stamped `derivativeSpecVersion` string into the proto `spec_version` int32. Total: a
 * missing/non-numeric value yields a typed [[SpecVersionError]] rather than a silent 0, so a
 * misconfiguration surfaces loudly instead of corrupting the published `MediaProcessed`. Transport-
 * free, so it lives in `core` alongside the other pure helpers.
 */
object SpecVersion:

  def parse(raw: String): Either[SpecVersionError, Int] =
    val trimmed = if raw == null then "" else raw.trim
    trimmed.toIntOption match
      case Some(n) => Right(n)
      case None => Left(SpecVersionError(s"derivative spec-version is not numeric: '$raw'"))
