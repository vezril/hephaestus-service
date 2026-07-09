# Tasks — add-metrics-endpoint

TDD where it fits. This is `server`-only (effectful metrics + HTTP route); no `core` changes. The
`/metrics` route + registry are testable with the Pekko HTTP route testkit (like `HealthRoutes`);
the `MetricsRecorder` seam keeps the `JobConsumer` metrics-free by default.

**CI-verified:** `server` compiles only in CI (no read:packages token). The route-testkit tests run
in the `server` suite in CI. Prometheus deps are public, so `sbt update` for them isn't the blocker —
the whole `server` module still needs the token to compile, so verify via CI.

Before starting: consult `/Users/cference/Code/claude-toolkit`. Read apollo-storage's
`server/.../metrics/MetricsRegistry.scala` + `http/MetricsRoutes.scala` + how `Main` wires them and
gates on `metrics.enabled`; read hephaestus's `http/HealthRoutes.scala`, `HttpServer.scala`, `Main`,
and `job/JobConsumer.scala` (where the recorder is injected).

## 1. Build + config

- [ ] 1.1 Add `io.prometheus` `simpleclient` + `simpleclient_hotspot` + `simpleclient_common` `0.16.0`
      to the `server` deps (match apollo-storage).
- [ ] 1.2 Config: `metrics.enabled` (default `true`, env `METRICS_ENABLED`); `AppConfig` + spec test.

## 2. Registry + route (`server`)

- [ ] 2.1 **Red**: route-testkit — `GET /metrics` returns 200 `text/plain; version=0.0.4` with
      Prometheus exposition text including a JVM metric and the `build_info`/`readiness` gauges;
      when `metrics.enabled=false`, `/metrics` is 404 (edge cases).
- [ ] 2.2 **Green**: `MetricsRegistry` (app `CollectorRegistry`, `DefaultExports.register`, a
      `build_info{version}` gauge, a `readiness` gauge wired to the readiness flag); `MetricsRoutes`
      rendering it via `TextFormat.write004`.
- [ ] 2.3 **Green**: concat `MetricsRoutes` with `HealthRoutes` in `HttpServer`/`Main` only when enabled.

## 3. Worker metrics via a recorder seam

- [ ] 3.1 **Red**: `MetricsRecorder` trait (`recordProcessed(lane, outcome)`, `time(lane)(block)`,
      `inflight` up/down) + a **no-op** default; a Prometheus impl backed by the registry
      (`jobs_processed_total{lane,outcome}` counter, `job_processing_seconds` histogram, `jobs_inflight`
      gauge). Unit-test the Prometheus impl increments the registry (scrape text reflects a recorded job).
- [ ] 3.2 **Green**: implement the recorder.
- [ ] 3.3 **Red/Green**: thread the recorder into `JobConsumer` (constructor param, default no-op) —
      record outcome (success/terminal/retriable) + duration + in-flight around each job; existing
      `JobConsumerSpec` stays green with the no-op default (no test churn), plus one test asserting the
      recorder is called with the right outcome for success vs terminal vs transient.

## 4. Wiring + verify

- [ ] 4.1 Wire in `Main`: build the registry + Prometheus recorder when `metrics.enabled`, else no-op +
      no route; pass the recorder to the `JobConsumer`. Readiness gauge reflects the readiness flag.
- [ ] 4.2 Push branch, PR into `development`, watch CI green (route-testkit + recorder tests run in the
      `server` suite). Confirm the default `/health` behavior + fast CI are unchanged.
