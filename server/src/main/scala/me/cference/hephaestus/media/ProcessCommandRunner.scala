package me.cference.hephaestus.media

import scala.sys.process.*

/**
 * The real [[CommandRunner]]: execs an argv directly (no shell — the argv from `core`'s `ToolArgs`
 * is passed straight to `ProcessBuilder`, so there is no quoting/injection surface) and captures
 * stdout and stderr separately. Lives in `server`, wired only where the pipeline is constructed;
 * unit tests inject a fake instead.
 */
object ProcessCommandRunner:

  val run: CommandRunner = argv =>
    val out = new StringBuilder
    val err = new StringBuilder
    val logger = ProcessLogger(
      line => { out.append(line).append('\n'); () },
      line => { err.append(line).append('\n'); () }
    )
    // Process(Seq) execs the argv directly (no shell interpretation of the elements).
    val exit = Process(argv).!(logger)
    CommandResult(exit, out.toString, err.toString)
