# metrics — Spec Delta

## ADDED Requirements

### Requirement: Prometheus metrics endpoint

The service SHALL expose `GET /metrics` on its HTTP port in Prometheus text exposition format
(`text/plain; version=0.0.4`), reporting JVM/process metrics, a `build_info` gauge (with the version)
and a `readiness` gauge (mirroring `/health`). The endpoint SHALL be toggleable by configuration
(`metrics.enabled`, default enabled); when disabled, no route is installed and metric collection is a
no-op.

#### Scenario: Metrics are scrapeable
- **Given** the service is running with metrics enabled
- **When** Prometheus scrapes `GET /metrics`
- **Then** it receives `200` `text/plain; version=0.0.4` with JVM metrics and the `build_info`/`readiness` gauges

#### Scenario: Readiness gauge mirrors health
- **Given** the service has withdrawn readiness (shutting down)
- **When** `/metrics` is scraped
- **Then** the `readiness` gauge reads 0 (consistent with `/health` returning 503)

#### Scenario: Edge case — metrics disabled
- **Given** `metrics.enabled=false`
- **When** a client requests `GET /metrics`
- **Then** the response is `404` and no metrics are collected (no overhead)

### Requirement: Worker throughput and failure metrics

The service SHALL record worker metrics through an injectable recorder (a no-op by default so
processing is unaffected when metrics are off): a `jobs_processed_total` counter labelled by lane and
outcome (`success` / `terminal` / `retriable`), a `job_processing_seconds` histogram, and a
`jobs_inflight` gauge — so throughput, latency, and failure rate are observable per lane.

#### Scenario: A processed job is counted by outcome
- **Given** metrics enabled and a job that succeeds on the `ingest` lane
- **When** it completes
- **Then** `jobs_processed_total{lane="ingest",outcome="success"}` increments and `job_processing_seconds` observes its duration

#### Scenario: A terminal failure is counted distinctly from a transient one
- **Given** one job that fails terminally and one that fails transiently
- **When** both are handled
- **Then** `jobs_processed_total` increments `outcome="terminal"` and `outcome="retriable"` respectively (distinguishable in Grafana)

#### Scenario: Edge case — metrics disabled means no-op recording
- **Given** `metrics.enabled=false`
- **When** jobs are processed
- **Then** no counters are touched and there is no measurable recording overhead
