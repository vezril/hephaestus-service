# Hephaestus

[![CI](https://github.com/vezril/hephaestus-service/actions/workflows/ci.yml/badge.svg)](https://github.com/vezril/hephaestus-service/actions/workflows/ci.yml)
[![Dev publish](https://github.com/vezril/hephaestus-service/actions/workflows/dev.yml/badge.svg)](https://github.com/vezril/hephaestus-service/actions/workflows/dev.yml)
[![Release](https://github.com/vezril/hephaestus-service/actions/workflows/release.yml/badge.svg)](https://github.com/vezril/hephaestus-service/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**The forge.** Hephaestus is the stateless media-worker service of the homelab "constellation".
It pulls `ProcessMediaJob`s from HermesMQ, reads originals from Apollo, produces derivatives
(thumbnails, samples, transcodes) with **ffmpeg** and **libvips**, writes them back to Apollo as
content-addressed blobs, and reports `MediaProcessed` / `MediaFailed`.

Hephaestus holds **no durable state** — no aggregate, no journal. Durability is HermesMQ's
(redelivery); idempotency is content-addressing's (a redelivered job re-produces identical bytes).
It is a pure function `job → derivatives + result`, made reliable by the queue plus
content-addressing.

> **Milestone status.** This repository currently implements milestone §0 — the runtime shell:
> a Pekko HTTP service with a `/health` endpoint, env-driven configuration, a media-toolchain
> startup probe, and a Docker image that bundles ffmpeg + libvips. Apollo I/O, the media pipeline,
> HermesMQ consumption, and result reporting arrive in later changes.

## Architecture

Two sbt modules:

| Module   | Purpose                                                              | Pekko? |
| -------- | ------------------------------------------------------------------- | ------ |
| `core`   | Pure media/toolchain logic (toolchain probe), exhaustively tested   | No     |
| `server` | Pekko runtime, HTTP health endpoint, config, `Main`, Docker image   | Yes    |

`core` has **zero Pekko dependencies** by design — it is unit-testable in isolation and the
command runner behind the toolchain probe is injected so tests never require ffmpeg/vips to be
installed.

## Build, test, run

Requires JDK 21+ and sbt 1.10.7 (see `project/build.properties`).

```bash
# Check formatting, compile, and run the full test suite.
sbt scalafmtCheckAll compile test

# Run the service locally (binds :8080 by default). ffmpeg + libvips are
# probed at startup; if either is missing, /health reports 503 DOWN.
sbt server/run

# Build the Docker image locally (bundles ffmpeg + libvips).
sbt server/Docker/publishLocal
```

The build version is derived from git tags via sbt-dynver (no version literal is committed).
Tag a release commit `vX.Y.Z` for a clean version; untagged commits get a snapshot version.

### End-to-end tests (opt-in)

The `E2E`-tagged suite (`server/.../e2e/MediaWorkerE2ESpec`) runs the real worker against the
**published Apollo + Hermes images** via [testcontainers](https://testcontainers.com/) — it is
excluded from the default `sbt test` and requires a Docker daemon plus `ffmpeg`/`libvips-tools` on
`PATH`. Run it explicitly with `-De2e=true`:

```bash
# Boots calvinference/apollostorage + calvinference/hermesmq (+ their Postgres) as containers.
sbt -De2e=true "server/testOnly me.cference.hephaestus.e2e.MediaWorkerE2ESpec"
```

CI runs it via the dedicated `e2e.yml` workflow (`workflow_dispatch` + nightly `schedule`), not the
fast PR checks. A `docker-compose.e2e.yml` is provided for bringing the same stack up by hand.

## Configuration

Every operational value is loaded from HOCON (`server/src/main/resources/application.conf`) with an
environment-variable override. In this milestone the values are loaded and validated only (no
client is wired yet). A missing or blank required key fails startup fast with a message naming the
key.

| HOCON key                                | Env var                    | Default                | Description                                        |
| ---------------------------------------- | -------------------------- | ---------------------- | -------------------------------------------------- |
| `hephaestus.http.host`                   | `HTTP_HOST`                | `0.0.0.0`              | HTTP bind host                                     |
| `hephaestus.http.port`                   | `HTTP_PORT`                | `8080`                 | HTTP bind port                                     |
| `hephaestus.hermes.endpoint`             | `HERMES_ENDPOINT`          | `hermesmq:8080`        | HermesMQ message-queue endpoint                    |
| `hephaestus.hermes.ingest-lane`          | `HERMES_INGEST_LANE`       | `media.ingest`         | Lane for new-upload jobs (drained first)           |
| `hephaestus.hermes.reprocess-lane`       | `HERMES_REPROCESS_LANE`    | `media.reprocess`      | Lane for backfill/reprocess jobs                   |
| `hephaestus.apollo.endpoint`             | `APOLLO_ENDPOINT`          | `apollostorage:8443`   | Apollo object-store endpoint                       |
| `hephaestus.derivatives.thumbnail-px`    | `DERIVATIVE_THUMBNAIL_PX`  | `250`                  | Thumbnail long-edge size (px)                      |
| `hephaestus.derivatives.sample-px`       | `DERIVATIVE_SAMPLE_PX`     | `850`                  | Sample long-edge size (px)                         |
| `hephaestus.derivatives.spec-version`    | `DERIVATIVE_SPEC_VERSION`  | `v1`                   | Spec version stamped on every result               |
| `hephaestus.thresholds.sample-min-long-edge-px` | `SAMPLE_MIN_LONG_EDGE_PX` | `850`           | Only emit a sample when the long edge exceeds this |

## Deployment (docker compose)

```yaml
services:
  hephaestus:
    image: calvinference/hephaestus:latest
    ports:
      - "8080:8080"
    environment:
      HTTP_PORT: "8080"
      HERMES_ENDPOINT: "hermesmq:8080"
      APOLLO_ENDPOINT: "apollostorage:8443"
      DERIVATIVE_SPEC_VERSION: "v1"
    healthcheck:
      test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3; grep -q '200 OK' <&3"]
      interval: 10s
      timeout: 3s
      start_period: 30s
      retries: 5
```

The image runs as the non-root user `hephaestus` (uid 1001) and bundles ffmpeg + libvips on
`PATH`. The container `HEALTHCHECK` polls `/health`; the service reports `200 UP` only once the
media toolchain probe passes.

## Health endpoint

`GET /health` returns:

- `200` `{"status":"UP","service":"hephaestus","version":"<version>"}` when ready
- `503` `{"status":"DOWN",...}` when readiness is withdrawn (missing toolchain, or during
  coordinated shutdown)
- `404` for any unknown route

## AI Usage Disclaimer

This project is developed with an AI-assisted SDLC agent team under human review. The agents
involved are: **sdlc-orchestrator**, **requirements-analyst**, **solution-architect**,
**story-planner**, **tdd-coach**, **scala-fp-reviewer**, **git-and-ci-reviewer**, and
**clean-code-reviewer**. All AI-generated code, configuration, and documentation is reviewed by a
human (Calvin Ference) before it is merged. The AI accelerates authoring and review; it does not
replace human judgment or accountability for what ships.

## License

Released under the [MIT License](LICENSE) — Copyright (c) 2026 Calvin Ference.
