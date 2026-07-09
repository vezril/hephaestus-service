import com.typesafe.sbt.packager.docker.Cmd

import java.nio.file.Files

// ---------------------------------------------------------------------------
// Hephaestus — the forge. Stateless media-worker service of the constellation.
//
//   core   — pure media/toolchain logic (ZERO Pekko deps), exhaustively unit-tested.
//   server — Pekko runtime + Main + Docker image (bundles ffmpeg + libvips).
//
// Version is derived from git tags by sbt-dynver (project/plugins.sbt); no
// version literal is committed. The dynver separator is Docker-tag-safe ('-').
// ---------------------------------------------------------------------------

ThisBuild / organization := "me.cference.hephaestus"
ThisBuild / scalaVersion := "3.3.4" // Scala 3 LTS

ThisBuild / homepage := Some(url("https://github.com/vezril/hephaestus-service"))
ThisBuild / licenses := Seq(
  "MIT" -> url("https://github.com/vezril/hephaestus-service/blob/main/LICENSE")
)
ThisBuild / startYear := Some(2026)
ThisBuild / developers := List(
  Developer(
    id = "vezril",
    name = "Calvin Ference",
    email = "calvin.ference@proton.me",
    url = url("https://github.com/vezril")
  )
)

// sbt-dynver: no version literal committed. Use a Docker-tag-safe separator
// (git describe's default '+' is illegal in image tags).
ThisBuild / dynverSeparator := "-"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Wunused:all"
)

// Converged on Pekko 1.2.0: the Lexicon gRPC stubs (and Apollo) are built there,
// and Pekko forbids a mixed-version classpath (add-apollo-io / hephaestus-pekko-convergence).
lazy val pekkoVersion = "1.2.0"
lazy val pekkoHttpVersion = "1.2.0"
// pekko-grpc runtime that the generated Lexicon client was produced against (plugin 1.1.1).
lazy val pekkoGrpcVersion = "1.1.1"
lazy val scalaTestVersion = "3.2.19"
lazy val logbackVersion = "1.5.16"
lazy val logstashEncoderVersion = "8.0"
// Prometheus simpleclient (add-metrics-endpoint): the app CollectorRegistry, JVM/process
// collectors (hotspot), and the text exposition renderer. Pinned to match apollo-storage so the
// constellation shares one metrics stack. All three artifacts are public (no read:packages token).
lazy val prometheusVersion = "0.16.0"

// The Apollo gRPC contract is consumed as the published Lexicon stubs, not a local .proto
// (add-apollo-io). GitHub Packages requires auth even for public reads: use a read:packages
// token from LEXICON_TOKEN, else GITHUB_TOKEN, else an sbt ~/.sbt/.credentials file if present.
ThisBuild / resolvers += "GitHub Packages — the-lexicon".at(
  "https://maven.pkg.github.com/vezril/the-lexicon"
)
// The HermesMQ Scala client (+ its domain value types) is published to a SEPARATE GitHub Packages
// repo (add-job-consumption). Same host (maven.pkg.github.com) as the-lexicon, so the credential
// block below authenticates both — the read:packages token must grant BOTH packages.
ThisBuild / resolvers += "GitHub Packages — hermesmq".at(
  "https://maven.pkg.github.com/vezril/hermesmq"
)
// Add the env-token credential ONLY when a token is actually present. A blank-password Credentials
// entry would still match host maven.pkg.github.com and, since Credentials.forHost is first-wins,
// shadow the ~/.sbt/.credentials fallback below — silently 401ing local dev. (CI always sets
// LEXICON_TOKEN, so the env cred is still index 0 there.)
ThisBuild / credentials ++=
  sys.env
    .get("LEXICON_TOKEN")
    .filter(_.nonEmpty)
    .orElse(sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty))
    .map(tok => Credentials("GitHub Package Registry", "maven.pkg.github.com", "vezril", tok))
    .toSeq
ThisBuild / credentials ++= {
  val dotCredentials = Path.userHome / ".sbt" / ".credentials"
  if (Files.exists(dotCredentials.toPath)) Seq(Credentials(dotCredentials)) else Seq.empty
}

// --- root: aggregate only, not published -------------------------------------
lazy val root = (project in file("."))
  .aggregate(core, server)
  .settings(
    name := "hephaestus",
    publish / skip := true
  )

// --- core: pure media/toolchain logic, no Pekko. -----------------------------
lazy val core = (project in file("core"))
  .settings(
    name := "hephaestus-core",
    libraryDependencies += "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  )

// --- server: Pekko runtime + Main + Docker image. ----------------------------
lazy val server = (project in file("server"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(
    name := "hephaestus-server",
    Compile / mainClass := Some("me.cference.hephaestus.Main"),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      // Apollo object-store gRPC stubs (ObjectApi client + messages), generated once in the
      // Lexicon from the shared contract (add-apollo-io). Pinned SemVer; no local .proto.
      // One Lexicon version across the service: the async message contracts (below) ship at
      // 0.4.0, so the gRPC pin is bumped to match (add-job-consumption).
      "io.codex" %% "lexicon-grpc" % "0.4.0",
      // The async HermesMQ message contracts (ProcessMediaJob et al.) as ScalaPB case classes +
      // scalapb-json4s canonical-JSON codec — the wire format Hephaestus decodes (add-job-consumption).
      "io.codex" %% "lexicon-messages" % "0.4.0",
      // The HermesMQ pull/ack REST client (+ transitive hermesmq-domain identifiers). Built against
      // Pekko 1.2.0, matching the service's converged version.
      "me.cference.hermesmq" %% "hermesmq-client" % "1.4.0",
      // gRPC runtime the generated client needs (GrpcClientSettings, ServiceHandler). We consume
      // pre-generated stubs, so the pekko-grpc sbt PLUGIN is not needed — only its runtime.
      "org.apache.pekko" %% "pekko-grpc-runtime" % pekkoGrpcVersion,
      // GrpcClientSettings resolves endpoints via pekko-discovery; align it with pekkoVersion
      // (it is otherwise pulled transitively at a possibly-mismatched version).
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // Structured JSON log encoding (LogstashEncoder) for the constellation observability stack
      // (add-structured-logging). Selected at runtime by LOG_FORMAT (see server logback.xml).
      "net.logstash.logback" % "logstash-logback-encoder" % logstashEncoderVersion,
      // Prometheus metrics (add-metrics-endpoint): app CollectorRegistry + JVM collectors + text
      // exposition. Same trio (and version) as apollo-storage so the constellation is uniform.
      "io.prometheus" % "simpleclient" % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
      "io.prometheus" % "simpleclient_common" % prometheusVersion,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test
    ),
    // BuildInfo exposes the dynver version to the running app (health endpoint).
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "me.cference.hephaestus.build",
    buildInfoOptions += BuildInfoOption.ToJson,
    // --- Docker image (docker.io/calvinference/hephaestus) — service-runtime spec ---
    dockerBaseImage := "eclipse-temurin:21-jre",
    dockerExposedPorts := Seq(8080),
    dockerUpdateLatest := false, // release workflow controls :latest explicitly
    Docker / packageName := "hephaestus",
    // Image namespace. CI provides DOCKERHUB_USERNAME (single source of truth,
    // matching the workflows); DOCKER_USERNAME is honored for local overrides,
    // then a sensible default so the image builds standalone.
    dockerUsername := Some(
      sys.env
        .get("DOCKERHUB_USERNAME")
        .orElse(sys.env.get("DOCKER_USERNAME"))
        .getOrElse("calvinference")
    ),
    Docker / version := version.value.replace('+', '-'),
    // Shipped image logs structured JSON (LOG_FORMAT=json); local `sbt run` (no override) stays
    // human-readable text — see server/src/main/resources/logback.xml (add-structured-logging).
    dockerEnvVars := Map("HTTP_PORT" -> "8080", "LOG_FORMAT" -> "json"),
    // Non-root daemon user (design: process must not run as root).
    Docker / daemonUserUid := Some("1001"),
    Docker / daemonUser := "hephaestus",
    // Install the media toolchain (ffmpeg + libvips) as root, BEFORE the USER
    // switch, so the shipped image has ffmpeg/ffprobe/vips on PATH.
    dockerCommands := {
      val cmds = dockerCommands.value
      val installTools = Cmd(
        "RUN",
        "apt-get update && apt-get install -y --no-install-recommends ffmpeg libvips-tools " +
          "&& rm -rf /var/lib/apt/lists/*"
      )
      val userIdx = cmds.lastIndexWhere {
        case Cmd("USER", _*) => true
        case _ => false
      }
      if (userIdx >= 0) cmds.patch(userIdx, Seq(installTools), 0) else cmds :+ installTools
    },
    // HEALTHCHECK uses bash's /dev/tcp so no extra packages (wget/curl) are
    // needed. Exec form keeps the whole script as a single arg to `bash -c`;
    // bash expands the HTTP_PORT override at runtime.
    dockerCommands ++= Seq(
      Cmd(
        "HEALTHCHECK",
        "--interval=10s --timeout=3s --start-period=30s --retries=5 CMD " +
          """["bash","-c","exec 3<>/dev/tcp/127.0.0.1/${HTTP_PORT:-8080}; """ +
          """printf 'GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3; """ +
          """grep -q '200 OK' <&3"]"""
      )
    )
  )
