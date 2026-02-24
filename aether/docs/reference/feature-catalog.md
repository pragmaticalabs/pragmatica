# Aether Feature Catalog

Comprehensive inventory of all Aether distributed runtime capabilities.

**Status legend:**
- **Battle-tested** — Proven through multi-node E2E tests with failure injection (node kills, partitions, leader failovers)
- **Complete** — Production-ready, tested (unit tests, possibly basic E2E)
- **Partial** — Core implemented, gaps noted
- **Planned** — Designed but not yet implemented

---

## Deployment & Lifecycle

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 1 | Blueprint management | Battle-tested | Declarative TOML-based deployment specs with dependency ordering, validation, and status tracking |
| 2 | Slice lifecycle | Battle-tested | Full state machine: DOWNLOADING, LOADING, STARTING, ACTIVE, UNLOADING, UNLOADED, FAILED. Per-node tracking via KV-Store |
| 3 | Rolling updates | Battle-tested | Zero-downtime version deployments with traffic shifting (new:old ratio), health thresholds, auto-progression, rollback, and cleanup policies |
| 4 | Auto-healing | Battle-tested | Automatic reconciliation of desired vs. actual state on node departure. Leader-only with failover |
| 5 | Classloader isolation | Complete | Per-slice classloader prevents dependency conflicts between slices |
| 6 | Manifest versioning | Complete | Envelope format versioning (v1, v2, v3) for backward-compatible manifest evolution |

## Scaling & Control

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 7 | CPU-based auto-scaling | Battle-tested | DecisionTreeController evaluates CPU thresholds, issues ScaleUp/ScaleDown decisions via ControlLoop |
| 8 | minInstances enforcement | Complete | Blueprint minimum instance count as hard floor across auto-scaler, manual API, and rolling updates |
| 9 | Manual scale API | Complete | `POST /api/scale` with blueprint membership guard and minInstances validation |
| 10 | Dynamic controller config | Complete | Runtime-adjustable CPU thresholds and evaluation interval |
| 11 | TTM predictive scaling | Partial | ONNX model inference, forecast analysis, adaptive decision tree. **Gap:** Not connected to live model training, disabled by default |
| 12 | Dynamic aspects | Complete (superseded by #42) | Runtime method-level instrumentation (LOG, METRICS, LOG_AND_METRICS) via KV-Store. CLI and API control |

## Cluster & Consensus

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 13 | Rabia consensus | Battle-tested | Leaderless Byzantine fault-tolerant consensus for KV-Store replication |
| 14 | Leader election | Battle-tested | Lightweight leader detection with virtually instant re-election on departure |
| 15 | Quorum state management | Battle-tested | Monotonic-sequenced quorum notifications, graceful degradation on quorum loss, automatic restoration |
| 16 | Topology management | Battle-tested | Node discovery, addition/removal events, health tracking, grace period for departures |
| 17 | Distributed KV-Store | Battle-tested | Consensus-replicated store with typed keys (SliceNode, SliceTarget, HttpRoute, AppBlueprint, VersionRouting, RollingUpdate, Threshold, LogLevel, Config, TopicSubscription) |

## Networking & Routing

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 18 | HTTP route registration | Complete | Dynamic per-slice route discovery and registration via KV-Store |
| 19 | Endpoint registry | Complete | Artifact-to-node mapping for slice instance tracking and load balancing |
| 20 | Service-to-service invocation | Battle-tested | SliceInvoker with HTTP routing, load balancer selection, timeout/retry, metrics |
| 21 | Version routing | Battle-tested | Traffic splitting between old/new versions during rolling updates (configurable ratio) |

## Messaging (Pub-Sub)

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 22 | Publisher/Subscriber API | Complete | `Publisher<T>` functional interface, `Subscriber` marker, `@Subscription` annotation. 18 unit tests |
| 23 | Topic subscription registry | Complete | KV-Store backed subscriber discovery with competing consumers (round-robin). Tested |
| 24 | Message delivery | Complete | TopicPublisher fans out via SliceInvoker. PublisherFactory registered as SPI. Tested |
| 25 | Resource lifecycle | Complete | Reference-counted `releaseAll()`, generated `stop()` cleanup, consumer tracking. SliceId auto-injected into ProvisioningContext |

## Scheduled Invocation

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 26 | Scheduled task registry | Complete | KV-Store backed registry tracking periodic task registrations with change listener pattern. 8 unit tests |
| 27 | Scheduled task manager | Complete | Timer lifecycle manager with leader-only semantics, quorum gating, interval parsing (s/m/h/d), automatic start/cancel on registry changes. 10 unit tests |
| 28 | Cron expression parser | Complete | 5-field cron syntax (minute hour day-of-month month day-of-week) with ranges, steps, lists. 11 unit tests |
| 29 | Scheduled task KV types | Complete | `ScheduledTaskKey` and `ScheduledTaskValue` in KV-Store with interval and cron task factories |
| 30 | Deployment lifecycle wiring | Complete | Publish/unpublish scheduled tasks during slice activation, deactivation, reactivation, and failure cleanup |
| 31 | Scheduled tasks management API | Complete | `GET /api/scheduled-tasks` (list all with active timer count), `GET /api/scheduled-tasks/{configSection}` (filtered). CLI subcommand with list/get |

## Storage & Data

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 32 | Artifact repository | Battle-tested | Maven-compatible, chunked storage, checksum verification (MD5/SHA1), 64MB upload limit, metadata XML generation |
| 33 | Distributed hash table | Battle-tested | Consistent hash ring (150 vnodes, 1024 partitions), quorum R/W, anti-entropy repair (CRC32 digest exchange, migration on mismatch), re-replication on node departure (DHTRebalancer), per-use-case config via `scoped()` |
| 34 | Configuration service | Complete | TOML-based config with runtime overrides via KV-Store, environment variable interpolation, system property fallback |

## Observability & Metrics

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 35 | System metrics | Battle-tested | CPU, heap memory, event loop lag per node. 120-minute aggregation window |
| 36 | Invocation metrics | Complete | Per-method call count, success/failure rates, latency percentiles (P50/P95/P99), slow invocation detection |
| 37 | Cluster metrics API | Battle-tested | Aggregated load, deployment timeline, error rates, saturation, health score, capacity prediction |
| 38 | Historical metrics | Complete | Time-range queries (5m, 15m, 1h, 2h) with per-node snapshots |
| 39 | Alert management | Complete | Active/historical alerts, threshold-based triggering, KV-Store persistence, CLI control |
| 40 | Dynamic thresholds | Complete | Runtime warning/critical threshold configuration per metric |
| 41 | Prometheus export | Battle-tested | Micrometer integration with Prometheus scrape endpoint |
| 42 | Unified invocation observability | Complete | Sampling-based tracing + depth-to-SLF4J bridge + adaptive per-node sampling. Replaces DynamicAspect system. CLI and REST API |
| 43 | Cluster event aggregator | Complete | Ring buffer (1000 events) collecting 11 event types (topology, leader, quorum, deployment, slice failure, network). REST API (`/api/events` with `since` filter), WebSocket feed (`/ws/events` delta broadcast), CLI command |

## Resource Provisioning

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 44 | SPI resource factories | Complete | ServiceLoader discovery, config-driven provisioning, type-safe qualifiers |
| 45 | Database resources | Complete | JDBC, R2DBC, jOOQ, jOOQ-R2DBC, JPA with connection pooling and transaction management |
| 46 | HTTP client resource | Complete | Configurable outbound HTTP with timeouts, retries, SSL/TLS, Jackson integration |
| 47 | Interceptor framework | Complete | Method-level interceptors: retry, circuit breaker, rate limit, logging, metrics. Runtime enable/disable |
| 48 | Runtime extensions | Complete | `registerExtension()` for injecting runtime components into resource factories |

## Management

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 49 | REST management API | Battle-tested | 60+ endpoints across 13 route classes: status, health, blueprints, slices, scaling, rolling updates, config, thresholds, alerts, aspects, logging, TTM, invocation metrics, controller config, node lifecycle |
| 50 | Interactive CLI | Complete | Batch and REPL modes. Commands: status, nodes, slices, metrics, health, scale, artifact, blueprint, update, invocation-metrics, controller, alerts, thresholds, aspects, traces, observability, config, logging, events, node lifecycle/drain/activate/shutdown |
| 51 | WebSocket streams | Complete | `/ws/dashboard` (metrics), `/ws/status` (cluster state), `/ws/events` (real-time cluster events with delta broadcasting) |
| 52 | Dynamic log levels | Complete | Runtime log level adjustment per logger via KV-Store. CLI and API control |
| 53 | E2E test framework | Complete | Testcontainers-based cluster testing with echo slices (v1/v2), Docker image building from JAR |

## Developer Tooling

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 54 | Slice annotation processor | Complete | Compile-time code generation: factory classes, manifests, route sources, pub-sub wiring |
| 55 | JBCT compliance | Complete | Format linting, return type validation, pattern checking, factory naming conventions. Maven plugin |
| 56 | Envelope format versioning | Complete | `ENVELOPE_FORMAT_VERSION` in ManifestGenerator with runtime compatibility check |
| 57 | Forge simulator | Battle-tested | Standalone cluster simulator with load generation (constant/ramp/spike), chaos injection, visual dashboard, REST API |
| 58 | Web dashboard | Partial | Forge dashboard complete (cluster visualization, load generation, chaos injection, metrics). Node management dashboard needs modernization for production use |

## Node Operations

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 63 | Node lifecycle state machine | Complete | States: JOINING, ON_DUTY, DRAINING, DECOMMISSIONED, SHUTTING_DOWN. Self-registration (ON_DUTY on quorum), remote shutdown (SHUTTING_DOWN via KV watch), lifecycle key cleanup on node departure |
| 64 | Graceful node drain | Complete | Drain orchestration (CDM evacuates slices respecting disruption budget), cancel drain (return to ON_DUTY), automatic DECOMMISSIONED on eviction complete |
| 65 | Disruption budget | Complete | `minAvailable` in blueprint TOML, budget enforcement in scale-down and drain eviction |

## Security & Resilience

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 59 | Graceful quorum degradation | Battle-tested | Control loop suspension on quorum loss, reconciliation on restoration, leader transition with state preservation |
| 60 | Blueprint membership guard | Complete | `POST /api/scale` rejects slices not deployed via blueprint |
| 61 | Health check endpoint | Battle-tested | `/api/health` with ready flag, quorum status, connected peers, node count |
| 62 | Orphaned entry cleanup | Complete | CDM `reconcile()` cleans up orphaned UNLOADING entries after blueprint removal |

## Known Limitations

| Area | Limitation | Planned Fix |
|------|-----------|-------------|
| Security | No per-endpoint role authorization (all API keys get same access) | RBAC Tier 2 (#63) |
| Security | No TLS between cluster nodes | TLS Certificate Management (#66) |
| Data durability | No KV-Store backup/restore; quorum loss = data loss | KV-Store State Backup (#72) |
| Networking | Single-region only; no multi-region deployment | Not yet planned |
| Storage | KV-Store in-memory only (recovered from peers via consensus) | KV-Store State Backup (#72) |

## Planned Features

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 61 | Per-data-source DB schema management | Planned | Cluster-level schema migration managed by Aether runtime. Leader-driven execution via consensus. Readiness gate blocks traffic until schema current |
| 62 | Canary & blue-green deployment | Planned | Canary with automatic rollback on error threshold, blue-green with instant switchover, A/B testing with traffic splitting |
| 63 | RBAC Tier 2 — per-endpoint authorization | Planned | Per-endpoint role-based authorization rules (admin, operator, viewer). Route-level security policy from KV-Store. Auth failure rate limiting |
| 64 | Per-route rate limiting | Planned | Per-HTTP-route rate limiting via blueprint or management API. Token bucket or sliding window. Cluster-aware distributed counters |
| 65 | Spot instance support | Planned | Elastic pool of spot/preemptible instances for cost-optimized scaling. Core (on-demand) + elastic (spot) pools. Prerequisite: Cloud Integration |
| 66 | Cluster expense tracking | Planned | Real-time cost visibility from cloud billing APIs. Per-node, per-slice, per-request cost derivation. Budget alerts. Prerequisite: Cloud Integration |
| 67 | TLS certificate management | Planned | Mutual TLS between cluster nodes and management API authentication |
| 69 | KV-Store state backup | Planned | Periodic KV-Store snapshots to durable storage (filesystem, S3). Disaster recovery when quorum permanently lost |
| 70 | Aether runtime rolling upgrade | Planned | Upgrade Aether node software across running cluster without downtime. Node-by-node with health verification |
| 71 | Email messaging resource | Planned | Facade with pluggable backends (SMTP, AWS SES, SendGrid). Sending (plain text + HTML, attachments) and receiving (automated conversations). SPI-based |
| 72 | Dead letter handling | Planned | KV-Store backed DLQ for failed pub-sub messages and scheduled task invocations. Retry, inspect, replay via API and CLI |

---

## Statistics

| Status | Count |
|--------|-------|
| Battle-tested | 21 |
| Complete | 42 |
| Partial | 2 |
| Planned | 11 |
| Total | 76 |

**Battle-tested features (21):** Blueprint management, Slice lifecycle, Rolling updates, Auto-healing, CPU-based auto-scaling, Rabia consensus, Leader election, Quorum state management, Topology management, Distributed KV-Store, Service-to-service invocation, Version routing, Artifact repository, Distributed hash table, System metrics, Cluster metrics API, Prometheus export, REST management API, Forge simulator, Graceful quorum degradation, Health check endpoint

**Partial features and their gaps:**

| Feature | Key Gap |
|---------|---------|
| TTM predictive scaling | Disabled by default, no live model training |
| Web dashboard | Node management dashboard needs modernization for production use |

**Planned features:**

| Feature | Key Dependency |
|---------|---------------|
| Per-data-source DB schema management | Design spec ready |
| Canary & blue-green deployment | — |
| RBAC Tier 2 — per-endpoint authorization | RBAC Tier 1 complete |
| TLS certificate management | — |
| Per-route rate limiting | — |
| Spot instance support | Cloud Integration |
| Cluster expense tracking | Cloud Integration |
| Dead letter handling | Pub-sub + scheduler complete |
| KV-Store state backup | — |
| Aether runtime rolling upgrade | Official container or binaries |
| Email messaging resource | — |

---

*Last updated: 2026-02-24 (v0.18.0)*
