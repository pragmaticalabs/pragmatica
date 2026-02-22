# Aether Feature Catalog

Comprehensive inventory of all Aether distributed runtime capabilities.

**Status legend:**
- **Complete** — Production-ready, tested
- **Partial** — Core implemented, gaps noted
- **Planned** — Designed (RFC exists) but not yet implemented

---

## Deployment & Lifecycle

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 1 | Blueprint management | Complete | Declarative TOML-based deployment specs with dependency ordering, validation, and status tracking |
| 2 | Slice lifecycle | Complete | Full state machine: DOWNLOADING, LOADING, STARTING, ACTIVE, UNLOADING, UNLOADED, FAILED. Per-node tracking via KV-Store |
| 3 | Rolling updates | Complete | Zero-downtime version deployments with traffic shifting (new:old ratio), health thresholds, auto-progression, rollback, and cleanup policies |
| 4 | Auto-healing | Complete | Automatic reconciliation of desired vs. actual state on node departure. Leader-only with failover |
| 5 | Classloader isolation | Complete | Per-slice classloader prevents dependency conflicts between slices |
| 6 | Manifest versioning | Complete | Envelope format versioning (v1, v2) for backward-compatible manifest evolution |

## Scaling & Control

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 7 | CPU-based auto-scaling | Complete | DecisionTreeController evaluates CPU thresholds, issues ScaleUp/ScaleDown decisions via ControlLoop |
| 8 | minInstances enforcement | Complete | Blueprint minimum instance count as hard floor across auto-scaler, manual API, and rolling updates |
| 9 | Manual scale API | Complete | `POST /api/scale` with blueprint membership guard and minInstances validation |
| 10 | Dynamic controller config | Complete | Runtime-adjustable CPU thresholds and evaluation interval |
| 11 | TTM predictive scaling | Partial | ONNX model inference, forecast analysis, adaptive decision tree. **Gap:** Not connected to live model training, disabled by default |
| 12 | Dynamic aspects | Complete | Runtime method-level instrumentation (LOG, METRICS, LOG_AND_METRICS) via KV-Store. CLI and API control |

## Cluster & Consensus

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 13 | Rabia consensus | Complete | Leaderless Byzantine fault-tolerant consensus for KV-Store replication |
| 14 | Leader election | Complete | Lightweight leader detection with virtually instant re-election on departure |
| 15 | Quorum state management | Complete | Monotonic-sequenced quorum notifications, graceful degradation on quorum loss, automatic restoration |
| 16 | Topology management | Complete | Node discovery, addition/removal events, health tracking, grace period for departures |
| 17 | Distributed KV-Store | Complete | Consensus-replicated store with typed keys (SliceNode, SliceTarget, HttpRoute, AppBlueprint, VersionRouting, RollingUpdate, Threshold, LogLevel, Config, TopicSubscription) |

## Networking & Routing

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 18 | HTTP route registration | Complete | Dynamic per-slice route discovery and registration via KV-Store |
| 19 | Endpoint registry | Complete | Artifact-to-node mapping for slice instance tracking and load balancing |
| 20 | Service-to-service invocation | Complete | SliceInvoker with HTTP routing, load balancer selection, timeout/retry, metrics |
| 21 | Version routing | Complete | Traffic splitting between old/new versions during rolling updates (configurable ratio) |

## Messaging (Pub-Sub)

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 22 | Publisher/Subscriber API | Partial | `Publisher<T>` functional interface, `Subscriber` marker, `@Subscription` annotation. **Gap:** No tests |
| 23 | Topic subscription registry | Partial | KV-Store backed subscriber discovery with competing consumers (round-robin). **Gap:** No tests |
| 24 | Message delivery | Partial | TopicPublisher fans out via SliceInvoker. PublisherFactory registered as SPI. **Gap:** No tests, message type serialization not verified |
| 25 | Resource lifecycle | Partial | Reference-counted `releaseAll()`, generated `stop()` cleanup, consumer tracking. **Gap:** SliceId propagation in ProvisioningContext unverified |

## Storage & Data

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 26 | Artifact repository | Complete | Maven-compatible, chunked storage, checksum verification (MD5/SHA1), 64MB upload limit, metadata XML generation |
| 27 | Distributed hash table | Partial | Consistent hash ring (150 vnodes, 1024 partitions), quorum R/W (R=2, W=2), topology listener. **Gap:** No re-replication on node departure, digest handlers are no-ops, no data migration |
| 28 | Configuration service | Complete | TOML-based config with runtime overrides via KV-Store, environment variable interpolation, system property fallback |

## Observability & Metrics

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 29 | System metrics | Complete | CPU, heap memory, event loop lag per node. 120-minute aggregation window |
| 30 | Invocation metrics | Complete | Per-method call count, success/failure rates, latency percentiles (P50/P95/P99), slow invocation detection |
| 31 | Cluster metrics API | Complete | Aggregated load, deployment timeline, error rates, saturation, health score, capacity prediction |
| 32 | Historical metrics | Complete | Time-range queries (5m, 15m, 1h, 2h) with per-node snapshots |
| 33 | Alert management | Complete | Active/historical alerts, threshold-based triggering, KV-Store persistence, CLI control |
| 34 | Dynamic thresholds | Complete | Runtime warning/critical threshold configuration per metric |
| 35 | Prometheus export | Complete | Micrometer integration with Prometheus scrape endpoint |

## Resource Provisioning

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 36 | SPI resource factories | Complete | ServiceLoader discovery, config-driven provisioning, type-safe qualifiers |
| 37 | Database resources | Complete | JDBC, R2DBC, jOOQ, jOOQ-R2DBC, JPA with connection pooling and transaction management |
| 38 | HTTP client resource | Complete | Configurable outbound HTTP with timeouts, retries, SSL/TLS, Jackson integration |
| 39 | Interceptor framework | Complete | Method-level interceptors: retry, circuit breaker, rate limit, logging, metrics. Runtime enable/disable |
| 40 | Runtime extensions | Complete | `registerExtension()` for injecting runtime components into resource factories |

## Management

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 41 | REST management API | Complete | 60+ endpoints across 12 route classes: status, health, blueprints, slices, scaling, rolling updates, config, thresholds, alerts, aspects, logging, TTM, invocation metrics, controller config |
| 42 | Interactive CLI | Complete | Batch and REPL modes. Commands: status, nodes, slices, metrics, health, scale, artifact, blueprint, update, invocation-metrics, controller, alerts, thresholds, aspects, config, logging |
| 43 | Web dashboard | Complete | Real-time cluster monitoring with WebSocket streaming, metrics visualization, topology view |
| 44 | WebSocket streams | Complete | `/ws/dashboard` (metrics) and `/ws/status` (cluster state with node/slice details) |
| 45 | Dynamic log levels | Complete | Runtime log level adjustment per logger via KV-Store. CLI and API control |

## Developer Tooling

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 46 | Slice annotation processor | Complete | Compile-time code generation: factory classes, manifests, route sources, pub-sub wiring |
| 47 | JBCT compliance | Complete | Format linting, return type validation, pattern checking, factory naming conventions. Maven plugin |
| 48 | Envelope format versioning | Complete | `ENVELOPE_FORMAT_VERSION` in ManifestGenerator with runtime compatibility check |
| 49 | Forge simulator | Complete | Standalone cluster simulator with load generation (constant/ramp/spike), chaos injection, visual dashboard, REST API |
| 50 | E2E test framework | Complete | Testcontainers-based cluster testing with echo slices (v1/v2), Docker image building from JAR |

## Security & Resilience

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 51 | Graceful quorum degradation | Complete | Control loop suspension on quorum loss, reconciliation on restoration, leader transition with state preservation |
| 52 | Blueprint membership guard | Complete | `POST /api/scale` rejects slices not deployed via blueprint |
| 53 | Health check endpoint | Complete | `/api/health` with ready flag, quorum status, connected peers, node count |
| 54 | Orphaned entry cleanup | Complete | CDM `reconcile()` cleans up orphaned UNLOADING entries after blueprint removal |

---

## Statistics

| Status | Count |
|--------|-------|
| Complete | 46 |
| Partial | 8 |
| Total | 54 |

**Partial features and their gaps:**

| Feature | Key Gap |
|---------|---------|
| TTM predictive scaling | Disabled by default, no live model training |
| Publisher/Subscriber API | No tests |
| Topic subscription registry | No tests |
| Message delivery | No tests, serialization registration unverified |
| Resource lifecycle | SliceId propagation unverified |
| DHT replication | No re-replication on node departure, digest/migration handlers are no-ops |

---

*Last updated: 2026-02-22 (v0.17.0)*
