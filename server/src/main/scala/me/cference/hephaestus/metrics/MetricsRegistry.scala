package me.cference.hephaestus.metrics

import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{Collector, CollectorRegistry, Gauge, GaugeMetricFamily}

import java.io.StringWriter

/**
 * Owns the application `CollectorRegistry` and the base metrics Hephaestus always exposes
 * (add-metrics-endpoint): JVM/process metrics (via `DefaultExports`), a `build_info` gauge carrying
 * the deployed version as a label, and a `readiness` gauge that reads the shared readiness flag at
 * scrape time (so it mirrors `/health`). Worker throughput/latency metrics are registered
 * separately by [[PrometheusMetricsRecorder]] onto this same [[registry]].
 */
final class MetricsRegistry(version: String, readiness: () => Boolean):

  val registry: CollectorRegistry = new CollectorRegistry(true)

  // Build-info: a constant 1-valued gauge carrying the version as a label so Grafana can correlate
  // behaviour with a deployed version.
  locally {
    val buildInfo = Gauge
      .build()
      .name("build_info")
      .help("Build information; value is always 1, version carried as a label.")
      .labelNames("version")
      .register(registry)
    buildInfo.labels(version).set(1.0)
  }

  // Readiness: a custom collector so the value always reflects the shared flag at scrape time rather
  // than the last write (consistent with /health flipping to DOWN during shutdown).
  registry.register(
    new Collector:
      def collect(): java.util.List[Collector.MetricFamilySamples] =
        java.util.Collections.singletonList(
          new GaugeMetricFamily(
            "readiness",
            "1 when the service is ready to serve, else 0.",
            if readiness() then 1.0 else 0.0
          )
        )
  )

  DefaultExports.register(registry)

  /** Render the registry in Prometheus text exposition format (version 0.0.4). */
  def scrape(): String =
    val writer = new StringWriter()
    TextFormat.write004(writer, registry.metricFamilySamples())
    writer.toString

/** The content type of the text exposition format returned by [[MetricsRegistry.scrape]]. */
object MetricsRegistry:
  val ContentType: String = TextFormat.CONTENT_TYPE_004
