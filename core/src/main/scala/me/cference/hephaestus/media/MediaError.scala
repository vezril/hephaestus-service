package me.cference.hephaestus.media

/**
 * Typed failure surface for the media pipeline. Every case carries a `retriable` flag so §4
 * result-reporting can leave retriable jobs for redelivery and quarantine terminal ones. It extends
 * `RuntimeException` (a JDK type — `core` stays free of Pekko) so the effectful `server` pipeline
 * can fail a `Future`/stream with it directly.
 *
 * Almost everything is **terminal**: an unsupported type, corrupt input, a media-tool crash, a
 * malformed plan. The one retriable-capable case is [[Upstream]], which carries through Apollo's
 * own `retriable` classification (an md5 mismatch is terminal; an Apollo outage is retriable).
 */
sealed abstract class MediaError(val message: String, val retriable: Boolean)
    extends RuntimeException(message)

object MediaError:

  /** The declared/derived media type is none of image/animated/video — terminal, no tools run. */
  final case class UnsupportedType(detail: String)
      extends MediaError(s"unsupported media type: $detail", retriable = false)

  /** The input decoded to nothing usable (a tool rejected valid-looking bytes) — terminal. */
  final case class CorruptInput(detail: String)
      extends MediaError(s"corrupt input: $detail", retriable = false)

  /** A media tool exited nonzero / emitted unparseable output on valid input — terminal. */
  final case class ToolFailed(tool: String, detail: String)
      extends MediaError(s"$tool failed: $detail", retriable = false)

  /** Deriving a content-addressed derivative key failed (a bad md5) — terminal. */
  final case class PlanFailed(detail: String)
      extends MediaError(s"plan failed: $detail", retriable = false)

  /**
   * A failure from an upstream dependency (Apollo object I/O), carrying that dependency's own
   * `retriable` verdict through unchanged so a transient outage stays retriable and a checksum/
   * not-found stays terminal.
   */
  final case class Upstream(op: String, detail: String, isRetriable: Boolean)
      extends MediaError(s"upstream $op: $detail", retriable = isRetriable)

  /** Any unclassified failure — conservatively terminal (do not hammer on unknown errors). */
  final case class Unexpected(detail: String)
      extends MediaError(s"unexpected: $detail", retriable = false)
