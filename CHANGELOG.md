# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.17.0] - Unreleased

### Added
- DHT anti-entropy repair pipeline — CRC32 digest exchange between replicas, automatic data migration on mismatch
- DHT re-replication on node departure — DHTRebalancer pushes partition data to new replicas when a node leaves
- Per-use-case DHT config via `DHTClient.scoped(DHTConfig)` — artifact storage (RF=3) and cache (RF=1) use independent configs
- SliceId auto-injection into ProvisioningContext for resource lifecycle tracking
- 67 new unit tests: DHTNode (12), DistributedDHTClient (19), DHTAntiEntropy (10), DHTRebalancer (8), ArtifactStore (9), DHTCacheBackend (3), pub-sub (18: TopicSubscriptionRegistry 10, TopicPublisher 4, PublisherFactory 4)
- Blueprint membership guard on `POST /api/scale` — rejects scaling slices not deployed via blueprint
- Blueprint `minInstances` as hard floor for scale-down — enforced in auto-scaler, manual `/api/scale`, and rolling updates
- Pub-sub messaging infrastructure and resource lifecycle management (RFC-0011) — `Publisher<T>`, `Subscriber<T>`, `TopicSubscriptionRegistry`, `TopicPublisher`, `PublisherFactory`
- Pub-sub code generation in slice-processor — subscription metadata in manifest, `stop()` resource cleanup, envelope v2
- RFC-0010 Unified Invocation Observability (supersedes RFC-0009)
- Envelope format versioning for slice JARs — `ENVELOPE_FORMAT_VERSION` in ManifestGenerator, runtime compatibility check in SliceManifest
- Properties manifest (`META-INF/slice/*.manifest`) now included in per-slice JARs for full metadata at runtime
- JaCoCo coverage infrastructure across 6 aether modules (427 tests)

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
