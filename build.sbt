import com.typesafe.sbt.packager.docker.Cmd

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

lazy val pekkoVersion = "1.1.3"
lazy val pekkoHttpVersion = "1.1.0"
lazy val scalaTestVersion = "3.2.19"
lazy val logbackVersion = "1.5.16"

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
      "ch.qos.logback" % "logback-classic" % logbackVersion,
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
    dockerEnvVars := Map("HTTP_PORT" -> "8080"),
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
