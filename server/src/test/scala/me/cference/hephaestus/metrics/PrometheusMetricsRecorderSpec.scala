package me.cference.hephaestus.metrics

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit coverage for the Prometheus-backed recorder (add-metrics-endpoint): a recorded job is
 * reflected in the registry's scrape text — the counter by lane+outcome, the duration histogram,
 * and the in-flight gauge.
 */
final class PrometheusMetricsRecorderSpec extends AnyWordSpec with Matchers:

  "PrometheusMetricsRecorder" should {

    "reflect a recorded job in the registry's scrape text" in {
      val m = new MetricsRegistry("v", () => true)
      val rec = new PrometheusMetricsRecorder(m.registry)

      rec.recordProcessed("ingest", "success")
      rec.recordProcessed("ingest", "success")
      rec.recordProcessed("reprocess", "terminal")
      rec.observeSeconds("ingest", 0.05)
      rec.inflightInc()

      val out = m.scrape()
      out should include("""jobs_processed_total{lane="ingest",outcome="success",} 2.0""")
      out should include("""jobs_processed_total{lane="reprocess",outcome="terminal",} 1.0""")
      out should include("job_processing_seconds")
      out should include("""job_processing_seconds_count{lane="ingest",} 1.0""")
      out should include("jobs_inflight 1.0")
    }

    "distinguish terminal from retriable outcomes" in {
      val m = new MetricsRegistry("v", () => true)
      val rec = new PrometheusMetricsRecorder(m.registry)
      rec.recordProcessed("ingest", "terminal")
      rec.recordProcessed("ingest", "retriable")
      val out = m.scrape()
      out should include("""jobs_processed_total{lane="ingest",outcome="terminal",} 1.0""")
      out should include("""jobs_processed_total{lane="ingest",outcome="retriable",} 1.0""")
    }

    "use media-worker duration buckets spanning tens of seconds to minutes" in {
      // The histogram must NOT use the Prometheus default buckets (top 10s): a 90s transcode has to
      // land in a real bucket, not only +Inf, or p95/p99 above 10s are unrecoverable.
      val m = new MetricsRegistry("v", () => true)
      val rec = new PrometheusMetricsRecorder(m.registry)
      rec.observeSeconds("reprocess", 90.0) // a slow-but-typical transcode
      val out = m.scrape()
      // Explicit high buckets are present …
      out should include("""job_processing_seconds_bucket{lane="reprocess",le="30.0",} 0.0""")
      out should include("""job_processing_seconds_bucket{lane="reprocess",le="120.0",} 1.0""")
      out should include("""job_processing_seconds_bucket{lane="reprocess",le="300.0",} 1.0""")
      // … and a 90s observation is captured below +Inf (i.e. not lost to the top bucket).
      out should include("""job_processing_seconds_bucket{lane="reprocess",le="60.0",} 0.0""")
    }
  }
