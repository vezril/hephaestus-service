// Version derived from git tags (project-scaffolding spec) — no version literal in source.
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")

// Packages the service as a runnable app and a Docker image (bundles ffmpeg + libvips).
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")

// Formatting (project-scaffolding spec: CI runs scalafmtCheckAll).
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

// Static analysis / linting (OrganizeImports + DisableSyntax).
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")

// Test coverage (add-static-analysis): CI reports it; add a `coverageMinimumStmtTotal := N`
// gate in build.sbt once the suite is mature — a gate now would red the build on an immature
// suite. Version pinned to the new-scala-service template so the constellation stays uniform.
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.12")

// Build-time version info exposed to the app (health endpoint reports version).
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")
