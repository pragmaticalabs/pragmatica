# Aether Feature Catalog

Comprehensive inventory of all Aether distributed runtime capabilities.

**Status legend:**
- **Battle-tested** — Proven through multi-node E2E tests with failure injection (node kills, partitions, leader failovers)
- **Complete** — Production-ready, tested (unit tests, possibly basic E2E)
- **Critical** — Requires significant development effort before production use
- **Partial** — Core implemented, gaps noted
- **Planned** — Designed but not yet implemented

---

## Deployment & Lifecycle

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 1 | Blueprint management | Battle-tested | Declarative TOML-based deployment specs with dependency ordering, validation, pub-sub orphan detection, and status tracking |
| 2 | Slice lifecycle | Battle-tested | Full state machine: DOWNLOADING, LOADING, STARTING, ACTIVE, UNLOADING, UNLOADED, FAILED. Per-node tracking via KV-Store |
| 3 | Unified deployment strategies | Battle-tested | Single `aether deploy` command and `/api/deploy` endpoint supporting immediate, canary (progressive traffic shift with auto-evaluation), blue-green (atomic switchover with instant rollback), and rolling (weighted traffic shifting with health thresholds) strategies. Includes promote, rollback, complete lifecycle, cleanup policies |
| 4 | Auto-healing | Battle-tested | Bidirectional convergence: scale-up (provision replacements) and scale-down (terminate surplus). Node selection: empty-nodes-first, most-recent, never-self. CAS-based state transitions. Separate configured vs desired size for future blue-green support. Leader-only with failover |
| 5 | Classloader isolation | Complete | Per-slice classloader prevents dependency conflicts between slices |
| 6 | Manifest versioning | Complete | Envelope format versioning (v1-v6) for backward-compatible manifest evolution |
| 66 | Compile-time serde | Complete | `@Codec` annotation processor generates `*Codec` classes for records, enums, and sealed interfaces with recursive nested type scanning. `SliceCodec` wire format with deterministic hash-based tags, VLQ encoding, zero runtime reflection. Replaces Fory/Kryo for slice boundary serialization |
| 102 | Multi-blueprint lifecycle independence | Complete | Blueprint-scoped artifact ownership (`owningBlueprint` in SliceTargetValue), artifact exclusivity enforcement (rejects duplicate artifact across blueprints), owner-filtered blueprint deletion (only removes owned artifacts), rolling update deletion guard, KV-Store restore with ownership. Tier 1 correctness for multi-blueprint clusters |
| 126 | Blueprint Artifacts | Complete | Blueprint packaged as JAR with resources.toml and schema/ |
| 127 | Config Separation | Complete | App config (blueprint) vs infra config (node) with hierarchical merge |
| 128 | Schema Migration Engine | Complete | End-to-end migration engine (V/R/U/B types), history table, checksum validation, orchestrator with exclusive consensus locking, CDM readiness gating (blocks ACTIVATE until COMPLETED), DatasourceConnectionProvider for SqlConnector provisioning, schema directory convention (`schema/` root = `[database]`, subdirectories = `[database.<name>]`), strict datasource resolution (no fallback), REST API, CLI. Failure recovery (auto-retry with exponential backoff 5s/15s/45s for transient failures, manual retry via REST/CLI), natural language SchemaEvent hierarchy, failure classification (transient vs permanent), `schema_required` blueprint config |
| 129 | Endpoint Config | Complete | `[endpoints.*]` sections in aether.toml for infrastructure endpoints |
| 130 | Deployment State Machine (RFC-0014) | Complete | Documented CDM/NDM handoff protocol, 11-state lifecycle, schema migration gate, dependency-gated activation, failure classification (fatal/transient), quorum loss/restoration, reconciliation algorithm, blueprint atomicity, drain eviction protocol |
| 131 | Consensus Operation Retry | Complete | All NDM consensus operations (state transitions, topic subscriptions, scheduled tasks, endpoints) use unified `applyWithRetry` with 30s timeout × 2 retries. Prevents activation failures under consensus pipeline saturation |
| 135 | A/B Testing | Complete | Deterministic traffic split by request context (header, cookie, percentage), ScopedValue variant propagation |

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
| 13 | Rabia consensus | Battle-tested | Leaderless crash-fault tolerant (CFT) consensus for KV-Store replication. Formal spec verification suite (invariant tests, supermajority fast path, quorum intersection, value locking, multi-phase) |
| 14 | Leader election | Battle-tested | Lightweight leader detection with virtually instant re-election on departure |
| 15 | Quorum state management | Battle-tested | Monotonic-sequenced quorum notifications, graceful degradation on quorum loss, automatic restoration |
| 16 | Topology management | Battle-tested | Node discovery, addition/removal events, health tracking, grace period for departures |
| 17 | Distributed KV-Store | Battle-tested | Consensus-replicated store with typed keys (SliceNode, SliceTarget, HttpRoute, AppBlueprint, VersionRouting, RollingUpdate, Threshold, LogLevel, Config, TopicSubscription, NodeArtifact, NodeRoutes) |

## Networking & Routing

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 18 | HTTP route registration | Complete | Dynamic per-slice route discovery and registration via KV-Store |
| 19 | Endpoint registry | Complete | Artifact-to-node mapping for slice instance tracking and load balancing |
| 20 | Service-to-service invocation | Battle-tested | SliceInvoker with HTTP routing, load balancer selection, timeout/retry, metrics |
| 21 | Version routing | Battle-tested | Traffic splitting between old/new versions during deployments (configurable ratio) |
| 67 | Passive load balancer | Complete | Cluster-aware LB node (NodeRole.PASSIVE) joins cluster network, receives route table via committed Decisions, forwards HTTP via binary protocol. Smart routing, automatic failover, live topology awareness. No HTTP re-serialization. Reusable `PassiveNode<K,V>` abstraction in `integrations/cluster` |
| 68 | NodeRole cluster membership | Complete | ACTIVE/PASSIVE roles in NodeInfo. Passive nodes excluded from quorum/leader election, receive only Decision messages via deliverToPassive() filtering |
| 69 | HttpForwarder (reusable) | Complete | Extracted HTTP forwarding with round-robin selection, retry with backoff, node departure failover. Used by both AppHttpServer and passive LB |
| 137 | SharedScheduler consolidation | Complete | Unified shared scheduler (min 8 platform threads) replaces per-subsystem thread pools. 10 production schedulers migrated: SWIM, CircuitBreaker, Retry, canary evaluation, heartbeat, and others |
| 154 | Server UDP support | Complete | `Server` supports optional UDP port binding alongside TCP via `ServerConfig.withUdpPort()`. UDP DatagramChannel shares workerGroup with TCP. Foundation for SWIM integration |
| 155 | Shared EventLoopGroups | Complete | HTTP servers share Server's boss/worker EventLoopGroups instead of creating their own. Reduces per-node thread pools from 6+ to 2 |
| 159 | QUIC transport | Complete | QUIC-based cluster transport alongside TCP. QuicClusterNetwork, QuicClusterServer, QuicClusterClient, QuicTlsProvider, QuicTransportMetrics. 7 classes, 5 tests |
| 160 | HTTP/3 server | Complete | Http3Server and Http3ServerAdapter — HTTP/3 (QUIC-based) server support alongside HTTP/1.1 and HTTP/2 |

## Messaging (Pub-Sub)

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 22 | Publisher/Subscriber API | Complete | `Publisher<T>` functional interface, `Subscriber` marker, `@Subscription` annotation. 18 unit tests |
| 23 | Topic subscription registry | Complete | KV-Store backed subscriber discovery with competing consumers (round-robin). Tested |
| 24 | Message delivery | Battle-tested | TopicPublisher fans out via SliceInvoker. PublisherFactory registered as SPI. Forge PubSubTest validates cross-node delivery, multi-click fan-out, and leader failover scenarios |
| 25 | Resource lifecycle | Complete | Reference-counted `releaseAll()`, generated `stop()` cleanup, consumer tracking. SliceId auto-injected into ProvisioningContext |

## Scheduled Invocation

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 26 | Scheduled task registry | Complete | KV-Store backed registry tracking periodic task registrations with change listener pattern. Includes `paused` field for operational control. 8 unit tests |
| 27 | Scheduled task manager | Complete | Timer lifecycle manager with ExecutionMode (SINGLE/ALL), quorum gating, interval parsing (s/m/h/d/w), cron scheduling, pause/resume support, execution state tracking. `ALL` mode fires independently on every node with the slice deployed. 15 unit tests |
| 28 | Cron expression parser | Complete | 5-field cron syntax (minute hour day-of-month month day-of-week) with ranges, steps, lists. Wired into ScheduledTaskManager for one-shot+re-schedule pattern. 11 unit tests |
| 29 | Scheduled task KV types | Complete | `ScheduledTaskKey`/`ScheduledTaskValue` (with `paused` field), `ScheduledTaskStateKey`/`ScheduledTaskStateValue` (execution metrics) |
| 30 | Deployment lifecycle wiring | Complete | Publish/unpublish scheduled tasks during slice activation, deactivation, reactivation, and failure cleanup |
| 31 | Scheduled tasks management API | Complete | Full CRUD: list, filter, pause, resume, manual trigger, execution state query. CLI: list/get/pause/resume/trigger |
| 104 | Execution state tracking | Complete | Tracks last execution time, consecutive failures, total executions per task. Passive `ScheduledTaskStateRegistry` watches KV-Store. Enriched REST responses |

## Storage & Data

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 32 | Artifact repository | Battle-tested | Maven-compatible, chunked storage, checksum verification (MD5/SHA1), 64MB upload limit, metadata XML generation |
| 33 | Distributed hash table | Battle-tested | Consistent hash ring (150 vnodes, 1024 partitions), quorum R/W, anti-entropy repair (CRC32 digest exchange, migration on mismatch), re-replication on node departure (DHTRebalancer), per-use-case config via `scoped()` |
| 34 | Configuration service | Complete | TOML-based config with runtime overrides via KV-Store, environment variable interpolation, system property fallback |
| 105 | Hybrid Logical Clock | Complete | `integrations/hlc/` module. HlcTimestamp (48-bit microseconds + 16-bit counter packed into long), HlcClock (thread-safe, ReentrantLock), drift detection, counter overflow protection. Foundation for causal ordering |
| 106 | DHT versioned writes | Complete | HLC-stamped puts with atomic version comparison in storage. Rejects stale writes (version ≤ current). Synchronous notification delivery via `withSuccess()` preserves causal write ordering. Full replication (`DHTConfig.FULL`) for control plane maps |
| 107 | Centralized timeout configuration | Complete | All operator-facing timeouts externalized to `TimeoutsConfig` with 13 subsystem groups. TOML `[timeouts.*]` sections with human-readable duration strings. Legacy `_ms` fields supported with automatic migration |
| 139 | KV-Store durable backup | Complete | Cluster metadata serialized to TOML file in local git repo. Git provides versioning, history, diffs, optional remote push. BackupService with automatic periodic + on-change triggers. REST API, CLI |
| 140 | Hierarchical Storage Engine | Complete | Content-addressed block storage (`integrations/storage` library). SHA-256 BlockId, Memory + LocalDisk tiers with CAS-bounded capacity, write-through + tier-waterfall reads, SingleFlightCache dedup, MetadataStore, SnapshotManager, StorageReadinessGate, per-instance TOML config, ArtifactStore migration, REST + CLI management. 93 tests |

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
| 43 | Cluster event aggregator | Complete | Ring buffer (1000 events) collecting 11 event types (topology, leader, quorum, deployment, slice failure, network). REST API, WebSocket feed, CLI |

## Resource Provisioning

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 44 | SPI resource factories | Complete | ServiceLoader discovery, config-driven provisioning, type-safe qualifiers |
| 45 | Database resources | Complete | JDBC, R2DBC, jOOQ, jOOQ-R2DBC, jOOQ-async, JPA, postgres-async (native async + R2DBC adapter) with connection pooling, query pipelining, configurable IO threads, transaction management, and LISTEN/NOTIFY |
| 45a | Aether Store — PostgreSQL persistence | Complete | `@PgSql` type-safe persistence with compile-time SQL validation against migration-derived schema. Validates parameter types, return field mappings, column existence, NOT NULL coverage. Generates CRUD from method names (Spring Data conventions). 41-rule migration linter, record/enum codegen, named parameter rewriting, query narrowing, record expansion (INSERT VALUES + UPDATE SET). `pg-maven-plugin` for schema → Java generation. CLI scaffolding via `jbct add persistence` |
| 46 | HTTP client resource | Complete | Configurable outbound HTTP with timeouts, retries, SSL/TLS, Jackson integration |
| 47 | Interceptor framework | Complete | Method-level interceptors: retry, circuit breaker, rate limit, logging, metrics. Runtime enable/disable |
| 48 | Runtime extensions | Complete | `registerExtension()` for injecting runtime components into resource factories |
| 161 | PgNotification subscriber | Complete | Slice-level PostgreSQL LISTEN/NOTIFY subscription. PgNotification, PgNotificationSubscriber, PgNotificationConfig in slice-api; NotificationListenerFactory in db-async |

## Cloud Integration

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 108 | Environment integration SPI | Complete | Faceted SPI (`EnvironmentIntegration`) with 4 optional facets: compute, secrets, load balancer, discovery. ServiceLoader discovery |
| 109 | SecretsProvider implementations | Complete | `EnvSecretsProvider`, `FileSecretsProvider`, `CompositeSecretsProvider` (first-success chain). Zero-dependency |
| 110 | DiscoveryProvider SPI | Complete | Peer discovery interface: `discoverPeers()`, `watchPeers()`, `registerSelf()`/`deregisterSelf()`. Wired into AetherNode bootstrap |
| 111 | Hetzner Cloud compute | Complete | `HetznerComputeProvider` — provision, terminate, list, status, restart, tag management, label-filtered listing |
| 112 | Hetzner Cloud discovery | Complete | `HetznerDiscoveryProvider` — label-based peer discovery via `aether-cluster` server labels |
| 113 | Hetzner Cloud load balancer | Complete | `HetznerLoadBalancerProvider` — IP-based target management on pre-existing Hetzner LB |
| 114 | Hetzner REST client | Complete | Promise-based async Hetzner Cloud API client. Rate limit handling, typed errors |
| 115 | AWS REST client | Complete | Promise-based async AWS API client. EC2, ELBv2, Secrets Manager. SigV4 signing from scratch — no AWS SDK |
| 116 | AWS environment integration | Complete | `AwsComputeProvider`, `AwsLoadBalancerProvider`, `AwsDiscoveryProvider`, `AwsSecretsProvider` |
| 117 | GCP REST client | Complete | Promise-based async GCP API client. Compute Engine, NEGs, Secret Manager. RS256 JWT — no GCP SDK |
| 118 | GCP environment integration | Complete | `GcpComputeProvider`, `GcpLoadBalancerProvider`, `GcpDiscoveryProvider`, `GcpSecretsProvider` |
| 119 | Azure REST client | Complete | Promise-based async Azure ARM API client. VMs, Load Balancers, Resource Graph, Key Vault. Dual OAuth2 — no Azure SDK |
| 120 | Azure environment integration | Complete | `AzureComputeProvider`, `AzureLoadBalancerProvider`, `AzureDiscoveryProvider`, `AzureSecretsProvider` |
| 121 | XML mapper integration | Complete | `integrations/xml/jackson-xml` — `XmlMapper` mirroring `JsonMapper` pattern. Used for AWS EC2 XML response parsing |
| 122 | CDM cloud VM termination | Complete | `completeDrain()` calls `ComputeProvider.terminate()` via tag-based instance lookup. Prevents billing on drained cloud VMs |
| 123 | ComputeProvider SPI extensions | Complete | `provision(ProvisionSpec)`, `listInstances(TagSelector)`. Backward-compatible defaults |
| 124 | LoadBalancerProvider SPI extensions | Complete | 7 new default methods including `createLoadBalancer`, `configureHealthCheck`, `syncWeights`, `configureTls` |
| 125 | SecretsProvider SPI extensions | Complete | `resolveSecretWithMetadata`, `resolveSecrets` (batch), `watchRotation`. Plus `CachingSecretsProvider` (TTL cache) |

## Management

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 49 | REST management API | Battle-tested | 60+ endpoints across 13 route classes: status, health, blueprints, slices, scaling, rolling updates, config, thresholds, alerts, aspects, logging, TTM, invocation metrics, controller config, node lifecycle |
| 50 | Interactive CLI | Complete | Batch and REPL modes. Commands: status, nodes, slices, routes, metrics, health, scale, artifact, blueprint, deploy, invocation-metrics, controller, alerts, thresholds, aspects, traces, observability, config, logging, events, node lifecycle/drain/activate/shutdown |
| 51 | WebSocket streams | Complete | `/ws/dashboard` (metrics), `/ws/status` (cluster state), `/ws/events` (real-time cluster events with delta broadcasting) |
| 52 | Dynamic log levels | Complete | Runtime log level adjustment per logger via KV-Store. CLI and API control |
| 53 | E2E test framework | Battle-tested | Testcontainers-based cluster testing with 10 test classes on bridge networking. Container DNS for inter-node communication, network partition/disconnect/reconnect support |
| 76 | Forge integration tests | Battle-tested | In-process EmberCluster tests: 16 test classes covering cluster formation, node failure, chaos, rolling updates, pub-sub delivery, invocation metrics, graceful shutdown, network partitions |
| 136 | Docker scaling test infrastructure | Complete | 5-core + 7-worker Docker Compose setup with phase-based orchestrator, k6 load tests, Maven protocol artifact upload |
| 146 | In-memory streaming | Complete | StreamPublisher/StreamSubscriber/StreamAccess API, OffHeapRingBuffer, StreamPartitionManager, ResourceFactory SPI, StreamConsumerAdapter, CDM stream creation + consumer wiring, consensus cursor checkpointing, annotation processor (envelope v7), consumer runtime with configurable retries, dead-letter handler, REST API, CLI |
| 147 | Declarative cluster management | Complete (Phase 1) | TOML-based cluster config, 12-step bootstrap orchestrator, cluster registry, CLI commands, Management API |
| 58 | Web dashboard | Critical | Forge dashboard complete (cluster visualization, load generation, chaos injection, metrics, scaling events, deployment timing). **Node management dashboard requires significant work** — missing: observability depth UI, invocation trace viewer, log level management UI, storage management UI, streaming dashboard, worker pool visualization. **Requires major development effort.** |

## Developer Tooling

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 54 | Slice annotation processor | Complete | Compile-time code generation: factory classes, manifests, route sources, pub-sub wiring |
| 55 | JBCT compliance | Complete | Format linting, return type validation, pattern checking, factory naming conventions. Maven plugin |
| 56 | Envelope format versioning | Complete | `ENVELOPE_FORMAT_VERSION` in ManifestGenerator with runtime compatibility check |
| 57 | Forge simulator | Battle-tested | Standalone cluster simulator with load generation (constant/ramp/spike), chaos injection, visual dashboard, REST API |
| 77 | Topology graph | Complete | Compile-time topology extraction (envelope v6): HTTP routes, resources, pub-sub topics from `.manifest` files. REST `GET /api/topology`, WebSocket `INITIAL_STATE`. Swim-lane SVG layout with Manhattan routing, HSL color-coded topic groups, hover highlighting, search filtering |
| 78 | `jbct add-slice` command | Complete | Scaffolds a new slice into an existing project: creates interface, test, routes.toml, config, and dependency manifest |
| 79 | IDE plugins | Planned | Slice development plugins for IntelliJ IDEA (native), VS Code, Eclipse, NetBeans. Shared LSP backend for routes.toml support, JBCT diagnostics, TOML schema validation |
| 138 | Core value objects | Complete | Reusable validated value objects in `org.pragmatica.lang.vo`: Email, Url, NonBlankString, Uuid, IsoDateTime |
| 141 | GitHub Issues as worklog | Complete | GitHub Issues adopted as primary work tracking and project log |
| 158 | V1.0.0 roadmap | Complete | Evolutionary implementation protocol with phased milestones, feature prioritization, and release criteria for Aether 1.0 |
| 162 | JBCT code formatter | Complete | CST-based Java code formatter (`jbct-format` module). Records, enums, switch expressions, text blocks, chain alignment, multiline parameters, ternary operators, lambdas, comments. 17 golden test files |
| 163 | JBCT compliance scorer | Complete | Numeric JBCT compliance scoring (`jbct-core/score`). ScoreCalculator, ScoreResult, ScoreCategory, RuleCategoryMapping |
| 164 | JBCT project scaffolding | Complete | Full project initialization (`jbct-init` module). ProjectInitializer, SliceProjectInitializer, PersistenceAdder, EventAdder, AiToolsInstaller, self-upgrade mechanism. 17 classes, 4 test classes |
| 165 | Property-based testing | Complete | Property-based testing library (`testing` module). PropertyTest, Arbitrary, Shrinkable, Shrinkers, RandomSource. 7 classes, 3 tests |

## Reusable Libraries

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 166 | Generic state machine | Complete | Reusable state machine framework (`integrations/statemachine`). StateMachine, StateMachineDefinition, InMemoryStateMachine, Transition. 7 classes, tests |
| 167 | DNS client | Complete | Async DNS resolution (`integrations/net/dns`). DnsClient, DomainNameResolver, DomainName, DomainAddress, InetUtils. 6 classes, 5 tests |
| 168 | TOML parser/writer | Complete | Standalone TOML parser and writer (`integrations/config/toml`). TomlParser, TomlWriter, TomlDocument, TomlError |
| 169 | KSUID generator | Complete | K-Sortable Unique IDs (`integrations/utility`). KSUID, IdGenerator, RingBuffer, HierarchyScanner |
| 170 | Core parse library | Complete | Type-safe parsers for domain primitives (`core/parse`). Text, Number, TimeSpan, DateTime, DataSize, Network, I18n. 7 parsers, 7 test classes |
| 171 | Multipart file upload | Complete | HTTP multipart/form-data parsing (`integrations/http-routing`). MultipartParser, MultipartRequest, FileUpload |
| 172 | ProblemDetail (RFC 7807) | Complete | Structured error responses following RFC 7807 (`integrations/http-routing`) |
| 173 | Static file serving | Complete | StaticFileRouteSource for serving static files from filesystem/classpath (`integrations/http-routing`) |

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
| 88 | Inter-node mTLS | Complete | CertificateProvider SPI, SelfSignedCertificateProvider (BouncyCastle, HKDF-derived deterministic CA, EC P-256), automatic mTLS for all TCP transports |
| 89 | SWIM gossip encryption | Complete | AES-256-GCM encryption for SWIM protocol messages with dual-key rotation support |
| 90 | Certificate lifecycle | Complete | CertificateRenewalScheduler with automatic renewal at 50% validity, gossip key rotation via consensus KV store |
| 91 | TLS default for containers | Complete | TLS enabled by default for DOCKER and KUBERNETES environments (LOCAL remains plain for development) |
| 92 | RBAC — per-route security | Complete | Per-route security via routes.toml `[security]` section (public/authenticated/role:name), type-safe SecurityPolicy with `canAccess()`, route-level enforcement in AppHttpServer, Principal/SecurityContext injection in handlers, blueprint operator overrides with strengthen_only policy, security metadata in KV-Store, dashboard security badges, security denial metrics |

## Embeddable Runtime

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 73 | Ember embeddable cluster | Complete | Headless cluster runtime extracted from Forge as `aether/ember/` module. Fluent builder API: `Ember.cluster(5).withH2().start()`. Programmatic lifecycle management via `EmberInstance` |
| 74 | Remote Maven repositories | Complete | Resolve slices from Maven Central or private Nexus. SHA-1 verification, local `~/.m2/repository` cache, `settings.xml` auth |
| 75 | Load Balancer | Complete | Standalone `aether/lb/` module. Round-robin routing, active health checking, automatic retry, X-Forwarded-* headers, hop-by-hop stripping |

## Worker Pools

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 80 | SWIM failure detection | Complete | UDP-based protocol with periodic probes, indirect probing, piggybacked membership updates. Standalone `integrations/swim/` module. Sole failure detector. Shares Server's workerGroup. Used for both worker-to-worker and core-to-core health detection via `CoreSwimHealthDetector`. Stabilized in v0.21.1: 13 bugs fixed. Startup delay + revival grace period |
| 81 | Worker node | Complete | Passive compute nodes that run slices without participating in Rabia consensus. Role-aware AetherNode with observer consensus mode, ForwardingClusterNode for KV writes, SWIM + Governor + WorkerDeploymentManager. Full slice lifecycle. Single binary for both core and worker roles. WORKER→CORE promotion supported |
| 82 | Governor election | Complete | Pure deterministic computation — lowest ALIVE NodeId from SWIM membership, scoped to own group. No election messages exchanged. Governor cleanup removes dead node DHT entries |
| 83 | Worker endpoint registry | Complete | Unified `EndpointRegistry` fed by DHT `ReplicatedMap` subscription events. SliceInvoker uses single registry for both core and worker endpoints |
| 84 | CDM pool awareness | Complete | AllocationPool for core + worker node sets. PlacementPolicy (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL) flows from SliceTargetValue through CDM allocation |
| 85 | Worker management API | Complete | `GET /api/workers`, `GET /api/workers/health`, `GET /api/workers/endpoints`. `POST /api/scale` accepts `placement` parameter. CLI: `workers list`, `workers health`, `scale --placement` |
| 86 | Core-to-core SWIM health | Complete | `CoreSwimHealthDetector` bridges SWIM `FAULTY`/`LEFT` events to `DisconnectNode`. Detection in 1-2s vs TCP disconnect 15s-2min |
| 87 | Automatic topology growth | Complete | CDM assigns core vs worker role to joining non-seed nodes. `RabiaEngine` activation gating. `coreMax`/`coreMin` configurable via TOML. Management API and CLI |
| 93 | DHT node cleanup | Complete | `DhtNodeCleanup` removes dead node's endpoints from DHT maps on SWIM DEAD detection |
| 94 | SliceNodeKey DHT migration | Complete | SliceNodeKey reads/writes moved from consensus to `slice-nodes` ReplicatedMap |
| 95 | HttpNodeRouteKey DHT migration | Complete | HttpNodeRouteKey reads/writes moved from consensus to `http-routes` ReplicatedMap |
| 96 | DHT replication config | Complete | `[dht.replication]` TOML section: `cooldown_delay_ms`, `cooldown_rate`, `target_rf`. Environment-aware defaults |
| 97 | Multi-group worker topology | Complete | Zone-aware group computation from SWIM membership. `WorkerGroupId`, `GroupAssignment`, `GroupMembershipTracker`. Per-group governor election and Decision relay |
| 98 | CDM community-aware placement | Complete | `AllocationPool` extended with `workersByCommunity`. CDM tracks `GovernorAnnouncementValue` per community |
| 99 | Worker zone configuration | Complete | `WorkerConfig` extended with `groupName`, `zone`, `maxGroupSize`. Backward compatible defaults |
| 100 | Event-based community scaling | Complete | Governors monitor follower metrics via `WorkerMetricsPing`/`Pong`, detect sustained threshold breaches, send `CommunityScalingRequest` to core. Zero baseline bandwidth |
| 101 | Governor advertised address | Complete | Governors announce routable TCP address. Auto-detect or configurable `worker.advertise_address` in TOML |
| 132 | Role-aware unified node | Complete | Single `aether-node.jar` binary for CORE or WORKER. Consensus observer mode, `ForwardingClusterNode`, `SwitchableClusterNode`. WORKER→CORE promotion via `authorizeActivation()` |
| 150 | DHT-backed ReplicatedMap | Complete | Generic typed `ReplicatedMap<K,V>` abstraction over `DHTClient` with namespace-prefixed keys, `MapSubscription` event callbacks. Drain loop prevents subscriber re-entrance. `CachedReplicatedMap` adds LRU + TTL caching. `aether/aether-dht/` module |
| 151 | Community-aware replication | Complete | `ReplicationPolicy` with home-replica rule (1 home + 2 ring replicas = RF=3). `HomeReplicaResolver` for deterministic community-local selection. Spot-node exclusion |
| 152 | Endpoint DHT migration | Complete | Endpoints moved from consensus KV-Store to DHT `ReplicatedMap`. O(3) write amplification vs O(N) with consensus |
| 153 | Replication cooldown | Complete | Startup RF=1 with background push to RF=3 after configurable delay. Rate-limited to prevent boot storm |
| 156 | Compound KV-Store key types | Complete | `NodeArtifactKey` merges EndpointKey + SliceNodeKey; `NodeRoutesKey` merges HttpNodeRouteKey. ~10x entry count reduction. WorkerNetwork eliminated — inter-worker messaging consolidated into NCN via DelegateRouter |

## Known Limitations

| Area | Limitation | Planned Fix |
|------|-----------|-------------|
| Security | Node certificates are self-signed (no external CA integration) | External CA provider SPI implementation |
| Networking | Single-region only; no multi-region deployment | Not yet planned |
| Dashboard | Node management dashboard requires significant work | Critical priority (#58) |

## Planned Features

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 64 | Per-route rate limiting | Planned | Per-HTTP-route rate limiting via blueprint or management API. Token bucket or sliding window. Cluster-aware distributed counters |
| 65 | Spot instance support | Planned | Elastic pool of spot/preemptible instances for cost-optimized scaling. Core (on-demand) + elastic (spot) pools |
| 66 | Cluster expense tracking | Planned | Real-time cost visibility from cloud billing APIs. Per-node, per-slice, per-request cost derivation. Budget alerts |
| 70 | Aether runtime rolling upgrade | Partial | Phase 1: `POST /api/cluster/upgrade` endpoint and CLI. Full rolling orchestration deferred to Phase 2 |
| 71 | Email notification resource | Complete | `integrations/net/smtp` (async Netty SMTP client), `integrations/email-http` (HTTP sender with SendGrid/Mailgun/Postmark/Resend SPI), `aether/resource/notification` (ResourceFactory + @Notify qualifier). 57 tests |
| 79 | IDE plugins | Planned | Slice development plugins for IntelliJ IDEA, VS Code, Eclipse, NetBeans. Shared LSP backend |
| 157 | Per-blueprint artifact scoping (Tier 2) | Planned | Per-blueprint SliceTargetKey scoping for multi-tenant clusters. Prerequisite: Tier 1 (#102) |
| 174 | DigitalOcean cloud integration | Planned | DigitalOcean compute, discovery, load balancer providers. Spec exists |
| 175 | Fluid cross-environment migration | Planned | Cross-environment migration protocol. Spec exists |

---

## Statistics

| Status | Count |
|--------|-------|
| Battle-tested | 24 |
| Complete | 140 |
| Critical | 1 |
| Partial | 2 |
| Planned | 6 |
| Total | 173 |

**Critical features:**
| Feature | Issue |
|---------|-------|
| Web dashboard (#58) | Node management dashboard requires major development — missing observability, invocation traces, log management, storage, streaming, worker pool UIs |

**Partial features and their gaps:**
| Feature | Key Gap |
|---------|---------|
| TTM predictive scaling (#11) | Disabled by default, no live model training |
| Aether runtime rolling upgrade (#70) | Phase 1 only — full rolling orchestration deferred |

---

*Last updated: 2026-03-31*
