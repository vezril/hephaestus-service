package me.cference.hephaestus.metrics

import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}

/**
 * The Prometheus-backed [[MetricsRecorder]] (add-metrics-endpoint). Registers the worker metrics on
 * the app registry so they scrape alongside the JVM/build-info/readiness collectors: a
 * `jobs_processed_total{lane,outcome}` counter, a `job_processing_seconds{lane}` histogram, and a
 * `jobs_inflight` gauge. Label values are drawn only from closed sets (the two lane names and the
 * three outcome values), bounding cardinality.
 */
final class PrometheusMetricsRecorder(registry: CollectorRegistry) extends MetricsRecorder:

  private val processed: Counter = Counter
    .build()
    .name("jobs_processed_total")
    .help("Media jobs processed, by lane and outcome (success/terminal/retriable).")
    .labelNames("lane", "outcome")
    .register(registry)

  private val processingSeconds: Histogram = Histogram
    .build()
    .name("job_processing_seconds")
    .help("Media job processing duration in seconds, by lane.")
    .labelNames("lane")
    .register(registry)

  private val inflight: Gauge = Gauge
    .build()
    .name("jobs_inflight")
    .help("Media jobs currently being processed.")
    .register(registry)

  def recordProcessed(lane: String, outcome: String): Unit =
    processed.labels(lane, outcome).inc()

  def observeSeconds(lane: String, seconds: Double): Unit =
    processingSeconds.labels(lane).observe(seconds)

  def inflightInc(): Unit = inflight.inc()

  def inflightDec(): Unit = inflight.dec()
