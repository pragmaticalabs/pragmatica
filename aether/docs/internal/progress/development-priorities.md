# Development Priorities

## Current Status (v0.20.0)

Release 0.18.0 delivered six major themes: unified invocation observability (RFC-0010), production-grade DHT (anti-entropy repair, re-replication, per-use-case config), pub-sub messaging infrastructure (RFC-0011), blueprint-only deployment model, node lifecycle with disruption budget and graceful drain, and pricing-engine multi-slice example with cross-slice invocations.

## Completed ✅

### DHT & Data (v0.18.0)
- **DHT Anti-Entropy Repair** - CRC32 digest exchange between replicas, automatic data migration on mismatch
- **DHT Re-Replication** - DHTRebalancer pushes partition data to new replicas when a node departs
- **Per-Use-Case DHT Config** - `DHTClient.scoped(DHTConfig)` — artifact storage (RF=3) and cache (RF=1) use independent configs
- **Distributed Hash Table** - Consistent hash ring (150 vnodes, 1024 partitions), quorum R/W, topology-aware

### Pub-Sub Messaging (v0.18.0, RFC-0011)
- **Publisher/Subscriber API** - `Publisher<T>` functional interface, `Subscriber` marker, `@Subscription` annotation
- **Topic Subscription Registry** - KV-Store backed subscriber discovery with competing consumers (round-robin)
- **Message Delivery** - TopicPublisher fans out via SliceInvoker, PublisherFactory registered as SPI
- **Resource Lifecycle** - Reference-counted `releaseAll()`, generated `stop()` cleanup, SliceId auto-injected into ProvisioningContext
- **Pub-Sub Code Generation** - Subscription metadata in manifest, envelope v2

### Scheduled Invocation (v0.18.0)
- **Scheduled.java marker interface** - `@ResourceQualifier(type=Scheduled.class)` on zero-arg `Promise<Unit>` methods
- **Interval and cron scheduling** - Fixed-rate (`"5m"`, `"30s"`) and 5-field cron (`"0 0 * * *"`) modes
- **Leader-only and all-node execution** - Quorum-gated timer lifecycle
- **KV-Store backed registry** - Runtime reconfiguration via Management API
- **Full stack** - Annotation processor, manifest generation (envelope v3), deployment wiring, CronExpression parser, ScheduledTaskRegistry, ScheduledTaskManager, REST API, CLI, 29 unit tests

### Security & Health Probes (v0.18.0)
- **Readiness vs Liveness Probes** — `/health/live` (always 200) and `/health/ready` (200/503 with component checks: consensus, routes, quorum). Container orchestrator compatible. App HTTP `/health` endpoint also added
- **RBAC for Management API** (Tier 1 — API key authentication, audit logging) — API key auth for management server, app HTTP server, and WebSocket connections. Per-key names/roles via TOML config or env. SHA-256 key hashing. Audit logging via dedicated logger. CLI `--api-key` / `-k` flag. `InvocationContext` principal propagation via ScopedValues + MDC

### Node Lifecycle & Reliability (v0.18.0)
- **Node Lifecycle State Machine** — JOINING → ON_DUTY ↔ DRAINING → DECOMMISSIONED → SHUTTING_DOWN with self-registration on quorum, remote shutdown via KV watch, lifecycle key cleanup on departure
- **Disruption Budget** — `minAvailable` per slice in blueprint, enforced in scale-down and drain eviction
- **Graceful Node Drain** — CDM eviction orchestration respecting disruption budget, cancel drain support, automatic DECOMMISSIONED on eviction complete
- **Management API** — `GET /api/nodes/lifecycle`, `GET /api/node/lifecycle/{nodeId}`, `POST /api/node/drain/{nodeId}`, `POST /api/node/activate/{nodeId}`, `POST /api/node/shutdown/{nodeId}`
- **CLI** — `node lifecycle`, `node drain`, `node activate`, `node shutdown`

### Compile-Time Serde (v0.19.x)
- **Compile-Time Codec Generation** — `@Codec` annotation processor (`codec-processor`) generates `*Codec` classes for records, enums, and sealed interfaces at compile time. `SliceCodec` wire format with tag-based dispatch (deterministic hash tags, VLQ encoding), zero runtime reflection. Replaces Fory/Kryo for slice boundary serialization. Modules: `integrations/serialization/api`, `integrations/serialization/codec-processor`, `integrations/serialization/benchmark`

### Log Level Management UI (v0.19.x)
- **Dashboard UI + API** — Per-package log level controls in dashboard. Current effective levels display. Backend: `/api/logging/levels` endpoints with cluster-wide KV-store consensus sync

### Forge Scaffold Generation (v0.19.x)
- **Auto-generated forge config** — `slice-processor` auto-generates `forge.toml` and `run-forge.sh` alongside `blueprint.toml`. Derives node count default, DB enabled flag (from `@Sql` presence), and test curl commands (from `routes.toml`). Eliminates manual boilerplate for new examples

### Async Postgres Driver (v0.19.x)
- **Full async PostgreSQL module** — Resurrected native async driver from pragmatica-lite. Built on Netty — non-blocking, zero-copy, no reactor overhead. 14 test classes, production-ready. Eliminates HikariCP thread pool and reactor-core from the hot path. Available as alternative `SqlConnector` SPI implementation bypassing both JDBC and R2DBC

### Core Infrastructure
- **Request ID Propagation** - ScopedValue-based context with ServiceLoader propagation hook in Promise
- **SliceInvoker Immediate Retry** - Event-driven retry on node departure (matches AppHttpServer pattern)
- **Local-First Invocation Routing** - SliceInvoker bypasses network for slices co-located on the same node
- **Structured Keys** - KV schema foundation
- **Consensus Integration** - Distributed operations working
- **ClusterDeploymentManager** - Cluster orchestration
- **EndpointRegistry** - Service discovery with weighted routing
- **NodeDeploymentManager** - Node-level slice management
- **HTTP Router** - External request routing with route self-registration
- **Management API** - Complete cluster control endpoints (60+ endpoints across 12 route classes)
- **CLI** - REPL and batch modes with full command coverage
- **Automatic Route Cleanup** - Routes removed on last slice instance deactivation
- **HTTP Keep-Alive** - Connection reuse in Netty server; load generators pinned to HTTP/1.1
- **LoadBalancerProvider SPI** - Provider-agnostic interface with Hetzner L4 implementation and LoadBalancerManager

### Observability & Control
- **Metrics Collection** - Per-node CPU/JVM metrics at 1-second intervals
- **Cluster-Wide Metrics Broadcast** - MetricsPing carries full cluster snapshot; every node has complete picture
- **Invocation Metrics** - Per-method call tracking with percentiles
- **Prometheus Endpoint** - Standard metrics export format
- **Alert Thresholds** - Persistent threshold configuration via consensus
- **Secrets Resolution** - ${secrets:...} placeholder resolution in ConfigurationProvider via EnvironmentIntegration SPI
- **Controller Configuration** - Runtime-configurable scaling thresholds
- **Decision Tree Controller** - Programmatic scaling rules
- **TTM Predictive Scaling** - ONNX-based traffic prediction and scaling recommendations
- **DeploymentMap** - Event-driven slice-to-node index with cluster-wide deployment visibility
- **EMA Latency Smoothing** - 5-second effective window for stable dashboard metrics
- **Zero-Loss Forwarding** - Fresh re-routing on retry, proactive disconnection detection, and backoff retry with delayed re-query for route table healing during node transitions
- **Fast-Path Route Eviction** - Immediate local cache eviction from `HttpRouteRegistry` on node departure, eliminating ~5s window of failed forwards during rolling restarts
- **Unified Invocation Observability (RFC-0010)** - Sampling-based tracing with depth-to-SLF4J bridge, adaptive per-node sampling (`AdaptiveSampler`), `ObservabilityInterceptor` wired into local and remote invocation paths, `InvocationTraceStore` ring buffer (50K traces), `ObservabilityDepthRegistry` with KV-store consensus sync, REST API (`/api/traces`, `/api/observability/depth`), CLI commands (`traces`, `observability`). Supersedes DynamicAspect system.
- **Dynamic Configuration** - Runtime config updates via consensus KV store with REST API. Config overlay pattern: base config from TOML/env/system properties, overrides from KV store. Cluster-wide and per-node scoped keys. No restart required.
- **Logging Overhaul (tinylog → Log4j2)** - Production-grade structured logging: request ID auto-injected via SLF4J MDC (`[rid=%X{requestId}]`), optional JSON output via `-Dlog4j2.appender=JsonConsole`, runtime log level management via Management API (`/api/logging/levels`) with cluster-wide KV-store consensus sync, noise reduction (Fury→error, Netty→warn, H2→error), shared `test-logging` module replacing 47 per-module tinylog configs. Unblocks structured logging in RFC-0010 (SLF4J bridge).

### Dashboard & Real-Time
- **WebSocket Push** - Zero-polling dashboard via `/ws/status` with polling fallback
- **Dashboard Deployments** - Cluster-wide deployment data (artifact, state, per-instance status)
- **Forge WebSocket** - StatusWebSocketHandler/Publisher for Forge dashboard
- **Node WebSocket** - DashboardWebSocketHandler/Publisher for management dashboard
- **Production Logging** - H2 SQL suppression, debug overrides removed from fat jars
- **Rolling Restart State Sync** - Button state preserved across tab switches in Forge dashboard

### Deployment Features
- **Rolling Updates** - Two-stage deploy/route model
- **Weighted Routing** - Traffic distribution during updates
- **Blueprint Parser** - Standard TOML format
- **Docker Infrastructure** - Separate images for node and forge
- **Blueprint-Only Deployment** - Removed individual slice deploy/undeploy to enforce dependency validation. Scale guarded by blueprint membership. Eliminates the correctness gap where undeploying a dependency could orphan active slices.

### Passive Worker Pools Phase 1 Completion (v0.19.3)
- **SWIM Core-to-Core Health Detection (P1.13)** — `CoreSwimHealthDetector` bridges SWIM membership events to `TopologyChangeNotification.nodeRemoved`. TCP disconnect decoupled from topology — no longer fires `RemoveNode`, only triggers reconnection. SWIM port = cluster port + 1. Config: period=500ms, probeTimeout=300ms, K=3, suspectTimeout=3s. Detection latency: 1-2s (vs 15s-2min with TCP)
- **Automatic Topology Growth (P1.14)** — CDM dynamically assigns core vs worker role to joining non-seed nodes. `RabiaEngine` activation gating: seed nodes auto-activate on quorum, non-seed nodes wait for CDM `ActivateConsensus` signal. `TopologyConfig` extended with `coreMax`/`coreMin`. New `TopologyGrowthMessage` sealed interface (`ActivateConsensus`, `AssignWorkerRole`). Management API: `GET /api/cluster/topology`. CLI: `aether topology status`

### Inter-Node TLS & Certificate Management (v0.19.3)
- **CertificateProvider SPI** — narrow interface for certificate issuance, CA access, gossip key management
- **SelfSignedCertificateProvider** — BouncyCastle EC P-256, HKDF deterministic CA from shared `clusterSecret`. All nodes with same secret produce identical CA certificate
- **mTLS for all TCP** — consensus, DHT, management API, app HTTP. `TlsConfig.fromProvider()` bridge produces `Mutual` config from `CertificateProvider`
- **SWIM gossip encryption** — AES-256-GCM symmetric encryption. Wire format: `[4B keyId][12B nonce][ciphertext+16B GCM tag]`. Dual-key support for seamless rotation
- **Certificate lifecycle** — `CertificateRenewalScheduler` renews at 50% of validity (3.5 days for 7-day certs). Gossip key rotation via consensus KV store (`GossipKeyRotationKey`/`Value`)
- **TLS by default** — DOCKER and KUBERNETES environments enable TLS automatically. `clusterSecret` via TOML `[tls]` section or `AETHER_CLUSTER_SECRET` env var
- **Config integration** — `ConfigLoader` parses `[tls]` section with `auto_generate`, cert paths, cluster secret. `Main.java` bootstrap: resolves secret → creates provider → wires mTLS + gossip encryption
- **Tests** — SelfSignedCertificateProviderTest (8 tests), AesGcmGossipEncryptorTest (10 tests), TlsConfig fromProvider bridge (4 tests). E2E: all existing tests now run with mTLS active (67 pass)

### KV-Store Durable Backup & Recovery (v0.19.3)
- **TOML Writer** — serialization support added to custom TOML library (inline table parsing + writing)
- **KV-Store TOML Serializer** — converts all 18 AetherKey/AetherValue types to/from TOML with pipe-delimited values grouped by key-type sections
- **Git-backed persistence** — `GitBackedPersistence` implements `RabiaPersistence` using git CLI via ProcessBuilder. Atomic snapshots with commit history
- **RabiaEngine persistence parameter** — persistence is now a constructor parameter (was hardcoded `inMemory()`). Backward-compatible: existing constructors default to in-memory
- **Backup configuration** — `BackupConfig` record with enabled/interval/path/remote fields, environment-aware defaults (LOCAL: `./aether-backups`, DOCKER: `/data/backups`, K8S: `/var/aether/backups`). `[backup]` TOML section in ConfigLoader
- **BackupService interface** — `backupNow()`, `listBackups()`, `restore(commitId)` with sealed `BackupError` cause types and `disabled()` no-op factory
- **Management API** — `POST /api/backup`, `GET /api/backups`, `POST /api/backup/restore` via `BackupRoutes`
- **CLI commands** — `aether backup trigger`, `aether backup list`, `aether backup restore <commit>`
- **Documentation** — operational runbook, API reference, CLI reference, configuration reference, feature catalog updated

### Container, Install/Upgrade, Rolling Upgrade (v0.19.3)
- **Official Container Image Publishing** — Dockerfiles use build-arg `VERSION` for OCI labels, `release.yml` builds multi-arch (amd64+arm64) images via buildx, publishes to GHCR and Docker Hub, generates SHA256 checksums for all release artifacts
- **Install/Upgrade Scripts** — `aether/install.sh` enhanced with `--version` flag, SHA256 checksum verification, WSL2 detection. New `aether/upgrade.sh` with version detection, atomic binary swap (backup → move → cleanup), running process warning. Root `install.sh` fixed to reference `main` branch
- **Rolling Cluster Upgrade** — `aether/script/rolling-aether-upgrade.sh` API-driven script: discovers nodes via `/api/nodes/lifecycle`, drains → shuts down → waits for restart → activates → canary checks each node one at a time. Supports `--dry-run`, `--canary-wait`, `--api-key`, `--skip-download`. Mixed-version clusters safe via envelope versioning

### Slice Lifecycle (v0.8.0)
- **start()/stop() Timeouts** - Configurable via `SliceActionConfig.startStopTimeout`
- **Eager Dependency Validation** - Dependencies verified during ACTIVATING before start()
- **SliceLoadingContext** - Deferred handle materialization pattern
- **Lifecycle Hook Documentation** - Complete semantics documented

### Infrastructure Services (infra-slices)
- **Cache** - `infra-cache` in-memory implementation
- **Pub/Sub** - `infra-pubsub` topic-based messaging (in-memory)
- **State Machine** - `infra-statemachine` for local state transitions
- **Artifact Repository** - Maven protocol subset, deploy/resolve operations
- **Database** - `infra-database` abstraction
- **Rate Limiter** - `infra-ratelimit` for throttling (in-memory)
- **Distributed Lock** - `infra-lock` for coordination (in-memory)
- **Scheduler** - `infra-scheduler` for timed tasks (in-memory)
- **Config** - `infra-config` configuration management (in-memory)
- **HTTP Client** - `infra-http` HTTP client wrapper
- **Aspects** - `infra-aspect` decorators/cross-cutting concerns

### Examples & Testing
- **Order Domain Demo** - 5-slice order domain example
- **URL Shortener Demo** - E2E slice invocation with HTTP, inter-slice calls, and CacheService
- **Aether Forge** - Local development environment with dashboard
- **Comprehensive Forge Test Suite** - Tests use InventoryService (no slice dependencies)
- **Docker E2E Infrastructure** - Hostname-based peer resolution, local Maven repo fallback for artifact resolution, CI timeout adaptation, leader election stabilization in containers

### Documentation
- **CLI Reference** - Complete command documentation
- **Management API** - Full HTTP API reference
- **Runbooks** - Deployment, scaling, troubleshooting
- **Developer Guides** - Slice development, migration
- **Slice Lifecycle** - Hook semantics, materialization, execution order

### Resource Provisioning & Infra Modules Overhaul
- **Unified `@ResourceQualifier(type, config)` pattern** — injected dependencies (database, HTTP client) AND behavioral wrappers (cache, rate limit, retry, circuit breaker, log, metrics). No separate "aspect" category.
- **Annotation processor** — `ResourceQualifierModel` detection + `FactoryClassGenerator` code generation. Parameter annotation = inject resource, method annotation = wrap method.
- **Core SPI** — `ResourceFactory`, `ResourceProvider`, `SpiResourceProvider` with ServiceLoader discovery, priority-based factory selection, instance caching
- **SliceCreationContext** — unified invoker + resource facades with `ProvisioningContext` (type tokens, key extractors)
- **Pre-made qualifiers** — `@Sql` (database), `@Http` (HTTP client)
- **Interceptors** — cache (in-memory + DHT + tiered), circuit breaker, retry, rate-limit, logging, metrics
- **HttpClient JSON API** — typed request/response with `JsonConfig`, `HttpClientError`, default headers, zero Jackson leakage

### DB Connector Infrastructure
- **JDBC** — HikariCP-based pooling, full SQL API (`queryOne`, `queryOptional`, `queryList`, `update`, `batch`, `transactional`)
- **R2DBC** — Reactive/async SQL with connection pooling
- **jOOQ sync** — Type-safe queries via JDBC-backed jOOQ
- **jOOQ async** — Type-safe queries via R2DBC-backed jOOQ
- **postgres-async native** — Direct async SQL via postgres-async driver (zero adapter overhead), LISTEN/NOTIFY support
- **postgres-async R2DBC adapter** — R2DBC SPI wrapper enabling jOOQ async via postgres-async transport
- **`DatabaseConnectorConfig`** — Rich config with JDBC/R2DBC/async URL overrides, pool settings, properties
- **`DatabaseConnectorError`** — Sealed error types, `@Sql` qualifier annotation

---

## Next Up

### TOP PRIORITY - Cleanup

0. **Eliminate Raw JDK HTTP Client Usage**
   - All HTTP operations must go through `integrations/net/http-client` (`HttpOperations` / `JdkHttpOperations`)
   - Raw `java.net.http.HttpClient` instantiation and `.send()` calls are forbidden outside the integration module
   - **Main code:** `AetherCli.java`, `RemoteRepository.java`, `AlertForwarder.java`
   - **Test code:** `CloudNode.java`, `ConfigurableLoadRunner.java`, `ForgeTestBase.java`, `ChaosTest.java`
   - **JBCT tools:** `JarInstaller.java`, `GitHubReleaseChecker.java`, `GitHubVersionResolver.java`
   - **Already migrated:** `AetherNodeContainer.java` (E2E tests)
   - **Why:** Raw usage leaks null exception messages, duplicates error handling, and creates inconsistent failure modes across the codebase. Single integration point ensures typed errors (`HttpError`), consistent timeouts, and uniform retry behavior.

### HIGH PRIORITY - Core & Operations

1. **Cloud Integration**
   - Implement `NodeLifecycleManager.executeAction(NodeAction)`
   - Cloud provider adapters: Hetzner (primary), AWS, GCP, Azure
   - Execute `StartNode`, `StopNode`, `MigrateSlices` decisions from controller
   - Node auto-discovery and registration
   - Node pool support: core (on-demand) vs elastic (spot) pools
   - Instance type parameter for spot/on-demand selection
   - **Secrets management:** HashiCorp Vault integration, AWS Secrets Manager / Azure Key Vault support (basic `${secrets:...}` resolution already works via ConfigurationProvider)
   - **Cloud Load Balancer Configuration:**
     - Automatic target group registration/deregistration on node join/leave
     - Health check endpoint configuration (path, interval, thresholds)
     - AWS ALB/NLB, GCP Cloud Load Balancing, Azure Load Balancer adapters
     - Weighted routing sync: Aether rolling update weights → cloud LB weights
     - Drain connections before node removal (graceful deregistration delay)
     - TLS termination configuration (certificate ARN/ID passthrough)
   - **Partially complete:** LoadBalancerProvider SPI + Hetzner L4 done, ComputeProvider SPI + Hetzner done
   - **Enables:** Expense Tracking in FUTURE section

2. **Per-Data-Source DB Schema Management** — [design spec](schema-management-design.md)
    - Cluster-level schema migration managed by Aether runtime, not individual nodes
    - Per-datasource lifecycle with independent history tables (`aether_schema_history`)
    - Leader-driven execution via Rabia consensus; exactly one node runs migrations
    - TOML-declared schema versions in service descriptors; versioned SQL scripts (`V001__*.sql`)
    - Readiness gate: traffic only routed after all datasources reach declared version
    - Expand-contract support for zero-downtime schema changes
    - Lightweight `AetherSchemaManager` — plain JDBC, no Flyway/Liquibase dependency

### Cloud Provider Support

Part of Cloud Integration (#1). Per-provider status:

| Provider | Tier | Compute | Load Balancer | Discovery | Secrets | Status |
|----------|------|---------|---------------|-----------|---------|--------|
| Hetzner | 2 | ComputeProvider done | LoadBalancerProvider done | Label-based (planned) | — | **Partial** |
| AWS | 1 | Planned | ALB/NLB | Tag-based | Secrets Manager | Planned |
| GCP | 1 | Planned | Cloud LB | Label-based | Secret Manager | Planned |
| Azure | 1 | Planned | Azure LB | Tag-based | Key Vault | Planned |
| DigitalOcean | 2 | Planned | DO LB | Tag-based | — | Planned |
| Vultr | 2 | Planned | Vultr LB | Tag-based | — | Planned |

**Cloud Testing Milestones** (initial provider: Hetzner):
- Cloud cluster formation — code committed (`545300ce`), **not yet executed against real infrastructure**
- Load balancer integration — create LB, add servers as targets, verify traffic distribution
- Node failure + re-election — kill servers, verify quorum maintained and new leader elected
- Network partition — manipulate firewall rules to block traffic between nodes
- Slice deployment — deploy via management API, verify ACTIVE on all nodes
- Pre-baked images — provider snapshot with Java + JAR for instant boot (~30s vs ~3min)
- Multi-region cluster — test consensus over higher-latency links
- Cloud-native discovery — label/tag-based peer discovery (eliminates static peer list)
- Disaster recovery — terminate majority, bring up replacements, verify state recovery via Rabia sync

### MEDIUM PRIORITY - Developer Tooling & Deployment

3. **Distributed Scheduler Resource**
    - Distributed task scheduling as a `@ResourceQualifier` resource for user slices
    - Builds on existing `ScheduledTaskManager`/`ScheduledTaskRegistry` (internal Aether scheduling, v0.18.0)
    - `infra-scheduler` currently in-memory only — needs distributed coordination via KV-Store consensus
    - **API shape:** `@Scheduled` resource qualifier with interval/cron config, distributed locking to prevent duplicate execution
    - **Execution modes:** single-node (leader-elected), all-nodes, per-community (worker pools)
    - **Persistence:** durable task state in KV-Store — survives leader failover and node restarts
    - **Observability:** task execution history, next-fire tracking, failure counts, dead letter integration (#7)
    - **Management:** REST API for task listing, pause/resume, manual trigger; CLI commands

4. **Notification Resource**
    - Unified notification facade with pluggable backends via SPI (same `@ResourceQualifier` pattern)
    - **Channels:** Email, SMS, push notifications
    - **Email backends (SPI):** SMTP, AWS SES, SendGrid, Mailgun
    - **SMS backends (SPI):** Twilio, AWS SNS
    - **Push backends (SPI):** Firebase Cloud Messaging, Apple Push Notification Service
    - **API shape:** `NotificationSender` resource type with channel-specific configuration
    - **Config:** per-backend TOML section (`[notifications.smtp]`, `[notifications.ses]`, `[notifications.twilio]`, etc.)
    - **Scope exclusions:** no template engine (slices own their content), no mailing list management
    - **Depends on:** Cloud Integration (#1) for SES/SNS/cloud-based backends; SMTP backend standalone

5. **Canary & Blue-Green Deployment Strategies**
     - Current: Rolling updates with weighted routing exist
     - Add explicit canary deployment with automatic rollback on error threshold
     - Add blue-green deployment with instant switchover
     - A/B testing support with traffic splitting by criteria

6. **RBAC Tier 2 — Per-Endpoint Role Authorization**
     - Per-endpoint role-based authorization rules (admin, operator, viewer)
     - Route-level security policy from KV-Store
     - Auth failure rate limiting
     - Currently all authenticated keys have equivalent access; Tier 2 differentiates by role

7. **Dead Letter Handling**
    - Failed pub-sub messages and failed scheduled task invocations currently logged and lost
    - DLQ storage: KV-Store backed dead letter queue per topic/task
    - Retry policy: configurable max attempts with exponential backoff before dead-lettering
    - Inspection: Management API endpoints to list, inspect, replay, or purge dead letters
    - CLI: `aether dead-letters list`, `aether dead-letters replay <id>`

8. **Slice Development IDE Plugins**
    - IDE plugins for Aether slice development, providing deep integration with the JBCT toolchain
    - **Recommended approach:** build a shared **Language Server (LSP)** backend first, then thin IDE-specific clients. IntelliJ IDEA gets a native plugin for features that LSP cannot express (refactoring, inspections, run configs). VS Code, Eclipse, and NetBeans consume the LSP directly.

    **Core features (all IDEs via LSP):**
    - `routes.toml` support: syntax validation, auto-completion for method names (cross-referenced with `@Slice` interface), route conflict detection
    - Slice scaffolding: "New Aether Slice" action that runs `jbct add-slice` under the hood
    - JBCT format-on-save: trigger `jbct format` when saving `.java` files in JBCT projects (detected via `jbct.toml`)
    - JBCT lint diagnostics: inline warnings/errors from `jbct lint` as editor diagnostics
    - `aether.toml` / `forge.toml` schema validation and auto-completion
    - Blueprint preview: show resolved `blueprint.toml` content after annotation processing
    - Error mapping visualization: link `[errors]` glob patterns in `routes.toml` to matching `Cause` types

    **IntelliJ IDEA native plugin (highest priority):**
    - Gutter icons: navigate between `@Slice` interface methods and their `routes.toml` entries
    - Live templates: `slice`, `slicemethod`, `cause`, `resourcequalifier` — expand to JBCT-compliant boilerplate
    - Inspections: detect JBCT violations at edit time (nested error channels, missing factory method, wrong return type)
    - Run configurations: "Run Forge" with embedded console, "Deploy to Forge" one-click action
    - Project wizard: "New Aether Slice Project" that calls `jbct init` with UI for parameters
    - Annotation processor output viewer: inspect generated factory, routes, and manifest without digging in `target/`
    - Slice dependency graph: visualize inter-slice dependencies from manifests

    **VS Code extension (high priority):**
    - LSP client + TOML language support for routes/config files
    - Task integration: `jbct check`, `jbct format`, `run-forge.sh` as VS Code tasks
    - Snippets: JBCT patterns (slice, cause, request record, resource qualifier)
    - Debug configuration: attach to running Forge JVM

    **Eclipse plugin (lower priority):**
    - LSP client (Eclipse LSP4E)
    - Basic project facet for JBCT projects

    **NetBeans plugin (lowest priority):**
    - LSP client (NetBeans has built-in LSP support since 12.0)

    **Implementation phases:**
    1. LSP server (Kotlin or Java, runs as standalone process) — routes.toml support, JBCT diagnostics, TOML schemas
    2. VS Code extension (thin LSP client + tasks/snippets)
    3. IntelliJ native plugin (LSP + platform-specific features: inspections, gutter icons, run configs)
    4. Eclipse/NetBeans LSP clients (community-driven or on-demand)

    **Complexity:** Medium-high for LSP + IntelliJ; low for VS Code/Eclipse/NetBeans LSP clients
    **Prerequisite:** Stable JBCT CLI and annotation processor APIs

9. **Forge Modular Rework**
    - ~80% done: modules separated (`forge-simulator`, `forge-load`, `forge-cluster`), Ember works
    - **Remaining scope:**
      - Remote cluster support in load generator (target remote clusters, not just embedded)
      - Forge Script DSL: declarative scenario language for load/chaos test definitions, reusable scenario libraries, CI/CD integration
    - Three independently usable components:
      - **Ember** — single-process Aether runtime. Standalone for production migration, embedded for development
      - **Tester** — load generator + chaos testing + Forge Script DSL. Standalone for remote clusters, embedded for local
      - **Forge** — Ember + Tester + dashboard for local development convenience

### LOWER PRIORITY

10. **Configurable Rate Limiting per HTTP Route**
     - Per-route rate limiting configuration in blueprint or management API
     - Token bucket or sliding window algorithm
     - Configurable limits: requests/second, burst size
     - 429 Too Many Requests response with Retry-After header
     - Cluster-aware: distributed counters via consensus or per-node local limits
     - Note: `infra-ratelimit` exists for slice-internal use; this is for external HTTP routes

11. **Per-Blueprint Artifact Scoping (Tier 2)** — When artifact exclusivity (Tier 1) becomes too restrictive for multi-tenant clusters, add per-blueprint SliceTargetKey scoping. Changes: `SliceTargetKey(BlueprintId, ArtifactBase)`, CDM `Map<BlueprintId, Map<Artifact, Blueprint>>`, SliceNodeValue `owningBlueprint` field, WorkerSliceDirectiveKey blueprint scoping, Management API `blueprintId` parameter on `/api/scale`. Instance count = sum of all blueprints' allocations. Rolling update guard: reject if artifact has multiple blueprint owners. Prerequisite: Tier 1 (multi-blueprint correctness).

12. **Passive Worker Pools — Remaining Phases** — [design spec](../../specs/passive-worker-pools-spec.md)
    - Phases 1, 2a, 2b, 2b.5 complete in v0.19.3. Remaining work driven by real demand:
      - Phase 2c: Spot pool, spot-node exclusion from DHT ring
      - Phase 3: Multi-region, cross-region governors
    - **Architecture:** Small consensus core (5-7-9 active nodes) + self-organizing worker pools with elected governors. SWIM gossip for O(1) membership. Zone-aware grouping. Event-based community scaling.
    - **Research:** [10-system comparative analysis](../../internal/passive-worker-pool-research.md)

13. **Observability Dashboard UI**
   - Wire `ObservabilityDepthRegistry` data to dashboard with UI for configuring per-method depth thresholds
   - Backend REST API (`/api/observability/depth`) and KV-store sync already implemented
   - Current state is functional; production value but no customers yet

14. **Invocation Observability Dashboard Tab**
   - "Requests" tab: table view with timestamp, requestId, caller → callee, depth, duration, status
   - Click-to-expand tree view showing invocation depth with input/output at each level
   - Waterfall view for multi-hop request visualization
   - Filters: time range, slice, method, status, depth range
   - Auto-refresh via existing WebSocket push
   - See [RFC-0010](../../../../docs/rfc/RFC-0010-unified-invocation-observability.md) for data model and API
   - Backend complete (RFC-0010): REST API and trace store ready

### FUTURE

- **Official Installation Binaries** — Pre-built distribution archives (`aether-node`, `aether-cli`, `aether-forge`). Formats: `.tar.gz`, `.zip`, `.deb`, `.rpm`, Homebrew. Self-contained via jlink/jpackage. Java audience already has JDK — low priority.

- **Cluster Expense Tracking** — Real-time cost visibility for cluster operations. Cloud billing API integration (AWS Cost Explorer, GCP Billing, Azure Cost Management). Cost per node/slice/request. Spot savings tracking. Budget alerts. **Prerequisite:** Cloud Integration (#1)

- **LLM Integration (Layer 3)** — Claude/GPT API integration. Complex reasoning workflows. Multi-cloud decision support.

- **Mini-Kafka (Message Streaming)** — Ordered message streaming with partitions (differs from pub/sub). In-memory storage (initial). Consumer group coordination. Retention policies.

- **Cross-Slice Transaction Support (2PC)**
    - Distributed transactions via Transaction aspect
    - Scope: DB transactions + internal services (pub-sub, queues, streaming)
    - NOT Saga pattern (user-unfriendly compensation design)

    **Design Decisions:**
    | Aspect | Decision |
    |--------|----------|
    | Coordinator | Per-transaction (no bottleneck) |
    | Timeout | Auto-abort + orphan cleanup for coordinator disappearance |
    | Streaming | Queue pattern (bounded batch as transaction unit) |
    | Read-only participants | Skip prepare phase (hold locks until decision) |
    | Context propagation | Reuse existing requestId propagation mechanism |
    | Deadlock detection | Timeout-based (DAG-like call structure minimizes cycles) |
    | Connection pooling | Per-node pool (similar to monolith pattern) |

    **Open Questions:**
    - Transaction log location: consensus KV store for coordinator state?
    - Orphan recovery: query KV for state, abort if no decision recorded
    - Aspect API: annotation vs explicit scope (JBCT alignment TBD)
    - Nested semantics: savepoints vs flat "join existing"
    - Transaction ID format: `{originNode}-{timestamp}-{sequence}`

    **Guarantees:**
    - Aether's "each call eventually succeeds, if cluster is alive" applies
    - DB failure = transaction failure (expected behavior)

- **Multi-Region Federation** — Current architecture is single-region (Rabia all-to-all consensus requires low-latency links). Multi-region requires federation: independent Aether clusters per region, cross-region data sync (async replication, conflict resolution), global load balancing, region-aware client routing. Not a single-cluster stretch — fundamentally different architecture. Phase 2 adds zones within a region but not cross-region.

- **Distributed Saga Orchestration** — Long-running transaction orchestration (saga pattern). Durable state transitions with compensation on failure. Differs from local state machine — coordinates across multiple slices. Automatic retry, timeout, and dead-letter handling. Visualization of in-flight sagas and their states.

---

## Infra Development

All infrastructure modules transition to unified `@ResourceQualifier(type, config)` pattern. All resource types complete as of v0.18.0.

| Module | Target | Status |
|--------|--------|--------|
| infra-notifications | `@ResourceQualifier(type=NotificationSender.class)` | **Planned** — email + SMS + push (#8) |
| infra-config | Superseded by Dynamic Configuration via KV store | **Remove** |
| infra-aspect | Fn1 composition utilities (`Aspects.withCaching()`, `@Key`, etc.) | **Keep** |

**Dropped:** infra-server, infra-streaming, infra-outbox, infra-blob, infra-feature

---

## Deprecated

- **MCP Server** - Replaced by direct agent API (see [metrics-control.md](../../contributors/metrics-control.md))

---

## Tech Debt — Hardcoded Values

The following values are compile-time constants that should eventually be externalized to configuration (TOML or management API). Listed by module and priority.

**Should be configurable (operator-facing):**

| Location | Constant | Value | Notes |
|----------|----------|-------|-------|
| `AppHttpServer` | `MAX_CONTENT_LENGTH` | 16 MB | App HTTP request size limit |
| `ManagementServer` | `MAX_CONTENT_LENGTH` | 64 MB | Management API request size limit (artifact uploads) |
| `WebSocketAuthenticator` | `AUTH_TIMEOUT_MS` | 5s | WebSocket auth deadline |
| `DashboardMetricsPublisher` | `BROADCAST_INTERVAL_MS` | 1000 ms | WebSocket push frequency |
| `AlertManager` | `MAX_ALERT_HISTORY` | 100 | Alert history ring buffer |
| `ScalingConfig` | `DEFAULT_EVALUATION_INTERVAL_MS` | 5000 ms | Auto-scaler evaluation tick |
| `ScalingConfig` | `DEFAULT_WINDOW_SIZE` | 10 | Metric smoothing window |
| `ArtifactStore` | `CHUNK_SIZE` | 64 KB | DHT chunk size for artifact storage |
| `InvocationTraceStore` | `DEFAULT_CAPACITY` | 50,000 | Trace ring buffer size |
| `MetricsCollector` | `SLIDING_WINDOW_MS` | 2 hours | Metric retention window |

**Reasonable defaults (low priority):**

| Location | Constant | Value | Notes |
|----------|----------|-------|-------|
| `AppHttpServer` | `RETRY_DELAY_MS` | 200 ms | Forward retry backoff |
| `SliceInvoker` | `CLEANUP_INTERVAL_MS` | 60s | Stale invocation cleanup |
| `NodeDeploymentManager` | `MAX_LIFECYCLE_RETRIES` | 10 | Slice start/stop retries |
| `EventLoopMetricsCollector` | `PROBE_INTERVAL_MS` | 100 ms | Event loop lag probe |
| `RollingUpdateManager` | `TERMINAL_RETENTION_MS` | 1 hour | Completed update cleanup |
| `CronExpression` | `MAX_SEARCH_YEARS` | 4 | Cron next-fire search bound |
| `ForgeCluster` | `ROLLING_RESTART_DELAY_MS` | 5s | Forge rolling restart pace |

---

## Implementation Approach

Focus on stability and production readiness:

1. E2E tests prove all features work correctly
2. CLI must be reliable for human operators
3. Agent API must be well-documented
4. Decision tree must handle all common cases
5. Only then add LLM layer

See [metrics-control.md](../../contributors/metrics-control.md) for controller architecture.
