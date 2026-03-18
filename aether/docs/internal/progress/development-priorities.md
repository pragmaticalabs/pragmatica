# Development Priorities

## Current Status (v0.21.0)

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

### Distributed Scheduler Resource (v0.18.0–v0.20.0)
- **`@Scheduled` resource qualifier** — `@ResourceQualifier(type=Scheduled.class)` on zero-arg `Promise<Unit>` methods with interval + cron + weeks support
- **Execution modes** — `SINGLE` (leader-only) and `ALL` (every node with the slice fires independently) via `ExecutionMode` enum
- **KV-Store consensus integration** — pause state + execution state persisted, survives leader failover and restarts
- **Observability** — `ScheduledTaskStateRegistry` tracks last execution, next fire, consecutive failures, total executions
- **Management** — REST endpoints + CLI commands for pause, resume, trigger, state query
- **Full stack** — Annotation processor, manifest generation (envelope v3), deployment wiring, CronExpression parser, ScheduledTaskRegistry, ScheduledTaskManager, REST API, CLI

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

### Async Postgres Driver (v0.19.x–v0.20.0)
- **Full async PostgreSQL module** — Resurrected native async driver from pragmatica-lite. Built on Netty — non-blocking, zero-copy, no reactor overhead. 14 test classes, production-ready. Eliminates HikariCP thread pool and reactor-core from the hot path. Available as alternative `SqlConnector` SPI implementation bypassing both JDBC and R2DBC
- **Transaction-mode connection pooling** — PgBouncer-like multiplexing built into the driver. `PoolMode.TRANSACTION` multiplexes N logical connections over M physical connections (borrow per query/transaction, return on completion). `LogicalConnection` state machine (IDLE/ACTIVE/IN_TX/PINNED/CLOSED), prepared statement migration across physical backends, LISTEN/NOTIFY pinning, nested transactions via savepoints. `ReadyForQuery` now parses transaction status byte. Session mode remains default

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

### Blueprint Artifact Transition (v0.21.0)
- **Blueprint artifacts** — blueprints packaged as deployable JAR artifacts containing `blueprint.toml`, optional `resources.toml` (app-level config), and optional `schema/` directory (database migration scripts)
- **`PackageBlueprintMojo`** — new Maven plugin goal (`package-blueprint`) produces classifier `blueprint` JARs with `Blueprint-Id` and `Blueprint-Version` manifest entries
- **`publishFromArtifact`** — new deployment path via `POST /api/blueprint/deploy` or `aether blueprint deploy <coords>`
- **Config separation** — application config (`resources.toml`) at GLOBAL scope; infrastructure endpoints (`[endpoints.*]` in `aether.toml`) at NODE scope. ConfigService merges hierarchically (SLICE > NODE > GLOBAL)
- **Schema migration prep** — blueprint artifacts carry `schema/{datasource}/*.sql` migration scripts. Schema metadata stored in KV-Store for future execution
- **New KV types** — `BlueprintResourcesKey/Value`, `SchemaVersionKey/Value`, `SchemaMigrationLockKey/Value`
- **CLI commands** — `blueprint deploy <coords>` and `blueprint upload <file>`

### Cloud Integration (v0.21.0)
- **4-Provider Cloud Support** -- Hetzner, AWS, GCP, Azure with compute, load balancer, discovery, and secrets facets
- **CloudConfig + TOML `[cloud]` section** -- generic string-map config with `${env:VAR}` interpolation, provider-agnostic parsing in ConfigLoader
- **EnvironmentIntegrationFactory SPI** -- ServiceLoader-based provider selection; each provider module registers its factory
- **ComputeProvider** -- instance creation (with tagging, user data, network config), restart, termination, tag-based lookup for all 4 providers
- **LoadBalancerProvider** -- automatic target registration/deregistration on node join/leave; Hetzner LB, AWS ELBv2 target groups, GCP NEGs, Azure backend pools
- **DiscoveryProvider** -- tag/label-based peer discovery replacing static peer lists; configurable poll interval; all 4 providers
- **SecretsProvider** -- `${secrets:path}` resolution; Hetzner env vars (`AETHER_SECRET_*`), AWS Secrets Manager, GCP Secret Manager, Azure Key Vault (path: `vaultName/secretName`)
- **CachingSecretsProvider** -- in-memory TTL cache wrapping any secrets backend
- **NodeLifecycleManager.executeAction(NodeAction)** -- CDM executes StartNode/StopNode decisions via cloud provider
- **CDM terminate-on-drain** -- automatic cloud instance termination after graceful drain completes
- **loadBalancerInfo()** -- LB state query for all providers
- **Drain with deregistration** -- AWS `deregisterWithDrain` for connection draining before target removal
- **Reference docs** -- [Cloud Integration guide](../../reference/cloud-integration.md), [Configuration reference](../../reference/configuration.md)

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

### HIGH PRIORITY - Core & Operations

1. **Per-Data-Source DB Schema Management** — [design spec](schema-management-design.md)
    - Cluster-level schema migration managed by Aether runtime, not individual nodes
    - Per-datasource lifecycle with independent history tables (`aether_schema_history`)
    - Leader-driven execution via Rabia consensus; exactly one node runs migrations
    - TOML-declared schema versions in service descriptors; versioned SQL scripts (`V001__*.sql`)
    - Readiness gate: traffic only routed after all datasources reach declared version
    - Expand-contract support for zero-downtime schema changes
    - Lightweight `AetherSchemaManager` — plain JDBC, no Flyway/Liquibase dependency

2. **Canary & Blue-Green Deployment Strategies** — [design spec](../../specs/canary-blue-green-spec.md)
     - Current: Rolling updates with weighted routing exist
     - Add explicit canary deployment with automatic rollback on error threshold
     - Add blue-green deployment with instant switchover
     - A/B testing support with traffic splitting by criteria
     - **Spec complete:** 5-stage canary progression, atomic blue-green switchover, A/B split rules, KV types, REST API, CLI, TOML config, failure handling. 5 phases (18-26 days)

3. **Cloud Integration — Remaining Work (demand-driven)** — [SPI architecture spec](../../specs/cloud-integration-spi-spec.md)
   - **Core implementation complete** (v0.21.0): 4 providers (Hetzner, AWS, GCP, Azure) with compute, LB, discovery, secrets. See Completed section.
   - Spot pool support: core (on-demand) vs elastic (spot/preemptible) pools
   - LB provider overrides: health check config, weighted routing sync, TLS termination
   - HashiCorp Vault integration
   - Pre-baked images (provider snapshot for instant boot)
   - Cloud testing: node failure, network partition, multi-region, disaster recovery
   - DigitalOcean + Vultr providers (Tier 2)
   - **Enables:** Expense Tracking in FUTURE section

### Cloud Provider Support

Part of Cloud Integration (#3). Per-provider status:

| Provider | Tier | Compute | Load Balancer | Discovery | Secrets | Spec | Status |
|----------|------|---------|---------------|-----------|---------|------|--------|
| Hetzner | 2 | ComputeProvider (restart, tags, filtered list) | LoadBalancerProvider (L4 targets) | Label-based | EnvSecretsProvider (`AETHER_SECRET_*`) | [reference sheet](../../specs/cloud-integration-spi-spec.md#8-hetzner-provider-sheet-reference) | **Complete** |
| AWS | 1 | EC2 (create, terminate, restart, tags) | ELBv2 target groups (with drain) | Tag-based | Secrets Manager | [provider sheet](../../specs/cloud-provider-aws.md) | **Complete** |
| GCP | 1 | Compute Engine (create, delete, reset, labels) | NEG endpoints | Label-based | Secret Manager | [provider sheet](../../specs/cloud-provider-gcp.md) | **Complete** |
| Azure | 1 | VM (create, delete, restart, tags) | ARM LB backend pools | Resource Graph | Key Vault (`vaultName/secretName`) | [provider sheet](../../specs/cloud-provider-azure.md) | **Complete** |
| DigitalOcean | 2 | Planned | DO LB | Tag-based | — | [provider sheet](../../specs/cloud-provider-digitalocean.md) | Planned |
| Vultr | 2 | Planned | Vultr LB | Tag-based | — | — | Planned |

**Cloud Testing Milestones** (initial provider: Hetzner):
- ~~Cloud cluster formation~~ **Done (v0.21.0)** — code committed (`545300ce`), Hetzner integration complete: discovery, secrets, compute extensions
- ~~Cloud-native discovery~~ **Done (v0.21.0)** — HetznerDiscoveryProvider with AetherNode bootstrap wiring; AWS/GCP/Azure discovery providers added
- ~~Load balancer integration~~ **Done (v0.21.0)** — all 4 providers: target registration/deregistration, loadBalancerInfo(), LB reconciliation on leader change
- ~~Secrets management~~ **Done (v0.21.0)** — Hetzner env vars, AWS SM, GCP SM, Azure KV; CachingSecretsProvider with TTL
- Node failure + re-election — kill servers, verify quorum maintained and new leader elected
- Network partition — manipulate firewall rules to block traffic between nodes
- Slice deployment — deploy via management API, verify ACTIVE on all nodes
- Pre-baked images — provider snapshot with Java + JAR for instant boot (~30s vs ~3min)
- Multi-region cluster — test consensus over higher-latency links
- Disaster recovery — terminate majority, bring up replacements, verify state recovery via Rabia sync

### MEDIUM PRIORITY - Developer Tooling & Deployment

4. ~~**Notification Resource**~~ → **Complete (v0.21.0, Phase 1 — Email)**
    - `integrations/net/smtp` — async SMTP client on Netty (STARTTLS, IMPLICIT TLS, AUTH PLAIN, connection-per-send)
    - `integrations/email-http` — HTTP email sender with SendGrid, Mailgun, Postmark, Resend via VendorMapping SPI
    - `aether/resource/notification` — `NotificationSender` resource type, `NotificationSenderFactory`, `@Notify` qualifier, retry with exponential backoff
    - 57 tests (20 SMTP + 26 email-http + 11 notification)
    - **Phase 2 (future):** SMS (Twilio, AWS SNS), push (FCM, APNS), SMTP connection pooling, MX lookup

5. **RBAC Tier 2 — Per-Endpoint Role Authorization**
     - Per-endpoint role-based authorization rules (admin, operator, viewer)
     - Route-level security policy from KV-Store
     - Auth failure rate limiting
     - Currently all authenticated keys have equivalent access; Tier 2 differentiates by role

6. **Forge Modular Rework**
    - ~80% done: modules separated (`forge-simulator`, `forge-load`, `forge-cluster`), Ember works
    - **Remaining scope:**
      - Remote cluster support in load generator (target remote clusters, not just embedded)
      - Forge Script DSL: declarative scenario language for load/chaos test definitions, reusable scenario libraries, CI/CD integration
    - Three independently usable components:
      - **Ember** — single-process Aether runtime. Standalone for production migration, embedded for development
      - **Tester** — load generator + chaos testing + Forge Script DSL. Standalone for remote clusters, embedded for local
      - **Forge** — Ember + Tester + dashboard for local development convenience

### LOWER PRIORITY

7. **Configurable Rate Limiting per HTTP Route**
     - Per-route rate limiting configuration in blueprint or management API
     - Token bucket or sliding window algorithm
     - Configurable limits: requests/second, burst size
     - 429 Too Many Requests response with Retry-After header
     - Cluster-aware: distributed counters via consensus or per-node local limits
     - Note: `infra-ratelimit` exists for slice-internal use; this is for external HTTP routes

8. **Passive Worker Pools — Remaining Phases** — [design spec](../../specs/passive-worker-pools-spec.md)
    - Phases 1, 2a, 2b, 2b.5 complete in v0.19.3. Remaining work driven by real demand:
      - Phase 2c: Spot pool, spot-node exclusion from DHT ring
      - Phase 3: Multi-region, cross-region governors
    - **Architecture:** Small consensus core (5-7-9 active nodes) + self-organizing worker pools with elected governors. SWIM gossip for O(1) membership. Zone-aware grouping. Event-based community scaling.
    - **Research:** [10-system comparative analysis](../../internal/passive-worker-pool-research.md)

9. **Observability Dashboard UI**
   - Wire `ObservabilityDepthRegistry` data to dashboard with UI for configuring per-method depth thresholds
   - Backend REST API (`/api/observability/depth`) and KV-store sync already implemented
   - Current state is functional; production value but no customers yet

10. **Invocation Observability Dashboard Tab**
   - "Requests" tab: table view with timestamp, requestId, caller → callee, depth, duration, status
   - Click-to-expand tree view showing invocation depth with input/output at each level
   - Waterfall view for multi-hop request visualization
   - Filters: time range, slice, method, status, depth range
   - Auto-refresh via existing WebSocket push
   - See [RFC-0010](../../../../docs/rfc/RFC-0010-unified-invocation-observability.md) for data model and API
   - Backend complete (RFC-0010): REST API and trace store ready

11. **Slice Development IDE Plugins**
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

### FUTURE

- **Official Installation Binaries** — Pre-built distribution archives (`aether-node`, `aether-cli`, `aether-forge`). Formats: `.tar.gz`, `.zip`, `.deb`, `.rpm`, Homebrew. Self-contained via jlink/jpackage. Java audience already has JDK — low priority.

- **Cluster Expense Tracking** — Real-time cost visibility for cluster operations. Cloud billing API integration (AWS Cost Explorer, GCP Billing, Azure Cost Management). Cost per node/slice/request. Spot savings tracking. Budget alerts. **Prerequisite:** Cloud Integration (#1)

- **LLM Integration (Layer 3)** — Claude/GPT API integration. Complex reasoning workflows. Multi-cloud decision support.

- **Per-Blueprint Artifact Scoping (Tier 2)** — Tier 1 complete (ownership tracking, artifact exclusivity enforcement). Tier 2 would relax exclusivity for multi-tenant clusters: same artifact deployable by multiple blueprints with independent scaling. Deferred until a concrete multi-tenant use case arises.

- **In-Memory Streams** — [design spec](../../specs/in-memory-streams-spec.md) — Ordered, replayable, consumer-paced streaming (Kafka-like primitive). Partition-based with DHT ownership, consumer groups with cursor tracking, time/size-based retention, configurable replication. Blueprint-declared streams as first-class resources. Status: Exploratory Draft.

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
| infra-notifications | `@ResourceQualifier(type=NotificationSender.class)` | **Phase 1 Complete** (v0.21.0) — email via SMTP + HTTP vendors. Phase 2: SMS + push |
| infra-config | Superseded by Dynamic Configuration via KV store | **Remove** |
| infra-aspect | Fn1 composition utilities (`Aspects.withCaching()`, `@Key`, etc.) | **Keep** |

**Dropped:** infra-server, infra-streaming, infra-outbox, infra-blob, infra-feature

---

## Deprecated

- **MCP Server** - Replaced by direct agent API (see [metrics-control.md](../../contributors/metrics-control.md))

---

## Known Issues

| Issue | Severity | Details |
|-------|----------|---------|
| **Rolling restart loses slice state** | High | After sequential restart of ALL nodes in a 3-node cluster, slices are NOT_FOUND despite blueprint surviving in KV-Store. Root cause: consensus state restore + CDM/NDM activation ordering during full membership turnover. Partial fixes applied (lifecycle race, LOAD tracking, NDM scan, SWIM relay), but the core issue requires container-level debugging of the CDM reconcile path. `RollingRestartE2ETest` consistently fails. |

## Tech Debt — Hardcoded Values

The following values are compile-time constants that should eventually be externalized to configuration (TOML or management API). Listed by module and priority.

**Migrated to `TimeoutsConfig` (v0.20.0):** WebSocket auth timeout, dashboard broadcast interval, alert history size, evaluation interval, trace store capacity, metrics sliding window, forwarding retry delay, invocation cleanup interval, lifecycle retries, event loop probe interval, rolling update terminal retention. See [timeout-configuration.md](../../reference/timeout-configuration.md).

**Remaining (not timeout-related):**

| Location | Constant | Value | Notes |
|----------|----------|-------|-------|
| `AppHttpServer` | `MAX_CONTENT_LENGTH` | 16 MB | App HTTP request size limit |
| `ManagementServer` | `MAX_CONTENT_LENGTH` | 64 MB | Management API request size limit (artifact uploads) |
| `ScalingConfig` | `DEFAULT_WINDOW_SIZE` | 10 | Metric smoothing window |
| `ArtifactStore` | `CHUNK_SIZE` | 64 KB | DHT chunk size for artifact storage |
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
