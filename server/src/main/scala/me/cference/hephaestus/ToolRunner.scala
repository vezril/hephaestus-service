package me.cference.hephaestus

import scala.sys.process.*
import scala.util.Try

/**
 * The real command runner that invokes external binaries, wired into `ToolchainProbe.probe` only in
 * Main. Kept in `server` (not `core`) so `core` stays pure and free of process IO; unit tests
 * inject fakes instead of using this.
 */
object ToolRunner:

  /**
   * Run a command, capturing stdout+stderr. Returns `Success(output)` on exit code 0, otherwise a
   * `Failure` (including the case where the executable is absent from PATH).
   */
  val run: Seq[String] => Try[String] = cmd =>
    Try {
      val output = new StringBuilder
      val logger = ProcessLogger { line =>
        val _ = output.append(line).append('\n')
      }
      val exit = Process(cmd).!(logger)
      if exit == 0 then output.toString
      else throw new RuntimeException(s"${cmd.mkString(" ")} exited $exit")
    }
