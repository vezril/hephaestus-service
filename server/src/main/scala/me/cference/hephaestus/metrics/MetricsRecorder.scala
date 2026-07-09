package me.cference.hephaestus.metrics

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The worker-metrics seam (add-metrics-endpoint). The `JobConsumer` records each job's outcome,
 * duration, and in-flight count through this trait so it stays free of any Prometheus dependency
 * and its loop tests stay pure. The default [[MetricsRecorder.NoOp]] does nothing (used when
 * metrics are disabled, and in tests that don't care); [[PrometheusMetricsRecorder]] is the real
 * implementation.
 */
trait MetricsRecorder:
  /** Count one completed job by lane and outcome (`success` / `terminal` / `retriable`). */
  def recordProcessed(lane: String, outcome: String): Unit

  /** Observe one job's processing duration (seconds) on a lane. */
  def observeSeconds(lane: String, seconds: Double): Unit

  /** In-flight gauge: a job started processing. */
  def inflightInc(): Unit

  /** In-flight gauge: a job finished processing. */
  def inflightDec(): Unit

  /**
   * Time a job on `lane`: mark it in-flight, run `f`, and — whether it succeeds or fails — observe
   * its duration and clear the in-flight mark. A synchronous throw from `f` becomes a failed
   * `Future` so the in-flight gauge is never left stuck.
   */
  final def time[A](lane: String)(f: => Future[A])(using ec: ExecutionContext): Future[A] =
    inflightInc()
    val start = System.nanoTime()
    val fut =
      try f
      catch { case NonFatal(e) => Future.failed(e) }
    fut.andThen { case _ =>
      // Clear the in-flight mark FIRST: were observeSeconds ever to throw, a dec-after-observe order
      // would leak the gauge. Decoupling them keeps jobs_inflight correct unconditionally.
      inflightDec()
      observeSeconds(lane, (System.nanoTime() - start).toDouble / 1.0e9)
    }

object MetricsRecorder:
  /** A recorder that records nothing — zero overhead when metrics are disabled. */
  object NoOp extends MetricsRecorder:
    def recordProcessed(lane: String, outcome: String): Unit = ()
    def observeSeconds(lane: String, seconds: Double): Unit = ()
    def inflightInc(): Unit = ()
    def inflightDec(): Unit = ()
