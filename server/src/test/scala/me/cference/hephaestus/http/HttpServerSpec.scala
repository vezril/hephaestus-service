package me.cference.hephaestus.http

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Verifies the bind path fails fast (rather than hanging) when the port is already occupied. */
final class HttpServerSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()
  private given system: ActorSystem[?] = testKit.system

  override def afterAll(): Unit = testKit.shutdownTestKit()

  test("bind fails fast when the port is already occupied") {
    // Bind a first Pekko HTTP server, then attempt a second bind to the same port.
    val first = Await.result(HttpServer.bind(complete("ok"), "127.0.0.1", 0), 5.seconds)
    val port = first.localAddress.getPort
    try
      val result = HttpServer.bind(complete("ok"), "127.0.0.1", port)
      val thrown = intercept[Exception](Await.result(result, 5.seconds))
      thrown.getMessage should not be empty
    finally
      val _ = Await.result(first.unbind(), 5.seconds)
  }
