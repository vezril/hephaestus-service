package me.cference.hephaestus.http

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Route tests for the health surface. Readiness is an injected `() => Boolean` so the test controls
 * UP/DOWN without starting real probes or an actor system beyond the testkit's.
 */
final class HealthRoutesSpec extends AnyFunSuite with Matchers with ScalatestRouteTest:

  private val version = "1.2.3-test"

  test("GET /health returns 200 UP with service + version when ready") {
    val route = HealthRoutes(version, () => true)
    Get("/health") ~> route ~> check {
      status shouldBe StatusCodes.OK
      val body = responseAs[String]
      body should include(""""status":"UP"""")
      body should include(""""service":"hephaestus"""")
      body should include(s""""version":"$version"""")
    }
  }

  test("GET /health returns 503 DOWN when readiness is withdrawn") {
    val route = HealthRoutes(version, () => false)
    Get("/health") ~> route ~> check {
      status shouldBe StatusCodes.ServiceUnavailable
      responseAs[String] should include(""""status":"DOWN"""")
    }
  }

  test("unknown route GET /nope returns 404 and /health still works after") {
    val route = HealthRoutes(version, () => true)
    Get("/nope") ~> route ~> check {
      handled shouldBe false
    }
    // A sealed route yields a concrete 404; verify via seal.
    Get("/nope") ~> org.apache.pekko.http.scaladsl.server.Route.seal(route) ~> check {
      status shouldBe StatusCodes.NotFound
    }
    Get("/health") ~> route ~> check {
      status shouldBe StatusCodes.OK
    }
  }
