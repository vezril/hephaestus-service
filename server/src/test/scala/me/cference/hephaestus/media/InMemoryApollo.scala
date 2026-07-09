package me.cference.hephaestus.media

import apollostorage.grpc.ObjectMetadata
import me.cference.hephaestus.apollo.{ApolloClient, ApolloError, PutResult}
import me.cference.hephaestus.storage.Md5
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * A lightweight in-memory [[ApolloClient]] for orchestration tests: originals are seeded directly,
 * derivative writes are captured in a map, and read failures can be injected — both a failed
 * `readOriginal` Future (a transient outage) and a mid-stream payload failure (a terminal md5
 * mismatch, as §1's verifier would raise). It exercises the pipeline's read/stage/write wiring
 * without a gRPC round-trip (the real round-trip is covered by the integration spec).
 */
final class InMemoryApollo(
    readFutureFailure: Option[ApolloError] = None,
    readStreamFailure: Option[ApolloError] = None
)(using system: ActorSystem[?])
    extends ApolloClient:

  private given ec: ExecutionContext = system.executionContext
  private given mat: Materializer = Materializer.matFromSystem(using system)

  private val originals = TrieMap.empty[(String, String), (Array[Byte], String)]
  val writes: TrieMap[(String, String), Array[Byte]] = TrieMap.empty

  /** Seed an original the pipeline will read + stage. */
  def seedOriginal(bucket: String, key: String, bytes: Array[Byte], contentType: String): Unit =
    originals.update((bucket, key), (bytes, contentType))
    ()

  def readOriginal(
      bucket: String,
      key: String
  ): Future[(ObjectMetadata, Source[ByteString, NotUsed])] =
    readFutureFailure match
      case Some(err) => Future.failed(err)
      case None =>
        originals.get((bucket, key)) match
          case None =>
            Future.failed(ApolloError.NotFound("readOriginal", s"$bucket/$key"))
          case Some((bytes, contentType)) =>
            val meta = ObjectMetadata(
              bucket = bucket,
              `object` = key,
              contentType = contentType,
              size = bytes.length.toLong,
              crc32C = "",
              md5 = Md5.hex(bytes),
              generation = 1L
            )
            val payload = Source(bytes.grouped(8).map(ByteString.fromArrayUnsafe).toList)
            val source = readStreamFailure match
              case Some(err) =>
                payload.concat(Source.failed(err).mapMaterializedValue(_ => NotUsed))
              case None => payload
            Future.successful((meta, source.mapMaterializedValue(_ => NotUsed)))

  def writeDerivative(
      bucket: String,
      key: String,
      contentType: String,
      data: Source[ByteString, NotUsed],
      expectedMd5: Option[String]
  ): Future[PutResult] =
    data.runWith(Sink.fold(ByteString.empty)(_ ++ _)).map { collected =>
      val bytes = collected.toArray
      writes.update((bucket, key), bytes)
      PutResult(Md5.hex(bytes), bytes.length.toLong)
    }

  def headExists(bucket: String, key: String): Future[Option[ObjectMetadata]] =
    Future.successful(None)

  def close(): Future[Done] = Future.successful(Done)
