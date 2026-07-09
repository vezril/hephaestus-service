package me.cference.hephaestus.metrics

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit coverage for the base metrics registry: exposition rendering plus the build-info, readiness,
 * and JVM/process collectors (add-metrics-endpoint).
 */
final class MetricsRegistrySpec extends AnyWordSpec with Matchers:

  "MetricsRegistry" should {

    "render exposition text with build-info, readiness, and JVM metrics" in {
      val m = new MetricsRegistry("1.2.3", () => true)
      val out = m.scrape()
      out should include("build_info")
      out should include("""version="1.2.3"""")
      out should include("readiness 1.0")
      out should include("jvm_") // DefaultExports registered
    }

    "reflect readiness changes in the readiness gauge at scrape time" in {
      val ready = new AtomicBoolean(false)
      val m = new MetricsRegistry("v", () => ready.get())
      m.scrape() should include("readiness 0.0")
      ready.set(true)
      m.scrape() should include("readiness 1.0")
    }
  }
