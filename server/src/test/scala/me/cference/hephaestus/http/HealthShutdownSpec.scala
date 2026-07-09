package me.cference.hephaestus.http

import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Proves the "health during shutdown ⇒ DOWN" scenario end to end: the `PhaseBeforeServiceUnbind`
 * Coordinated Shutdown task registered by `HttpServer.wireShutdown` actually flips the readiness
 * `AtomicBoolean` that `HealthRoutes` reads, so `/health` would report DOWN before the port
 * unbinds.
 *
 * Runs full Coordinated Shutdown (which terminates the actor system), so it lives in its own spec
 * with its own testkit to avoid disturbing other suites.
 */
final class HealthShutdownSpec extends AnyFunSuite with Matchers:

  test("coordinated shutdown withdraws readiness before unbind") {
    val testKit = ActorTestKit()
    given system: ActorSystem[?] = testKit.system
    try
      val readiness = new AtomicBoolean(true)
      val routes = HealthRoutes("test", () => readiness.get())
      val binding = Await.result(HttpServer.bind(routes, "127.0.0.1", 0), 5.seconds)
      HttpServer.wireShutdown(binding, readiness)

      // Readiness is UP before shutdown begins.
      readiness.get() shouldBe true

      // Run Coordinated Shutdown to completion. The withdraw-readiness task registered by
      // wireShutdown in PhaseBeforeServiceUnbind flips the flag before the port unbinds.
      Await.result(
        CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason),
        10.seconds
      )

      // The CS task flipped the AtomicBoolean the route reads ⇒ /health would now answer 503 DOWN.
      readiness.get() shouldBe false
    finally testKit.shutdownTestKit()
  }
