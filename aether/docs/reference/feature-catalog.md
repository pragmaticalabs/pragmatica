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
| 1 | Blueprint management | Battle-tested | Declarative TOML-based deployment specs with dependency ordering, validation, pub-sub orphan detection, and status tracking |
| 2 | Slice lifecycle | Battle-tested | Full state machine: DOWNLOADING, LOADING, STARTING, ACTIVE, UNLOADING, UNLOADED, FAILED. Per-node tracking via KV-Store |
| 3 | Rolling updates | Battle-tested | Zero-downtime version deployments with traffic shifting (new:old ratio), health thresholds, auto-progression, rollback, and cleanup policies |
| 4 | Auto-healing | Battle-tested | Automatic reconciliation of desired vs. actual state on node departure. Leader-only with failover |
| 5 | Classloader isolation | Complete | Per-slice classloader prevents dependency conflicts between slices |
| 6 | Manifest versioning | Complete | Envelope format versioning (v1-v6) for backward-compatible manifest evolution |
| 66 | Compile-time serde | Complete | `@Codec` annotation processor generates `*Codec` classes for records, enums, and sealed interfaces with recursive nested type scanning. `SliceCodec` wire format with deterministic hash-based tags, VLQ encoding, zero runtime reflection. Replaces Fory/Kryo for slice boundary serialization |
| 102 | Multi-blueprint lifecycle independence | Complete | Blueprint-scoped artifact ownership (`owningBlueprint` in SliceTargetValue), artifact exclusivity enforcement (rejects duplicate artifact across blueprints), owner-filtered blueprint deletion (only removes owned artifacts), rolling update deletion guard, KV-Store restore with ownership. Tier 1 correctness for multi-blueprint clusters |
| 126 | Blueprint Artifacts | Complete | Blueprint packaged as JAR with resources.toml and schema/ |
| 127 | Config Separation | Complete | App config (blueprint) vs infra config (node) with hierarchical merge |
| 128 | Schema Migration Prep | Partial | Schema files in blueprint, metadata in KV-Store, REST API (status/migrate/undo/baseline), CLI commands; actual migration execution pending |
| 129 | Endpoint Config | Complete | `[endpoints.*]` sections in aether.toml for infrastructure endpoints |

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
| 17 | Distributed KV-Store | Battle-tested | Consensus-replicated store with typed keys (SliceNode, SliceTarget, HttpRoute, AppBlueprint, VersionRouting, RollingUpdate, Threshold, LogLevel, Config, TopicSubscription, NodeArtifact, NodeRoutes) |

## Networking & Routing

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 18 | HTTP route registration | Complete | Dynamic per-slice route discovery and registration via KV-Store |
| 19 | Endpoint registry | Complete | Artifact-to-node mapping for slice instance tracking and load balancing |
| 20 | Service-to-service invocation | Battle-tested | SliceInvoker with HTTP routing, load balancer selection, timeout/retry, metrics |
| 21 | Version routing | Battle-tested | Traffic splitting between old/new versions during rolling updates (configurable ratio) |
| 67 | Passive load balancer | Complete | Cluster-aware LB node (NodeRole.PASSIVE) joins cluster network, receives route table via committed Decisions, forwards HTTP via binary protocol. Smart routing, automatic failover, live topology awareness. No HTTP re-serialization. Reusable `PassiveNode<K,V>` abstraction in `integrations/cluster` separates infrastructure from consumer-specific wiring |
| 68 | NodeRole cluster membership | Complete | ACTIVE/PASSIVE roles in NodeInfo. Passive nodes excluded from quorum/leader election, receive only Decision messages via deliverToPassive() filtering |
| 69 | HttpForwarder (reusable) | Complete | Extracted HTTP forwarding with round-robin selection, retry with backoff, node departure failover. Used by both AppHttpServer and passive LB |
| 105 | Server UDP support | Complete | `Server` supports optional UDP port binding alongside TCP via `ServerConfig.withUdpPort()`. UDP DatagramChannel shares workerGroup with TCP (no extra thread pool). Foundation for SWIM integration and future lightweight UDP messaging |
| 106 | Shared EventLoopGroups | Complete | HTTP servers (AppHttpServer, ManagementServer, AetherPassiveLB) share Server's boss/worker EventLoopGroups instead of creating their own. Reduces per-node thread pools from 6+ to 2. `HttpServer.httpServer()` overload accepts external groups; `NettyHttpServer.createShared()` skips group shutdown on stop |

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
| 105 | Hybrid Logical Clock | Complete | `integrations/hlc/` module. HlcTimestamp (48-bit microseconds + 16-bit counter packed into long), HlcClock (thread-safe, ReentrantLock), drift detection, counter overflow protection. Foundation for causal ordering |
| 106 | DHT versioned writes | Complete | HLC-stamped puts with atomic version comparison in storage. Rejects stale writes (version ≤ current). Synchronous notification delivery via `withSuccess()` preserves causal write ordering. Superseded flag in PutResponse skips stale notifications. Full replication (`DHTConfig.FULL`) for control plane maps |
| 34 | Configuration service | Complete | TOML-based config with runtime overrides via KV-Store, environment variable interpolation, system property fallback |
| 107 | Centralized timeout configuration | Complete | All operator-facing timeouts externalized to `TimeoutsConfig` with 13 subsystem groups (invocation, forwarding, deployment, rolling update, cluster, consensus, election, SWIM, observability, DHT, worker, security, repository, scaling). TOML `[timeouts.*]` sections with human-readable duration strings. Legacy `_ms` fields supported with automatic migration. See [timeout-configuration.md](timeout-configuration.md) |

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
| 45 | Database resources | Complete | JDBC, R2DBC, jOOQ, jOOQ-R2DBC, JPA, postgres-async (native async + R2DBC adapter) with connection pooling, query pipelining (multiple in-flight queries per connection), configurable IO threads, transaction management, and LISTEN/NOTIFY |
| 46 | HTTP client resource | Complete | Configurable outbound HTTP with timeouts, retries, SSL/TLS, Jackson integration |
| 47 | Interceptor framework | Complete | Method-level interceptors: retry, circuit breaker, rate limit, logging, metrics. Runtime enable/disable |
| 48 | Runtime extensions | Complete | `registerExtension()` for injecting runtime components into resource factories |

## Cloud Integration

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 108 | Environment integration SPI | Complete | Faceted SPI (`EnvironmentIntegration`) with 4 optional facets: compute, secrets, load balancer, discovery. ServiceLoader discovery. Used by CDM for auto-heal, node bootstrap for peer discovery |
| 109 | SecretsProvider implementations | Complete | `EnvSecretsProvider` (AETHER_SECRET_* env vars), `FileSecretsProvider` (/run/secrets files), `CompositeSecretsProvider` (first-success chain). Zero-dependency, works in any environment |
| 110 | DiscoveryProvider SPI | Complete | Peer discovery interface: `discoverPeers()`, `watchPeers()`, `registerSelf()`/`deregisterSelf()`. Wired into AetherNode bootstrap — registers on start, deregisters on shutdown |
| 111 | Hetzner Cloud compute | Complete | `HetznerComputeProvider` — provision, terminate, list, status, restart, tag management, label-filtered listing. Maps Hetzner server lifecycle to InstanceInfo |
| 112 | Hetzner Cloud discovery | Complete | `HetznerDiscoveryProvider` — label-based peer discovery via `aether-cluster` server labels. Polling-based watch with configurable interval. Self-registration/deregistration via label updates |
| 113 | Hetzner Cloud load balancer | Complete | `HetznerLoadBalancerProvider` — IP-based target management on pre-existing Hetzner LB. Route-change sync, node removal, reconciliation with diff-based add/remove |
| 114 | Hetzner REST client | Complete | Promise-based async Hetzner Cloud API client. Servers (CRUD + labels + reboot), SSH keys, networks, firewalls, load balancers (CRUD + IP targets). Rate limit handling, typed errors |
| 115 | AWS REST client | Complete | Promise-based async AWS API client. EC2 (RunInstances, TerminateInstances, DescribeInstances, RebootInstances, CreateTags) via Query API + XML responses, ELBv2 (RegisterTargets, DeregisterTargets, DescribeTargetHealth) via JSON, Secrets Manager via JSON. SigV4 signing from scratch — no AWS SDK |
| 116 | AWS environment integration | Complete | `AwsComputeProvider` (EC2 lifecycle, tag-based filtering), `AwsLoadBalancerProvider` (ELBv2 target groups), `AwsDiscoveryProvider` (tag-based peer discovery), `AwsSecretsProvider` (Secrets Manager) |
| 117 | GCP REST client | Complete | Promise-based async GCP API client. Compute Engine (instances CRUD, labels, reset), Network Endpoint Groups (attach/detach/list), Secret Manager (access with base64 decode). RS256 JWT token management with caching — no GCP SDK |
| 118 | GCP environment integration | Complete | `GcpComputeProvider` (instance lifecycle, label filtering), `GcpLoadBalancerProvider` (NEG-based), `GcpDiscoveryProvider` (label-based), `GcpSecretsProvider` (Secret Manager) |
| 119 | Azure REST client | Complete | Promise-based async Azure ARM API client. VMs (CRUD, restart, tags), Load Balancers (backend pool management), Resource Graph (KQL queries), Key Vault (secrets). Dual OAuth2 tokens (management + vault) — no Azure SDK |
| 120 | Azure environment integration | Complete | `AzureComputeProvider` (VM lifecycle, Resource Graph filtering), `AzureLoadBalancerProvider` (backend address pools), `AzureDiscoveryProvider` (Resource Graph KQL), `AzureSecretsProvider` (Key Vault) |
| 121 | XML mapper integration | Complete | `integrations/xml/jackson-xml` — `XmlMapper` interface mirroring `JsonMapper` pattern backed by Jackson XML. Used for AWS EC2 XML response parsing |
| 122 | CDM cloud VM termination | Complete | `completeDrain()` now calls `ComputeProvider.terminate()` via tag-based instance lookup (`aether-node-id`). Prevents billing on drained cloud VMs. Fire-and-forget after DECOMMISSIONED state. Works uniformly for all providers |
| 123 | ComputeProvider SPI extensions | Complete | `provision(ProvisionSpec)` for detailed specs with pool/tags/image/userData, `listInstances(TagSelector)` typed filter. Backward-compatible defaults |
| 124 | LoadBalancerProvider SPI extensions | Complete | 7 new default methods: `createLoadBalancer`, `deleteLoadBalancer`, `loadBalancerInfo`, `configureHealthCheck`, `syncWeights`, `deregisterWithDrain(drainTimeout)`, `configureTls`. Plus `LoadBalancerSpec`, `LoadBalancerInfo`, `HealthCheckConfig`, `TlsTerminationConfig` types |
| 125 | SecretsProvider SPI extensions | Complete | `resolveSecretWithMetadata` (version/expiry), `resolveSecrets` (batch via `Promise.allOf`), `watchRotation` (rotation callback). Plus `SecretValue`, `SecretRotationCallback`, `CachingSecretsProvider` (TTL cache) |

## Management

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 49 | REST management API | Battle-tested | 60+ endpoints across 13 route classes: status, health, blueprints, slices, scaling, rolling updates, config, thresholds, alerts, aspects, logging, TTM, invocation metrics, controller config, node lifecycle |
| 50 | Interactive CLI | Complete | Batch and REPL modes. Commands: status, nodes, slices, metrics, health, scale, artifact, blueprint, update, invocation-metrics, controller, alerts, thresholds, aspects, traces, observability, config, logging, events, node lifecycle/drain/activate/shutdown |
| 51 | WebSocket streams | Complete | `/ws/dashboard` (metrics), `/ws/status` (cluster state), `/ws/events` (real-time cluster events with delta broadcasting) |
| 52 | Dynamic log levels | Complete | Runtime log level adjustment per logger via KV-Store. CLI and API control |
| 53 | E2E test framework | Battle-tested | Testcontainers-based cluster testing with 10 test classes on bridge networking. Container DNS for inter-node communication, network partition/disconnect/reconnect support. Tests: ArtifactRepository, NodeFailure, RollingRestart, SwimDetection, NodeDrain, NetworkPartition, SliceLifecycle, TopologyGrowth, LoadBalancerFailover, LeaderIsolation |
| 76 | Forge integration tests | Battle-tested | In-process EmberCluster tests: 16 test classes covering cluster formation, node failure, chaos, rolling updates, pub-sub delivery, invocation metrics, graceful shutdown, network partitions. Class-level cluster setup, health-endpoint readiness polling, `@Tag("Heavy")` for 5 resource-intensive tests |

## Developer Tooling

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 54 | Slice annotation processor | Complete | Compile-time code generation: factory classes, manifests, route sources, pub-sub wiring |
| 55 | JBCT compliance | Complete | Format linting, return type validation, pattern checking, factory naming conventions. Maven plugin |
| 56 | Envelope format versioning | Complete | `ENVELOPE_FORMAT_VERSION` in ManifestGenerator with runtime compatibility check |
| 57 | Forge simulator | Battle-tested | Standalone cluster simulator with load generation (constant/ramp/spike), chaos injection, visual dashboard, REST API |
| 58 | Web dashboard | Partial (WIP) | Forge dashboard complete (cluster visualization, load generation, chaos injection, metrics, scaling events, deployment timing). Node management dashboard in active development — missing observability depth UI, invocation trace viewer, log level management |
| 77 | Topology graph | Complete | Compile-time topology extraction (envelope v6): HTTP routes, resources, pub-sub topics from `.manifest` files. REST `GET /api/topology`, WebSocket `INITIAL_STATE`. Swim-lane SVG layout: per-slice lanes with inputs (endpoints/subscribers) left, slice center, outputs (resources/publishers) right. Manhattan routing for cross-slice topic connectors (right gutter) and dependency edges (left gutter). HSL color-coded topic groups, hover highlighting, search filtering |
| 78 | `jbct add-slice` command | Complete | Scaffolds a new slice into an existing project: creates interface, test, routes.toml, config, and dependency manifest in a dedicated sub-package (enforcing one-slice-per-package convention) |
| 79 | IDE plugins | Planned | Slice development plugins for IntelliJ IDEA (native), VS Code, Eclipse, NetBeans. Shared LSP backend for routes.toml support, JBCT diagnostics, TOML schema validation. IntelliJ gets gutter icons, inspections, run configs |

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
| 89 | SWIM gossip encryption | Complete | AES-256-GCM encryption for SWIM protocol messages with dual-key rotation support, wire format [keyId][nonce][ciphertext+tag] |
| 90 | Certificate lifecycle | Complete | CertificateRenewalScheduler with automatic renewal at 50% validity, gossip key rotation via consensus KV store |
| 91 | TLS default for containers | Complete | TLS enabled by default for DOCKER and KUBERNETES environments (LOCAL remains plain for development) |

## Embeddable Runtime

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 73 | Ember embeddable cluster | Complete | Headless cluster runtime extracted from Forge as `aether/ember/` module. Fluent builder API: `Ember.cluster(5).withH2().start()`. Programmatic lifecycle management via `EmberInstance` |
| 74 | Remote Maven repositories | Complete | Resolve slices from Maven Central or private Nexus. SHA-1 verification, local `~/.m2/repository` cache, `settings.xml` auth. Config: `repositories = ["local", "remote:central"]` |
| 75 | Load Balancer | Complete | Standalone `aether/lb/` module. Round-robin routing, active health checking (GET /health/ready), automatic retry, X-Forwarded-* headers, hop-by-hop stripping. Integrated into Ember lifecycle |

## Worker Pools

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 80 | SWIM failure detection | Complete | UDP-based protocol with periodic probes, indirect probing, piggybacked membership updates. Standalone `integrations/swim/` module. Sole failure detector — NCN Ping/Pong removed. Shares Server's workerGroup (no separate thread pool). Used for both worker-to-worker and core-to-core health detection via `CoreSwimHealthDetector` |
| 81 | Worker node | Complete | Passive compute nodes that run slices without participating in Rabia consensus. WorkerNode composes PassiveNode + SWIM + Governor + WorkerDeploymentManager. Full slice lifecycle: receives directives from CDM, self-assigns instances via consistent hashing, loads/activates slices, publishes endpoints to DHT |
| 82 | Governor election | Complete | Pure deterministic computation — lowest ALIVE NodeId from SWIM membership, scoped to own group. No election messages exchanged. Governor cleanup removes dead node DHT entries. Reconciliation on new governor election. GovernorAnnouncement published to consensus for core-side discovery |
| 83 | Worker endpoint registry | Complete | `WorkerEndpointRegistry` removed (Phase 2a) — replaced by unified `EndpointRegistry` fed by DHT `ReplicatedMap` subscription events. SliceInvoker uses single registry for both core and worker endpoints |
| 84 | CDM pool awareness | Complete | AllocationPool for core + worker node sets. CDM discovers workers via `ActivationDirectiveKey(WORKER)`, writes `WorkerSliceDirectiveKey/Value` directives to consensus. PlacementPolicy (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL) flows from SliceTargetValue through CDM allocation |
| 85 | Worker management API | Complete | `GET /api/workers`, `GET /api/workers/health`, `GET /api/workers/endpoints`. `POST /api/scale` accepts `placement` parameter. CLI: `workers list`, `workers health`, `scale --placement` |
| 86 | Core-to-core SWIM health | Complete | `CoreSwimHealthDetector` bridges SWIM `FAULTY`/`LEFT` events to `DisconnectNode`. SWIM is sole failure detector (NCN Ping/Pong removed). Detection in 1-2s vs TCP disconnect 15s-2min. Shares Server's workerGroup via `start(Option<EventLoopGroup>)`. TCP disconnect decoupled from topology — only triggers reconnection |
| 87 | Automatic topology growth | Complete | CDM assigns core vs worker role to joining non-seed nodes. `RabiaEngine` activation gating: seed nodes auto-activate on quorum, non-seed nodes wait for `ActivateConsensus` from CDM. `TopologyConfig` extended with `coreMax`/`coreMin`. Management API: `GET /api/cluster/topology`. CLI: `aether topology status` |
| 88 | DHT-backed ReplicatedMap | Complete | Generic typed `ReplicatedMap<K,V>` abstraction over `DHTClient` with namespace-prefixed keys, serialization, and `MapSubscription` event callbacks. Drain loop (trampoline) prevents subscriber re-entrance from delivering stale notifications. `CachedReplicatedMap` adds LRU + TTL caching. `aether/aether-dht/` module |
| 89 | Community-aware replication | Complete | `ReplicationPolicy` with home-replica rule (1 home + 2 ring replicas = RF=3). `HomeReplicaResolver` for deterministic community-local selection. Spot-node exclusion via `ConsistentHashRing.nodesFor(key, count, filter)` |
| 90 | Endpoint DHT migration | Complete | Endpoints moved from consensus KV-Store to DHT `ReplicatedMap`. `EndpointRegistry` fed by DHT subscription events. `NodeDeploymentManager` writes endpoints via DHT. O(3) write amplification vs O(N) with consensus |
| 91 | Replication cooldown | Complete | Startup RF=1 with background push to RF=3 after configurable delay. Rate-limited to prevent boot storm |
| 92 | Governor mesh (infrastructure) | Complete | `GovernorMesh` and `GovernorDiscovery` for cross-community DHT traffic routing. Governors advertise routable address (auto-detect or configurable `advertise_address`). Cross-host TCP connectivity functional |
| 97 | Multi-group worker topology | Complete | Zone-aware group computation from SWIM membership. `WorkerGroupId` (`groupName:zone`), `GroupAssignment` (deterministic splitting at configurable `maxGroupSize`), `GroupMembershipTracker`. Per-group governor election and Decision relay. `GovernorAnnouncementKey/Value` in consensus for core-side community tracking |
| 98 | CDM community-aware placement | Complete | `AllocationPool` extended with `workersByCommunity`. CDM tracks `GovernorAnnouncementValue` per community. `WorkerSliceDirectiveValue` extended with optional `targetCommunity` for scoped deployment |
| 99 | Worker zone configuration | Complete | `WorkerConfig` extended with `groupName`, `zone`, `maxGroupSize`. TOML: `worker.group_name`, `worker.zone`, `worker.max_group_size`. Backward compatible — defaults produce single "default:local" group |
| 93 | DHT node cleanup | Complete | `DhtNodeCleanup` removes dead node's endpoints from DHT maps on SWIM DEAD detection |
| 94 | SliceNodeKey DHT migration | Complete | SliceNodeKey reads/writes moved from consensus to `slice-nodes` ReplicatedMap. CDM and NDM write via DHT. 5 subscribers via `asSliceNodeSubscription()` adapters |
| 95 | HttpNodeRouteKey DHT migration | Complete | HttpNodeRouteKey reads/writes moved from consensus to `http-routes` ReplicatedMap. HttpRoutePublisher writes via DHT. 3 subscribers via `asHttpRouteSubscription()` adapters |
| 103 | Compound KV-Store key types | Complete | `NodeArtifactKey` merges EndpointKey + SliceNodeKey into single per-node-per-artifact entry; `NodeRoutesKey` merges HttpNodeRouteKey into compound routes. ~10x entry count reduction. All publishers write only new types. All consumers handle new types via KVNotificationRouter. CDM cleanup migrated to new key types. WorkerNetwork eliminated — inter-worker messaging consolidated into NCN via DelegateRouter |
| 96 | DHT replication config | Complete | `[dht.replication]` TOML section: `cooldown_delay_ms`, `cooldown_rate`, `target_rf`. Environment-aware defaults |
| 100 | Event-based community scaling | Complete | Governors monitor follower metrics via `WorkerMetricsPing`/`Pong`, detect sustained threshold breaches (CPU >80%, P95 >500ms, error rate >10%), send `CommunityScalingRequest` to core. Zero baseline bandwidth. `CommunityScalingEvaluator` (sliding window, cooldown), `WorkerMetricsAggregator` (periodic aggregation). Core `ControlLoop` validates and applies scaling. On-demand `CommunityMetricsSnapshot` for diagnostics/dashboard |
| 101 | Governor advertised address | Complete | Governors announce routable TCP address instead of `0.0.0.0`. Auto-detects via `InetAddress.getLocalHost()` or configurable `worker.advertise_address` in TOML. Required for cross-host governor mesh |

## Known Limitations

| Area | Limitation | Planned Fix |
|------|-----------|-------------|
| Security | No per-endpoint role authorization (all API keys get same access) | RBAC Tier 2 (#63) |
| Security | Node certificates are self-signed (no external CA integration) | External CA provider SPI implementation |
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
| 65 | Spot instance support | Planned | Elastic pool of spot/preemptible instances for cost-optimized scaling. Core (on-demand) + elastic (spot) pools. Prerequisite: Cloud Integration. Deferred from worker pool Phase 2 to Phase 3 |
| 66 | Cluster expense tracking | Planned | Real-time cost visibility from cloud billing APIs. Per-node, per-slice, per-request cost derivation. Budget alerts. Prerequisite: Cloud Integration |
| 67 | ~~TLS certificate management~~ | ~~Complete~~ | ~~Moved to Security section (features 88-91)~~ |
| 69 | KV-Store state backup | Planned | Periodic KV-Store snapshots to durable storage (filesystem, S3). Disaster recovery when quorum permanently lost |
| 70 | Aether runtime rolling upgrade | Planned | Upgrade Aether node software across running cluster without downtime. Node-by-node with health verification |
| 71 | Email notification resource | Complete | Phase 1: `integrations/net/smtp` (async Netty SMTP client), `integrations/email-http` (HTTP sender with SendGrid/Mailgun/Postmark/Resend SPI), `aether/resource/notification` (ResourceFactory + @Notify qualifier). SMTP and HTTP backends with retry. 57 tests |
| 103 | Per-blueprint artifact scoping (Tier 2) | Planned | Per-blueprint SliceTargetKey scoping for multi-tenant clusters. Blueprint-scoped CDM maps, WorkerSliceDirectiveKey blueprint scoping, Management API `blueprintId` parameter. Prerequisite: Tier 1 (#102) |

---

## Statistics

| Status | Count |
|--------|-------|
| Battle-tested | 24 |
| Complete | 102 |
| Partial | 2 |
| Planned | 11 |
| Total | 139 |

**Battle-tested features (24):** Blueprint management, Slice lifecycle, Rolling updates, Auto-healing, CPU-based auto-scaling, Rabia consensus, Leader election, Quorum state management, Topology management, Distributed KV-Store, Service-to-service invocation, Version routing, Artifact repository, Distributed hash table, System metrics, Cluster metrics API, Prometheus export, REST management API, Forge simulator, Graceful quorum degradation, Health check endpoint, Message delivery (pub-sub), E2E test framework, Forge integration tests

**Partial features and their gaps:**

| Feature | Key Gap |
|---------|---------|
| TTM predictive scaling | Disabled by default, no live model training |
| Schema Migration Prep | Schema files in blueprint, metadata in KV-Store, REST API + CLI; execution pending |

**Planned features:**

| Feature | Key Dependency |
|---------|---------------|
| Per-data-source DB schema management | Design spec ready |
| Canary & blue-green deployment | — |
| RBAC Tier 2 — per-endpoint authorization | RBAC Tier 1 complete |
| ~~TLS certificate management~~ | ~~Complete in 0.19.3~~ |
| Per-route rate limiting | — |
| Spot instance support | Cloud Integration (complete) |
| Cluster expense tracking | Cloud Integration (complete) |
| ~~KV-Store state backup~~ | ~~Complete in 0.19.3~~ |
| Aether runtime rolling upgrade | Official container or binaries |
| Per-blueprint artifact scoping (Tier 2) | Multi-blueprint lifecycle Tier 1 (#102) |

---

*Last updated: 2026-03-18 (v0.21.0)*
