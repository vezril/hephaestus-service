package me.cference.hephaestus.e2e

import com.dimafeng.testcontainers.GenericContainer
import org.slf4j.LoggerFactory
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait

import java.time.Duration

/**
 * The end-to-end container topology (add-e2e-integration): the published Apollo + Hermes images,
 * each with its own `postgres:16-alpine`, on ONE shared Docker network. Both services boot as a
 * single-node Pekko cluster:
 *
 *   - **Apollo** self-forms a cluster of one via management bootstrap. Its `cluster.conf` already
 *     defaults `min-nr-of-members=1`, `canonical.hostname=127.0.0.1`, and a config contact-point at
 *     `127.0.0.1:8558` — so leaving `CLUSTER_HOST`/`CONTACT_POINT_HOST` UNSET makes a lone
 *     container bootstrap standalone. We only pin `CLUSTER_MIN_MEMBERS=1` explicitly for clarity.
 *     Readiness is gated on `GET /health` returning `"UP"` (cluster up + schema self-migrated) with
 *     a GENEROUS timeout, because Pekko bootstrap can be slow to converge in a cold runner.
 *   - **Hermes** self-seeds a one-node cluster at `127.0.0.1:25520` (its `application.conf`
 *     default) and self-migrates its schema on start (`migrate-on-start=true`), so no schema mount
 *     is needed. Readiness is gated on `GET /health/ready` == 200 (cluster formed + delivery
 *     projection live).
 *
 * The worker (this repo's real code) runs IN-PROCESS in the test, pointed at the mapped host ports.
 */
final class ConstellationContainers:

  private val log = LoggerFactory.getLogger(classOf[ConstellationContainers])

  // A GENEROUS startup budget — Apollo's cluster bootstrap + schema migration is the slow part.
  private val StartupTimeout = Duration.ofMinutes(5)

  private val network: Network = Network.newNetwork()

  // --- Apollo + its Postgres -------------------------------------------------

  private val apolloPg: GenericContainer = GenericContainer(
    dockerImage = "postgres:16-alpine",
    env = Map(
      "POSTGRES_DB" -> "apollostorage",
      "POSTGRES_USER" -> "apollostorage",
      "POSTGRES_PASSWORD" -> "apollostorage"
    ),
    waitStrategy = Wait
      .forLogMessage(".*database system is ready to accept connections.*", 2)
      .withStartupTimeout(Duration.ofMinutes(2))
  ).configure { c =>
    c.withNetwork(network)
    c.withNetworkAliases("apollo-pg")
    ()
  }

  private val apollo: GenericContainer = GenericContainer(
    dockerImage = "calvinference/apollostorage:latest",
    exposedPorts = Seq(8080, 8443),
    env = Map(
      "HTTP_PORT" -> "8080",
      "GRPC_PORT" -> "8443",
      "POSTGRES_HOST" -> "apollo-pg",
      "POSTGRES_PORT" -> "5432",
      "POSTGRES_DB" -> "apollostorage",
      "POSTGRES_USER" -> "apollostorage",
      "POSTGRES_PASSWORD" -> "apollostorage",
      // Single-node: bootstrap a cluster of one. Host/contact-point stay at the image default
      // (127.0.0.1) so the lone node discovers only itself.
      "CLUSTER_MIN_MEMBERS" -> "1"
    ),
    // Gate readiness on the HTTP health endpoint reporting UP (cluster up + schema migrated).
    waitStrategy = Wait
      .forHttp("/health")
      .forPort(8080)
      .forResponsePredicate((body: String) => body.contains("UP"))
      .withStartupTimeout(StartupTimeout)
  ).configure { c =>
    c.withNetwork(network)
    c.withNetworkAliases("apollostorage")
    c.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("e2e.apollo")))
    ()
  }

  // --- Hermes + its Postgres -------------------------------------------------

  private val hermesPg: GenericContainer = GenericContainer(
    dockerImage = "postgres:16-alpine",
    env = Map(
      "POSTGRES_DB" -> "hermesmq",
      "POSTGRES_USER" -> "hermes",
      "POSTGRES_PASSWORD" -> "hermes"
    ),
    waitStrategy = Wait
      .forLogMessage(".*database system is ready to accept connections.*", 2)
      .withStartupTimeout(Duration.ofMinutes(2))
  ).configure { c =>
    c.withNetwork(network)
    c.withNetworkAliases("hermes-pg")
    ()
  }

  private val hermes: GenericContainer = GenericContainer(
    dockerImage = "calvinference/hermesmq:latest",
    exposedPorts = Seq(8080),
    env = Map(
      "HERMESMQ_HTTP_PORT" -> "8080",
      "HERMESMQ_DB_HOST" -> "hermes-pg",
      "HERMESMQ_DB_PORT" -> "5432",
      "HERMESMQ_DB_NAME" -> "hermesmq",
      "HERMESMQ_DB_USER" -> "hermes",
      "HERMESMQ_DB_PASSWORD" -> "hermes"
      // Cluster: image default self-seeds a one-node cluster at 127.0.0.1:25520; schema is
      // self-migrated (migrate-on-start=true) — no seeds/host/schema overrides needed.
    ),
    // /health/ready flips 200 only once the cluster is formed and the delivery projection is live.
    waitStrategy = Wait
      .forHttp("/health/ready")
      .forPort(8080)
      .forStatusCode(200)
      .withStartupTimeout(StartupTimeout)
  ).configure { c =>
    c.withNetwork(network)
    c.withNetworkAliases("hermesmq")
    c.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("e2e.hermes")))
    ()
  }

  /** Start the databases first (blocking on readiness), then the services that depend on them. */
  def start(): Unit =
    log.info("Starting Apollo + Hermes Postgres containers ...")
    apolloPg.start()
    hermesPg.start()
    log.info("Starting ApolloStorage (single-node cluster boot may take a while) ...")
    apollo.start()
    log.info("Starting HermesMQ ...")
    hermes.start()
    log.info(
      "Constellation up — Apollo gRPC {}, Hermes HTTP {}",
      apolloGrpcEndpoint,
      hermesHttpEndpoint
    )

  /** Stop everything; never throws (best-effort teardown). */
  def stop(): Unit =
    Seq(hermes, hermesPg, apollo, apolloPg).foreach { c =>
      try c.stop()
      catch case scala.util.control.NonFatal(_) => ()
    }
    try network.close()
    catch case scala.util.control.NonFatal(_) => ()

  /** `host:port` for Apollo's mapped gRPC (h2c) port — feeds `ApolloConfig`. */
  def apolloGrpcEndpoint: String = s"${apollo.host}:${apollo.mappedPort(8443)}"

  /** `http://host:port` for Hermes' mapped REST port — feeds `HermesClient`. */
  def hermesHttpEndpoint: String = s"http://${hermes.host}:${hermes.mappedPort(8080)}"
