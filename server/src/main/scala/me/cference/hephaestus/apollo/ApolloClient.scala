package me.cference.hephaestus.apollo

import apollostorage.grpc.{ObjectApiClient, ObjectMetadata}
import me.cference.hephaestus.config.ApolloConfig
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

/** Apollo's computed result for a completed write: the md5 and byte size it committed. */
final case class PutResult(md5: String, size: Long)

/**
 * The service-facing Apollo object-store client. The rest of Hephaestus depends on THIS interface,
 * not the generated `ObjectApiClient` stub, so the media pipeline and tests inject a fake or an
 * in-process-server-backed instance freely.
 *
 * Operations surface the header-then-chunks gRPC framing as Pekko Streams and classify failures as
 * retriable vs terminal via [[ApolloError]].
 */
trait ApolloClient:

  /**
   * Server-stream an original: yields its `ObjectMetadata` header, then a backpressured
   * `Source[ByteString]` of the payload whose bytes are md5-verified against the header while
   * streaming. A mismatch or a truncated stream fails the byte source with a terminal
   * [[ApolloError]]. A `NOT_FOUND`/unreachable failure surfaces from the returned `Future`.
   */
  def readOriginal(
      bucket: String,
      key: String
  ): Future[(ObjectMetadata, Source[ByteString, org.apache.pekko.NotUsed])]

  /**
   * Client-stream a derivative (`PutHeader` then chunks), committed atomically on stream
   * completion. Returns Apollo's computed md5/size. `expectedMd5`, when supplied, is sent for
   * server-side verification.
   */
  def writeDerivative(
      bucket: String,
      key: String,
      contentType: String,
      data: Source[ByteString, org.apache.pekko.NotUsed],
      expectedMd5: Option[String] = None
  ): Future[PutResult]

  /**
   * Head lookup: `Some(metadata)` when present, `None` when absent (a typed not-found, not a
   * throw).
   */
  def headExists(bucket: String, key: String): Future[Option[ObjectMetadata]]

  /** Release the underlying gRPC channel (wired into Coordinated Shutdown). */
  def close(): Future[Done]

object ApolloClient:

  /**
   * Build the production client from configuration: a plaintext (h2c) `GrpcClientSettings` to
   * Apollo's host/port with the configured per-call deadline, wrapping the generated
   * `ObjectApiClient`.
   */
  def fromConfig(apollo: ApolloConfig)(using system: ActorSystem[?]): ApolloClient =
    val settings = GrpcClientSettings
      .connectToServiceAt(apollo.host, apollo.port)(system)
      .withTls(false)
      .withDeadline(apollo.deadline)
    new LexiconApolloClient(ObjectApiClient(settings))
