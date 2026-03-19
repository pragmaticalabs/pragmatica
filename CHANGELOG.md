# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.21.0] - Unreleased

### Added
- **Per-datasource schema migration engine** — full migration execution engine with Flyway-style versioned (V), repeatable (R), undo (U), and baseline (B) migration types. Schema history tracked in `aether_schema_history` table per datasource. Checksum validation, transactional per-script execution, configurable failure/failover policy
- **Schema orchestration** — distributed coordination layer with consensus-based locking, artifact resolution, and status tracking (PENDING → MIGRATING → COMPLETED/FAILED). CDM integration gates slice deployment on schema readiness
- **Schema management REST API and CLI** — REST endpoints (`/api/schema/status`, `/api/schema/migrate/{ds}`, `/api/schema/undo/{ds}`, `/api/schema/baseline/{ds}`) and CLI commands (`aether schema status|history|migrate|undo|baseline`)
- **Blueprint artifact auto-packaging** — `generate-blueprint` goal now automatically packages the blueprint JAR (no need to add `package-blueprint` explicitly). Schema directory default changed to `${project.basedir}/schema`
- **Forge artifact-based deployment** — `--blueprint` accepts artifact coordinates with classifier (`groupId:artifactId:version:classifier`). Forge resolves via configured Repository chain (local Maven repo in dev, DHT in production). TOML deployment path removed
- **Enriched `/api/nodes` endpoint** — now returns role (CORE/WORKER) and isLeader flag per node, with role sourced from `ActivationDirectiveValue` in KV-Store
- **`GET /api/cluster/governors` endpoint** — exposes governor announcements from KV-Store: governor ID, community, member count, and member list

### Changed
- **KV-commit-driven allocation/deallocation** — slice allocation and deallocation now triggered exclusively by KV-Store commit notifications (`onSliceTargetPut`/`onSliceTargetRemove`), eliminating double-allocation race in blueprint handler
- **ReconciliationAdjustment events** — CDM emits scaling events to cluster event stream when reconciliation adjusts instance counts

### Fixed
- **Deployment flow audit** — comprehensive CDM/NDM handoff audit: schema migration gate blocks ACTIVATE until migrations complete, exclusive schema lock acquisition prevents split-brain races, allocation index bounds check prevents IOOBE, drain eviction excluded from reconciliation, retry counters scoped to (artifact, node), optimistic sliceStates write removed, stuck timeout multiplier increased to 3×, blueprint stores combined into single consensus batch
- **Timeout failure misclassification** — `updateSliceStateWithRetry` re-classified already-classified failures through a string round-trip, converting transient timeouts (`CoreError.Timeout` → `Intermittent`) into fatal errors (`Fatal.UnexpectedError`). Pre-classified `failureReason` and `fatal` flag now passed directly to `NodeArtifactValue`
- **Consensus pipeline saturation during activation** — all consensus operations in NDM activation chain (topic subscriptions, scheduled tasks, endpoints, cleanup) now use `applyWithRetry` with 30s timeout × 2 retries, matching state transition retry behavior. Previously only `updateSliceStateWithRetry` had retry logic; bare `cluster.apply().timeout()` calls would fail under multi-slice deployment load
- **JBCT compliance across deployment subsystem** — factory methods for `SliceNodeKey`, `SliceDeployment`, `SuspendedSlice`, `ParsedArtifactCoords`; null checks replaced with `Option.option()`; multi-statement lambdas extracted to named methods; `create*Command` renamed to `build*Command`; `seedNodes` changed to `Set.copyOf()`; blueprint iteration snapshot in reconcile; `fold()` replaced with `.map().or()`
- **`coreMax` config wiring** — `core_max` from TOML `[cluster]` section now threaded through ConfigLoader → AetherConfig → AetherNodeConfig → TopologyConfig. Previously always defaulted to 0 (unlimited), preventing worker node assignment
- **Blueprint artifact resolution** — `publishFromArtifact` resolves via configured Repository chain (local Maven, DHT) with explicit classifier support. Clear error on missing classifier
- **Leader election reliability** — `triggerElection()` now defers with retry when called before LeaderManager is active, instead of silently dropping. Fixes flaky leader election in Forge (single-JVM multi-node) where rank-0 node's trigger was lost due to startup race
- **NDM promise chain ordering** — failure/success handlers in loading, activation, deactivation, and unloading chains changed from `onFailure`/`onSuccess` (async) to `withFailure`/`withSuccess` (sequential), preventing state write races
- **Activation timeout alignment** — ACTIVATING stall timeout (90s) and NDM activation chain timeout (90s) aligned; stall detector fires at 3 min (2× multiplier), after NDM has had time to fail and write FAILED state
- **Consensus operation timeouts** — all `cluster.apply()` calls in NDM now have 15s timeout, preventing orphaned Rabia proposals from hanging activation chains forever
- **Double slice allocation** — blueprint handler no longer allocates directly; allocation deferred to `onSliceTargetPut` notification, fixing race where 5 instances were created instead of 3
- **Cloud providers — AWS, GCP, Azure** — complete cloud integration for all major providers:
  - `integrations/xml/jackson-xml` — XML mapper module (Jackson XML) mirroring `JsonMapper` pattern, needed for AWS EC2 XML responses
  - `integrations/cloud/aws` — AWS cloud client with SigV4 signing from scratch, EC2 (XML), ELBv2 (JSON), Secrets Manager (JSON). No AWS SDK
  - `integrations/cloud/gcp` — GCP cloud client with RS256 JWT token management, Compute Engine, Network Endpoint Groups, Secret Manager. No GCP SDK
  - `integrations/cloud/azure` — Azure cloud client with dual OAuth2 tokens (management + Key Vault), ARM REST API, Resource Graph KQL, Key Vault. No Azure SDK
  - `aether/environment/aws` — AWS environment integration: EC2 compute, ELBv2 load balancing, tag-based discovery, Secrets Manager
  - `aether/environment/gcp` — GCP environment integration: Compute Engine, NEG load balancing, label-based discovery, Secret Manager
  - `aether/environment/azure` — Azure environment integration: VM compute, LB backend pools, Resource Graph discovery, Key Vault secrets
  - CDM `completeDrain()` now calls `ComputeProvider.terminate()` to stop billing on drained cloud VMs. Tag-based instance lookup via `aether-node-id`. Works uniformly for all providers (Hetzner, AWS, GCP, Azure)
  - `AetherNode` applies `aether-node-id` tag on startup via IP-based self-identification for CDM terminate correlation
  - `ComputeProvider` SPI extended: `provision(ProvisionSpec)` for detailed specs, `listInstances(TagSelector)` typed filter
  - `LoadBalancerProvider` SPI extended: 7 new default methods — `createLoadBalancer`, `deleteLoadBalancer`, `loadBalancerInfo`, `configureHealthCheck`, `syncWeights`, `deregisterWithDrain`, `configureTls`
  - `SecretsProvider` SPI extended: `resolveSecretWithMetadata`, `resolveSecrets` (batch), `watchRotation`
  - `CachingSecretsProvider` — TTL-cached wrapper for any SecretsProvider
  - New SPI types: `ProvisionSpec`, `TagSelector`, `LoadBalancerSpec`, `LoadBalancerInfo`, `HealthCheckConfig`, `TlsTerminationConfig`, `SecretValue`, `SecretRotationCallback`
- **Cloud integration — Hetzner end-to-end** — complete Hetzner Cloud integration for real cloud testing:
  - `SecretsProvider` implementations: `EnvSecretsProvider` (AETHER_SECRET_* env vars), `FileSecretsProvider` (/run/secrets files), `CompositeSecretsProvider` (first-success chain). Zero cloud dependencies, universal fallback
  - `DiscoveryProvider` SPI: label-based peer discovery replacing static TOML peer lists. `discoverPeers()`, `watchPeers()` (polling), `registerSelf()`/`deregisterSelf()`. Wired into AetherNode bootstrap — registers on start, deregisters on graceful shutdown
  - `HetznerDiscoveryProvider`: discovers peers via `aether-cluster` server labels, extracts host/port from private IPs and `aether-port` label, configurable poll interval
  - `ComputeProvider` extensions: `restart()`, `applyTags()`, `listInstances(tagFilter)` with default implementations. Hetzner provider overrides all three using API reboot/label update/label selector
  - `InstanceInfo.tags` field for cloud metadata passthrough (server labels → instance tags)
  - `HetznerClient` extensions: `listServers(labelSelector)`, `updateServerLabels()`, `rebootServer()`, `Server.labels` field
  - `EnvironmentIntegration.discovery()` facet with backward-compatible wiring
- **Blueprint Artifact Transition** — blueprints packaged as deployable JAR artifacts:
  - **Blueprint artifacts**: Blueprints are now packaged as deployable JAR artifacts containing `blueprint.toml`, optional `resources.toml` (app-level config), and optional `schema/` directory (database migration scripts)
  - **`PackageBlueprintMojo`**: New Maven plugin goal (`package-blueprint`) produces classifier `blueprint` JARs with `Blueprint-Id` and `Blueprint-Version` manifest entries
  - **`publishFromArtifact`**: New deployment path — upload blueprint JAR to ArtifactStore, then deploy via `POST /api/blueprint/deploy` or `aether blueprint deploy <coords>`
  - **Config separation**: Application config (`resources.toml`) travels with blueprint at GLOBAL scope; infrastructure endpoints (`[endpoints.*]` in `aether.toml`) stay at NODE scope. ConfigService merges both hierarchically (SLICE > NODE > GLOBAL)
  - **Schema migration prep**: Blueprint artifacts can carry `schema/{datasource}/*.sql` migration scripts (Flyway-style naming: V/R/U/B prefixes). Schema metadata stored in KV-Store for future DB migration execution
  - **New KV types**: `BlueprintResourcesKey/Value`, `SchemaVersionKey/Value`, `SchemaMigrationLockKey/Value` for blueprint resources and schema tracking
  - **CLI commands**: `blueprint deploy <coords>` and `blueprint upload <file>` for artifact-based blueprint deployment
- **Notification resource (Phase 1 — Email)** — three new modules delivering async email notifications:
  - `integrations/net/smtp` — async SMTP client on Netty with STARTTLS, IMPLICIT TLS, AUTH PLAIN/LOGIN, multi-recipient support, connection-per-send. Full state machine (GREETING→EHLO→STARTTLS→AUTH→MAIL FROM→RCPT TO→DATA→QUIT)
  - `integrations/email-http` — HTTP email sender with pluggable vendor mappings via SPI. Built-in: SendGrid, Mailgun, Postmark, Resend. Hand-built JSON/form-data (no Jackson dependency)
  - `aether/resource/notification` — thin Aether resource wiring (`NotificationSender` + `NotificationSenderFactory`). Routes to SMTP or HTTP backend based on config. Exponential backoff retry. `@Notify` resource qualifier annotation for slice injection

## [0.20.0] - 2026-03-17

### Added
- **Scheduled task ExecutionMode** — replaced `boolean leaderOnly` with `ExecutionMode` enum (`SINGLE`, `ALL`). `SINGLE` (default) fires on leader only, `ALL` fires independently on every node with the slice deployed. TOML: `executionMode = "ALL"` in `[scheduling.*]` sections
- **Blueprint pub-sub validation** — deploy-time validation rejects blueprints where a publisher topic has no subscriber. `PubSubValidator` cross-references all publisher/subscriber config sections across all slices in the blueprint. Orphan publishers produce a descriptive error and the blueprint is not deployed
- **Transaction-mode connection pooling** — postgres-async driver now supports `PoolMode.TRANSACTION` which multiplexes N logical connections over M physical connections. Borrows per-query/transaction, returns on completion. Includes prepared statement migration across physical backends, LISTEN/NOTIFY pinning, nested transaction (savepoint) support, and `ReadyForQuery` transaction status parsing. Eliminates need for external PgBouncer
- **Compound KV-Store key types** — `NodeArtifactKey` (replaces per-method EndpointKey + SliceNodeKey) and `NodeRoutesKey` (replaces per-route HttpNodeRouteKey) with compound values. Single writer per node per artifact, ~10x reduction in entry count and consensus commits
- **Hybrid Logical Clock** — new `integrations/hlc` module providing `HlcTimestamp` (packed 48-bit micros + 16-bit counter) and thread-safe `HlcClock` with drift detection, used for DHT versioned writes
- **Cron scheduling** — wired existing `CronExpression` parser into `ScheduledTaskManager` with one-shot+re-schedule pattern. Cron tasks fire at the next matching time, then re-schedule automatically
- **Weeks interval unit** — `IntervalParser` now supports `w` suffix (e.g., `2w` = 14 days) for schedules that cron can't express naturally
- **Pause/resume scheduled tasks** — operators can pause and resume individual scheduled tasks via REST API (`POST .../pause`, `.../resume`) and CLI (`scheduled-tasks pause/resume`). Paused state persisted in KV-Store through consensus
- **Manual trigger** — fire any scheduled task immediately via REST API (`POST .../trigger`) or CLI (`scheduled-tasks trigger`), regardless of schedule or paused state
- **Execution state tracking** — `ScheduledTaskStateRegistry` tracks last execution time, consecutive failures, total executions per task. State written to KV-Store after each execution (fire-and-forget). REST API responses enriched with execution metrics
- **Execution state endpoint** — `GET /api/scheduled-tasks/{config}/{artifact}/{method}/state` returns detailed execution state including failure messages
- **Centralized timeout configuration** — all operator-facing timeouts consolidated into `TimeoutsConfig` with 14 subsystem groups. TOML `[timeouts.*]` sections with human-readable duration strings (`"5s"`, `"2m"`, `"500ms"`). Covers invocation, forwarding, deployment, rolling updates, cluster, consensus, election, SWIM, observability, DHT, worker, security, repository, and scaling. Legacy `_ms` fields (`forward_timeout_ms`, `cooldown_delay_ms`) supported with automatic migration. Reference: `aether/docs/reference/timeout-configuration.md`

### Changed
- **Invocation timeouts reduced** — server-side timeout 25s→15s, client-side invoker timeout 30s→20s. Faster failure detection for stuck invocations
- **Activation chain timeout increased** — 2m→5m to accommodate loading (2m) + activating (1m) with headroom
- **Local repository locate timeout reduced** — 30s→10s (local filesystem operations don't need 30s)
- **Config record field standardization** — all `long *Ms`/`int *Seconds`/`Duration` fields in config records replaced with `TimeSpan`. Affected: `AppHttpConfig`, `WorkerConfig`, `TtmConfig`, `RollbackConfig`, `AlertConfig.WebhookConfig`, `NodeConfig`, `PassiveLBConfig`
- **Control plane KV-Store migration (complete)** — all control plane data migrated from DHT to KV-Store with compound key types. Publishers write only `NodeArtifactKey`/`NodeRoutesKey` (no dual-write). All consumers (EndpointRegistry, DeploymentMap, HttpRouteRegistry, ControlLoop, ArtifactDeploymentTracker, LoadBalancerManager) handle new types via KVNotificationRouter. CDM cleanup uses new key types for stale entry removal. ~10x reduction in consensus commits per deployment
- **WorkerNetwork eliminated** — consolidated inter-worker TCP transport into NettyClusterNetwork (NCN) via PassiveNode's DelegateRouter. Workers now use a single Netty TCP stack instead of two. All inter-worker messaging (mutations, decisions, snapshots, metrics, DHT relay) flows through NCN's `Send`/`Broadcast` messages
- **Server UDP support** — `Server` now supports optional UDP port binding alongside TCP, sharing the same workerGroup (EventLoopGroup). Configured via `ServerConfig.withUdpPort()`. Foundation for future lightweight UDP messaging
- **SWIM sole failure detector** — removed NCN's Ping/Pong keepalive. SWIM is now the only failure detection mechanism. Eliminates redundant probing and simplifies the network layer
- **SWIM shared thread pool** — `NettySwimTransport` can use Server's workerGroup instead of creating a separate `NioEventLoopGroup(1)`. Passed via `CoreSwimHealthDetector` on quorum establishment
- **HTTP server shared EventLoopGroups** — `HttpServer` accepts external `EventLoopGroup` instances via new factory overload. `NettyHttpServer.createShared()` binds on provided groups without owning them (no shutdown on stop). AppHttpServer, ManagementServer, and AetherPassiveLB now share Server's boss/worker groups, reducing per-node thread pools from 6+ to 2
- **Worker module JBCT compliance** — converted 7 types (`MutationForwarder`, `GovernorCleanup`, `DecisionRelay`, `WorkerBootstrap`, `WorkerMetricsAggregator`, `WorkerDeploymentManager`, `GovernorElection`) from final classes/sealed interfaces to JBCT-compliant interfaces with local record implementations. Eliminated Mockito from all 7 worker test files, replaced with simple record stubs
- **DHT versioned writes** — every DHT put now carries an HLC version; storage rejects writes with version <= current, fixing out-of-order state overwrites (e.g., LOADED overwriting ACTIVE)
- **ReplicatedMap local cache** — `NamespacedReplicatedMap` now maintains a `ConcurrentHashMap` local cache with `forEach()` for iteration, enabling CDM to rebuild slice state from DHT
- **CDM state rebuild** — `ClusterDeploymentManager` rebuilds slice state from DHT `ReplicatedMap` instead of consensus KV-Store
- **DHT notification broadcasting** — active nodes broadcast DHT route mutations to passive peers (load balancers) via `DHTNotification` protocol messages

### Fixed
- **CDM reconciliation interval** — ClusterDeploymentManager was incorrectly wired to cluster topology interval (5s) instead of its own 30s deployment reconciliation cycle, causing 6x excessive reconciliation
- **TcpTopologyManager node resurrection race** — `get()`+`put()` pattern in connection failure/established handlers was not atomic; a concurrent `remove()` between the two calls could resurrect a removed node. Fixed with `computeIfPresent()` for atomic read-modify-write
- **Route eviction on node death** — `HttpRouteRegistry.evictNode()` existed but was never wired to `NodeRemoved` topology event. Dead nodes stayed in route tables until CDM's slow consensus-based cleanup completed (60s+). Now evicted immediately on disconnect. Also added `cleanupStaleNodeRoutes()` to periodic reconcile as defense-in-depth
- **NodeLifecycleKey race on restart** — `registerLifecycleOnDuty()` skipped write if key existed, but pending consensus batch could delete the stale key after the check. Now unconditionally writes ON_DUTY (only guards DECOMMISSIONED). Added `onRemove` defense-in-depth handler to re-register if key is unexpectedly removed
- **CDM LOAD command tracking race** — `issueLoadCommand()` put LOAD in `sliceStates` before consensus confirmed, causing phantom instances that blocked reconcile retries. Moved tracking to `withSuccess` callback
- **NDM pending LOAD scan** — NDM now scans KV-Store for pending LOAD commands on activation, catching commands issued by CDM before NDM transitioned from Dormant
- **Worker thread bottleneck offloading** — SWIM `DisconnectNode` routing uses `routeAsync` to avoid blocking shared SWIM thread. `StaticFileHandler` caches classpath resources in `ConcurrentHashMap` to eliminate repeated blocking I/O
- **HTTP forwarding zero-copy bodies** — removed unnecessary defensive `byte[]` cloning from `HttpRequestContext` and `HttpResponseData` constructors and accessors, eliminating ~4 array copies per forwarded request
- **Anti-entropy migration HLC poisoning** — migration data now carries HLC versions and uses `putVersioned()` instead of unversioned `put()` which was storing with `Long.MAX_VALUE`, permanently blocking all subsequent versioned writes to affected keys
- **GitBackedPersistence** — configure git user email/name after `git init` to prevent commit failures on CI runners without global git config
- **ReadTimeoutHandler removed** — Netty `ReadTimeoutHandler` removed from cluster network; SWIM health detection handles peer liveness instead
- **ReplicatedMap async notification race** — `NamespacedReplicatedMap` used `.onSuccess()` (async dispatch) for cache updates and subscriber notifications, causing rapid state transitions (LOADED→ACTIVE) to arrive out of order at CDM. Changed to `.withSuccess()` (synchronous dispatch) to preserve causal write ordering
- **ReplicatedMap subscriber re-entrance** — synchronous notification delivery exposed a re-entrance bug: when subscriber callbacks trigger nested puts (e.g., CDM reacting to LOADED by issuing ACTIVATE), the outer `forEach` continued delivering stale values to later subscribers (DeploymentMap). Replaced with drain loop (trampoline pattern) that enqueues notifications and processes them iteratively, ensuring each state transition is fully delivered to all subscribers before the next begins
- **Full DHT replication for control plane** — AetherMaps now uses `DHTConfig.FULL` replication so all nodes receive all control plane notifications (slice-nodes, endpoints, routes), fixing notification delivery gaps on non-replica nodes
- **Route eviction on node departure** — removed redundant `routeRegistry.evictNode()` call from `HttpForwarder`; DHT cleanup handles route removal
- **RemoteRepositoryTest** — assertion updated to accept both "Download failed" and "HTTP operation failed" error messages after HttpOperations refactor
- **CodecProcessor doubly-nested types** — `@Codec` annotation processor now recursively scans nested helper types inside permitted subclasses (e.g., `RouteEntry` inside `NodeRoutesValue`). Previously only scanned one nesting level, causing `No codec registered` errors at runtime
- **Virtual thread starvation in example tests** — `InMemoryDatabaseConnector` now uses synchronous `Promise.resolved()` instead of async `Promise.lift()` for in-memory operations, preventing carrier thread starvation on low-vCPU CI runners
- **Test await timeouts** — all example test `await()` calls now use 10-second timeouts to prevent indefinite hangs on resource-constrained environments

## [0.19.3]

### Multi-Blueprint Lifecycle
- Fixed critical bug: blueprint deletion now only removes artifacts owned by the deleted blueprint (was removing ALL artifacts)
- Fixed critical bug: `owningBlueprint` field in SliceTargetValue now correctly populated during blueprint deployment
- Added artifact exclusivity enforcement — prevents two blueprints from deploying the same artifact (rejects with descriptive error)
- Added deletion guard — prevents blueprint deletion while its artifacts have active rolling updates
- CDM state restore now correctly populates blueprint ownership from KV-Store
- Added `SliceTargetValue.sliceTargetValue(Version, int, int, Option<BlueprintId>)` factory

### Added
- **Governor mesh advertised address** — governors now announce a routable TCP address instead of hardcoded `0.0.0.0`. Auto-detects via `InetAddress.getLocalHost()` or uses configurable `advertise_address` in `[worker]` TOML section. Fixes cross-host governor mesh connections
- **Event-based community scaling** — governors monitor follower metrics locally and send scaling requests to core only when thresholds are sustained. Zero baseline bandwidth. Architecture:
  - **Worker metrics messages** — `WorkerMetricsPing`/`WorkerMetricsPong` between governor and followers (~100 bytes per pong)
  - **Community scaling messages** — `CommunityScalingRequest` (governor→core, event-driven), `CommunityMetricsSnapshotRequest`/`CommunityMetricsSnapshot` (core→governor, on-demand diagnostics)
  - **CommunityScalingEvaluator** — sliding window (5 samples × 5s default) with sustained-breach detection for CPU, P95 latency, error rate. Per-direction cooldown prevents thrashing
  - **WorkerMetricsAggregator** — governor-side component with periodic ping cycle, follower pong collection, JMX self-metrics, stale cleanup, evaluator integration
  - **ControlLoop community scaling handler** — validates evidence freshness (<30s), checks blueprint existence and cooldown, applies scaling via existing KV-Store path, publishes ScalingEvent
  - **Scaling cap includes workers** — `prepareChangeToBlueprint()` now counts worker nodes in cluster size for scaling cap calculation
  - **ClusterEvent types** — added `COMMUNITY_SCALE_REQUEST` and `COMMUNITY_METRICS_SNAPSHOT` to EventType enum

- **Passive Worker Pools Phase 2a — DHT-Backed ReplicatedMap** — moves high-cardinality endpoint data from consensus KV-Store to DHT, reducing write amplification from O(N) to O(3):
  - **`aether/aether-dht` module** — generic typed `ReplicatedMap<K,V>` abstraction with namespace-prefixed keys, `MapSubscription` event callbacks, `CachedReplicatedMap` (LRU + TTL), `ReplicatedMapFactory`
  - **Community-aware replication** — `ReplicationPolicy` with home-replica rule (1 home + 2 ring replicas = RF=3), `HomeReplicaResolver` for deterministic community-local selection, `ConsistentHashRing` spot-node exclusion filter
  - **Endpoint migration** — `EndpointRegistry` unified with DHT subscription events (core + worker endpoints in single registry), `NodeDeploymentManager` writes endpoints via DHT `ReplicatedMap`, `SliceInvoker` simplified to single-registry lookup
  - **Replication cooldown** — startup RF=1 with background push to RF=3 after configurable delay, rate-limited to prevent boot storm
  - **Governor mesh infrastructure** — `GovernorMesh` and `GovernorDiscovery` for cross-community DHT traffic routing (full wiring in Phase 2b)
  - **DHT node cleanup** — `DhtNodeCleanup` removes dead node endpoints from DHT on SWIM DEAD detection
  - **AetherMaps** — factory for 3 named maps (endpoints, slice-nodes, http-routes) with serializers
- **Worker Slice Execution (P1+P2a Completion)** — end-to-end worker node functionality: slices deployed with `WORKERS_PREFERRED` placement run on worker nodes, publish endpoints to DHT, and SliceInvoker routes traffic to workers:
  - **CDM worker awareness** — ClusterDeploymentManager discovers workers via `ActivationDirectiveKey(WORKER)`, populates `AllocationPool` with worker nodes, writes `WorkerSliceDirectiveKey/Value` directives to consensus for worker slice deployment
  - **PlacementPolicy in SliceTargetValue** — `placement` field (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL) added to slice target configuration. Management API `POST /api/scale` accepts optional `placement` parameter. CLI: `aether scale --placement`
  - **WorkerDeploymentManager** — sealed interface with Dormant/Active states managing slice lifecycle on workers: watches `WorkerSliceDirectiveKey` from KVNotificationRouter, self-assigns instances via consistent hashing of SWIM members, drives SliceStore load→activate chain, publishes endpoints and slice-node state to DHT
  - **WorkerInstanceAssignment** — deterministic consistent hashing for instance distribution across workers. Same inputs produce same assignment on every worker — no coordination needed
  - **Governor cleanup** — `GovernorCleanup` maintains per-node index of DHT entries (endpoints, slice-nodes, HTTP routes). On SWIM FAULTY/LEFT, governor removes dead node's entries from all three DHT maps. `GovernorReconciliation` runs on governor election to clean orphaned entries
  - **KVNotificationRouter on workers** — workers build notification router on PassiveNode's KVStore to watch `WorkerSliceDirectiveKey` entries, same pattern as AetherNode's notification wiring
  - **SliceNodeKey DHT migration** — SliceNodeKey reads/writes moved from consensus to `slice-nodes` ReplicatedMap. CDM, NDM, ControlLoop, DeploymentMap, ArtifactDeploymentTracker all subscribe via `asSliceNodeSubscription()` adapters
  - **HttpNodeRouteKey DHT migration** — HttpNodeRouteKey reads/writes moved from consensus to `http-routes` ReplicatedMap. HttpRoutePublisher, HttpRouteRegistry, AppHttpServer, LoadBalancerManager all subscribe via `asHttpRouteSubscription()` adapters
  - **WorkerEndpointRegistry removed** — dead code from Phase 1 replaced by DHT-backed endpoint registry. `WorkerRoutes`, `WorkerGroupHealthReport`, `WorkerEndpointEntry` deleted
  - **DHT replication config** — `[dht.replication]` TOML section for `cooldown_delay_ms`, `cooldown_rate`, `target_rf` with environment-aware defaults
- **Container image publishing** — `release.yml` builds multi-arch Docker images (amd64+arm64) via buildx, publishes to GHCR and Docker Hub. SHA256 checksums generated for all release artifacts
- **Upgrade script** (`aether/upgrade.sh`) — detects current version, downloads new JARs to temp dir, verifies SHA256 checksums, atomic binary swap with backup, running process detection
- **Rolling cluster upgrade script** (`aether/script/rolling-aether-upgrade.sh`) — API-driven zero-downtime upgrades: discovers nodes, drains → shuts down → waits for restart → activates → canary checks each node. Supports `--dry-run`, `--canary-wait`, `--api-key`, `--skip-download`
- **Passive worker pools design spec** (`aether/docs/specs/passive-worker-pools-spec.md`) — architecture for scaling to 10K+ nodes: elected governors, SWIM gossip, KV-Store split, auto flat↔layered transition, 3-phase rollout plan
- **Passive worker pools Phase 1** — foundation for scaling beyond Rabia consensus limits (5-9 nodes) with passive compute nodes:
  - **SWIM protocol module** (`integrations/swim/`) — UDP-based failure detection with periodic probes, indirect probing, piggybacked membership updates
  - **Worker node module** (`aether/worker/`) — WorkerNode composes PassiveNode + SWIM + Governor election + Decision relay + Mutation forwarding + Bootstrap
  - **Governor election** — pure deterministic computation (lowest ALIVE NodeId), no election messages exchanged
  - **Worker configuration** — `WorkerConfig` with SWIM settings, core node addresses, placement policy (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL)
  - **Worker endpoint registry** — non-consensus ConcurrentHashMap-based registry with round-robin load balancing, governor health report population
  - **SliceInvoker dual lookup** — core endpoints first, worker endpoints fallback via governor routing
  - **CDM pool awareness** — `AllocationPool` record, `WorkerSliceDirectiveKey`/`WorkerSliceDirectiveValue` in consensus KV-Store
  - **Worker management API** — `GET /api/workers`, `GET /api/workers/health`, `GET /api/workers/endpoints`
  - **CLI commands** — `aether workers list`, `aether workers health`

- **Multi-Group Worker Topology (Phase 2b)** — workers self-organize into zone-aware groups with per-group governors. Deterministic group computation from SWIM membership — same inputs produce same groups on every worker:
  - **WorkerGroupId** — `(groupName, zone)` identity record with `communityId()` format (`groupName:zone`)
  - **GroupAssignment** — deterministic zone-aware group computation: extracts zone from NodeId, splits zones exceeding `maxGroupSize` via round-robin subgroups
  - **GroupMembershipTracker** — tracks SWIM membership and computes zone-aware groups, exposes `myGroup()`, `myGroupMembers()`, `allGroups()`
  - **Per-group governor election** — governor election scoped to own group members, not all SWIM members
  - **Per-group Decision relay** — governor only relays Decisions to own group followers, reducing broadcast scope
  - **GovernorAnnouncementKey/Value** — governors announce themselves to consensus KV-Store. Core nodes track community sizes and governor identities via `ClusterDeploymentManager`
  - **CDM community-aware placement** — `AllocationPool` extended with `workersByCommunity` map. CDM tracks governor announcements for community-aware instance distribution. End-to-end wiring: CDM distributes instances across communities, writes per-community directives, workers filter by targetCommunity
  - **WorkerSliceDirectiveValue** extended with optional `targetCommunity` for community-scoped deployment
  - **AetherKey community serialization** — `GovernorAnnouncementKey` round-trip through KV-Store backup/restore with pipe-delimited communityId format
  - **Worker configuration** — `WorkerConfig` extended with `groupName` (default `"default"`), `zone` (default `"local"`), `maxGroupSize` (default `100`). TOML: `worker.group_name`, `worker.zone`, `worker.max_group_size`

- **KV-Store durable backup** — serializes cluster metadata (slice targets, node lifecycle, config) to a single TOML file managed in a local git repo. Git provides versioning, history, diffs, and optional remote push for offsite backup
  - **TOML Writer** (`integrations/config/toml`) — serialization support added to the custom TOML library, including inline table parsing
  - **KV-Store serializer** (`aether/slice`) — converts all 18 AetherKey/AetherValue types to/from TOML with pipe-delimited values grouped by key-type sections
  - **Git-backed persistence** (`integrations/consensus`) — `GitBackedPersistence` implements `RabiaPersistence` using git CLI via ProcessBuilder for atomic snapshots
  - **Backup configuration** — `[backup]` TOML section with enabled, interval, path, remote fields and environment-aware defaults
  - **Management API** — `POST /api/backup`, `GET /api/backups`, `POST /api/backup/restore`
  - **CLI commands** — `aether backup trigger`, `aether backup list`, `aether backup restore <commit>`

- **SWIM core-to-core health detection** (P1.13) — replaces TCP disconnect as health signal for core nodes. `CoreSwimHealthDetector` bridges SWIM membership events to `TopologyChangeNotification`. 1-2s failure detection vs 15s-2min with TCP. TCP disconnect no longer triggers topology removal — only SWIM `FAULTY`/`LEFT` does
- **Automatic topology growth** (P1.14) — CDM dynamically assigns core vs worker role to joining nodes. `RabiaEngine` activation gating: seed nodes auto-activate, non-seed nodes wait for CDM authorization. `TopologyConfig` extended with `coreMax`/`coreMin`. New `TopologyGrowthMessage` sealed interface (`ActivateConsensus`, `AssignWorkerRole`). Management API: `GET /api/cluster/topology`. CLI: `aether topology status`
- **E2E test rework: container networking** — replaced dual-mode networking (Linux host / macOS bridge with PID-based port allocation) with standard bridge networking for all platforms. All containers use identical internal ports (8080/8090) and communicate via DNS. Eliminates port conflicts and enables realistic test scenarios
- **E2E test scenarios** — 8 new tests leveraging container networking:
  - `RollingRestartE2ETest` — zero-downtime sequential node restart
  - `SwimDetectionE2ETest` — SWIM failure detection timing bound
  - `NodeDrainE2ETest` — graceful drain lifecycle via management API
  - `NetworkPartitionE2ETest` — minority partition isolation and reconvergence
  - `SliceLifecycleE2ETest` — full deploy/scale/invoke/undeploy cycle
  - `TopologyGrowthE2ETest` — dynamic node addition to running cluster
  - `LoadBalancerFailoverE2ETest` — slice invocation rerouting after failure
  - `LeaderIsolationE2ETest` — leader disconnect recovery without split-brain

### Security
- **Inter-node mTLS** — CertificateProvider SPI with SelfSignedCertificateProvider (BouncyCastle EC P-256, HKDF deterministic CA from shared `clusterSecret`). All TCP transports (consensus, DHT, management, app HTTP) secured with mutual TLS
- **SWIM gossip encryption** — AES-256-GCM symmetric encryption for all SWIM protocol messages. Wire format: `[keyId][nonce][ciphertext+GCM tag]`. Dual-key support for seamless rotation
- **Certificate renewal scheduler** — automatic renewal at 50% of validity (3.5 days for 7-day certs), 1-hour retry on failure
- **Gossip key rotation** — `GossipKeyRotationKey`/`GossipKeyRotationValue` in consensus KV store for coordinated key rotation
- **TLS by default** — DOCKER and KUBERNETES environments enable TLS automatically. `clusterSecret` configurable via TOML `[tls]` section or `AETHER_CLUSTER_SECRET` env var (dev default: `aether-dev-cluster-secret`)
- **Unit tests** — SelfSignedCertificateProviderTest (8 tests: deterministic CA, cert issuance, gossip key), AesGcmGossipEncryptorTest (10 tests: round-trip, dual-key, error cases), TlsConfig fromProvider bridge tests (4 tests)

### Changed
- Dockerfile version labels now use build-arg `VERSION` instead of hardcoded values
- TCP disconnect in `NettyClusterNetwork` no longer fires topology removal — reconnection continues while SWIM handles health detection
- `TcpTopologyManager` never routes `RemoveNode` on connection failure — always continues reconnection with backoff
- Dockerfile source URLs updated to `pragmaticalabs/pragmatica`
- `install.sh` enhanced with `--version` flag, SHA256 checksum verification, WSL2 detection
- Root `install.sh` references `main` branch instead of `release-0.19.3`

### Fixed
- `AetherNode.VERSION` updated from `0.19.0` to `0.19.3`
- `AetherUp.VERSION` updated from `0.7.2` to `0.19.3`
- **SWIM codecs not registered** — `NodeCodecs` was missing `SwimCodecs.CODECS`, causing all SWIM probes to fail silently
- **SWIM false positives during startup** — deferred SWIM start to after quorum establishment to prevent marking alive nodes FAULTY during cluster formation
- **Activation gating** — `isSeedNode()` always returned true because `TcpTopologyManager` requires self in `coreNodes`. Replaced with explicit `activationGated` boolean on `AetherNodeConfig`/`NodeConfig`, passed through to `RabiaEngine`
- **Passive LB false FAULTY** — removed SWIM from passive LB; core nodes don't know about the LB as a SWIM peer, so indirect probes always fail, cascading to false FAULTY for all core nodes. LB gets health info through consensus data stream instead
- **SWIM selfAddress corruption** — `CoreSwimHealthDetector` used `0.0.0.0` as selfAddress, which would corrupt member addresses when piggybacked via SWIM refutation updates. Now uses actual host from topology config

## [0.19.2] - 2026-03-08

### Added
- **`jbct add-slice`** — scaffold new slice into existing project (creates source, test, routes, config, manifest in sub-package)
- **`jbct add-event`** — generate pub-sub event annotations + auto-append messaging config to `aether.toml`
- **`jbct init --version`** — override dependency versions for pre-release testing
- **Unified installer** (`install.sh`) — downloads jbct, aether CLI, and aether-forge
- **Scaffold scripts** — `run-forge.sh`, `start-postgres.sh`, `stop-postgres.sh`, `deploy-forge.sh`, `deploy-test.sh`, `deploy-prod.sh`, `generate-blueprint.sh`
- **ALL_OR_NOTHING deployment atomicity** — default for all blueprint deployments; no partial deploys
- **Blueprint auto-rollback** — on deployment failure, all slices revert to previous state automatically
- **Cause-based deployment retry** — error propagation through KV store with `SliceLoadingFailure` hierarchy
- **Database URL inference** — type, host, and database name inferred from JDBC URL; explicit fields optional
- **Optional database port** — URL-only configuration supported (no separate port field required)
- **Config service factory methods** — record validation via factory methods in config records

### Fixed
- CLI REPL mode with `-c` connection flag now works correctly
- CLI missing `/api/` prefix on 31 management API paths
- Double JSON serialization in management API responses (pre-serialized strings no longer re-wrapped)
- Scale command preserves existing `minInstances` from blueprint
- Rollback route/endpoint/subscription cleanup via `forceCleanupSlice`
- Reactivation failure cleanup — full cleanup chain on slice reload failure
- Topology graph edge routing — links start right, arrows enter left
- `Verify.Is.blank()` null-safe (no longer throws on null input)
- Format-check error message now includes file names
- Slice processor error messages include file reference and slice name
- Domain error recovery from failed Promises in `SliceRouter`
- Infinite reconciliation loop for deterministic deployment failures
- `install.sh` uses semver sort instead of `/releases/latest`

### Changed
- `TimeSpan` instead of `Duration` in `PoolConfig` (plain-number-as-seconds support)
- Partial nested record merge with `DEFAULT` strategy
- HelloWorld scaffold in own subpackage (consistent with `add-slice`)

## [0.19.1] - 2026-03-05

### Added
- **postgres-async integration** — native async PostgreSQL driver wired into Aether resource provisioning
  - `asyncUrl` config field on `DatabaseConnectorConfig` for transport selection (priority 20, preferred over JDBC/R2DBC)
  - `postgres-r2dbc-adapter` module — R2DBC SPI adapter over postgres-async (ConnectionFactory, Connection, Statement, Result, Row, RowMetadata)
  - `db-async` module — `AsyncSqlConnector` using postgres-async directly (zero adapter overhead) with LISTEN/NOTIFY support
  - `db-jooq-async` module — `AsyncJooqConnector` via R2DBC adapter for full jOOQ compatibility
- **Configurable IO threads for postgres-async** — `io_threads` field in `[database.pool_config]` controls Netty event loop thread count. Default `0` = auto-detect (`max(availableProcessors, 8)`). Removes single-thread serialization bottleneck that limited throughput to ~3500 req/s
- **PubSubTest** — Forge-based cross-node pub-sub integration test: deploys url-shortener + analytics slices, verifies click event delivery (single, multi-click, leader failover)
- **Dashboard topology graph** — Deployments tab now shows endpoint→slice→resource data flow graph (SVG, column-based DAG layout). Compile-time topology data: HTTP routes, resources, pub-sub topics extracted from `.manifest` files (envelope v6). REST endpoint `GET /api/topology`, included in WebSocket `INITIAL_STATE`
- **Topology swim-lane layout** — complete rewrite of topology graph renderer with per-slice swim lanes, Manhattan routing for cross-slice topic connectors (right gutter) and dependency edges (left gutter), HSL color-coded topic groups, hover highlighting (dims non-related elements), and search filtering
- **Per-slice topology wire format** — topology nodes carry `sliceArtifact`, edges carry `topicConfig`. Resources and topics are now per-slice (no more shared nodes). Cross-slice pub-sub matching connects all publishers to all subscribers with the same config (many-to-many)
- **Route declaration order preservation** — `RouteConfig`, `RouteConfigLoader`, and `TomlDocument` now preserve TOML declaration order using `LinkedHashMap` instead of `Map.copyOf()`

### Performance
- **postgres-async driver optimizations** — single-buffer DataRow (N+1→3 allocations per row), connection pool lock consolidation (3→1 lock acquisitions per getConnection), static protocol constants, ByteArrayOutputStream elimination in wire protocol parsing. Benchmarked: **50% lower p95 at 2000 req/s** (4.78ms→2.38ms), **35% lower p95 at 5000 req/s** (180ms→117ms)

### Changed
- **E2E test suite reduced from 13 to 2 classes** — removed 11 tests that fully overlap with Forge equivalents (ClusterFormation, NetworkPartition, NodeDrain, SliceDeployment, ManagementApi, SliceInvocation, RollingUpdate, GracefulShutdown, Metrics, Controller, Ttm). Kept ArtifactRepositoryE2ETest (unique DHT coverage) and NodeFailureE2ETest (simplified to 2 focused container-specific tests)
- **Forge tests moved to class-level cluster setup** — 8 test classes converted from per-method to `@BeforeAll/@AfterAll` with `@TestInstance(PER_CLASS)`, reducing ~300 cluster starts to ~50
- **Sleep-based stabilization replaced with health endpoint polling** — removed all `Thread.sleep()` stabilization in Forge tests, replaced with awaitility polling on `/api/health` ready+quorum status
- **CI restructured** — Forge tests run in `build-and-test` job (no Docker needed); E2E job slimmed to 20-min timeout with 2 focused test classes. 5 heavy Forge tests (`@Tag("Heavy")`) excluded from CI 2-core runners
- **NodeFailureE2ETest simplified** — rewritten from 3 ordered shared-cluster tests to 2 independent tests (single node failure + leader failover) extending AbstractE2ETest
- **E2E default cluster size reduced from 5 to 3** — `AbstractE2ETest.clusterSize()` returns 3; NodeFailureE2ETest overrides to 5
- **E2E timeouts reduced** — DEFAULT_TIMEOUT 30→15s, DEPLOY_TIMEOUT 3min→90s, RECOVERY_TIMEOUT 60→30s, QUORUM_TIMEOUT 120→60s, CI multiplier 2.0→1.5
- **Forge pom.xml** — `reuseForks=true` (was false), process timeout 1800s
- **postgres-async tests skipped by default** — all 15 test classes require Testcontainers/Docker; `<skipTests>true</skipTests>` in module pom

## [0.19.0] - 2026-03-02

### Added
- **Ember** — embeddable headless cluster runtime extracted from `forge-cluster` into `aether/ember/` module with fluent builder API (`Ember.cluster(5).withH2().start()`)
- **Remote Maven repositories** — resolve slices from Maven Central or private Nexus repos (`repositories = ["local", "remote:central"]`). SHA-1 verification, local cache to `~/.m2/repository`, auth from `settings.xml`
- **Passive Load Balancer** — cluster-aware `aether/lb/` module: passive node joins cluster network, receives route table via committed Decisions, forwards HTTP requests via internal binary protocol (no HTTP re-serialization). Smart routing to correct node, automatic failover on node departure, live topology awareness
- Load balancer integration in Ember/Forge — auto-starts passive LB on cluster boot, configurable via `[lb]` TOML section
- **NodeRole** — `ACTIVE`/`PASSIVE` roles in `NodeInfo` for cluster membership. Passive nodes excluded from quorum and leader election but receive committed Decisions
- **HttpForwarder** — extracted reusable HTTP request forwarding from `AppHttpServer` into `aether-invoke` module with round-robin selection, retry with backoff, and node departure failover

### Fixed
- `InvocationMetricsTest` — fixed stale factory name `forgeH2Server` → `emberH2Server`
- Passive LB topology bootstrap — self node now included in `coreNodes` list (required by `TcpTopologyManager`)
- Passive LB topology manager lifecycle — `start()` now activates topology manager reconciliation loop, enabling cluster peer connections and Decision delivery

### Changed
- **PassiveNode abstraction** — extracted reusable passive cluster node infrastructure (`DelegateRouter`, `TcpTopologyManager`, `NettyClusterNetwork`, `KVStore`, message wiring) from `AetherPassiveLB` into `integrations/cluster` module (`PassiveNode<K,V>` interface). Follows `RabiaNode` pattern: interface + factory + inline record + `SealedBuilder` routes
- k6 test scripts default to routing through passive LB (`FORGE_NODES` → LB URL). Per-node scripts use `FORGE_ALL_NODES`
- `RepositoryType` converted from enum to sealed interface with `Local`, `Builtin`, and `Remote` record variants
- `forge-cluster` module deleted — all cluster management code now in `aether/ember/` with `Ember*` naming
- `ForgeCluster` → `EmberCluster`, `ForgeConfig` → `EmberConfig`, `ForgeH2Server` → `EmberH2Server`, `ForgeH2Config` → `EmberH2Config`

## [0.18.0] - 2026-02-26

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
