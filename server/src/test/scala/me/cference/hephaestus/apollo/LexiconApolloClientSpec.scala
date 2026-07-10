package me.cference.hephaestus.apollo

import apollostorage.grpc.*
import com.google.protobuf.ByteString as ProtoBytes
import com.typesafe.config.ConfigFactory
import io.grpc.Status
import me.cference.hephaestus.storage.Md5
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.grpc.{GrpcClientSettings, GrpcServiceException}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * In-process gRPC round-trip tests for [[LexiconApolloClient]] (h2c), mirroring apollo-storage's
 * `ObjectApiSpec`: a stub `ObjectApiPowerApi` backed by an in-memory map is bound over HTTP/2, and
 * a real generated `ObjectApiClient` is pointed at it and wrapped by the client under test. The
 * stub can inject a mismatched header so md5/truncation verification is exercised end-to-end.
 */
final class LexiconApolloClientSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("pekko.http.server.preview.enable-http2 = on")
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(1, Seconds))

  private val bucket = "media"
  private var stub: StubObjectApi = scala.compiletime.uninitialized
  private var apollo: ApolloClient = scala.compiletime.uninitialized

  override protected def beforeAll(): Unit =
    super.beforeAll()
    stub = new StubObjectApi
    val handler: HttpRequest => Future[HttpResponse] = ObjectApiPowerApiHandler(stub)
    val binding = Http()(system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
    val port = binding.localAddress.getPort
    val client = ObjectApiClient(
      GrpcClientSettings.connectToServiceAt("127.0.0.1", port)(system).withTls(false)
    )
    apollo = new LexiconApolloClient(client)

  // --- helpers ---------------------------------------------------------------

  private def source(bytes: Array[Byte]): Source[ByteString, NotUsed] =
    Source(bytes.grouped(8).map(ByteString.fromArrayUnsafe).toList)

  private def drain(src: Source[ByteString, NotUsed]): Array[Byte] =
    src.runWith(Sink.fold(ByteString.empty)(_ ++ _)).futureValue.toArray

  // --- tests -----------------------------------------------------------------

  "readOriginal" should {
    "yield the header then md5-verified bytes for a stored original" in {
      val payload = ("forge bytes " * 40).getBytes("UTF-8")
      apollo.writeDerivative(bucket, "orig-a", "image/jpeg", source(payload)).futureValue
      val (meta, bytes) = apollo.readOriginal(bucket, "orig-a").futureValue
      meta.md5 shouldBe Md5.hex(payload)
      meta.size shouldBe payload.length.toLong
      drain(bytes) shouldBe payload
    }

    "fail terminally with Md5Mismatch when the bytes do not hash to the header md5" in {
      val payload = "corrupt".getBytes("UTF-8")
      stub.injectRaw(bucket, "bad", payload, "application/octet-stream", "0" * 32, payload.length)
      val (_, bytes) = apollo.readOriginal(bucket, "bad").futureValue
      val ex = bytes.runWith(Sink.ignore).failed.futureValue
      ex shouldBe a[ApolloError.Md5Mismatch]
      ex.asInstanceOf[ApolloError].retriable shouldBe false
    }

    "fail terminally with Truncated when the stream ends before the declared size" in {
      val payload = "short".getBytes("UTF-8")
      stub.injectRaw(bucket, "trunc", payload, "text/plain", Md5.hex(payload), payload.length + 10L)
      val (_, bytes) = apollo.readOriginal(bucket, "trunc").futureValue
      val ex = bytes.runWith(Sink.ignore).failed.futureValue
      ex shouldBe a[ApolloError.Truncated]
      ex.asInstanceOf[ApolloError].retriable shouldBe false
    }

    "surface a terminal NotFound for a missing object" in {
      val ex = apollo.readOriginal(bucket, "ghost").failed.futureValue
      ex shouldBe a[ApolloError.NotFound]
      ex.asInstanceOf[ApolloError].retriable shouldBe false
    }

    "surface a typed retriable ApolloError when the payload stream drops mid-transfer" in {
      val payload = ("bytes " * 40).getBytes("UTF-8")
      // Header md5/size agree with the bytes, but the server drops (UNAVAILABLE) after the chunks —
      // the failure must reach the collector classified, not as a raw StatusRuntimeException.
      stub.injectMidStreamDrop(bucket, "dropped", payload, "application/octet-stream")
      val (_, bytes) = apollo.readOriginal(bucket, "dropped").futureValue
      val ex = bytes.runWith(Sink.ignore).failed.futureValue
      ex shouldBe a[ApolloError]
      ex.asInstanceOf[ApolloError].retriable shouldBe true
    }

    "fail terminally when the metadata header carries no md5 (Apollo contract violation)" in {
      val payload = "unverifiable".getBytes("UTF-8")
      stub.injectRaw(bucket, "nomd5", payload, "text/plain", "", payload.length.toLong)
      val (_, bytes) = apollo.readOriginal(bucket, "nomd5").futureValue
      val ex = bytes.runWith(Sink.ignore).failed.futureValue
      ex shouldBe a[ApolloError.Protocol]
      ex.asInstanceOf[ApolloError].retriable shouldBe false
    }
  }

  "writeDerivative" should {
    "commit atomically and return Apollo's md5/size" in {
      val payload = ("thumb " * 30).getBytes("UTF-8")
      val res =
        apollo.writeDerivative(bucket, "d/thumb.webp", "image/webp", source(payload)).futureValue
      res.md5 shouldBe Md5.hex(payload)
      res.size shouldBe payload.length.toLong
      val (_, bytes) = apollo.readOriginal(bucket, "d/thumb.webp").futureValue
      drain(bytes) shouldBe payload
    }

    "overwrite byte-identically on a repeated write (idempotent reprocess)" in {
      val payload = "same-bytes".getBytes("UTF-8")
      val first = apollo.writeDerivative(bucket, "idem", "text/plain", source(payload)).futureValue
      val second = apollo.writeDerivative(bucket, "idem", "text/plain", source(payload)).futureValue
      second.md5 shouldBe first.md5
      second.size shouldBe first.size
    }

    "commit nothing when the payload stream fails mid-transfer" in {
      val failing: Source[ByteString, NotUsed] =
        Source(List(ByteString("partial")))
          .concat(Source.failed(new RuntimeException("boom")))
          .mapMaterializedValue(_ => NotUsed)
      apollo.writeDerivative(bucket, "atomic", "text/plain", failing).failed.futureValue
      apollo.headExists(bucket, "atomic").futureValue shouldBe None
    }
  }

  "headExists" should {
    "return metadata for a present object" in {
      val payload = "hello".getBytes("UTF-8")
      apollo.writeDerivative(bucket, "h", "text/plain", source(payload)).futureValue
      val meta = apollo.headExists(bucket, "h").futureValue
      meta.map(_.size) shouldBe Some(payload.length.toLong)
      meta.map(_.md5) shouldBe Some(Md5.hex(payload))
    }

    "return None (typed not-found) for an absent object" in {
      apollo.headExists(bucket, "absent").futureValue shouldBe None
    }
  }

  /**
   * In-memory `ObjectApiPowerApi` stub. Stores objects keyed by (bucket, object) with an explicit
   * header md5/size so tests can inject a header that disagrees with the bytes. Only the RPCs the
   * client exercises are implemented; the rest fail UNIMPLEMENTED.
   */
  final private class StubObjectApi extends ObjectApiPowerApi:
    private given scala.concurrent.ExecutionContext = system.executionContext

    final private case class Stored(
        bytes: Array[Byte],
        contentType: String,
        headerMd5: String,
        headerSize: Long,
        generation: Long
    )
    private val store = TrieMap.empty[(String, String), Stored]
    // Keys whose GetObject emits the header + chunks then drops with UNAVAILABLE (simulates an
    // Apollo mid-stream failure). md5/size are honest so the drop, not verification, is under test.
    private val dropMidStream = TrieMap.empty[(String, String), Boolean]

    /** Seed an object whose advertised header md5/size may differ from the actual bytes. */
    def injectRaw(
        bucket: String,
        obj: String,
        bytes: Array[Byte],
        contentType: String,
        headerMd5: String,
        headerSize: Long
    ): Unit =
      store.update((bucket, obj), Stored(bytes, contentType, headerMd5, headerSize, 1L))

    /** Seed an object whose GetObject drops (UNAVAILABLE) after emitting its header and chunks. */
    def injectMidStreamDrop(
        bucket: String,
        obj: String,
        bytes: Array[Byte],
        contentType: String
    ): Unit =
      store.update(
        (bucket, obj),
        Stored(bytes, contentType, Md5.hex(bytes), bytes.length.toLong, 1L)
      )
      dropMidStream.update((bucket, obj), true)

    private def notFound: GrpcServiceException =
      new GrpcServiceException(Status.NOT_FOUND.withDescription("no such object"))

    private def unimplemented: GrpcServiceException =
      new GrpcServiceException(Status.UNIMPLEMENTED)

    private def metadataFor(bucket: String, obj: String, s: Stored): ObjectMetadata =
      ObjectMetadata(
        bucket = bucket,
        `object` = obj,
        contentType = s.contentType,
        size = s.headerSize,
        crc32C = "",
        md5 = s.headerMd5,
        generation = s.generation
      )

    def putObject(
        in: Source[PutObjectRequest, NotUsed],
        metadata: Metadata
    ): Future[PutObjectResponse] =
      // Commit-on-completion: buffer the whole stream; if it fails, nothing is stored (atomic).
      in.runWith(Sink.seq).map { msgs =>
        val header = msgs
          .map(_.payload)
          .collectFirst { case PutObjectRequest.Payload.Header(h) => h }
          .getOrElse(throw new GrpcServiceException(Status.INVALID_ARGUMENT))
        val bytes = msgs
          .map(_.payload)
          .collect { case PutObjectRequest.Payload.Chunk(b) => b.toByteArray }
          .toArray
          .flatten
        val md5 = Md5.hex(bytes)
        val gen = store.get((header.bucket, header.`object`)).map(_.generation + 1L).getOrElse(1L)
        store.update(
          (header.bucket, header.`object`),
          Stored(bytes, header.contentType, md5, bytes.length.toLong, gen)
        )
        PutObjectResponse(generation = gen, crc32C = "", md5 = md5, size = bytes.length.toLong)
      }

    def getObject(in: GetObjectRequest, metadata: Metadata): Source[GetObjectResponse, NotUsed] =
      store.get((in.bucket, in.`object`)) match
        case None => Source.failed(notFound).mapMaterializedValue(_ => NotUsed)
        case Some(s) =>
          val header = GetObjectResponse(
            GetObjectResponse.Payload.Header(metadataFor(in.bucket, in.`object`, s))
          )
          val chunks = s.bytes
            .grouped(8)
            .map(b => GetObjectResponse(GetObjectResponse.Payload.Chunk(ProtoBytes.copyFrom(b))))
            .toList
          val emitted = Source(header :: chunks)
          if dropMidStream.contains((in.bucket, in.`object`)) then
            emitted
              .concat(
                Source
                  .failed(
                    new GrpcServiceException(Status.UNAVAILABLE.withDescription("apollo drop"))
                  )
                  .mapMaterializedValue(_ => NotUsed)
              )
              .mapMaterializedValue(_ => NotUsed)
          else emitted

    def headObject(in: HeadObjectRequest, metadata: Metadata): Future[ObjectMetadata] =
      store.get((in.bucket, in.`object`)) match
        case None => Future.failed(notFound)
        case Some(s) => Future.successful(metadataFor(in.bucket, in.`object`, s))

    def createBucket(in: CreateBucketRequest, metadata: Metadata): Future[BucketResponse] =
      Future.successful(BucketResponse(in.bucket))
    def deleteBucket(in: DeleteBucketRequest, metadata: Metadata): Future[BucketResponse] =
      Future.successful(BucketResponse(in.bucket))
    def deleteObject(in: DeleteObjectRequest, metadata: Metadata): Future[DeleteObjectResponse] =
      Future.failed(unimplemented)
    def listBuckets(in: ListBucketsRequest, metadata: Metadata): Future[ListBucketsResponse] =
      Future.failed(unimplemented)
    def listObjects(in: ListObjectsRequest, metadata: Metadata): Future[ListObjectsResponse] =
      Future.failed(unimplemented)
