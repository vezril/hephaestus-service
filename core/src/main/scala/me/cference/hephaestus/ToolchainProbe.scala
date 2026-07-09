package me.cference.hephaestus

import scala.util.{Success, Try}

/**
 * Outcome of probing the media toolchain. `Ready` carries the detected version string per tool;
 * `Degraded` names the tools that are missing or failed to report a version. Readiness is `UP` only
 * when every required tool is present.
 */
enum ToolchainStatus:
  case Ready(versions: Map[String, String])
  case Degraded(missing: Seq[String])

  /** True only when the full toolchain is present (drives HTTP readiness). */
  def isReady: Boolean = this match
    case _: Ready => true
    case _: Degraded => false

/**
 * Pure probe for the media toolchain (ffmpeg + libvips). The command runner is injected as `run:
 * Seq[String] => Try[String]` so this logic is unit-testable with fakes and does NOT depend on
 * ffmpeg/vips being installed. The real runner (invoking the actual binaries) lives in the `server`
 * module and is wired only in Main.
 */
object ToolchainProbe:

  /** The tools the media pipeline requires, each with the args that print its version. */
  val requiredTools: Seq[(String, Seq[String])] = Seq(
    "ffmpeg" -> Seq("ffmpeg", "-version"),
    "vips" -> Seq("vips", "--version")
  )

  /**
   * Run each required tool's version command via the injected runner. If all succeed, return
   * `Ready` with the first line of each tool's output as its version; otherwise `Degraded` listing
   * the tools that were missing or failed, in declaration order.
   */
  def probe(run: Seq[String] => Try[String]): ToolchainStatus =
    val results: Seq[(String, Try[String])] =
      requiredTools.map((tool, cmd) => tool -> run(cmd))

    val missing = results.collect { case (tool, t) if t.isFailure => tool }

    if missing.nonEmpty then ToolchainStatus.Degraded(missing)
    else
      val versions = results.collect { case (tool, Success(out)) =>
        tool -> firstLine(out)
      }.toMap
      ToolchainStatus.Ready(versions)

  private def firstLine(output: String): String =
    output.linesIterator.nextOption().getOrElse("").trim
