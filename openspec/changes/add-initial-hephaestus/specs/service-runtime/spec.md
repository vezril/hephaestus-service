# service-runtime — Spec Delta

## ADDED Requirements

### Requirement: Minimal Pekko application with health endpoint

The service SHALL run as a Pekko typed `ActorSystem` (no persistence in this capability) exposing
`GET /health` over HTTP, returning `200 OK` with JSON
`{"status":"UP","service":"hephaestus","version":"<version>"}` while the system is running and
ready.

#### Scenario: Health returns UP
- **Given** the service has started successfully and is ready
- **When** a client sends `GET /health`
- **Then** the response is `200` with `status = "UP"` and the build version in the body

#### Scenario: Edge case — unknown route
- **Given** the service is running
- **When** a client sends `GET /nope`
- **Then** the response is `404` and the connection remains healthy (subsequent `/health` still returns `200`)

#### Scenario: Edge case — health during shutdown
- **Given** the service has received a termination signal and coordinated shutdown has begun
- **When** a client sends `GET /health` before the port unbinds
- **Then** the response is `503` with `status = "DOWN"` (readiness is withdrawn before the actor system terminates)

### Requirement: Graceful startup and shutdown

The service SHALL bind its HTTP port before reporting ready, and on SIGTERM SHALL complete via
Pekko Coordinated Shutdown: unbind, drain in-flight requests, then terminate the actor system,
exiting with code 0.

#### Scenario: Clean SIGTERM
- **Given** the service is running in a container
- **When** the container runtime sends SIGTERM
- **Then** the process exits 0 within the shutdown timeout and no request in flight is abruptly reset

#### Scenario: Edge case — port already in use
- **Given** the configured port is occupied by another process
- **When** the service starts
- **Then** startup fails fast with a clear log message naming the port, and the process exits non-zero (no zombie actor system)

### Requirement: Env-overridable configuration

The service SHALL load configuration from HOCON with sensible defaults, and every operational key
SHALL be overridable by an environment variable: the HermesMQ endpoint and the `media.ingest` /
`media.reprocess` lane names, the Apollo endpoint, the derivative dimensions (thumbnail/sample
sizes), the `derivativeSpecVersion`, and processing thresholds. In this milestone these values are
loaded and validated only (no client is wired to them yet).

#### Scenario: Defaults load
- **Given** no environment overrides
- **When** the service loads its configuration
- **Then** all keys resolve to their documented defaults and the service starts

#### Scenario: Environment override wins
- **Given** `APOLLO_ENDPOINT` and `DERIVATIVE_SPEC_VERSION` are set in the environment
- **When** the service loads its configuration
- **Then** the loaded values reflect the environment, not the HOCON defaults

#### Scenario: Edge case — missing required value fails fast
- **Given** a required key has no default and no override
- **When** the service loads its configuration at startup
- **Then** startup fails fast with a message naming the missing key (no partial start)

### Requirement: Bundled media toolchain, verified at startup

The runtime SHALL have `ffmpeg`/`ffprobe` and `libvips` (`vips`) available on `PATH`, and SHALL
probe for them at startup, recording their versions; if either is absent the service SHALL report
readiness `DOWN` with a clear log rather than failing silently later during processing.

#### Scenario: Toolchain present
- **Given** the service starts in an image with ffmpeg and libvips installed
- **When** the startup probe runs
- **Then** it records both versions and readiness is `UP`

#### Scenario: Edge case — missing tool degrades readiness
- **Given** `vips` is not on `PATH`
- **When** the startup probe runs
- **Then** readiness is `DOWN`, `GET /health` returns `503`, and the log names the missing tool

### Requirement: Docker image via sbt-native-packager bundling ffmpeg + libvips

The build SHALL produce a Docker image (`sbt Docker/publishLocal`) on a JRE base that installs
`ffmpeg` and `libvips-tools`, runs the service as a non-root user, exposes the HTTP port, and
defines a container `HEALTHCHECK` against `/health`.

#### Scenario: Image boots and reports healthy
- **Given** the locally built image
- **When** the container is started with default configuration
- **Then** the container reaches `healthy` status and `GET /health` from the host returns `200`

#### Scenario: Media tools on PATH inside the container
- **Given** the built image
- **When** `ffmpeg -version` and `vips --version` run inside the container
- **Then** both succeed and print versions

#### Scenario: Edge case — runs as non-root
- **Given** the built image
- **When** the running container's UID is inspected
- **Then** the process runs as a non-root user

#### Scenario: Edge case — configuration override via environment
- **Given** the image started with `HTTP_PORT=9090`
- **When** the container is running
- **Then** the service listens on 9090 (not the default) and the health check succeeds against it
