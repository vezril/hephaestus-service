package me.cference.hephaestus

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

/**
 * Unit tests for the pure toolchain probe. The command runner is injected as a function so these
 * tests never depend on ffmpeg or libvips actually being installed on the build machine (libvips is
 * not installed here — the suite must still be green).
 */
final class ToolchainProbeSpec extends AnyFunSuite with Matchers:

  private val ffmpegVersionLine = "ffmpeg version 6.1.1 Copyright (c) 2000-2023"
  private val vipsVersionLine = "vips-8.15.1"

  /** A fake runner keyed on the executable name (the head of the command). */
  private def fakeRunner(responses: Map[String, Try[String]]): Seq[String] => Try[String] =
    cmd =>
      responses.getOrElse(
        cmd.headOption.getOrElse(""),
        Failure(new RuntimeException("no such command"))
      )

  test("both tools present ⇒ Ready with both versions") {
    val runner = fakeRunner(
      Map(
        "ffmpeg" -> Success(ffmpegVersionLine),
        "vips" -> Success(vipsVersionLine)
      )
    )
    ToolchainProbe.probe(runner) match
      case ToolchainStatus.Ready(versions) =>
        versions("ffmpeg") should include("6.1.1")
        versions("vips") should include("8.15.1")
      case other => fail(s"expected Ready, got $other")
  }

  test("vips missing ⇒ Degraded naming the missing tool") {
    val runner = fakeRunner(
      Map(
        "ffmpeg" -> Success(ffmpegVersionLine),
        "vips" -> Failure(new RuntimeException("command not found: vips"))
      )
    )
    ToolchainProbe.probe(runner) match
      case ToolchainStatus.Degraded(missing) => missing should contain("vips")
      case other => fail(s"expected Degraded, got $other")
  }

  test("ffmpeg missing ⇒ Degraded naming the missing tool") {
    val runner = fakeRunner(
      Map(
        "ffmpeg" -> Failure(new RuntimeException("command not found: ffmpeg")),
        "vips" -> Success(vipsVersionLine)
      )
    )
    ToolchainProbe.probe(runner) match
      case ToolchainStatus.Degraded(missing) => missing should contain("ffmpeg")
      case other => fail(s"expected Degraded, got $other")
  }

  test("both missing ⇒ Degraded listing both") {
    val runner = fakeRunner(Map.empty)
    ToolchainProbe.probe(runner) match
      case ToolchainStatus.Degraded(missing) => missing should contain allOf ("ffmpeg", "vips")
      case other => fail(s"expected Degraded, got $other")
  }

  test("isReady reflects Ready vs Degraded") {
    ToolchainStatus.Ready(Map("ffmpeg" -> "x", "vips" -> "y")).isReady shouldBe true
    ToolchainStatus.Degraded(Seq("vips")).isReady shouldBe false
  }
