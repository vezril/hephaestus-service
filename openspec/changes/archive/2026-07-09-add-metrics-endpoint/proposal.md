# Change: add-metrics-endpoint

## Why

The Codex observability stack scrapes each service's Prometheus `/metrics` endpoint (Apollo and
Hermes already expose one; the k3s Deployment can then carry scrape annotations). Hephaestus has
none, so it's invisible to Prometheus/Grafana — no JVM health, no worker throughput, no failure
rate. This change adds a `/metrics` endpoint mirroring the sibling services, plus worker-specific
metrics that make the CPU-heavy forge actually observable (how many jobs, how fast, how many failing).

## Decisions

| Concern | Choice |
|---------|--------|
| Library | **`io.prometheus` simpleclient** `0.16.0` (+ `simpleclient_hotspot`, `simpleclient_common`) — exactly matching apollo-storage, so the constellation uses one metrics stack. |
| Endpoint | `GET /metrics` on the **existing HTTP port** (8080), alongside `/health` (no new port), rendering the app `CollectorRegistry` via `TextFormat` (content-type `text/plain; version=0.0.4`). |
| Toggle | `metrics.enabled` config (default **true**, env-overridable) — when disabled, no route + a no-op recorder (zero overhead), mirroring apollo. |
| Base metrics | JVM/process via `DefaultExports` (hotspot), a `build_info` gauge (version) and a `readiness` gauge (mirrors `/health`). |
| Worker metrics | A **`MetricsRecorder` seam** injected into the `JobConsumer`: `jobs_processed_total{lane,outcome}` (outcome = `success`\|`terminal`\|`retriable`), `job_processing_seconds` histogram, and `jobs_inflight` gauge. A Prometheus impl + a **no-op** default (so §3's consumer tests stay pure and metrics-free). |
| Placement | Effectful (`server`): `metrics/MetricsRegistry` + `metrics/PrometheusMetricsRecorder` + `http/MetricsRoutes`, wired in `Main`. No `core` changes. |

## What Changes

- **metrics** (new capability): a Prometheus `GET /metrics` endpoint exposing JVM/process metrics, a
  version + readiness gauge, and worker metrics (jobs by lane+outcome, processing duration, in-flight),
  toggleable via config. The `JobConsumer` records outcomes through an injected `MetricsRecorder`
  (no-op by default, so existing tests are unaffected).
- **Build**: add the three `io.prometheus` deps (`0.16.0`).
- **Config**: `metrics.enabled` (default true, `METRICS_ENABLED`).
- **Main / HTTP**: build the registry when enabled, concat `MetricsRoutes` with `HealthRoutes`, and
  pass the recorder into the `JobConsumer`.

## Impact

- Affected specs: `metrics` is **ADDED**.
- Affected code: new `server` `metrics/*` + `http/MetricsRoutes`; a `MetricsRecorder` param threaded
  into `JobConsumer` (default no-op); `build.sbt` deps; config; `Main` wiring. No `core` changes.
- Dependencies: `io.prometheus` simpleclient 0.16.0 (all public, no token needed).
- Enables: Codex to turn on Prometheus scrape annotations on the hephaestus k3s Deployment.
- Out of scope: Grafana dashboards / alert rules (Codex-side); distributed tracing (OTel — deferred,
  as in apollo); per-derivative-type metric breakdowns (can be added later if useful).
