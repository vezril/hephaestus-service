# Change: add-initial-hephaestus

## Why

Hephaestus does not exist yet as code — only the validated design capture (`design-hephaestus`).
This change bootstraps the project from zero to a minimal, production-grade foundation for the
homelab: a versioned GitHub repository with CI/CD, a runnable Pekko service in Docker with a
health endpoint, **an image that bundles the media toolchain (ffmpeg + libvips)**, env-driven
configuration, published Docker artifacts, and complete project documentation. Every subsequent
milestone (Apollo I/O, the media pipeline, HermesMQ job consumption, result reporting) builds on
these foundations, so they must be correct, tested, and operable from day one.

Hephaestus is a **stateless worker** — no aggregate, no journal — so this change intentionally
has no domain-model or event-persistence capability (unlike the event-sourced siblings). It
stands up the runtime shell and the forge's tools; the pipeline itself arrives in later changes.

## What Changes

- **project-scaffolding** (new): GitHub repository layout, sbt multi-module build (`core` = pure
  media logic with zero Pekko deps, `server` = Pekko runtime), Scala 3.3 LTS, scalafmt + scalafix,
  `main`/`development` branching, semantic versioning via git tags (sbt-dynver), GitHub Actions
  pipelines (PR verification, release, dev builds).
- **service-runtime** (new): minimal Pekko typed application (no persistence) with an HTTP
  health/readiness endpoint, graceful startup/shutdown, env-overridable configuration
  (Hermes endpoint + lanes, Apollo endpoint, derivative dimensions, `derivativeSpecVersion`,
  thresholds — plumbed as config now, wired to clients in later changes), and a Docker image built
  via sbt-native-packager that **bundles ffmpeg + libvips** and verifies their presence at startup.
- **release-publishing** (new): CI publishes semver-tagged images to Docker Hub
  (`calvinference/hephaestus`) from `main` releases and `dev`-tagged experimental images from
  `development`.
- **documentation** (new): comprehensive `README.md` (description, CI/CD badges, AI Usage
  Disclaimer describing the SDLC agent team + human review, docker compose deployment example,
  configuration table mapping HOCON keys ↔ env vars, run/test instructions) and MIT `LICENSE`.

Out of scope for this change (future milestones): Apollo gRPC client + md5 verification
(`add-apollo-io`); the media pipeline — libvips/ffmpeg derivatives, phash, ffprobe metadata
(`add-media-pipeline`); HermesMQ two-lane consumption + leasing (`add-job-consumption`);
`MediaProcessed`/`MediaFailed` reporting (`add-result-reporting`). No gRPC/Pekko-gRPC plugin is
wired in this change — it arrives with Apollo I/O.

## Impact

- Affected specs: the four capabilities above are **ADDED** (greenfield — no existing specs are
  modified). Implements the runtime-shell slice of `design-hephaestus` (tasks §0).
- Affected code: new repository. New sbt multi-module build (`core`, `server`),
  `.github/workflows/`, Docker image via sbt-native-packager (temurin base + ffmpeg + libvips),
  `README.md`, `LICENSE`, scalafmt/scalafix configs, `.gitignore`.
- Infrastructure: Docker Hub repository `calvinference/hephaestus` + CI secrets
  (`DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`); GitHub repo `vezril/hephaestus-service` with branch
  protection. These are homelab/external steps gated to the human.
- No PostgreSQL (stateless worker — no journal). No GPU.
