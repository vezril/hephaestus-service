package me.cference.hephaestus.apollo

import apollostorage.grpc.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  ObjectApiClient,
  ObjectMetadata,
  PutHeader,
  PutObjectRequest
}
import com.google.protobuf.ByteString as ProtoBytes
import io.grpc.{Status, StatusRuntimeException}
import me.cference.hephaestus.storage.Md5State
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Materializer, Outlet}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed}

import scala.concurrent.Future

/**
 * The Lexicon-stub-backed [[ApolloClient]]. Wraps the generated `ObjectApiClient`, translating the
 * `oneof` header/chunk framing to/from Pekko Streams and mapping every failure to a typed
 * [[ApolloError]] via [[ApolloError.classify]].
 */
final class LexiconApolloClient(client: ObjectApiClient)(using system: ActorSystem[?])
    extends ApolloClient:

  private given ec: scala.concurrent.ExecutionContext = system.executionContext
  private given mat: Materializer = Materializer.matFromSystem(using system)

  def readOriginal(
      bucket: String,
      key: String
  ): Future[(ObjectMetadata, Source[ByteString, NotUsed])] =
    client
      .getObject(GetObjectRequest(bucket, key))
      // Split the leading header message from the chunk tail without buffering the payload.
      .prefixAndTail(1)
      .runWith(Sink.head)
      .map { case (prefix, tail) =>
        prefix.headOption.map(_.payload) match
          case Some(GetObjectResponse.Payload.Header(meta)) =>
            val bytes: Source[ByteString, NotUsed] = tail.map { resp =>
              resp.payload match
                case GetObjectResponse.Payload.Chunk(b) => ByteString.fromArrayUnsafe(b.toByteArray)
                case GetObjectResponse.Payload.Header(_) =>
                  throw ApolloError.Protocol("readOriginal", "unexpected second header in stream")
                case GetObjectResponse.Payload.Empty =>
                  throw ApolloError.Protocol("readOriginal", "empty payload message in stream")
            }
            // Classify failures on the payload Source too (not just the header Future): an Apollo
            // drop / DEADLINE_EXCEEDED mid-stream must surface as a typed, retriable-flagged
            // ApolloError. `classify` is idempotent, so it passes our terminal Md5Mismatch/Truncated
            // through unchanged.
            val verified = bytes
              .via(new Md5VerifyingStage(bucket, key, meta.md5, meta.size))
              .mapError { case t => ApolloError.classify("readOriginal", t) }
            (meta, verified)
          case _ =>
            throw ApolloError.Protocol("readOriginal", "first message was not a header")
      }
      .recoverWith { case t => Future.failed(ApolloError.classify("readOriginal", t)) }

  def writeDerivative(
      bucket: String,
      key: String,
      contentType: String,
      data: Source[ByteString, NotUsed],
      expectedMd5: Option[String]
  ): Future[PutResult] =
    val header = PutObjectRequest(
      PutObjectRequest.Payload.Header(
        PutHeader(
          bucket = bucket,
          `object` = key,
          contentType = contentType,
          expectedCrc32C = "",
          expectedMd5 = expectedMd5.getOrElse("")
        )
      )
    )
    val chunks = data.map(b =>
      PutObjectRequest(PutObjectRequest.Payload.Chunk(ProtoBytes.copyFrom(b.toArrayUnsafe())))
    )
    client
      .putObject(Source.single(header).concat(chunks))
      .map(resp => PutResult(resp.md5, resp.size))
      .recoverWith { case t => Future.failed(ApolloError.classify("writeDerivative", t)) }

  def headExists(bucket: String, key: String): Future[Option[ObjectMetadata]] =
    client
      .headObject(HeadObjectRequest(bucket, key))
      .map(Some(_))
      .recoverWith {
        case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.NOT_FOUND =>
          Future.successful(None)
        case t => Future.failed(ApolloError.classify("headExists", t))
      }

  def close(): Future[Done] = client.close()

/**
 * A pass-through flow that md5-hashes and size-counts the payload as it streams, and on clean
 * completion verifies both against the metadata header — failing the stream with a terminal
 * [[ApolloError]] on a missing header md5 (Apollo contract violation), a hash mismatch
 * (corruption), or a short count (truncation). Bytes are hashed in place (no per-chunk array copy)
 * and never buffered.
 */
final private class Md5VerifyingStage(
    bucket: String,
    key: String,
    expectedMd5: String,
    expectedSize: Long
) extends GraphStage[FlowShape[ByteString, ByteString]]:

  private val in = Inlet[ByteString]("Md5Verifying.in")
  private val out = Outlet[ByteString]("Md5Verifying.out")
  override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

  override def createLogic(attrs: Attributes): GraphStageLogic =
    new GraphStageLogic(shape):
      private var state = Md5State.empty
      private var seen = 0L

      setHandler(
        in,
        new InHandler:
          override def onPush(): Unit =
            val elem = grab(in)
            // Feed the digest directly from the ByteString's backing buffers — no array copy.
            elem.asByteBuffers.foreach(bb => state = state.update(bb))
            seen += elem.length
            push(out, elem)

          override def onUpstreamFinish(): Unit =
            // An original with no md5 in its metadata header is an Apollo contract violation, not a
            // signal to skip verification: fail terminally rather than pass unverified bytes.
            if expectedMd5.isEmpty then
              failStage(ApolloError.Protocol("readOriginal", "metadata header carried no md5"))
            else
              val actual = state.hexDigest
              if !actual.equalsIgnoreCase(expectedMd5) then
                failStage(ApolloError.Md5Mismatch(bucket, key, expectedMd5, actual))
              else if expectedSize >= 0 && seen != expectedSize then
                failStage(ApolloError.Truncated(bucket, key, expectedSize, seen))
              else completeStage()
      )

      setHandler(
        out,
        new OutHandler:
          override def onPull(): Unit = pull(in)
      )
