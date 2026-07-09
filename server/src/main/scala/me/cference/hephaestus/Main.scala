package me.cference.hephaestus

import me.cference.hephaestus.apollo.ApolloClient
import me.cference.hephaestus.build.BuildInfo
import me.cference.hephaestus.config.{AppConfig, ConfigError}
import me.cference.hephaestus.http.{HealthRoutes, HttpServer, MetricsRoutes}
import me.cference.hephaestus.job.{HermesMessageSource, JobCodec, JobConsumer}
import me.cference.hephaestus.media.{
  DerivativeSpec,
  MediaPipeline,
  ProcessCommandRunner,
  RealMediaTools
}
import me.cference.hephaestus.metrics.{MetricsRecorder, MetricsRegistry, PrometheusMetricsRecorder}
import me.cference.hephaestus.report.{HermesResultPublisher, HermesResultSink}
import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.client.HermesClient
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, DispatcherSelector}
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
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

    // Metrics (add-metrics-endpoint). When enabled, an app registry feeds the /metrics endpoint and
    // a Prometheus recorder instruments the consumer; when disabled, no route is installed and the
    // consumer records through the no-op recorder (zero overhead). The readiness gauge reads the
    // shared flag at scrape time, so it mirrors /health.
    val metricsRegistry: Option[MetricsRegistry] =
      if cfg.metrics.enabled then
        Some(new MetricsRegistry(BuildInfo.version, () => readiness.get()))
      else None
    val recorder: MetricsRecorder =
      metricsRegistry.fold(MetricsRecorder.NoOp)(m => new PrometheusMetricsRecorder(m.registry))
    log.info(
      "Metrics {} (GET /metrics on the HTTP port)",
      if cfg.metrics.enabled then "ENABLED" else "DISABLED"
    )

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

    // The job consumer runs the CPU-heavy media pipeline on a DEDICATED dispatcher so a burst of
    // transcodes never starves the HTTP health endpoint or the pull loop. It is started only when
    // the toolchain is present (a degraded toolchain would fail every job terminally); a Hermes
    // outage, by contrast, is a retriable pull failure, so consumer wiring never gates readiness.
    if toolchainReady then wireConsumer(cfg, apollo, recorder)
    else log.warn("Media toolchain degraded — job consumer NOT started (readiness stays DOWN)")

    // Health is always served; /metrics is concatenated only when metrics are enabled (else it
    // falls through to a sealed 404).
    val healthRoutes = HealthRoutes(BuildInfo.version, () => readiness.get())
    val routes: Route =
      metricsRegistry.map(MetricsRoutes.apply).toList.foldLeft(healthRoutes)(_ ~ _)

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

  /**
   * Wire and start the two-lane job consumer: the HermesMQ pull/ack source, the §2 media pipeline
   * on a dedicated blocking dispatcher, the §4 result-publisher seam (logging default), and the
   * decode → process → publish → ack loop. Graceful drain (stop pulling, finish + ack in-flight)
   * runs in Coordinated Shutdown's `service-requests-done` phase — before the Apollo client is
   * closed, so in-flight jobs can finish their durable writes.
   */
  private def wireConsumer(cfg: AppConfig, apollo: ApolloClient, recorder: MetricsRecorder)(using
      system: ActorSystem[Nothing]
  ): Unit =
    // Dedicated dispatcher for the CPU-heavy shell-outs (config: hephaestus-media-dispatcher).
    given mediaEc: ExecutionContext =
      system.dispatchers.lookup(DispatcherSelector.fromConfig("hephaestus-media-dispatcher"))

    val spec = DerivativeSpec(
      thumbnailPx = cfg.derivatives.thumbnailPx,
      samplePx = cfg.derivatives.samplePx,
      sampleMinLongEdgePx = cfg.thresholds.sampleMinLongEdgePx,
      specVersion = cfg.derivatives.specVersion
    )
    val tools = new RealMediaTools(ProcessCommandRunner.run)(using mediaEc)
    val pipeline = MediaPipeline(apollo, tools)(using system)

    val client = new HermesClient(hermesBaseUri(cfg.hermes.endpoint))(using system)
    // The real §4 result publisher: map the outcome → MediaProcessed/MediaFailed and publish to the
    // configured result topics via the same HermesClient (a transient publish failure leaves the
    // job unacked for redelivery, so readiness is unaffected).
    val publisher = new HermesResultPublisher(
      sink = new HermesResultSink(client)(using mediaEc),
      processedTopic = cfg.hermes.processedTopic,
      failedTopic = cfg.hermes.failedTopic,
      mediaBucket = cfg.apollo.mediaBucket
    )
    HermesMessageSource(client, cfg.hermes.ingestLane, cfg.hermes.reprocessLane)(using
      mediaEc
    ) match
      case Left(err) =>
        log.error("Invalid Hermes lane configuration — {}. Job consumer NOT started", err)
      case Right(source) =>
        val consumer = new JobConsumer(
          source = source,
          pipeline = pipeline,
          publisher = publisher,
          decode = JobCodec.decode(_, spec),
          settings = JobConsumer.Settings(
            batchSize = cfg.consumer.batchSize,
            concurrency = cfg.consumer.concurrency,
            pollInterval = cfg.consumer.pollInterval
          ),
          recorder = recorder
        )(using system, mediaEc)
        consumer.start()
        log.info(
          "Job consumer wired — Hermes {} (lanes {} → {}; results → {}/{})",
          cfg.hermes.endpoint,
          cfg.hermes.ingestLane,
          cfg.hermes.reprocessLane,
          cfg.hermes.processedTopic,
          cfg.hermes.failedTopic
        )
        CoordinatedShutdown(system).addTask(
          CoordinatedShutdown.PhaseServiceRequestsDone,
          "drain-job-consumer"
        ) { () =>
          import system.executionContext
          consumer.drain().map(_ => org.apache.pekko.Done)
        }

  /** HermesClient needs a scheme; the config carries a bare `host:port` (like Apollo's). */
  private def hermesBaseUri(endpoint: String): String =
    if endpoint.contains("://") then endpoint else s"http://$endpoint"
