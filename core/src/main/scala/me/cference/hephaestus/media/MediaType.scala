package me.cference.hephaestus.media

/**
 * The media families Hephaestus forges derivatives for. The derivative set is chosen per family:
 * images get a thumbnail + optional sample; animated get a poster thumbnail + sample; video gets a
 * poster thumbnail + sample + an eager 720p transcode.
 */
enum MediaType:
  case Image, Animated, Video

object MediaType:

  /** A type that maps to none of image/animated/video — a terminal failure (no tools are run). */
  final case class Unsupported(detail: String)

  /**
   * Total mapping to a [[MediaType]] from the descriptor's declared `mediaType` and its
   * `contentType`. The declared type wins when it is one of the recognized tokens; otherwise the
   * MIME `contentType` decides (`image/gif` ⇒ animated, any other `image/…` ⇒ image, a `video/…` ⇒
   * video). Anything else is `Left(Unsupported)`.
   */
  def from(declaredType: String, contentType: String): Either[Unsupported, MediaType] =
    val declared = normalize(declaredType)
    val ct = normalize(contentType)
    fromDeclared(declared).orElse(fromContentType(ct)) match
      case Some(mt) => Right(mt)
      case None =>
        Left(Unsupported(s"mediaType='$declaredType' contentType='$contentType'"))

  private def fromDeclared(declared: String): Option[MediaType] =
    declared match
      case "image" => Some(MediaType.Image)
      case "animated" | "animation" | "gif" => Some(MediaType.Animated)
      case "video" => Some(MediaType.Video)
      case _ => None

  private def fromContentType(ct: String): Option[MediaType] =
    if ct == "image/gif" then Some(MediaType.Animated)
    else if ct.startsWith("image/") then Some(MediaType.Image)
    else if ct.startsWith("video/") then Some(MediaType.Video)
    else None

  private def normalize(s: String): String =
    Option(s).map(_.trim.toLowerCase).getOrElse("")
