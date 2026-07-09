package me.cference.hephaestus.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for the pure config loader. Env overrides are exercised via Typesafe Config
 * `${?ENV_VAR}` substitutions: we supply a `withFallback` config carrying the "env" values (as if
 * the environment had been set) so no real environment mutation is needed and the loader stays a
 * pure `Config => Either[ConfigError, AppConfig]`.
 */
final class AppConfigSpec extends AnyFunSuite with Matchers:

  /** application.conf as shipped, fully resolved with no overrides. */
  private def defaultConfig = ConfigFactory.load("application")

  test("defaults load and validate") {
    AppConfig.load(defaultConfig) match
      case Right(cfg) =>
        cfg.http.port shouldBe 8080
        cfg.hermes.endpoint should not be empty
        cfg.hermes.ingestLane shouldBe "media.ingest"
        cfg.hermes.reprocessLane shouldBe "media.reprocess"
        cfg.apollo.endpoint should not be empty
        cfg.apollo.mediaBucket shouldBe "media"
        cfg.apollo.host shouldBe "apollostorage"
        cfg.apollo.port shouldBe 8443
        cfg.apollo.deadline.toSeconds shouldBe 30L
        cfg.derivatives.thumbnailPx shouldBe 250
        cfg.derivatives.samplePx shouldBe 850
        cfg.derivatives.specVersion should not be empty
        cfg.thresholds.sampleMinLongEdgePx shouldBe 850
      case Left(err) => fail(s"expected defaults to load, got $err")
  }

  test("environment override wins over HOCON defaults") {
    // Simulate APOLLO_ENDPOINT and DERIVATIVE_SPEC_VERSION being set in the env: the
    // ${?ENV_VAR} substitutions in application.conf pick these up.
    val overridden = ConfigFactory
      .parseString(
        """
          |APOLLO_ENDPOINT = "apollo.prod:9443"
          |DERIVATIVE_SPEC_VERSION = "v7"
          |HTTP_PORT = 9090
          |""".stripMargin
      )
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve()

    AppConfig.load(overridden) match
      case Right(cfg) =>
        cfg.apollo.endpoint shouldBe "apollo.prod:9443"
        cfg.apollo.host shouldBe "apollo.prod"
        cfg.apollo.port shouldBe 9443
        cfg.derivatives.specVersion shouldBe "v7"
        cfg.http.port shouldBe 9090
      case Left(err) => fail(s"expected override to load, got $err")
  }

  test("apollo endpoint host:port splits on the last colon") {
    ApolloConfig.splitHostPort("apollostorage:8443") shouldBe ("apollostorage", 8443)
    ApolloConfig.splitHostPort("apollo.prod:9443") shouldBe ("apollo.prod", 9443)
    // No port ⇒ 0 (client build surfaces the misconfiguration rather than guessing a default).
    ApolloConfig.splitHostPort("apollostorage") shouldBe ("apollostorage", 0)
    ApolloConfig.splitHostPort("host:notaport") shouldBe ("host", 0)
  }

  test("required key nulled out over the defaults fails fast naming the key") {
    // application.conf defaults hermes.endpoint to "hermesmq:8080"; nulling it (as an operator
    // override that clears the value) must fail — a null path reads as absent.
    val missing = ConfigFactory
      .parseString(
        """
          |hephaestus.hermes.endpoint = null
          |""".stripMargin
      )
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve()

    AppConfig.load(missing) match
      case Left(ConfigError(message)) => message should include("hermes.endpoint")
      case Right(cfg) => fail(s"expected failure, got $cfg")
  }

  test("omitting a required key entirely fails fast naming the key") {
    // A bare config (no reference.conf / application.conf merge) that never defines
    // hermes.endpoint must fail the missing-key branch, naming the absent key.
    val bare = ConfigFactory
      .parseString(
        """
          |hephaestus {
          |  http { host = "0.0.0.0", port = 8080 }
          |  hermes { ingest-lane = "media.ingest", reprocess-lane = "media.reprocess" }
          |  apollo { endpoint = "apollostorage:8443" }
          |  derivatives { thumbnail-px = 250, sample-px = 850, spec-version = "v1" }
          |  thresholds { sample-min-long-edge-px = 850 }
          |}
          |""".stripMargin
      )
      .resolve()

    AppConfig.load(bare) match
      case Left(ConfigError(message)) => message should include("hermes.endpoint")
      case Right(cfg) => fail(s"expected failure, got $cfg")
  }

  test("blank required key fails fast (requiredString blank-trim branch)") {
    // hermes.endpoint present but empty ⇒ Left; exercises the blank-after-trim guard.
    val blank = ConfigFactory
      .parseString(
        """
          |hephaestus {
          |  http { host = "0.0.0.0", port = 8080 }
          |  hermes { endpoint = "", ingest-lane = "media.ingest", reprocess-lane = "media.reprocess" }
          |  apollo { endpoint = "apollostorage:8443" }
          |  derivatives { thumbnail-px = 250, sample-px = 850, spec-version = "v1" }
          |  thresholds { sample-min-long-edge-px = 850 }
          |}
          |""".stripMargin
      )
      .resolve()

    AppConfig.load(blank) match
      case Left(ConfigError(message)) => message should include("hermes.endpoint")
      case Right(cfg) => fail(s"expected failure, got $cfg")
  }
