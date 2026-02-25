# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.18.0] - Unreleased

### Added
- **Unified Invocation Observability (RFC-0010)** — sampling-based distributed tracing with depth-to-SLF4J bridge
  - `InvocationNode` trace record with requestId, depth, caller/callee, duration, outcome, hops
  - `AdaptiveSampler` — per-node throughput-aware sampling (auto-adjusts: 100% at low load, ~1% at 50K/sec)
  - `InvocationTraceStore` — thread-safe ring buffer (50K capacity) for recent traces
  - `ObservabilityInterceptor` — replaces `DynamicAspectInterceptor` with sampling + depth-based SLF4J logging
  - `ObservabilityDepthRegistry` — per-method depth config via KV-store consensus with cluster notifications
  - `ObservabilityConfig` — depth threshold + sampling target configuration
  - Wire protocol: `InvokeRequest` extended with `depth`, `hops`, `sampled` fields
  - `InvocationContext` — ScopedValue-based `DEPTH` and `SAMPLED` propagation across invocation chains
  - REST API: `GET /api/traces`, `GET /api/traces/{requestId}`, `GET /api/traces/stats`, `GET/POST/DELETE /api/observability/depth`
  - CLI: `traces list|get|stats`, `observability depth|depth-set|depth-remove`
  - Forge proxy routes for trace and depth endpoints
- Liveness probe (`/health/live`) and readiness probe (`/health/ready`) with component-level checks (consensus, routes, quorum) for container orchestrator compatibility
- RBAC Tier 1: API key authentication for management server, app HTTP server, and WebSocket connections
- Per-API-key names and roles via config (`[app-http.api-keys.*]` TOML sections or `AETHER_API_KEYS` env)
- SHA-256 API key hashing — raw keys never stored in memory
- Audit logging via dedicated `org.pragmatica.aether.audit` logger
- WebSocket first-message authentication protocol for dashboard, status, and events streams
- CLI `--api-key` / `-k` flag and `AETHER_API_KEY` environment variable for authenticated access
- `InvocationContext` principal and origin node propagation via ScopedValues + MDC
- App HTTP server `/health` endpoint (always 200, for LB health checks on app port)
- Node lifecycle state machine (JOINING → ON_DUTY ↔ DRAINING → DECOMMISSIONED → SHUTTING_DOWN) with self-registration on quorum, remote shutdown via KV watch, lifecycle key cleanup on departure
- Disruption budget (`minAvailable`) for slice deployments — enforced in scale-down and drain eviction
- Graceful node drain with CDM eviction orchestration respecting disruption budget, cancel drain support, automatic DECOMMISSIONED on eviction complete
- Management API endpoints for node lifecycle operations (`GET /api/nodes/lifecycle`, `GET /api/node/lifecycle/{nodeId}`, `POST /api/node/drain/{nodeId}`, `POST /api/node/activate/{nodeId}`, `POST /api/node/shutdown/{nodeId}`)
- CLI commands for node lifecycle management (`node lifecycle`, `node drain`, `node activate`, `node shutdown`)
- **Class-ID-based serialization for cross-classloader slice invocations** — deterministic hash-based Fury class IDs eliminate `ClassCastException` across slice classloaders
  - `Slice.serializableClasses()` — compile-time declaration of all serializable types per slice
  - `SliceCoreClasses` — sequential ID registration for core framework types (Option, Result, Unit)
  - `FurySerializerFactoryProvider` rewritten with `requireClassRegistration(true)`, hash-based IDs [10000-30000), recursive type expansion, collision detection
  - Envelope format version bumped to v4

### Fixed
- **Fury → Fory migration** — upgraded from `org.apache.fury:fury-core:0.10.3` to `org.apache.fory:fory-core:0.16.0-SNAPSHOT` (patched fork with cross-classloader fixes)
- Removed speculative `HttpRequestContext` decode from `InvocationHandler` — eliminated `ArrayIndexOutOfBoundsException` during cross-node slice invocations
- Removed debug logging from consensus `Decoder` and `Handler` (InvokeMessage trace noise)
- Removed SLF4J dependency from `slice-processor` annotation processor — eliminated "No SLF4J providers" warning during compilation
- Configurable observability depth threshold via `forge.toml` `[observability] depth_threshold` — set to -1 to suppress trace logging during local development
- `InvocationContext.runWithContext()` signature alignment in `AppHttpServer` and `InvocationContextPrincipalTest` (missing `depth`/`sampled` params)

### Changed
- `examples/url-shortener` upgraded from standalone 0.17.0 to reactor-integrated 0.18.0 (inherits parent POM, managed versions, installable for forge artifact resolution)
- `InvocationMetricsTest` forge integration test: deploys url-shortener multi-slice (UrlShortener + Analytics), generates 1K round-trip requests, validates invocation metrics, Prometheus, and traces across 5-node cluster
- **BREAKING:** Removed `DynamicAspectMode`, `DynamicAspectInterceptor`, `DynamicAspectRegistry`, `DynamicAspectRoutes`, `AspectProxyRoutes` — superseded by Unified Observability
- **BREAKING:** Removed `/api/aspects` REST endpoints and `aspects` CLI command — use `/api/observability/depth` and `observability` command instead
- Removed `DynamicAspectKey`/`DynamicAspectValue` from KV-store types — replaced by `ObservabilityDepthKey`/`ObservabilityDepthValue`
- **BREAKING:** `SerializerFactoryProvider.createFactory()` signature changed from `List<TypeToken<?>>` to `(List<Class<?>>, ClassLoader)` for class-ID-based registration
- Removed `CrossClassLoaderCodec`, `decodeForClassLoader()`, deprecated `sliceBridgeImpl()`/`sliceBridge()` factory methods

## [0.17.0] - 2026-02-23

### Added
- DHT anti-entropy repair pipeline — CRC32 digest exchange between replicas, automatic data migration on mismatch
- DHT re-replication on node departure — DHTRebalancer pushes partition data to new replicas when a node leaves
- Per-use-case DHT config via `DHTClient.scoped(DHTConfig)` — artifact storage (RF=3) and cache (RF=1) use independent configs
- SliceId auto-injection into ProvisioningContext for resource lifecycle tracking
- Scheduled task infrastructure — `ScheduledTaskRegistry`, `ScheduledTaskManager`, `CronExpression` parser, KV-Store types (`ScheduledTaskKey`, `ScheduledTaskValue`), deployment lifecycle wiring, management API (`GET /api/scheduled-tasks`), CLI subcommand, 29 unit tests
- 67 new unit tests: DHTNode (12), DistributedDHTClient (19), DHTAntiEntropy (10), DHTRebalancer (8), ArtifactStore (9), DHTCacheBackend (3), pub-sub (18: TopicSubscriptionRegistry 10, TopicPublisher 4, PublisherFactory 4)
- Blueprint membership guard on `POST /api/scale` — rejects scaling slices not deployed via blueprint
- Blueprint `minInstances` as hard floor for scale-down — enforced in auto-scaler, manual `/api/scale`, and rolling updates
- Pub-sub messaging infrastructure and resource lifecycle management (RFC-0011) — `Publisher<T>`, `Subscriber<T>`, `TopicSubscriptionRegistry`, `TopicPublisher`, `PublisherFactory`
- Pub-sub code generation in slice-processor — subscription metadata in manifest, `stop()` resource cleanup, envelope v2
- RFC-0010 Unified Invocation Observability (supersedes RFC-0009)
- Envelope format versioning for slice JARs — `ENVELOPE_FORMAT_VERSION` in ManifestGenerator, runtime compatibility check in SliceManifest
- Properties manifest (`META-INF/slice/*.manifest`) now included in per-slice JARs for full metadata at runtime
- JaCoCo coverage infrastructure across 6 aether modules (427 tests)
- Cluster event aggregator — `/api/events` REST endpoint (with `since` filter), `/ws/events` WebSocket feed (delta broadcasting), CLI `events` command. 11 event types collected into ring buffer (1000 events)

### Fixed
- ProvisioningContext sliceId propagation — resource lifecycle tracking now works correctly for consumer reference counting
- UNLOADING stuck state — CDM `reconcile()` now calls `cleanupOrphanedSliceEntries()`, NDM `handleUnloadFailure()` properly chains Promise
- Rolling update UNLOADING stuck state and missing SliceTargetKey creation
- Monotonic sequencing on `QuorumStateNotification` to prevent race condition during leader failover
- Slice JAR manifest repackaging for rolling update version mismatch
- JBCT compliance fixes for HttpClient JSON API
- Fast-path route eviction on node departure
- 20K/50K/100K rate buttons on Forge dashboard

### Enabled
- 5 previously disabled E2E tests: partition healing, quorum transitions, artifact failover survival, rolling update completion, rolling update rollback

### Changed
- **BREAKING:** Removed individual slice `POST /api/deploy` and `POST /api/undeploy` endpoints — use blueprint commands instead
- **BREAKING:** Removed `deploy` and `undeploy` CLI commands — use `blueprint apply` and `blueprint delete`

### Removed
- Individual slice deploy/undeploy from REST API, CLI, and Forge proxy
- `handleSliceTargetRemoval` from ClusterDeploymentManager (unreachable after deploy/undeploy removal)

## [0.16.0] - 2026-02-18

### Added
- `aether/resource/` module group consolidating all infrastructure resources
- `MethodInterceptor` interface in slice-api for per-method concerns (retry, circuit breaker, rate limit, logging, metrics)
- `ProvisioningContext` in slice-api for passing type tokens and key extractors to resource factories
- 5 interceptor `ResourceFactory` implementations in `resource-interceptors` module
- `integrations/statemachine` module (relocated from infra-statemachine)

### Fixed

### Changed
- **BREAKING:** Renamed packages `org.pragmatica.aether.infra.*` → `org.pragmatica.aether.resource.*`
- **BREAKING:** Resources no longer implement `Slice` interface — `DatabaseConnector`, `HttpClient`, `ConfigService` etc. are pure resource types
- Consolidated 10 infra-slices + infra-api + infra-services into 8 resource modules (api, db-jdbc, db-r2dbc, db-jooq, db-jooq-r2dbc, http, interceptors, services)
- Flattened db-connector hierarchy: `infra-db-connector/{api,jdbc,r2dbc,...}` → `resource/{api,db-jdbc,db-r2dbc,...}`

### Removed
- `aether/infra-api/` — merged into `resource/api`
- `aether/infra-slices/` — 10 modules dropped or relocated:
  - `infra-aspect` (unused JDK proxy factories; config types preserved in resource/api)
  - `infra-database` (toy in-memory SQL, superseded by db-connector)
  - `infra-scheduler` (thin JDK wrapper)
  - `infra-ratelimit` (duplicated core/RateLimiter)
  - `infra-lock` (in-memory only, no distributed backend)
  - `infra-pubsub` (in-memory only, no distributed backend)
- `aether/infra-services/` — merged into `resource/services`

## [0.15.1] - 2026-02-12

### Added
- ClusterEventAggregator for structured event collection (topology, leader, quorum, deployment events)
- MetricsCollector invocation metrics in cluster-wide gossip
- MetricsCollector topology change handlers for departed node cleanup

### Fixed
- SliceStore.unloadSlice() stuck in UNLOADING state when slice loading had previously failed
- Shared dependency loading fails for runtime-provided libraries (e.g. `core` embedded in shaded JAR)
- Orphaned SliceNodeKey entries not cleaned up after undeploy during leader change
- E2E multi-instance deployment test used hardcoded instance count instead of cluster size
- E2E BeforeEach cleanup now retries undeploy to handle leader changes during teardown
- Pre-populate DHT ring with known peers and harden distributed operations
- Distributed DHT wiring — DistributedDHTClient replaces LocalDHTClient for cross-node artifact resolution

### Changed
- Disabled TTM E2E tests (trivial checks not worth 90-minute 5-node cluster overhead)

## [0.15.0] - 2026-02-02

### Added
- Monorepo consolidation of three projects:
  - pragmatica-lite (v0.11.3) - Core functional library
  - jbct-cli (v0.6.1) - CLI and Maven plugin for JBCT formatting/linting
  - aetherx (v0.8.2) - Distributed runtime
- Unified version management across all modules
- Consolidated documentation structure
- Moved `cluster` module from aether to integrations (generic distributed networking)
- AppHttpServer immediate retry on node departure (no more 5-second timeout wait)
- Production tinylog configuration for aether/node
- Tinylog format now includes thread name: `[{thread}]`
- Request ID logging for critical log statements in AppHttpServer and SliceInvoker
- Blueprint CLI commands: `list`, `get`, `delete`, `status`, `validate` (also in REPL)
- Blueprint REST API endpoints: GET/DELETE `/api/blueprint/{id}`, GET `/api/blueprints`,
  GET `/api/blueprint/{id}/status`, POST `/api/blueprint/validate`
- Consolidated startup banner showing node configuration (ID, ports, peers, TTM, TLS)

### Changed
- All modules now use version 0.15.0
- Root POM provides dependency management for entire ecosystem
- Unified CI workflows at monorepo root
- E2E and Forge tests moved to `-Pwith-e2e` profile (require examples to be installed first)
- Standardized tinylog configurations across all modules (24 files)
- Added Fury and Netty logging suppression to all test configs
- Comprehensive logging level overhaul in aether module:
  - Hot paths (SliceInvoker, InvocationHandler, AppHttpServer) moved to DEBUG/TRACE
  - Routine operations (deployment, slice lifecycle) moved to DEBUG
  - Important events (leader/quorum changes, rolling updates) kept at INFO
  - Production logs are now scannable and concise

### Technical Notes
- Group IDs preserved for Maven Central compatibility:
  - `org.pragmatica-lite` for core, integrations, jbct modules
  - `org.pragmatica-lite.aether` for aether modules
- Build: `mvn install -DskipTests` (bootstraps jbct-maven-plugin automatically)
- E2E tests: `mvn verify -Pwith-e2e -pl aether/e2e-tests,aether/forge/forge-tests`

---

## Pre-Monorepo History

Historical changelogs for individual projects:

- [Pragmatica Lite CHANGELOG](core/CHANGELOG.md)
- [JBCT CHANGELOG](jbct/CHANGELOG.md)
