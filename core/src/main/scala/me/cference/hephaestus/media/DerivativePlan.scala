package me.cference.hephaestus.media

import me.cference.hephaestus.storage.StorageKey

/**
 * One derivative the pipeline must produce: its [[DerivativeKind]], the file `name`, the
 * content-addressed Apollo `key` (`derivatives/<md5[0:2]>/<md5>/<name>`), the output `contentType`
 * and expected `dimensions`, and — for a transcode — the `variant`/`codec`.
 */
final case class DerivativeOutput(
    kind: DerivativeKind,
    name: String,
    key: String,
    contentType: String,
    dimensions: Dimensions,
    variant: Option[String] = None,
    codec: Option[String] = None
)

/**
 * The pure derivative plan: given the media type, the requested `want` set, the source dimensions,
 * the original's md5 and the derivative spec, produce the exact list of outputs to generate — with
 * their content-addressed keys. Rules:
 *
 *   - a **thumbnail** is always produced (when wanted);
 *   - a **sample** only when the source's long edge exceeds the sample threshold (a small image
 *     skips it — no redundant upscale);
 *   - a **720p transcode** only for video (when wanted).
 *
 * Key derivation is total: an invalid md5 surfaces as `Left(StorageKey.KeyError)` rather than a
 * throw.
 */
object DerivativePlan:

  private val ThumbName = "thumb.webp"
  private val SampleName = "sample.webp"
  private val TranscodeName = "720p.mp4"
  private val WebpType = "image/webp"
  private val Mp4Type = "video/mp4"
  private val TranscodeHeight = 720

  def plan(
      mediaType: MediaType,
      want: Set[DerivativeKind],
      source: Dimensions,
      md5: String,
      spec: DerivativeSpec
  ): Either[StorageKey.KeyError, Seq[DerivativeOutput]] =
    val specs: Seq[PlannedSpec] = Seq(
      Option.when(want.contains(DerivativeKind.Thumbnail))(
        PlannedSpec(
          DerivativeKind.Thumbnail,
          ThumbName,
          WebpType,
          Dimensions.fitLongEdge(source, spec.thumbnailPx)
        )
      ),
      Option.when(
        want.contains(DerivativeKind.Sample) && source.longEdge > spec.sampleMinLongEdgePx
      )(
        PlannedSpec(
          DerivativeKind.Sample,
          SampleName,
          WebpType,
          Dimensions.fitLongEdge(source, spec.samplePx)
        )
      ),
      Option.when(
        mediaType == MediaType.Video && want.contains(DerivativeKind.Transcode)
      )(
        PlannedSpec(
          DerivativeKind.Transcode,
          TranscodeName,
          Mp4Type,
          Dimensions.fitHeight(source, TranscodeHeight),
          variant = Some("720p"),
          codec = Some("h264")
        )
      )
    ).flatten

    traverse(specs) { s =>
      StorageKey
        .derivative(md5, s.name)
        .map(key =>
          DerivativeOutput(s.kind, s.name, key, s.contentType, s.dims, s.variant, s.codec)
        )
    }

  final private case class PlannedSpec(
      kind: DerivativeKind,
      name: String,
      contentType: String,
      dims: Dimensions,
      variant: Option[String] = None,
      codec: Option[String] = None
  )

  /** Short-circuiting traverse: the first `Left` aborts, otherwise all `Right`s in order. */
  private def traverse[E, A, B](xs: Seq[A])(f: A => Either[E, B]): Either[E, Seq[B]] =
    xs.foldLeft[Either[E, Seq[B]]](Right(Vector.empty)) { (acc, a) =>
      for
        bs <- acc
        b <- f(a)
      yield bs :+ b
    }
