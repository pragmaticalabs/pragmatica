# Aether Feature Catalog

Comprehensive inventory of all Aether Unified Application Runtime capabilities.

**Status legend:**
- **Battle-tested** — Proven through multi-node E2E tests with failure injection (node kills, partitions, leader failovers)
- **Complete** — Production-ready, tested (unit tests, possibly basic E2E)
- **Critical** — Requires significant development effort before production use
- **Partial** — Core implemented, gaps noted
- **Planned** — Designed but not yet implemented

---

## Terminology

Three storage/persistence concepts that are distinct and must not be conflated:

| Term | Abbreviation | What it is |
|------|-------------|------------|
| **Aether Persistence** | **AEP** | `@PgSql` type-safe relational persistence with compile-time SQL validation. Application-layer persistence for slice data in PostgreSQL. |
| **Consensus KV store** | — | The Rabia-replicated internal key-value store that holds cluster state (slice targets, routes, blueprints, topology). Internal infrastructure — not an application persistence layer. |
| **Aether Hierarchical Storage Engine** | **AHSE** | Tiered content-addressed block storage for artifacts and binary data (`integrations/storage`). |

---

## Deployment & Lifecycle

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 1 | Blueprint management | Battle-tested | Declarative TOML-based deployment specs with dependency ordering, validation, pub-sub orphan detection, and status tracking |
| 2 | Slice lifecycle | Battle-tested | Full state machine: DOWNLOADING, LOADING, STARTING, ACTIVE, UNLOADING, UNLOADED, FAILED. Per-node tracking via KV-Store |
| 3 | Unified deployment strategies | Battle-tested | Single `aether deploy` command and `/api/deploy` endpoint supporting immediate, canary (progressive traffic shift with auto-evaluation), blue-green (atomic switchover with instant rollback), and rolling (weighted traffic shifting with health thresholds) strategies. Includes promote, rollback, complete lifecycle, cleanup policies |
| 4 | Auto-healing | Battle-tested | Bidirectional convergence with metadata-aware scheduling: host-spread, zone-balanced provisioning. **Spot is opt-in per-role config** — a real market arm on AWS (rc3; `SpotMarketOptions`), loud-reject on providers without one (Hetzner rejects `SPOT`; enforced across providers by the W10 spot loud-fail validator); reclamation is handled as node failure via auto-heal; spot-aware placement is planned (demand-gated). PlacementHint-driven node placement. Node labels (hostname, zone, instance-type, pool) propagated via Hello handshake. CAS-based state transitions. Leader-only with failover. Cross-provider cluster-identity inheritance: replacement nodes receive the cluster-identity env allow-list (`AETHER_CLUSTER_NAME`, `AETHER_CLUSTER_SECRET`, `AETHER_PROVISIONED_BY`, `AETHER_API_KEY`; Docker also `AETHER_DOCKER_NETWORK`/`DOCKER_GID`) — Docker via `-e`, cloud via cloud-init userData (Azure now honors customData). Replacements reach READY in the leader's view; a replacement killed inside its joining window is re-provisioned. **Membership convergence is FSM-counted (#68/#94):** `LeaderReconciler` counts the per-member `MembershipFsm.countedMembers()` (MEMBER+SUSPECT), not NTT's presence set — a transient-SUSPECT replacement is never de-counted, eliminating the over-provisioning churn; NTT's debounced down-hysteresis crossing is routed into the FSM (`onDownHysteresisMet`) to bound SUSPECT (spec I4) so a sustained-absence node still departs once. **Membership-FSM unification (#68 storm root):** the FSM is now the single per-node membership authority every consumer reads (consensus broadcast targets `broadcastEligibleMembers()` not the transport peer cache — fixes the consensus dead-ULID retry-storm; quorum/forward-routing/DHT/quiesce-health all read the FSM); nodes self-describe `source`/`role` as SWIM labels; `NodeTopologyTracker` renamed `PresenceSampler` (debounce sensor). See [`membership-fsm-unification-spec.md`](../specs/membership-fsm-unification-spec.md) |
| 4a | Terminal-removal lifecycle (restart-disabled invariant) | Complete | A dead NodeId NEVER returns under the same identity: departure terminally removes the NodeId and recovery is always a brand-new node with a new ULID NodeId minted by auto-heal. **Container/process auto-restart MUST be disabled** for aether-node (`--restart no` / `restart: "no"` / `restartPolicy: Never` / systemd `Restart=no`) — auto-restarting under the same identity resurrects a terminally-removed NodeId and corrupts membership. Enforced in cloud-init (`UserDataTemplate` → `docker run --restart no`), systemd unit (`SystemdUnitTemplate` → `Restart=no`), and all node compose files (`restart: "no"`). See `aether/docs/operator/deployment-recovery.md` |
| 5 | Classloader isolation | Complete | Per-slice classloader prevents dependency conflicts between slices |
| 6 | Manifest versioning | Complete | Envelope format versioning (v1-v6) for backward-compatible manifest evolution |
| 66 | Compile-time serde | Complete | `@Codec` annotation processor generates `*Codec` classes for records, enums, and sealed interfaces with recursive nested type scanning. `SliceCodec` wire format with deterministic hash-based tags, VLQ encoding, zero runtime reflection. Replaces Fory/Kryo for slice boundary serialization |
| 102 | Multi-blueprint lifecycle independence | Complete | Blueprint-scoped artifact ownership (`owningBlueprint` in SliceTargetValue), artifact exclusivity enforcement (rejects duplicate artifact across blueprints), owner-filtered blueprint deletion (only removes owned artifacts), rolling update deletion guard, KV-Store restore with ownership. Tier 1 correctness for multi-blueprint clusters |
| 126 | Blueprint Artifacts | Complete | Blueprint packaged as JAR with resources.toml and schema/ |
| 127 | Config Separation | Complete | App config (blueprint) vs infra config (node) with hierarchical merge |
| 128 | Schema Migration Engine | Complete | End-to-end migration engine (V/R/U/B types), history table, checksum validation, orchestrator with exclusive consensus locking, CDM readiness gating (blocks ACTIVATE until COMPLETED), DatasourceConnectionProvider for SqlConnector provisioning, schema directory convention (`schema/` root = `[database]`, subdirectories = `[database.<name>]`), strict datasource resolution (no fallback), REST API, CLI. Failure recovery (auto-retry with exponential backoff 5s/15s/45s for transient failures, manual retry via REST/CLI), natural language SchemaEvent hierarchy, failure classification (transient vs permanent), `schema_required` blueprint config. **Dialect-aware statement splitter** (`aether/pg-tools/sql-splitter`, #337): pure lexer engine + per-dialect descriptors for PostgreSQL, MySQL/MariaDB, DB2, SQL Server, and Oracle (dollar-quoting, DELIMITER, --#SET TERMINATOR, GO, `/` PL/SQL terminator, Oracle alt-quoting, COPY-data). `AetherSchemaManager` runs all 5 via the splitter with a 2-mode TxStrategy (transactional PG/DB2/SQLServer; autocommit MySQL/Oracle). PG + MySQL validated against real DB containers; Oracle/DB2/SQLServer descriptor + unit only (real-engine validation deferred). H2/SQLite unchanged. **Atomic DDL + history (#338):** on transactional dialects (PG/DB2/SQL Server/H2/SQLite) the `aether_schema_history` INSERT runs inside the migration transaction — DDL + history commit as one unit, closing the applied-but-unrecorded crash window (proven by real-PG crash-injection test). **Internally-versioned history table (#338):** `aether_schema_history` is self-evolving via a meta-version table + ordered version-gated steps (v1 = original 8-column schema, v2 = dialect-aware ALTER adding `status` VARCHAR + `statements_completed` INT) — no non-portable `ADD COLUMN IF NOT EXISTS`; existing rows preserved on upgrade; idempotent re-run safe. **Autocommit checkpoint/resume (#338):** autocommit-dialect migrations (MySQL/Oracle) write an IN_PROGRESS row, checkpoint `statements_completed` after each statement, and finalize SUCCESS/FAILED. Re-entry skips durably-committed statements after checksum re-validation; `queryApplied` filters to `status='SUCCESS'` so partial rows never count as applied. Real-PG crash-injection + fake-connector resume tests (fail-at-K, resume skips 1…K, completes K+1…N) green. 714 aether-deployment tests green |
| 129 | Endpoint Config | Complete | `[endpoints.*]` sections in aether.toml for infrastructure endpoints |
| 130 | Deployment State Machine (RFC-0014) | Complete | Documented CDM/NDM handoff protocol, 11-state lifecycle, schema migration gate, dependency-gated activation, failure classification (fatal/transient), quorum loss/restoration, reconciliation algorithm, blueprint atomicity, drain eviction protocol |
| 131 | Consensus Operation Retry | Complete | All NDM consensus operations (state transitions, topic subscriptions, scheduled tasks, endpoints) use unified `applyWithRetry` with 30s timeout × 2 retries. Prevents activation failures under consensus pipeline saturation |
| 135 | A/B Testing | Complete | Deterministic traffic split by request context (header, cookie, percentage), ScopedValue variant propagation |

## Scaling & Control

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 7 | Per-slice auto-scaling | Partial | DecisionTreeController evaluates each artifact's own composite load (ACTIVE_INVOCATIONS + P95, error-rate gated) from per-slice `CommunityMetricsSnapshot` metrics; issues ScaleUp/ScaleDown **per slice** via ControlLoop. Cluster-average CPU dropped as a trigger — fixes cross-slice mis-attribution (#422/#423). Optional blueprint `maxInstances` + per-slice threshold overrides (#424); `SCALE_CAPPED` event + `GET /api/controller/decisions` per-slice decision snapshot (#425). **Proven by unit + in-JVM forge probe only; cloud/chaos validation, #369 bench tuning, and setpoint/forecast follow-ons (#435–#437) pending** |
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
| 15 | Quorum state management | Battle-tested | Monotonic-sequenced quorum notifications, graceful degradation on quorum loss, automatic restoration. **Cold-boot self-fence gate (A6):** the quorum-loss self-drain (and the SWIM never-HEALTHY FAULTY-suppression) are gated on a bounded post-boot convergence window (~75s, covering the transport's 60s force-dial) so a simultaneous full-cluster restart does not terminate healthy nodes on the transiently-low SWIM-alive count before convergence; a genuine minority still self-fences once the window elapses |
| 16 | Topology management | Battle-tested | Node discovery, addition/removal events, health tracking, grace period for departures |
| 17 | Distributed KV-Store | Battle-tested | Consensus-replicated store with typed keys (SliceNode, SliceTarget, HttpRoute, AppBlueprint, VersionRouting, RollingUpdate, Threshold, LogLevel, Config, TopicSubscription, NodeArtifact, NodeRoutes) |
| 175 | ClusterGeneration choreography | Complete | Epoch-fenced cluster-wide snapshot (`ClusterGenerationSnapshot`, `Epoch`, `Spokesman`, `ClusterQuiescence`). Leader projects membership/communities/DHT partition ownership at each commit; every node caches the latest snapshot via pings and serves it locally. `GET /api/cluster/generation` (always safe), `POST /api/cluster/await-quiesced?epoch=T:C&timeout=30s` (synchronous barrier for tests/operators). CLI: `aether cluster generation`, `aether cluster await-quiesced`. Replaces retry/sleep/self-heal compensation in the integration test harness with a deterministic quiesced-await. See [`cluster-generation-spec.md`](../specs/cluster-generation-spec.md) |

## Networking & Routing

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 18 | HTTP route registration | Complete | Dynamic per-slice route discovery and registration via KV-Store |
| 18a | Route media types (`produces`/`consumes`) | Complete | routes.toml inline-table entries declare request/response Content-Type (#339); slice-processor emits the declared output `.as(...)` + consumes-appropriate body binding (string/byte[]/multipart/json), with a strict compile-time check that the media category matches the method's Java types. Binary (`application/octet-stream`) returns `byte[]` verbatim. Recognized types map to `CommonContentType` constants; unrecognized types use the `ContentType.contentType(header, category)` escape hatch. See [API Versioning & Media Types guide](../slice-developers/api-versioning-and-media-types.md) + runnable [`examples/catalog`](../../../examples/catalog/README.md) |
| 18b | API versioning (path + header) | Complete | routes.toml `[api]` section + per-version `[vN.routes]`/`[vN]` blocks (#198): bind key resolves to `getV{N}` (D8, or explicit `method` override), routes mount at `{api.prefix}/v{N}/{path}` (path mode) or the bare `{api.prefix}/{path}` (header mode). Five compile-time checks via the unit-tested `VersionSchemaValidator` (schema mixing, duplicate bind key, single `defaultIfMissing`, `sunset` ISO-date, method resolution). Version metadata (`deprecated`/`sunset`/`defaultIfMissing`, `requireVersionHeader`) parsed + stored in the manifest (envelope 1002). Cluster-level detection mode via `[app-http] api_versioning_detection` (`path`/`header`) + `api_version_header` (default `API-Version`); header-mode selection policy: required-header → 400, `defaultIfMissing`, else latest-wins. Deprecation lifecycle emits `Deprecation`/`Sunset`/`Link: …; rel="successor-version"` response headers; observability via `http.requests.versioned`, `api.versioning.deprecated.requests`, `api.versioning.missing.header` counters + the `GET /api/versions` introspection endpoint and `aether versions` CLI. See [API Versioning & Media Types guide](../slice-developers/api-versioning-and-media-types.md) + runnable [`examples/catalog`](../../../examples/catalog/README.md). Deferred: per-slice detection-mode override (cluster-level for now) |
| 19 | Endpoint registry | Complete | Artifact-to-node mapping for slice instance tracking and load balancing |
| 20 | Service-to-service invocation | Battle-tested | SliceInvoker with HTTP routing, load balancer selection, timeout/retry, metrics |
| 21 | Version routing | Battle-tested | Traffic splitting between old/new versions during deployments (configurable ratio) |
| 67 | Passive load balancer | Complete | Cluster-aware LB node (NodeRole.PASSIVE) joins cluster network, receives route table via committed Decisions, forwards HTTP via binary protocol. Smart routing, automatic failover, live topology awareness. No HTTP re-serialization. Management API requests forwarded to core nodes via Pipeline.MANAGEMENT demuxing |
| 68 | NodeRole cluster membership | Complete | ACTIVE/PASSIVE roles in NodeInfo. Passive nodes excluded from quorum/leader election, receive only Decision messages via deliverToPassive() filtering |
| 69 | HttpForwarder (reusable) | Complete | Extracted HTTP forwarding with round-robin selection, retry with backoff, node departure failover. Used by both AppHttpServer and passive LB |
| 161 | Compile-time route registry | Complete | 116-route `ManagementRoute` enum with `RouteMatcher`, `RouteAssembler`, `RouteTarget` (LOCAL/ANY/TaskGroupTarget). Enum-keyed dispatch in `ManagementRouter`. All path literals eliminated from server and CLI. Compile-time safety on route add/rename |
| 162 | Task-group-aware forwarding | Complete | `TaskGroupAssignmentRegistry` maintains `TaskGroup→NodeId` via consensus. LB forwarder routes management requests to correct task-group owner. Encrypted cloud credentials in KV-Store for auto-heal |
| 163 | Cloud testing infrastructure | Complete | Hetzner Cloud test scripts (deploy/run/teardown), `CLOUD_MODE` in test library (SSH bastion, timeout multiplier, LB-routed operations), `aether-cloud.toml` config, private network + managed LB architecture |
| 204 | SharedScheduler consolidation | Complete | Unified shared scheduler (min 8 platform threads) replaces per-subsystem thread pools. 10 production schedulers migrated: SWIM, CircuitBreaker, Retry, canary evaluation, heartbeat, and others |
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
| 32 | Artifact repository | Battle-tested | Maven-compatible, chunked storage, checksum verification (MD5/SHA1), 64MB upload limit, metadata XML generation. Cross-node resolve of multi-MB artifacts (≥1MB) works: bounded chunk fan-out keeps the DHT QUIC lane under its backpressure watermark, storage releases block claims on failed write-through so retried chunk writes re-write, and the DHT lane retries backpressure to live peers rather than fast-fail-dropping (5MB cross-node resolve completes in <1s) |
| 33 | Distributed hash table | Battle-tested | Consistent hash ring (150 vnodes, 1024 partitions), quorum R/W, anti-entropy repair (CRC32 digest exchange, migration on mismatch), re-replication on node departure (DHTRebalancer), per-use-case config via `scoped()`. **Durability:** in-memory only — `MemoryStorageEngine` is the sole `StorageEngine` impl; a node restart loses that node's DHT state (survivors re-replicate), a full-cluster crash loses all DHT state — see guarantees.md §2 (#383, build under #349) |
| 34 | Configuration service | Complete | TOML-based config with runtime overrides via KV-Store, environment variable interpolation, system property fallback |
| 105 | Hybrid Logical Clock | Complete | `integrations/hlc/` module. HlcTimestamp (48-bit microseconds + 16-bit counter packed into long), HlcClock (thread-safe, ReentrantLock), drift detection, counter overflow protection. Foundation for causal ordering |
| 106 | DHT versioned writes | Complete | HLC-stamped puts with atomic version comparison in storage. Rejects stale writes (version ≤ current). Synchronous notification delivery via `withSuccess()` preserves causal write ordering. Full replication (`DHTConfig.FULL`) for control plane maps |
| 107 | Centralized timeout configuration | Complete | All operator-facing timeouts externalized to `TimeoutsConfig` with 13 subsystem groups. TOML `[timeouts.*]` sections with human-readable duration strings. Legacy `_ms` fields supported with automatic migration |
| 206 | KV-Store durable backup | Complete | Cluster metadata serialized to TOML file in local git repo. Git provides versioning, history, diffs, optional remote push. BackupService exposes manual `backup now`/`restore` (REST API, CLI); no periodic scheduler is wired — automatic saves fire on lifecycle events only (quorum-loss pause, membership reconfigure, graceful stop), so a crash/power-loss never snapshots. **Note:** git/disk backup is OPT-IN — `resolvePersistence` defaults to in-memory `RabiaPersistence::inMemory` unless a `[backup]` path is configured (`AetherNode.resolvePersistence`). A single-node restart survives via quorum re-sync (local persistence is consulted detect-only); a full-cluster cold restart needs `[backup]` configured and restores only the last lifecycle snapshot — see guarantees.md §1 / Known Gap #6 (#383, build under #349) |
| 207 | Hierarchical Storage Engine | Complete | Content-addressed block storage (`integrations/storage` library). SHA-256 BlockId, Memory + LocalDisk tiers with CAS-bounded capacity, write-through + tier-waterfall reads, SingleFlightCache dedup, MetadataStore, SnapshotManager, StorageReadinessGate, per-instance TOML config, ArtifactStore migration, REST + CLI management ([`/api/storage`](management-api.md#storage-hierarchical-storage-engine) + [`aether storage`](cli.md#storage)). 236 tests in `integrations/storage` (256 including the `aether/aether-storage` adapter) |

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
| 42a | Runtime-switchable per-injection-point observability | Complete (runtime engine) | Single observation engine (#277): each cross-slice/topic/timer/HTTP-entry dispatch seam carries an `ObservabilityStrategyCell` (one `AtomicStrategy` per `artifactBase/methodName`); the write-side `ObservabilityConfigRegistry` pre-composes the KV config into an "around" strategy and swaps it on change (push-on-event, no hot-path lookup). The retired `ObservabilityInterceptor` fleet layer is absorbed into a **baseline** posture (unconfigured = ambient facets: depth-leveled logging + sampled tracing + counting, spans off; HTTP entry gains ambient observability for the first time). Configured points run only the facets their toggles select (composed from the same bodies); explicit all-off = identity (surgical darkening). Scope hierarchy method → artifact (`*`) → global (`*/*`) → baseline (nearest wins whole). Depth store unified (`/api/observability/depth` re-backed; depth-set materializes a non-darkening method config). Triad: REST `GET/POST/DELETE /api/observability/config`, CLI `aether observability config[-get|-set|-remove]`, docs. **Pending:** deletion of the dead generated codegen `Aspect` seam (mechanism is dispatch-seam cells, not codegen weave); resource-call injection points (#268-gated) |
| 43 | Cluster event aggregator | Complete | Collects topology, leader, quorum, deployment, slice-failure and network events as a sealed `ClusterEvent` (`@Codec`). Events now flow through the replicated `system:cluster-events:1.0.0` partition stream (cross-node visible, owner-gated emit, RF=`max(3,N-2)`, production count/byte/age retention) — replacing the earlier node-local ring buffer + KV materialized view. REST API (`/api/events`), WebSocket feed, CLI. See #205 |
| 43a | Membership diagnostics surface | Complete | Per-node-local observability of the responding node's authoritative `MembershipFsm` lifecycle view + quorum-loss self-drain readiness: per-peer `state`/`incarnation`/`role`/`strictCore`/`countsTowardEffective`, plus `strictCoreMemberCount`, `countedCoreMemberCount`, `requiredThreshold` (`coreCount/2+1`), `belowThreshold`, `armed` (cold-start latch). Read-only, **not leader-forwarded** — query each survivor to diagnose SWIM-under-concurrent-loss (which peers SUSPECT/DEAD, whether self-drain is armed and below threshold). `GET /api/cluster/membership`, `aether cluster membership` |

## Resource Provisioning

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 44 | SPI resource factories | Complete | ServiceLoader discovery, config-driven provisioning, type-safe qualifiers |
| 45 | Database resources | Complete | JDBC, R2DBC, jOOQ, jOOQ-R2DBC, jOOQ-async, JPA, postgres-async (native async + R2DBC adapter) with connection pooling, query pipelining, configurable IO threads, transaction management, and LISTEN/NOTIFY |
| 45a | Aether Persistence (AEP) — PostgreSQL persistence | Complete | `@PgSql` type-safe persistence with compile-time SQL validation against migration-derived schema. Validates parameter types, return field mappings, column existence, NOT NULL coverage. Generates CRUD from method names (Spring Data conventions). 41-rule migration linter, record/enum codegen, named parameter rewriting, query narrowing, record expansion (INSERT VALUES + UPDATE SET). `pg-maven-plugin` for schema → Java generation. CLI scaffolding via `jbct add persistence` |
| 45b | jOOQ XML schema export | Complete | `JooqXmlExporter` generates jOOQ `XMLDatabase` input from pg-tools static analysis. Maven goals: `export-jooq-xml` + `check-jooq-xml`. 25+ PG type mappings, enums, domains, indexes, multi-schema. No jOOQ dependency — hand-written StAX emission |
| 46 | HTTP client resource | Complete | Configurable outbound HTTP with timeouts, retries, SSL/TLS, Jackson integration |
| 47 | Interceptor framework | Complete | Method-level interceptors: retry, circuit breaker, rate limit, logging, metrics. Runtime enable/disable |
| 48 | Runtime extensions | Complete | `registerExtension()` for injecting runtime components into resource factories |
| 209 | PgNotification subscriber | Complete | Slice-level PostgreSQL LISTEN/NOTIFY subscription. PgNotification, PgNotificationSubscriber, PgNotificationConfig in slice-api; NotificationListenerFactory in db-async |
| 217 | Durable entity | Partial | `@DurableEntity` resource primitive (`aether/resource/durable-entity`, 8 prod classes) provisioned via the resource SPI (`DurableEntityFactory`), with per-key serial execution (`PerKeySerialExecutor`). **Gap:** the prod factory returns `InMemoryDurableEntity` (`DurableEntityFactory.java:31`) — in-process state only: HA-oriented, NOT restart-durable. The fenced/persistent variants (`FencedDurableEntity`, `PartitionFencedDurableEntity`) exist and are tested but are unwired in the bootstrap path. **Plan:** #345 ownership-fence wiring (1d-iii → 1f) + #349 (durable-entity persistence substrate) |

## Cloud Integration

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 108 | Environment integration SPI | Complete | Faceted SPI (`EnvironmentIntegration`) with 4 optional facets: compute, secrets, load balancer, discovery. ServiceLoader discovery |
| 109 | SecretsProvider implementations | Complete | `EnvSecretsProvider`, `FileSecretsProvider`, `CompositeSecretsProvider` (first-success chain). Zero-dependency |
| 110 | DiscoveryProvider SPI | Complete | Peer discovery interface: `discoverPeers()`, `watchPeers()`, `registerSelf()`/`deregisterSelf()`. Wired into AetherNode bootstrap |
| 111 | Hetzner Cloud compute | Complete | `HetznerComputeProvider` — provision, terminate, list, status, restart, tag management, label-filtered listing. **Label/profile robustness (#442):** tag updates are read-modify-write merges (Hetzner label writes replace the whole map — merge preserves the create-stamped `aether-cluster`/`aether-role`/`aether-source` base set), label values are sanitized to Hetzner constraints (≤63 chars, `[a-zA-Z0-9._-]`, alphanumeric edges), and server-type / SSH-key resolution inherits the source's `instance_type` and operator key ids across generations with a loud failure when neither resolves — no hardcoded instance-type default. Unit-tested; cloud end-to-end not asserted. |
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
| 213 | Cluster init wizard | Complete | `aether cluster init` interactive wizard + batch-mode flags generating a ready-to-bootstrap `cluster-config.toml`. All 4 deployment targets (Docker / SSH / Cloud / Forge), topology auto-derive (refuses N<3, requires odd core), firewall presets (Standard / Restrictive / Open / Custom), TLS auto-generate vs env-var, optional database, offline IP auto-detect for restrictive admin CIDR. Shared `Prompt` utility consolidates 3 ad-hoc stdin readers. 67 tests across 6 classes. |
| 51 | WebSocket streams | Complete | `/ws/dashboard` (metrics), `/ws/status` (cluster state), `/ws/events` (real-time cluster events with delta broadcasting) |
| 52 | Dynamic log levels | Complete | Runtime log level adjustment per logger via KV-Store. CLI and API control |
| 53 | E2E test framework | Battle-tested | Testcontainers-based cluster testing with 10 test classes on bridge networking. Container DNS for inter-node communication, network partition/disconnect/reconnect support |
| 76 | Forge integration tests | Battle-tested | In-process EmberCluster tests: 16 test classes covering cluster formation, node failure, chaos, rolling updates, pub-sub delivery, invocation metrics, graceful shutdown, network partitions |
| 136 | Docker integration test infrastructure | Battle-tested | 5-node cluster + passive LB via Docker Compose. Chaos tests (19/19): kill, leader re-election, multi-kill quorum, kill-under-load with 0% error rate through LB, auto-heal provisioning. Scaling tests (16/16): quorum safety rejection, scale-up 5→7 (2s), scale-down 7→5 under load (34s). All management API traffic routed through LB via QUIC forwarding |
| 146 | In-memory streaming | Complete | StreamPublisher/StreamSubscriber/StreamAccess API, OffHeapRingBuffer with EvictionPolicy (DROP_OLDEST/REJECT_WHEN_FULL), StreamPartitionManager, ResourceFactory SPI, StreamConsumerAdapter with zero-copy MemorySegment reads, CDM stream creation + consumer wiring, push notification (appendListeners), adaptive polling (1ms-50ms backoff), producer batching API, cursor persistence (push + pull), consumer timeout (60s auto-unsubscribe), REST API (create/list/get/publish/read/delete/consumers), CLI. Off-heap allocation is floor-reserve + lazy segmented growth (256 KiB segments, grow-to-cap), CAS budget accounting against a 128 MB per-node pool, loud STREAM_MEMORY_EXCEEDED + ClusterEvent on exhaustion (#96). |
| 137 | Cross-node stream routing | Complete | Direct QUIC publish forwarding via `StreamForwardMessage` protocol. Producers on any node publish to any partition — no HTTP overhead, binary correlation with 5s timeout. Local path unchanged (~1-5us) |
| 507 | Key-based partition routing (`@PartitionKey`) | Complete | An event record component marked `@PartitionKey` routes every publish of that type to a partition derived from a STABLE 64-bit hash of the key (`ReplicaPlacement.stableHash64`, identical across nodes/JVMs), so a logical key keeps per-key ordering on one partition; event types without one keep round-robin. End-to-end since #507: the slice-processor resolves the annotated component of a `StreamPublisher<T>`/`StreamAccess<T>` event type and emits `.withKeyExtractor(...)` into the generated `ProvisioningContext`, which `StreamPublisherFactory`/`StreamAccessFactory` install into `DefaultStreamPublisher`/`PartitionedStreamAccess`. (Before #507 the annotation was declared and documented but never read by the processor, so all publishes round-robined.) Two `@PartitionKey` components on one record is a compile error. Topic `Publisher<T>` is unpartitioned and ignores the key. **Note:** the management-API publish path (`POST /api/streams/.../publish`) still hardwires partition 0 — operators cannot target a partition explicitly |
| 138 | Consumer group coordination | Complete | KV-Store-backed `ConsumerGroupCoordinator` (leader-side round-robin assignment) + `ConsumerGroupRegistry` (read-side mirror via KV notifications). Join/leave/status API endpoints. Automatic rebalance on topology change |
| 139 | Sync replication ack | Complete | `replicateAndAwait(minSyncReplicas)` waits for N replica acks. Configurable via `StreamConfig.minSyncReplicas`. PendingAck correlation with timeout |
| 140 | Batch replication | Complete | `ReplicationBatcher` accumulates events per partition (100 events or 1ms window), sends single `ReplicateEvents` message. 10-50x QUIC message reduction |
| 141 | Consumer read-preference | Partial | `ReadPreference` enum (GOVERNOR/ANY_REPLICA/NEAREST) exposed on read endpoint and selected via `PartitionedStreamAccess.readWithPreference()`. **Remote replica reads not yet implemented** — `selectReplicaAndRead()` picks a caught-up replica but falls back to local read (Phase 2 scope; see `PartitionedStreamAccess.java:278`) |
| 141a | Replica-state observability surface | Complete | Read-only per-partition replica-state snapshot — the regression sensor for the stream-replication class (#260/#261/#333/#445). Per replica: `state` (`SYNCING`/`CAUGHT_UP`/`LAGGING`), `confirmedOffset`, `isHrwOwner`; partition-level: resolved `hrwOwner`, `servedByOwner`, `ownerHeadOffset` (owner true tail), `earliestRetainedOffset`. Lets an operator/LLM spot a `CAUGHT_UP` replica whose `confirmedOffset` lags the owner's tail (the #333 write-idle residual). Assembled from the answering node's `ReplicaRegistry` + HRW owner resolver (snapshot read, no hot-path cost); **owner-aware, not owner-forwarded** — authoritative only on the HRW owner (`servedByOwner`), as per-partition-owner forwarding is not a management `RouteTarget` and the stream forward transport is event-only. `GET /api/streams/replicas/{name}/{partition}`, `aether stream replicas <stream> <partition>` |
| 142 | Segment compression | Partial | LZ4 and ZSTD `CompressionCodec` infrastructure; per-stream `StreamConfig.compression`, metadata in SegmentRef. `ContentStore` applies compression on its write path (`DefaultContentStore.frameCompress`). **Gap:** the AHSE engine write path (`StorageInstance.writeToAllTiers` / `DefaultStorageInstance`) writes raw bytes and applies NEITHER compression NOR encryption, so engine-sealed stream/content segments are uncompressed. **Plan:** AHSE-engine write-pipeline gap (codec exists + tested, not yet invoked by the engine) — no scheduled phase yet |
| 143 | Segment encryption | Partial | AES-256-GCM `BlockEncryptor` / `AesGcmBlockEncryptor` (tested), per-stream `StreamConfig.encryptionKeyId`, IV prepended to ciphertext. **Gap:** the AHSE engine write path (`StorageInstance.writeToAllTiers` / `DefaultStorageInstance`) never invokes the encryptor — segments are written as plaintext bytes (`.encrypt(` has zero callers in `src/main`). **Plan:** AHSE-engine write-pipeline gap (encryptor exists + tested, not yet invoked by the engine) — no scheduled phase yet |
| 144 | Transactional cursor commits | Planned | `PgTransactionalCursorCommit` wraps cursor UPSERT + business logic in a single PostgreSQL transaction for exactly-once semantics. **Gap:** test-only — referenced solely by `PgTransactionalCursorCommitTest`, never wired into a running node (and Phase A cursors are at-least-once: `CursorStore.replaceRef` is non-atomic). **Plan:** Phase C of [`streaming-persistence-implementation-plan.md`](../internal/progress/streaming-persistence-implementation-plan.md) (exactly-once via PG-transactional commit) |
| 145 | Compound retention | Complete | `RetentionMode.ALL`/`ANY` combinators for time + count + size policies. TOML-configurable via `retention-mode` |
| 204 | Stream namespaces & addressing | Complete | Streams addressed by fully-qualified `(namespace, stream, version)` (`StreamAddress`, `MAJOR.MINOR.PATCH` `StreamVersion`). Consensus-replicated `StreamRegistryKey` (namespaced, refcount) + locally-hydrated `StreamConfigKey` (flat config/retention/partitions) registries — non-governor nodes serve metadata from replicated state (#215). Namespaced `/api/streams/*` + `/api/stream-namespaces/*` route surface; `aether stream` CLI group (list/show/tail/delete/group create+delete). `system:*` HTTP writes rejected with 405 regardless of role. Active replica-set controller maintains RF for all streams via HRW placement on membership change; catch-up backfill; app-stream replication + write-forwarding |
| 205 | Replicated cluster-event stream | Complete | `system:cluster-events:1.0.0` moved from a node-local KV/ring-buffer materialized view to a **replicated partition stream** with cross-node visibility. Sealed `ClusterEvent` record (`@Codec`), owner-gated emit (HRW owner of partition 0 only), off-heap partition transport, RF=`max(3, N-2)`. Production retention by count + bytes + age (`CLUSTER_EVENTS_MAX_COUNT/BYTES/AGE_MS`, `CLUSTER_EVENTS_EVENT_SIZE_BYTES`). Stream lifecycle events (STREAM_REGISTERED/DELETED) deferred to RC2. Off-heap retention default lowered 64→16 MB so it no longer starves the app-stream budget (#96). |
| 216 | Pub/sub topic namespaces & addressing | Complete | Pub/sub topics addressed by fully-qualified `(namespace, topic, version)` (`TopicAddress`, `MAJOR.MINOR.PATCH` `TopicVersion`) — the topic-flavored view of the shared `ResourceAddress` abstraction that also backs streams (#204), so both surfaces share one grammar, version model and `system`-namespace reservation. Namespace derived from the publishing slice's blueprint Maven coordinates (`groupId.artifactId`) with back-compat for bare/legacy names (lifted to `default:<topic>:1.0.0` pre-deploy); explicit `namespace:topic:version` declarations accepted verbatim in slice config. `TopicSubscriptionKey` is namespaced and round-trips; `PubSubValidator` rejects the reserved `system:*` namespace for app topics (mirrors `StreamResourceValidator`). Topology/observability (`/api/slices/topology`, dashboard) keys topic node identity and cross-slice pub/sub matching on the resolved canonical address. No dedicated topic HTTP route or CLI surface today (pub/sub is in-process, declaration-driven) — operator visibility is via the topology graph |
| 147 | Declarative cluster management | Complete | TOML-based cluster config, 6-phase bootstrap orchestrator with state persistence/resume/cleanup, pre-flight validation with `--full-check`, dual KV-Store entries (TEMPLATE/CURRENT), parallel health checks, VM tagging, forge health gate, floating IP attachment, apply orchestrator with rolling restart, replace-before-retire, `--resume`/`--rollback`, terraform-style plan confirmation |
| 58 | Web dashboard | Critical | Forge dashboard complete (cluster visualization, load generation, chaos injection, metrics, scaling events, deployment timing). **Node management dashboard requires significant work** — missing: observability depth UI, invocation trace viewer, log level management UI, storage management UI, streaming dashboard, worker pool visualization. **Requires major development effort.** |

## Developer Tooling

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 54 | Slice annotation processor | Complete | Compile-time code generation: factory classes, manifests, route sources, pub-sub wiring |
| 55 | JBCT compliance | Complete | Format linting, return type validation, pattern checking, factory naming conventions. Maven plugin |
| 56 | Envelope format versioning | Complete | `ENVELOPE_FORMAT_VERSION` in ManifestGenerator with runtime compatibility check |
| 57 | Forge simulator | Battle-tested | Standalone cluster simulator with load generation (constant/ramp/spike), chaos injection, visual dashboard, REST API |
| 77 | Topology graph | Complete | Compile-time topology extraction (envelope v6): HTTP routes, resources, pub-sub topics from `.manifest` files. REST `GET /api/slices/topology`, WebSocket `INITIAL_STATE`. Swim-lane SVG layout with Manhattan routing, HSL color-coded topic groups, hover highlighting, search filtering |
| 78 | `jbct add-slice` command | Complete | Scaffolds a new slice into an existing project: creates interface, test, routes.toml, config, and dependency manifest |
| 79 | IDE plugins | Planned | Slice development plugins for IntelliJ IDEA (native), VS Code, Eclipse, NetBeans. Shared LSP backend for routes.toml support, JBCT diagnostics, TOML schema validation |
| 205 | Core value objects | Complete | Reusable validated value objects in `org.pragmatica.lang.vo`: Email, Url, NonBlankString, Uuid, IsoDateTime |
| 208 | GitHub Issues as worklog | Complete | GitHub Issues adopted as primary work tracking and project log |
| 158 | V1.0.0 roadmap | Complete | Evolutionary implementation protocol with phased milestones, feature prioritization, and release criteria for Aether 1.0 |
| 210 | JBCT code formatter | Complete | CST-based Java code formatter (`jbct-format` module). Records, enums, switch expressions, text blocks, chain alignment, multiline parameters, ternary operators, lambdas, comments. 17 golden test files |
| 211 | JBCT compliance scorer | Complete | Numeric JBCT compliance scoring (`jbct-core/score`). ScoreCalculator, ScoreResult, ScoreCategory, RuleCategoryMapping |
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
| 92 | RBAC — per-route security | Complete | Per-route security via routes.toml `[security]` section (public/authenticated/role:name), type-safe SecurityPolicy with `canAccess()` and deny-by-default for unknown values, route-level enforcement in AppHttpServer, Principal/SecurityContext injection in handlers, blueprint operator overrides with strengthen_only policy, security metadata in KV-Store, dashboard security badges, security denial metrics |
| 203 | Security hardening (RC1) | Complete | QUIC cluster transport mandates a real `TlsConfig` with deterministic CA derived from `AETHER_CLUSTER_SECRET` — no plaintext mode, no `AETHER_INSECURE_DEV_MODE` escape hatch, ALPN `"aether-cluster/1"` pinned. Dev-mode (`AETHER_INSECURE_DEV_MODE`) is refused at node startup when operator TLS certificates are configured (`TlsConfig.hasProvidedCertificates()`), and is propagated to replacements only when present (isolated, not in the cluster-identity allow-list). Node startup also aborts when cluster name is missing/empty. PostgreSQL `InsecureTrustManagerFactory` still gated behind an explicit system-property opt-in. Cloud config `toString()` redacts secrets. SQL injection prevention in PG LISTEN/UNLISTEN. SSH command injection prevention via image name validation. Bootstrap API key stored to file with 600 permissions instead of stdout. Docker Compose uses random fallback secret |

## Embeddable Runtime

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 73 | Ember embeddable cluster | Complete | Headless cluster runtime extracted from Forge as `aether/ember/` module. Fluent builder API: `Ember.cluster(5).withH2().start()`. Programmatic lifecycle management via `EmberInstance` |
| 74 | Remote Maven repositories | Complete | Resolve slices from Maven Central or private Nexus. SHA-256 verification (SHA-1 fallback), mandatory checksums, XXE-hardened XML parsing, local `~/.m2/repository` cache, `settings.xml` auth |
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
| 94 | SliceNodeKey DHT migration | Complete | SliceNodeKey reads/writes moved from consensus to `slice-nodes` ReplicatedMap — now **eventual + not crash-durable** (guarantee downgrade, #384); guarantee in [`guarantees.md`](guarantees.md) §2, scope in [`known-limitations.md`](known-limitations.md) |
| 95 | HttpNodeRouteKey DHT migration | Complete | HttpNodeRouteKey reads/writes moved from consensus to `http-routes` ReplicatedMap — now **eventual + not crash-durable** (guarantee downgrade, #384); see [`guarantees.md`](guarantees.md) §2 / [`known-limitations.md`](known-limitations.md) |
| 96 | DHT replication config | Complete | `[dht.replication]` TOML section: `cooldown_delay_ms`, `cooldown_rate`, `target_rf`. Environment-aware defaults |
| 97 | Multi-group worker topology | Complete | Zone-aware group computation from SWIM membership. `WorkerGroupId`, `GroupAssignment`, `GroupMembershipTracker`. Per-group governor election and Decision relay |
| 98 | CDM community-aware placement | Complete | `AllocationPool` extended with `workersByCommunity`. CDM tracks `GovernorAnnouncementValue` per community |
| 99 | Worker zone configuration | Complete | `WorkerConfig` extended with `groupName`, `zone`, `maxGroupSize`. Backward compatible defaults |
| 100 | Event-based community scaling | Complete | Governors monitor follower metrics via `WorkerMetricsPing`/`Pong`, detect sustained threshold breaches, send `CommunityScalingRequest` to core. Zero baseline bandwidth |
| 101 | Governor advertised address | Complete | Governors announce routable TCP address. Auto-detect or configurable `worker.advertise_address` in TOML |
| 132 | Role-aware unified node | Complete | Single `aether-node.jar` binary for CORE or WORKER. Consensus observer mode, `ForwardingClusterNode`, `SwitchableClusterNode`. WORKER→CORE promotion via `authorizeActivation()` |
| 150 | DHT-backed ReplicatedMap | Complete | Generic typed `ReplicatedMap<K,V>` abstraction over `DHTClient` with namespace-prefixed keys, `MapSubscription` event callbacks. Drain loop prevents subscriber re-entrance. `CachedReplicatedMap` adds LRU + TTL caching. `aether/aether-dht/` module |
| 151 | Community-aware replication | Complete | `ReplicationPolicy` with home-replica rule (1 home + 2 ring replicas = RF=3). `HomeReplicaResolver` for deterministic community-local selection. Spot-node exclusion |
| 152 | Endpoint DHT migration | Complete | Endpoints moved from consensus KV-Store to DHT `ReplicatedMap`. O(3) write amplification vs O(N) with consensus — the trade also makes endpoint reads **eventual + not crash-durable** (guarantee downgrade, #384); see [`guarantees.md`](guarantees.md) §2 / [`known-limitations.md`](known-limitations.md) |
| 153 | Replication cooldown | Complete | Startup RF=1 with background push to RF=3 after configurable delay. Rate-limited to prevent boot storm |
| 156 | Compound KV-Store key types | Complete | `NodeArtifactKey` merges EndpointKey + SliceNodeKey; `NodeRoutesKey` merges HttpNodeRouteKey. ~10x entry count reduction. WorkerNetwork eliminated — inter-worker messaging consolidated into NCN via DelegateRouter |

## Known Limitations

| Area | Limitation | Planned Fix |
|------|-----------|-------------|
| Security | Self-signed certs / single trust domain — see [`known-limitations.md`](known-limitations.md) (single source for scope) | External CA provider SPI implementation |
| Networking | Single-region only; no multi-region DR — see [`known-limitations.md`](known-limitations.md) | Not yet planned |
| Dashboard | Node management dashboard requires significant work | Critical priority (#58) |

## Planned Features

| # | Feature | Status | Description |
|---|---------|--------|-------------|
| 200 | Per-route rate limiting | Planned | Per-HTTP-route rate limiting via blueprint or management API. Token bucket or sliding window. Cluster-aware distributed counters |
| 201 | Spot instance support | Planned | Elastic pool of spot/preemptible instances for cost-optimized scaling. Core (on-demand) + elastic (spot) pools |
| 202 | Cluster expense tracking | Planned | Real-time cost visibility from cloud billing APIs. Per-node, per-slice, per-request cost derivation. Budget alerts |
| 70 | Aether runtime rolling upgrade | Partial | Phase 1: `POST /api/cluster/upgrade` endpoint and CLI. Full rolling orchestration deferred to Phase 2 |
| 71 | Email notification resource | Complete | `integrations/net/smtp` (async Netty SMTP client), `integrations/email-http` (HTTP sender with SendGrid/Mailgun/Postmark/Resend SPI), `aether/resource/notification` (ResourceFactory + @Notify qualifier). 57 tests |
| 157 | Per-blueprint artifact scoping (Tier 2) | Planned | Per-blueprint SliceTargetKey scoping for multi-tenant clusters. Prerequisite: Tier 1 (#102) |
| 174 | DigitalOcean cloud integration | Planned | DigitalOcean compute, discovery, load balancer providers. Spec exists |
| 212 | Fluid cross-environment migration | Planned | Cross-environment migration protocol. Spec exists |
| 176 | Application config provisioning | Complete | `@ResourceQualifier(type = ConfigurationSection.class)` pattern. Compile-time parser generation via `Result.all()`. Three-source merge (bundled + aether.toml + KV-Store). Runtime notification via single-threaded executor with record diff. ACTIVATE integration |
| 177 | ContentStore resource | Complete | `@ContentStoreQualifier` annotation, `ContentStoreFactory` SPI. AHSE-backed content-addressable storage with chunking and compression |
| 178 | Cloud certificate adapters | Complete | AWS (ACM/Secrets Manager), GCP (Certificate Manager), Azure (Key Vault) via `CertificateProvider` SPI. `CloudCertificateProvider` shared implementation |
| 179 | Streaming retention enforcement | Partial | Scheduled `RetentionEnforcer` with compound retention policy evaluation (ALL/ANY mode), scans SegmentIndex, removes expired segments from AHSE. **Gap:** the prod segment sink is `EvictionListener.NOOP` (`AetherNode.java:2531`), so no cold-tier segments are ever sealed — the enforcer scans an always-empty `SegmentIndex` and has nothing to evict. **Plan:** Phase A of [`streaming-persistence-implementation-plan.md`](../internal/progress/streaming-persistence-implementation-plan.md) wires the sink → durable tier |
| 180 | Streaming failover recovery | Partial | `StreamingCoordinator.activate()` triggers `GovernorFailoverHandler` for all stream partitions. Watermark-based catchup from AHSE segments + replica watermarks. **Gap:** only the live-replica catch-up leg works in prod; the "from AHSE segments" leg is moot because no segments are ever persisted (NOOP eviction sink → empty `SegmentIndex`, `GovernorFailoverHandler.java:74` finds nothing to replay). **#445 owner-failover safety (live-replica leg):** the live-replica catch-up leg no longer drops acked data or false-promotes under owner-failover churn — backfill now ranks over the single reconciled placement snapshot, closing the live-vs-reconciled divergence, and a new HRW owner that reads an EMPTY failover source now routes to the probe-gated path instead of promoting to `CAUGHT_UP@-1` or truncating below a confirmed-ahead survivor. Proven by deterministic unit repros (`PartitionBackfillTest`; aether-stream 622/0). **Validated on a real 5-node containerized cluster (integration suite 02-chaos, --env remote, 7p/0f): owner killed → newly-elected owner serves all 20 acked events → live writes accepted → replica set re-converges with RF restored.** Cloud (Hetzner) RF=2 owner-kill validation completed 2026-07-16 (two independent cloud-JVM passes of the full stream-failover script); remaining cloud-JVM 02-chaos debt is harness-side only (#459 snapshot image plumbing, #460 S19 JVM probe + budgets). **Plan:** Phase A/B of [`streaming-persistence-implementation-plan.md`](../internal/progress/streaming-persistence-implementation-plan.md) (A persists node-local segments; B = #265 places them for cross-node failover) |
| 181 | Stream memory management | Partial | Configurable `STREAM_MAX_MEMORY_BYTES` (default 128MB), `aether.streams.memory.used.ratio` Micrometer gauge; the off-heap memory-budget accounting is production-wired. **Gap:** the "STRONG streams require AHSE (`EvictionListener != NOOP`)" clause is not just a gate — in prod the eviction listener IS `NOOP`, so STRONG stream creation is outright **rejected** (`StreamPartitionManager.java:293`, `AHSE_REQUIRED_FOR_STRONG`); only DEFAULT (at-most-once) streams are creatable. **Plan:** Phase A of [`streaming-persistence-implementation-plan.md`](../internal/progress/streaming-persistence-implementation-plan.md) provides a non-NOOP sink (unlocks the gate); the STRONG consensus-publish path itself is separate (#192) |
| 185 | Consumer cursor persistence | Partial | `CursorStore` persists consumer group cursors in AHSE via named references (`aether-stream/segment/CursorStore.java`). **As-built:** `CursorStore` **is** constructed and disk-wired for the app `StreamAccess` path (`AetherNode.java:2861`/`:5114` → `StreamAccessFactory.java:85` → `PartitionedStreamAccess`). The offset block is write-through to disk, but the cursor ref is persisted only on a metadata snapshot (≤100 mutations / 30 s), so a commit is crash-durable **after the next metadata snapshot**; an un-snapshotted commit resumes from the prior cursor (or 0). A snapshotted cursor survives a same-node restart with a writable data dir. **Framework-driven auto-resume (#478, 2026-07-18):** `StreamAccess.fetchFromCommitted(group, partition, maxEvents)` resolves the committed cursor and reads from it in one call — a consumer resumes where it left off without an explicit re-seek (no cursor → from 0; explicit `fetch(offset)` overrides). Proven by `CursorAutoResumeRestartTest` (aether-node): commit K → rebuild stream storage over the same data dir → re-attached consumer resumes at K. **Scope/gap:** app path only — system `FrameworkStreamConsumer` paths receive `none()` (`SystemStreamFactories.java:90/121/168`) and replay from 0 by design (ruling: app consumers only); commit is at-least-once (`CursorStore.replaceRef` non-atomic). **Effectively-once** (atomic PG-transactional commit) tracked separately as #144 / row 199. |
| 186 | Node metadata labels | Complete | `NodeInfo.labels` (hostname, zone, instance-type, pool) propagated via Hello handshake. Bootstrap from environment |
| 187 | PlacementHint provisioning | Complete | `ZoneHint`, `HostGroupHint`, `AffinityHint`, `AntiAffinityHint` in `ProvisionSpec`. Cloud providers respect zone placement |
| 188 | Same-version deploy rejection | Complete | Strategy deploys rejected when oldVersion == newVersion. `/api/blueprints/publish` for register-without-deploy |
| 189 | Disruption budget enforcement | Complete | Drain endpoint checks quorum-based minAvailable before allowing transition to DRAINING |
| 190 | Tiered stream reader | Partial | `TieredStreamReader` reads across in-memory ring buffer and sealed storage tiers. Async prefetch of next segment when reading near tail. `aether-stream/segment/TieredStreamReader.java`, test `TieredStreamReaderTest.java`. **Gap:** in prod it is wired over an all-RAM `streamStorage` (memory tier + DHT-over-memory engine; `AetherNode.java:408,692-699`) and the cold tier is never populated (NOOP eviction), so post-restart reads return nothing from sealed storage. **Plan:** Phase A of [`streaming-persistence-implementation-plan.md`](../internal/progress/streaming-persistence-implementation-plan.md) (disk-backed `streamStorage` + restored, rebuilt index) |
| 191 | PostgreSQL stream backend | Planned | `PgStreamStore`, `PgSegmentSink`, `PgCursorStore` provide cold-tier storage and persistent cursor commits for streams. Phase 3 backend. `aether-stream/pg/`, tests `PgStreamStoreTest`, `PgSegmentSinkTest`, `PgCursorStoreTest`. **Gap:** these classes are constructed only in tests — there is no node-bootstrap reference wiring them into a running node. **Plan:** Phase C of [`streaming-persistence-implementation-plan.md`](../internal/progress/streaming-persistence-implementation-plan.md) (Postgres-backed segments + `SqlConnector` wiring on the streaming path) |
| 192 | Stream consensus publish path | Complete | STRONG-consistency publish via Rabia consensus: `ConsensusPublishPath`, `ConsensusProposer`, `StreamConsensusCommand`. Test: `ConsensusPublishPathTest`. `aether-stream/consensus/` |
| 193 | Stream dead-letter handling | Complete | `DeadLetterHandler` SPI with in-memory default for events exceeding retry limits or failing decode. Test: `DeadLetterHandlerTest`. `aether-stream/` |
| 194 | API key rotation | Complete | `aether cluster rotate-key` generates new key, pushes to KV-Store, marks old REVOKED with grace period, updates local file. Multi-key auth via `KvStoreApiKeyValidator`. Zero-downtime rotation |
| 195 | API key revocation | Complete | `aether cluster revoke-key <keyId> [--immediate]`. Grace period (default 5m) for in-flight requests. `aether cluster list-keys [--audit]` for status and history |
| 196 | API key audit trail | Complete | All key operations (create, rotate, revoke, expire) logged as `ApiKeyAuditValue` entries in KV-Store. Periodic expiration sweep (60s) on leader |
| 197 | Bootstrap resource tracking | Complete | `CreatedResource` sealed interface tracks VMs, firewall rules, floating IPs, containers, SSH configs. LIFO cleanup on failure. State persisted to `~/.aether/clusters/<name>/bootstrap-state.json` |
| 198 | Pre-flight validation | Complete | Static validation (30+ checks from §12). Default cloud credential ping. `--full-check` for SSH reachability, Docker CLI, floating IP ownership |
| 199 | Node config composition | Partial | 4-layer CLI-side TOML composition (global default + per-source-type default + operator override + CLI overlay), template inheritance for `node_config` subtrees, auto-detected `jdbc_url`/`async_url` per database, SSH path uses composed config. Follow-ups: #154 Docker bootstrap path, #155 cloud provisioning wiring, #156 Forge in-memory passing |
| 200 | Cloud bootstrap end-to-end (container) | Complete | `aether cluster bootstrap` against `type=cloud` source provisions Hetzner VMs, runs cloud-init (SSH keys, Docker install, image pull), SSHes each VM after provisioning to inject finalized 3-part PEERS via `docker run`, polls `/health/live` per node, persists API key + raw TOML to KV-Store, registers cluster. Validated end-to-end on Hetzner via `aether/tests/integration/env/cloud-hetzner.toml`. `--keep-on-failure` preserves VMs for diagnosis; `--ssh-public-key` injects operator key |
| 201 | Cloud bootstrap end-to-end (JVM) | Complete | Same as #200 but `type=jvm`: VMs install Temurin 25 from Adoptium, download `aether-node.jar` (default URL derived from `cluster.version`, optional `[runtime.jvm] jar_url` override), start via `nohup java -jar … --node-id= --port= --management-port= --peers= --config=`. SSH-back uses `pkill -f '^java -jar /opt/aether/aether-node.jar' && nohup java -jar …` to inject finalized PEERS. Validated end-to-end on Hetzner via `aether/tests/integration/env/cloud-hetzner-jvm.toml` |
| 202 | Cloud SSH preflight | Complete | `BootstrapPhaseDeploy` polls each cloud VM with `ssh ... 'cloud-init status --wait'` (180s outer budget, 5s interval, removes successful hosts each iteration) before issuing runtime restart commands. Eliminates the "SSH up but Docker not installed yet" race on slow VMs |
| 203 | Bootstrap resource ownership labels | Complete | All Hetzner VMs and SSH keys uploaded by `BootstrapPhaseProvision` / `BootstrapPhaseSshKey` are tagged `aether-cluster=<name>`, `aether-source=<sourceName>`, `aether-role=<role>`. `tools/cloud-reaper.sh --cluster <name>` filters by these labels for safe cluster-scoped cleanup independent of bootstrap state files |
| 204 | Pre-pulled VM snapshot support | Partial | Idempotent cloud-init guards in `UserDataTemplate` (skip `docker pull` when image cached, skip JAR download when present). `tools/build-aether-vm-snapshot.sh` Hetzner snapshot builder with `build` / `list` / `latest` / `destroy` / `prune-old` subcommands. Snapshot id set via the source role's per-role `image` field (RFC-0016 W2), resolved through the shared provider-agnostic `ProvisionRequest.resolve()` path (per-role `spec.imageId` → `[cloud.compute]` → loud stock default); provider parity is landed for all five providers (RFC-0016 W1, #463). Test runner env override (`AETHER_VM_SNAPSHOT_ID` / `AETHER_VM_SNAPSHOT_ID_JVM`). Operator doc: [`vm-snapshot.md`](../operator/vm-snapshot.md). **Partial:** builder script is Hetzner-only; AWS/GCP/Azure equivalents pending |

---

## Statistics

| Status | Count |
|--------|-------|
| Battle-tested | 25 |
| Complete | 160 |
| Critical | 1 |
| Partial | 12 |
| Planned | 9 |
| Total | 207 |

**Critical features:**
| Feature | Issue |
|---------|-------|
| Web dashboard (#58) | Node management dashboard requires major development — missing observability, invocation traces, log management, storage, streaming, worker pool UIs |

**Partial features and their gaps:**
| Feature | Key Gap |
|---------|---------|
| TTM predictive scaling (#11) | Disabled by default, no live model training |
| Aether runtime rolling upgrade (#70) | Phase 1 only — full rolling orchestration deferred |
| Streaming retention / failover / memory / cursor / tiered-reader (#179/#180/#181/#185/#190) | Prod streaming substrate is all-RAM — `EvictionListener.NOOP` seals nothing, cursors are RAM-only, STRONG stream creation is rejected. Covered by Phase A of [`streaming-persistence-implementation-plan.md`](../internal/progress/streaming-persistence-implementation-plan.md) (failover by Phase A/B). Guarantee & scope framing (crash-durability, at-most-once, RF=1) lives in [`guarantees.md`](guarantees.md) §4 and [`known-limitations.md`](known-limitations.md) — not restated per-row |
| Segment compression & encryption (#142/#143) | Codec + AES-GCM encryptor exist and are tested, but the AHSE engine write path applies neither. AHSE-engine write-pipeline gap (no scheduled phase yet) |
| Durable entity (#217) | Prod factory returns in-memory entity (HA-only, not restart-durable); fenced/persistent variants unwired. Covered by #345 / #349 |

---

*Last updated: 2026-06-27*
