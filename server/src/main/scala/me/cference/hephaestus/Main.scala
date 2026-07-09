package me.cference.hephaestus

import me.cference.hephaestus.apollo.ApolloClient
import me.cference.hephaestus.build.BuildInfo
import me.cference.hephaestus.config.{AppConfig, ConfigError}
import me.cference.hephaestus.http.{HealthRoutes, HttpServer}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{Failure, Success}

/**
 * Entry point. Loads + validates configuration (fail fast on a missing key), probes the media
 * toolchain (ffmpeg + libvips) to set readiness, binds the HTTP health endpoint, and wires Pekko
 * Coordinated Shutdown (withdraw readiness → unbind → drain → terminate, exit 0). A bind failure
 * (e.g. occupied port) logs clearly and exits non-zero.
 */
object Main:
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val raw = ConfigFactory.load()
    AppConfig.load(raw) match
      case Left(ConfigError(message)) =>
        log.error(s"Invalid configuration — $message")
        System.exit(1)
      case Right(cfg) =>
        run(cfg, raw)

  private def run(cfg: AppConfig, raw: com.typesafe.config.Config): Unit =
    given system: ActorSystem[Nothing] =
      ActorSystem[Nothing](Behaviors.empty[Nothing], "hephaestus", raw)
    import system.executionContext

    // Readiness starts DOWN and flips UP only once the toolchain probe passes.
    val readiness = new AtomicBoolean(false)

    val toolchain = ToolchainProbe.probe(ToolRunner.run)
    toolchain match
      case ToolchainStatus.Ready(versions) =>
        log.info(
          "Media toolchain ready — {}",
          versions.map((tool, v) => s"$tool: $v").mkString(", ")
        )
      case ToolchainStatus.Degraded(missing) =>
        log.error(
          "Media toolchain DEGRADED — missing/unusable: {}. Readiness will remain DOWN.",
          missing.mkString(", ")
        )
    val toolchainReady = toolchain.isReady

    // Apollo object-store gRPC client (originals in, derivatives out). Constructed lazily on the
    // shared channel; released on shutdown. Reachability is not gated into readiness here — a
    // transient Apollo outage is a retriable per-job failure, not a service-down condition.
    val apollo = ApolloClient.fromConfig(cfg.apollo)
    log.info("Apollo object-store client wired — endpoint {}", cfg.apollo.endpoint)
    CoordinatedShutdown(system).addTask(
      CoordinatedShutdown.PhaseServiceStop,
      "close-apollo-client"
    ) { () =>
      apollo.close()
    }

    val routes = HealthRoutes(BuildInfo.version, () => readiness.get())

    HttpServer.bind(routes, cfg.http.host, cfg.http.port).onComplete {
      case Success(binding: ServerBinding) =>
        HttpServer.wireShutdown(binding, readiness)
        readiness.set(toolchainReady)
        log.info(
          "Hephaestus {} bound HTTP :{} — readiness {}",
          BuildInfo.version,
          Integer.valueOf(binding.localAddress.getPort),
          if toolchainReady then "UP" else "DOWN (toolchain missing)"
        )
      case Failure(ex) =>
        log.error(
          s"Failed to bind HTTP ${cfg.http.host}:${cfg.http.port} — ${ex.getMessage}",
          ex
        )
        system.terminate()
        System.exit(1)
    }
