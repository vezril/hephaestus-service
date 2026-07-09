package me.cference.hephaestus.job

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Unit tests for the pure lane-priority rule: ingest is preferred until it drains. */
final class LanePrioritySpec extends AnyWordSpec with Matchers:

  "LanePriority.next" should {
    "pull ingest while ingest is not drained" in {
      LanePriority.next(ingestDrained = false) shouldBe Lane.Ingest
    }

    "pull reprocess only once ingest has drained" in {
      LanePriority.next(ingestDrained = true) shouldBe Lane.Reprocess
    }
  }
