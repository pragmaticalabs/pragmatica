# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.0.0-alpha] - Unreleased

### Added
- **PostgreSQL persistence adapter** — `@PgSql` type-safe persistence with compile-time SQL validation. Annotation processor validates `@Query` SQL and generates CRUD from method names (Spring Data conventions: `findBy*`, `save`, `insert`, `deleteBy*`, `countBy*`, `existsBy*`). Named parameter rewriting (`:param` → `$N`), query narrowing (`SELECT *` → explicit columns), record expansion for INSERT/UPDATE
- **PostgreSQL tooling (aether-pg-tools)** — SQL parser (PEG-based, ~500 rules), event-sourced schema model (25 event types), 41-rule migration linter (lock hazards, type design, schema design, migration practice), Java record/enum code generation from schema
- **pg-maven-plugin** — standalone Maven plugin for generating Java records/enums from PostgreSQL migration SQL files (`mvn pg:generate`)
- **`PgSqlConnector`** — PostgreSQL-specific marker interface extending `SqlConnector`, async-only factory routing (no JDBC/R2DBC fallback)
- **`@PgSql` qualifier** — resource qualifier annotation for persistence interfaces and slice factory parameters
- **`jbct add-persistence`** — CLI command to add PostgreSQL persistence support to existing projects
- **`--with-persistence` flag** — option on `jbct init` to scaffold persistence from the start
- **pg-showcase example** — demonstrates all persistence patterns: `@Query` with joins, CRUD auto-generation, record expansion, multi-table, projections
- **PostgreSQL persistence guide** — comprehensive developer documentation with setup, examples, validation rules

- **Compile-time validation stages 3-4** — parameter type checking against schema columns, return record field mapping against SELECT output, CRUD column existence validation, NOT NULL column coverage for insert/save, safe type coercion support
- **Record expansion wiring** — `VALUES(:request)` and `SET :request` patterns now expand record fields in generated SQL with accessor expressions in factory code
- **Migration manifest** — `pg-maven-plugin` generates `migrations.list` for reliable annotation processor schema discovery
- **JBCT file size limit** — `[files] maxFileSize` in `jbct.toml` (default 1MB) auto-skips grammar-generated parsers from format/lint
- **JBCT glob excludes** — `[files] excludes` in `jbct.toml` for explicit file pattern exclusion from format/lint
- **`@Contract` suppresses all JBCT rules** — marks Java API boundary methods (annotation processors, Maven Mojos) as exempt from JBCT lint
- **pg:lint Maven goal** — migration linting via `mvn pg:lint`, reports lock hazards, type design issues, schema design problems
- **Unified blueprint-level deployment** — single `aether deploy` command and `/api/deploy` endpoint replacing separate canary/blue-green/rolling-update commands. All deployment strategies operate on entire blueprints (all slices atomically), not individual slices
- **Unified deployment spec** — `aether/docs/specs/unified-deploy-spec.md` with complete API design

### Changed
- **Aether Store branding** — PostgreSQL persistence adapter branded as "Aether Store" in all user-facing documentation
- **build.sh** — replaced `-q` with grep filtering, JBCT formatting warnings visible, no more stalls on large files
- **Format logging** — JBCT formatter now logs reformatted files at WARN level (was DEBUG)
- **url-shortener examples** — migrated from raw `@Sql`/`SqlConnector` to typed `@PgSql` persistence interfaces
- **Deployment CLI** — `aether deploy --canary`, `aether deploy --blue-green`, `aether deploy --rolling` replace `aether canary`, `aether blue-green`, `aether update`
- **Deployment REST API** — `/api/deploy` replaces `/api/canary/*`, `/api/blue-green/*`, `/api/rolling-update/*`
- **Resource reference docs** — added `PgSqlConnector` section with link to persistence guide

### Fixed
- **SchemaLoader migration discovery** — expanded from 1 suffix to 28 common descriptions, plus manifest-first approach
- **Table name resolution** — `OrderRow` → `orders` (was `order`), correct pluralization via schema lookup
- **INSERT with record params** — expands record fields (was using parameter name as column)
- **FQCN in generated code** — `java.lang.Long` → `Long`, inner types simplified in factory output
- **FactoryGenerator mapper typeArg** — `getObject()` calls now include class argument for Instant/BigDecimal
- **`Result.failure(cause)` → `cause.result()`** — 7 sites in SchemaBuilder, CodegenPipeline, RecordGenerator
- **Multi-statement lambdas** — 6 extracted to named methods across SchemaBuilder, DdlAnalyzer, linter rules, TypeMapper
- **SWIM double-start race condition** — atomic `starting` flag prevents two ESTABLISHED notifications from creating duplicate SWIM protocols; transport bind failure now aborts protocol creation
- **Slice processor @PgSql detection** — `ResourceQualifierModel.fromParameter()` now checks type-level annotations, not just parameter annotations; persistence interfaces correctly classified as resources
- **Slice processor factory wrapping** — generated code maps `PgSqlConnector` through `{Interface}Factory` when resource type differs from parameter type
- **PgSqlConnectorFactory SPI registration** — added to `META-INF/services/org.pragmatica.aether.resource.ResourceFactory`
- **Blueprint deploy classifier** — CLI and server auto-append `:blueprint` classifier when only `groupId:artifactId:version` given
- **Integration test node count** — `cluster_node_count()` uses health endpoint (QUIC peers) instead of metrics-based status endpoint
- **Integration test deploy flow** — push artifacts before deploy, use CLI for deployment with failover

### Removed
- **Separate deployment commands** — `aether canary`, `aether blue-green`, `aether update` removed (use `aether deploy --strategy`)
- **Separate deployment REST endpoints** — `/api/canary/*`, `/api/blue-green/*`, `/api/rolling-update/*` removed (use `/api/deploy`)

## [0.25.0] - 2026-04-01

### Added
- **Hierarchical Storage Engine (AHSE)** — Content-addressed block storage with tiered Memory + Disk hierarchy. Core library at `integrations/storage` (zero Aether deps), Aether adapter at `aether/aether-storage`. BlockId (SHA-256), MemoryTier (CAS-bounded), LocalDiskTier (sharded filesystem), StorageInstance (write-through + tier-waterfall reads), SingleFlightCache (read dedup), MetadataStore (in-memory + KV-Store backed), SnapshotManager (dual-trigger: mutation count + time interval, rolling pruning), StorageReadinessGate (startup sequencing with read/write barriers), per-instance TOML config (`[storage.*]` sections), ArtifactStore migration (chunks via StorageInstance), config-driven StorageFactory with node wiring, per-node REST API (`/api/storage`, `/api/storage/{name}`, `/api/storage/{name}/snapshot`), per-cluster REST API (`/api/cluster/storage`, `/api/cluster/storage/{name}`) with KV-Store status publishing, CLI commands (`aether storage list/status/snapshot`), 107 unit + integration tests
- **Streaming Phase 1 runtime** — `StreamPublisherFactory` and `StreamAccessFactory` (ResourceFactory SPI), `StreamPublisherImpl` with partition-key routing or round-robin, `StreamAccessImpl` with cross-partition fetch and consensus cursor checkpointing, `StreamConsumerAdapter` for single-event and batch handlers, `StreamConfigParser` for blueprint `[streams.xxx]` TOML sections
- **CDM stream integration** — stream creation from blueprint config during deployment, consumer subscription registration at slice activation via KV-Store, unsubscription on deactivation
- **QUIC certificate rotation** — `CertificateRenewalScheduler` wired to node startup, triggers at 60% remaining validity, exponential retry backoff (5min→4h cap), server restart on same port with atomic SSL context swap
- **HTTP server certificate rotation** — ManagementServer and AppHttpServer receive renewed bundles and restart with new TLS contexts (H1 + H3)
- **Certificate expiry observability** — `GET /api/certificate` endpoint, `aether cert status` CLI command, expiry timestamp and renewal status
- **QUIC per-stream backpressure queue** — bounded queue (100 per peer per stream type) replaces silent drop, drain on channel writable, queue depth metrics
- **Declarative cluster management** — `aether-cluster.toml` config format with `[deployment]` + `[cluster]` sections, config parser with 14 validation rules, diff engine with field-to-action matrix, `aether cluster` CLI (bootstrap, apply, status, export, scale, upgrade, drain, destroy, list, use, remove), cluster registry (`~/.aether/clusters.toml`), cloud-init user-data template, KV-Store config storage with optimistic concurrency, 5 management API endpoints
- **Gossip key rotation** — `RotatingGossipEncryptor` with VarHandle hot-swap, epoch-day versioned keys from `SelfSignedCertificateProvider`, KV-Store `GossipKeyRotationHandler` for cluster-wide key distribution, 24-hour dual-key overlap window
- **TLS operator guide** — comprehensive documentation: auto-generated certs, manual cert files, rotation lifecycle, monitoring, gossip encryption, troubleshooting
- **On-premises SSH bootstrap** — `SshBootstrapOrchestrator` with per-node Docker deployment, `DockerComposeGenerator` for single-host testing, `SystemdUnitTemplate` for JVM deployments, `RemoteCommandRunner` (ProcessBuilder SSH/SCP), `--compose-only` flag
- **Notification hub example** — two-slice example exercising streaming + per-route security + Principal injection end-to-end
- **PostgreSQL LISTEN/NOTIFY resource** — `PgNotificationSubscriber` with dedicated connection, multi-channel config (`[pg-notifications.xxx]`), `PgNotification(channel, payload, pid)`, annotation processor detection, comprehensive developer guide
- **Dashboard observability** — depth registry config UI (inline edit, add/remove rules) + invocation requests tab (sortable metrics, slow requests, traces, filters)
- **Integration test suite** — 14 suites, 56 Docker-based test scripts (smoke, stability, chaos, scaling, streaming, security, deployment, cluster-mgmt, resources, artifacts, database, observability, network, edge-cases)
- **Installation binaries** — jlink custom JRE + shaded JAR bundles for node/cli/forge, multi-platform archives (linux-amd64, linux-arm64, darwin), platform-aware install.sh/upgrade.sh

- **Streaming Phase 2** — Governor-push replication (fire-and-forget with watermark tracking), strong consistency (Rabia consensus produce path for total ordering), sealed segment pipeline (EvictionListener → SegmentSealer → StorageSegmentSink → SegmentReader), consumer read-preference (LEADER/NEAREST/FOLLOWER_ONLY), governor failover recovery (watermark-based replica catch-up), tier-aware retention (aggressive post-seal eviction)
- **AHSE Phase 2** — RemoteTier (S3-backed StorageTier with SigV4 REST client), ContentStore (auto-chunking API with manifest blocks, compression integration), DemotionManager (4 eviction strategies: AGE/LFU/LRU/SIZE_PRESSURE, dormant/active lifecycle), StorageGarbageCollector (orphan collection with grace period, dormant/active), PromotionManager (frequency-based cold-to-hot promotion), write-behind policy (async slow-tier writes with bounded queue), cross-node prefetching (SWIM-piggybacked access hints)
- **AHSE Phase 3** — LZ4/ZSTD compression pipeline, AES-256-GCM block encryption, StorageBackedPersistence (ContentStore-backed RabiaPersistence)
- **S3 REST client** — SigV4-signed S3-compatible client in `integrations/cloud/aws/s3` (PutObject/GetObject/DeleteObject/HeadObject/ListObjectsV2, path-style MinIO support)
- **Architectural compliance** — dormant/active lifecycle on all background workers, KV-Store persistence abstractions (WatermarkStore, ReplicaAssignmentStore, TombstoneStore), SegmentIndex rebuild from storage refs, control-plane delegation investigation updated
- **Integration test metrics** — opt-in thread/heap/RSS collection before+after each test (`COLLECT_METRICS=true`)
- **Integration test README** — comprehensive setup guide, architecture docs, test-writing examples
- **Soak test exclusion** — `SKIP_SOAK=true` (default) in `run-all.sh` and `run-suite.sh` to skip long-running soak tests

### Removed
- **`-XX:+ZGenerational`** — removed from all JVM configurations (Java 25 makes generational ZGC the default)
- **`-XX:+UseCompactObjectHeaders`** — removed from all JVM configurations (no measurable impact in benchmarks)

### Changed
- **java-peglib 0.2.1** — parser regenerated, all 35 lint rules updated for new CST shape (ordered-choice container wrapping)
- **ConsumerConfig** — added `checkpointIntervalMs`, `maxRetries`, `deadLetterStream` fields (backward compatible)
- **StreamConfig** — added `maxEventSizeBytes` field with enforcement in `StreamPartitionManager.publishLocal()`
- **Nullable AtomicReference eliminated** — `CancellableTask` (VarHandle, 9 usages), `StoppableThread` (VarHandle, 4 usages), `AtomicHolder<T>` (VarHandle, 4 usages) in `core/` replace all `getAndSet(null)` patterns
- **Docker image base** — switched from `eclipse-temurin:25-alpine` to `eclipse-temurin:25-noble` (glibc required by netty-quiche native library)
- **SSH bootstrap** — Docker bridge network with container hostnames instead of `--network host`, env-var-based config (PEERS, CLUSTER_PORT, MANAGEMENT_PORT), `$HOME/aether` paths instead of `/opt/aether`
- **Docker config** — `repositories = ["builtin"]` (DHT is fully distributed; `local` fallback removed)
- **Integration test assertions** — cluster health checks use `/api/status` instead of `/health/ready` and `/api/nodes`
- **CLI global output formatting** — `--format` (json/table/value/csv), `--field` (dot-notation extraction), `--quiet`, `--no-color` / `NO_COLOR` env var on all ~100 commands via picocli mixin
- **CLI Jackson migration** — replaced hand-rolled JSON parsing with `JsonMapper` tree API; deleted `SimpleJsonReader`, `formatJson()`, `extractJsonString()` and duplicates
- **CLI standardized exit codes** — `SUCCESS=0`, `ERROR=1`, `TIMEOUT=2`, `NOT_FOUND=3` across all commands
- **CLI TLS support** — `--tls-skip-verify` / `-k` flag with trust-all SSL; scheme-aware URL resolution
- **CLI shell completions** — `aether generate-completion` for bash/zsh/fish; auto-install in `install.sh`
- **JsonMapper tree API** — `readTree()`, `extractField()`, `prettyPrint()` methods added to jackson integration module

### Changed
- **Cluster-wide `/api/slices`** — returns all slices across all nodes with per-node instance states, target counts, and version; old per-node behavior moved to `/api/node/slices`
- **Per-node route endpoint** — `/api/routes` moved to `/api/node/routes` for naming consistency
- **CLI `slices` command** — now shows cluster-wide view; added `node-slices`, `routes`, `node-routes` commands

### Added
- **Standalone passive load balancer** — `aether-lb.jar` shaded binary with `--peers`, `--http-port`, `--cluster-port` CLI args, joins cluster as PassiveNode, routes HTTP via binary protocol; includes Dockerfile
- **Passive node KV-Store snapshot sync** — passive nodes (LB) receive full KV-Store state on cluster join via `KVSyncRequest`/`KVSyncResponse`; LB works regardless of when it starts relative to blueprint deployment
- **Stream auto-creation on publish** — `POST /api/streams/{name}/publish` auto-creates stream with default config if it doesn't exist; follows Kafka `auto.create.topics.enable` pattern
- **Stream creation endpoint** — `POST /api/streams` for explicit stream creation with configurable partition count

### Changed
- **java-peglib 0.2.0** — PEG parser generator bumped from 0.1.8, Java25Parser regenerated (-2,940 lines net)

### Fixed
- **CLI version** — was hardcoded at 0.19.2, now correctly shows 0.25.0
- **`@CodecFor` annotation processor** — two-pass processing: register all types first, then generate codecs (fixes ordering issues); generates codecs for external records and enums
- **Java 25 ambiguous method reference** — `ListenNotifyTest.subscribe()` lambda → method reference for stricter overload resolution
- **Streaming API** — REST publish failed with "Stream not found" because streams were only created lazily by slice factories, not available via management API
- **Stream publish payload** — changed from base64-only to raw UTF-8 string for simpler management API usage
- **Stream memory allocation** — management API streams use 16MB default (was 1GB per stream, crashing containers)
- **Stream memory cap** — `StreamPartitionManager` enforces 128MB global off-heap cap, rejects new streams when exceeded instead of OOM crash
- **Idle stream reaper** — `reapIdleStreams()` destroys empty streams past retention age, freeing off-heap memory
- **Integration test `api_post`/`app_post`** — bash brace expansion bug `"${2:-{}}"` appended extra `}` to all POST bodies
- **Integration test suite** — API key auth (`aether-integration-test-key` default), correct url-shortener endpoints (`/api/v1/urls/`), stream payload format (`data` as string), stream info JSON parsing, concurrent deploy test, reduced streaming load duration (30s from 300s)
- **Autoscaler noisy log** — scale-down rule logged at INFO every 5s even when blocked by min-instances guard; changed to DEBUG
- **Java 25 TLS compatibility** — RSA self-signed certs for dev mode, BouncyCastle PEMParser for EC key loading (preserves named curve encoding for BoringSSL), explicit BC KeyFactory in `SelfSignedCertificateProvider`
- **Schema migration lock failover** — new leader scans for MIGRATING schemas with expired locks and resets to PENDING
- **Dashboard schema retry button** — FAILED migrations can be retried from dashboard UI
- **Certificate rotation race condition** — SSL contexts updated before server stop, eliminating null-server window

## [0.24.1] - 2026-03-25

### Added
- **ClusterTopologyManager** — new node lifecycle manager with reconciliation state machine (FORMING → CONVERGED ↔ RECONCILING). Handles auto-heal, scale-up/down, quorum safety. Replaces fragile boolean flags in CDM with clean state transitions. Single action path for all node count changes
- **Consensus-driven topology discovery** — Hello handshake carries node address; ON_DUTY lifecycle notifications trigger topology additions. Dynamically provisioned nodes become visible to all cluster members via consensus, not just the provisioning leader
- **Per-route security** — routes.toml `[security]` section with per-route policies (public/authenticated/role:name), type-safe `RouteSecurityPolicy` interface with `canAccess()`, `SecurityPolicy` sealed variants in Aether, route-level enforcement in AppHttpServer (per-route wins over global SecurityMode)
- **Principal/SecurityContext injection** — slice handler methods can declare `Principal` or `SecurityContext` parameters; code generator injects from `SecurityContextHolder` automatically
- **Blueprint security overrides** — operators can override route security at deploy time via `[security.overrides]` in blueprint.toml with `strengthen_only`/`full`/`none` policies
- **QUIC transport metrics** — `QuicTransportMetrics` with active connections, handshakes, messages sent/received, write failures, backpressure drops; exposed via `/api/metrics/transport`
- **Per-route request metrics** — Micrometer counters and timers per route pattern; security denial counters with denial type classification
- **Dashboard route security badges** — Routes panel on Deployments page shows security policy per route
- **Config validation warnings** — blueprint parser warns on unrecognized TOML sections
- **Streaming lifecycle operations spec** — §16 added to in-memory streams spec: replica count change, repartitioning, stream deletion, migration patterns

### Changed
- **Topology management refactored** — `TcpTopologyManager` renamed to `TopologyObserver` (pure observation); new `ClusterTopologyManager` wraps observer and manages cluster size. CDM no longer owns node provisioning
- **`NodeLifecycleValue` carries address** — host/port included in ON_DUTY registration for consensus-driven node discovery (backward compatible with old format)
- **`RouteSecurityPolicy` renamed to `SecurityPolicy`** — moved from transport-level to intent-based (Public, Authenticated, ApiKeyRequired, BearerTokenRequired, RoleRequired); extends generic `RouteSecurityPolicy` from http-routing layer
- **`[security]` section optional** — routes.toml without `[security]` defaults to PUBLIC with STRENGTHEN_ONLY policy (backward compatible)
- **Security validators handle all policy variants** — ApiKeySecurityValidator and JwtSecurityValidator now handle Authenticated and RoleRequired in addition to their primary types
- **Route security in KV-Store** — `NodeRoutesValue.RouteEntry` carries security field; serialization is backward compatible with old format

### Fixed
- **Node auto-heal** — killed nodes automatically replaced via ComputeProvider; batch provisioning proportional to deficit; quorum safety (never below 3); ON_DUTY health check before considering provision complete; leader failover detects ready nodes via consensus
- **Node departure healing** — SWIM FAULTY routes `RemoveNode` to topology manager; QUIC disconnect routes `RemoveNode` for passive LB; CDM rebuilds state before cleanup; sequential reconciliation prevents consensus batch collisions
- **Reconnection storm eliminated** — `ConnectionFailed` routed to topology manager for exponential backoff; reconciliation loop is sole reconnection driver; new nodes bypass ConnectionDirection for initial join
- **QUIC write failures detected** — `writeAndFlush()` listener detects failures, removes stale links, triggers reconnection
- **QUIC DataHandler error containment** — `exceptionCaught()` closes channel; deserialization wrapped in try-catch to prevent single malformed message from killing connection
- **QUIC write backpressure** — writability check before write; `WriteTimeoutHandler(10s)` in stream pipelines
- **QUIC Hello deserialization safety** — try-catch in both server and client Hello handlers
- **SecurityMode=NONE + authenticated route** — returns clear 401 "Route requires authentication but no security mode is configured" instead of vague error
- **WWW-Authenticate header** — no longer sent when SecurityMode=NONE (was misleadingly advertising ApiKey)
- **WebSocket auth timeout** — sends AUTH_TIMEOUT message before closing instead of silent disconnect
- **Overlapping route detection** — compile WARNING when two routes have same method+path pattern
- **Invocation metrics strategy** — returns 501 Not Implemented with clear message; CLI explains limitation

## [0.24.0] - 2026-03-24

### Added
- **QUIC cluster transport** — replaces TCP for all inter-node communication. Stream-per-message-type multiplexing (consensus stream 0, KV stream 1, HTTP forward stream 2, DHT stream 3), mandatory TLS 1.3 with auto-generated self-signed certs for dev, 0-RTT reconnection, connection migration, NodeId-ordered connection initiation. First Java distributed runtime on QUIC
- **Soak test infrastructure** — 4-hour k6 sustained load scenario with chaos injection phases (worker kill, rolling restart), Prometheus + Grafana monitoring with 14-panel auto-provisioned dashboard, automated pass/fail verdict (6 criteria: heap growth, GC pause, P99 drift, error rate, SWIM stability, node count), markdown report generation

### Changed
- **SWIM port offset** — changed from cluster_port+1 to cluster_port+100 to avoid port collisions in multi-node Forge
- **Passive LB** — uses own event loop groups instead of TCP server groups (QUIC has no TCP server)

### Fixed
- **QUIC message delivery** — DataHandler replaces Hello handler after handshake; messages were silently dropped post-Hello
- **QUIC message framing** — added LengthFieldBasedFrameDecoder to QUIC stream pipelines; QUIC streams are byte-oriented like TCP, not message-framed
- **QUIC message routing** — incoming messages now routed to MessageRouter via onMessageReceived callback
- **QUIC idle timeout** — disabled per RFC 9000 §10.1; peer-to-peer connections died after 30s of no traffic between consensus rounds (only leader→peer had regular MetricsPing traffic)
- **Passive LB event loop** — uses own groups instead of TCP server groups (QUIC has no TCP server)

## [0.23.1] - 2026-03-24

### Added
- **AppHttpServer: configurable request size limits** — `max_request_size` in TOML with `DataSize` parser (KB/MB/GB), 413 response when exceeded
- **AppHttpServer: multipart file upload** — `FileUpload`, `MultipartRequest` records, Netty `HttpPostRequestDecoder` integration, `RequestContext.multipartRequest()` accessor
- **AppHttpServer: API token auth** — `SecurityMode` config (none/api-key/jwt), reuses management RBAC infrastructure, `SecurityContextHolder` ScopedValue propagation to slice handlers, health endpoint bypasses auth
- **AppHttpServer: JWT auth with JWKS** — `JwtSecurityValidator`, `JwtTokenParser`, `JwtSignatureVerifier` (RS256/ES256), `JwksKeyStore` with TTL cache, clock skew tolerance — pure JDK crypto, no external JWT libraries
- **AppHttpServer: HTTP/3 via Netty QUIC** — `Http3Server` with dual-stack H1+H3 support, `QuicSslContextFactory`, `Alt-Svc` header for protocol upgrade hints, `HttpProtocol` config enum
- **Dashboard: new panels** — schema migration status, governor/community, deployment strategies (canary/blue-green/A/B), streams, cluster composition (core/worker counts)
- **Operational audit events** — 7 event types in cluster event stream (AccessDenied, NodeLifecycleChanged, ConfigChanged, BackupCreated/Restored, BlueprintDeployed/Deleted)
- **`@CodecFor` annotation** — compile-time + runtime codec validation for external types. Manual codecs required (no auto-generation), `REQUIRED_TYPES` validated at startup. Three-layer safety net: compile-time field check, `@CodecFor` declaration, runtime startup validation. Eliminates silent serialization failures permanently
- **Codec processor compile-time field validation** — ERROR for `@Codec` records with unregistered field types
- **ManagementServer HTTP/3** — dual-stack H1+H3 support matching AppHttpServer, `management_protocol` TOML config
- **NettyHttpOperations** — HTTP/1.1 + HTTP/3 client via Netty QUIC, alternative to JDK HttpClient. Full HTTP/3 stack (server + client) complete
- **Manual codecs for core types** — TimeSpan, Email, Url, NonBlankString, Uuid, IsoDateTime registered in NodeCodecs via `@CodecFor` with hand-written codecs

### Fixed
- **Dashboard: 24 audit issues** — alert data unwrapping, ALERT_RESOLVED broadcast, INITIAL_STATE node population, per-node metrics, real P50/P95/P99 percentiles, time range selector, WS auth, REST auth headers, error toasts, per-channel WS status, topology diffing, success rate chart Y-axis, latency panel after tab switch
- **JBCT compliance: 40+ issues** — constant-time API key comparison, generic error messages to clients, unknown role defaults to VIEWER, Result.lift for exception boundaries, Option for null policy, AtomicReference for thread safety, @Contract for lifecycle methods
- **`@Codec` on `AuthorizationRole`** — fixes serialization failure in HTTP forwarding
- **Pre-existing codec issues** — TimeSpan, MethodName, ExecutionMode, KVCommand, Blueprint, NodeLifecycleState, SchemaStatus all now have proper codec registration via `@Codec` or `@CodecFor`

## [0.23.0] - 2026-03-23

### Added
- **In-memory streaming (preview)** — ordered, replayable, consumer-paced streaming as a first-class Aether resource
  - `StreamPublisher<T>`, `StreamSubscriber`, `StreamAccess<T>` — slice-developer API with `@PartitionKey` annotation for partition routing
  - `OffHeapRingBuffer` — off-heap ring buffer using `MemorySegment` with circular wrap-around and retention eviction (count/size/age)
  - `StreamPartitionManager` — governor-local produce/consume with per-stream partition management
  - Annotation processor: detects stream resources, generates manifest entries, envelope format v7
  - `StreamConsumerRuntime` — push-based delivery with RETRY (exponential backoff), SKIP, and STALL error strategies
  - `DeadLetterHandler` — in-memory dead-letter storage for failed events
  - REST API: `GET /api/streams`, `GET /api/streams/{name}`, `POST /api/streams/{name}/publish`, `GET /api/streams/{name}/{partition}/read`
  - CLI: `aether stream list`, `aether stream status`, `aether stream publish`
  - KV-Store types for stream metadata, partition assignments, cursor checkpoints
  - 140+ tests across the streaming stack

## [0.22.0] - 2026-03-23

### Added
- **RBAC Tier 2 — role-based authorization** — three hierarchical roles (ADMIN/OPERATOR/VIEWER) with per-route enforcement in the management API pipeline. RoutePermissionRegistry resolves permissions by HTTP method and path prefix. 403 Forbidden for authorization failures. TOML config `authorization_role` field on API keys (defaults to ADMIN for backward compat). Independent security audit passed clean — all 40+ mutation routes verified
- **Operational audit events in cluster event stream** — 7 event types (AccessDenied, NodeLifecycleChanged, ConfigChanged, BackupCreated, BackupRestored, BlueprintDeployed, BlueprintDeleted) routed through ClusterEventAggregator alongside existing DeploymentEvent and SchemaEvent
- **Audit trail expansion** — AuditLog calls added to all mutation paths: schema migration lifecycle, CDM scaling decisions, config changes, backup/restore, node lifecycle transitions, blueprint deploy/undeploy

### Changed
- **Feature catalog updated** — reflects 0.21.1/0.21.2 additions, backup/restore contradictions resolved, statistics updated (145 features: 24 battle-tested, 113 complete)

## [0.21.2] - 2026-03-22

### Added
- **Schema migration failure recovery** — automatic retry with exponential backoff (5s/15s/45s) for transient failures, manual retry via `POST /api/schema/{ds}/retry` and `aether schema retry` CLI command
- **Schema migration events** — structured `SchemaEvent` hierarchy (MigrationStarted, MigrationCompleted, MigrationFailed, MigrationRetrying, ManualRetryRequested) with natural language explanations suitable for both human operators and LLM agents
- **Failure classification** — transient (connection timeout, lock contention) vs permanent (SQL syntax, checksum mismatch) with appropriate retry behavior
- **`schema_required` blueprint config** — `[deployment]` section option to skip schema migration gate, allowing slices that don't need schema to deploy immediately

## [0.21.1] - 2026-03-22

### Added
- **Docker scaling test infrastructure** — 5-core + 7-worker Docker Compose setup with phase-based orchestrator, k6 load tests (steady-state + scaling verification), Maven protocol artifact upload
- **CORE_MAX env var** — Docker containers configure core/worker role via environment variable instead of per-node TOML
- **X-Node-Id header on all HTTP responses** — enables k6 to verify traffic distribution across nodes
- **SWIM startup delay** — configurable cooldown after quorum (default 10s) before first probe, allowing TCP connections to stabilize
- **SWIM revival grace period** — recently-revived members skip probing for configurable duration (default 5s)

### Changed
- **SharedScheduler consolidation** — migrated 10 production schedulers to SharedScheduler (min 8 platform threads), eliminating thread pool proliferation across SWIM, CircuitBreaker, Retry, canary evaluation, and heartbeat
- **SWIM transport uses Netty built-in DnsNameResolver** — replaced custom DomainNameResolver with Netty's native DNS resolver, eliminating Promise chain overhead in the send path. DNS resolution stays entirely within Netty's event loop
- **SWIM logging levels** — recv messages at TRACE (was INFO), SUSPECT/FAULTY at WARN (was INFO)
- **SwimConfig uses TimeSpan** — replaced Duration with TimeSpan, relaxed defaults for Docker (period=1s, probeTimeout=800ms, suspectTimeout=15s)
- **PiggybackBuffer dissemination counting** — changed from drain-on-read to peek-and-age with configurable max disseminations, preventing premature update loss

### Fixed
- **InetSocketAddress codec missing** — no codec was registered for `InetSocketAddress`, causing silent serialization failure for ALL SWIM Ping/Ack messages with piggybacked membership updates. Every probe timed out, causing universal SUSPECT cascade. Root cause of all SWIM flapping
- **SWIM relay sequence collision** — relay Pings reused original requester's sequence number, colliding with local probes. Fixed with dedicated relay sequence and RelayInfo mapping
- **SWIM PingReq sender address** — handlePingReq looked up requester from member list (hostname-based, possibly missing). Fixed by passing actual UDP sender address
- **SWIM relay cleanup** — age-based expiry instead of pendingProbes presence check (which removed ALL relays since relay sequences are never in pendingProbes)
- **SWIM state priority enforcement** — FAULTY > SUSPECT > ALIVE at same incarnation prevents stale ALIVE piggyback from overriding SUSPECT
- **SWIM round-robin probing** — deterministic member selection instead of random, ensuring all members probed equally
- **SWIM FAULTY member cleanup** — bounded growth with 3× suspectTimeout eviction threshold
- **SWIM incarnation bump on Ack** — prevents stale SUSPECT piggyback from re-suspecting a node that just responded
- **Schema migration concurrency** — local deduplication via inFlightMigrations Set prevents duplicate migrations from concurrent KV-Store notifications
- **AppHttpConfig wiring** — Main.java now reads `[app-http]` TOML section and calls `withAppHttp()`
- **ConfigurationProvider wiring** — Main.java now builds and wires ConfigurationProvider from TOML file
- **Missing SqlConnector factories in node JAR** — added resource-db-async and resource-db-jdbc dependencies
- **GossipEncryptor race condition** — resolved at quorum time instead of assembly, when certificate provider is initialized

## [0.21.0] - 2026-03-21

### Added
- **Per-datasource schema migration engine** — full migration execution engine with Flyway-style versioned (V), repeatable (R), undo (U), and baseline (B) migration types. Schema history tracked in `aether_schema_history` table per datasource. Checksum validation, transactional per-script execution, configurable failure/failover policy
- **Schema orchestration** — distributed coordination layer with consensus-based locking, artifact resolution, and status tracking (PENDING → MIGRATING → COMPLETED/FAILED). CDM integration gates slice deployment on schema readiness
- **Schema management REST API and CLI** — REST endpoints (`/api/schema/status`, `/api/schema/migrate/{ds}`, `/api/schema/undo/{ds}`, `/api/schema/baseline/{ds}`) and CLI commands (`aether schema status|history|migrate|undo|baseline`)
- **Schema directory convention** — `schema/` root maps to default `[database]` config section (matching `@Sql`), subdirectories map to `[database.<name>]` sections. Single-datasource slices need no subdirectory
- **Schema migration executes end-to-end** — DatasourceConnectionProvider provisions SqlConnector per datasource, wiring migration engine to actual database execution
- **Strict datasource resolution** — missing config section causes explicit failure with descriptive error; no silent fallback or derivation
- **Removed embedded H2 from Forge** — Forge no longer provides an embedded H2 database; external PostgreSQL required via `start-postgres.sh`
- **Schema migration prerequisites** — `start-postgres.sh` scripts create the required database; migration engine requires pre-existing databases (creates tables, not databases)
- **Blueprint artifact auto-packaging** — `generate-blueprint` goal now automatically packages the blueprint JAR (no need to add `package-blueprint` explicitly). Schema directory default changed to `${project.basedir}/schema`
- **Forge artifact-based deployment** — `--blueprint` accepts artifact coordinates with classifier (`groupId:artifactId:version:classifier`). Forge resolves via configured Repository chain (local Maven repo in dev, DHT in production). TOML deployment path removed
- **Enriched `/api/nodes` endpoint** — now returns role (CORE/WORKER) and isLeader flag per node, with role sourced from `ActivationDirectiveValue` in KV-Store
- **`GET /api/cluster/governors` endpoint** — exposes governor announcements from KV-Store: governor ID, community, member count, and member list

### Deployment Strategies
- **Canary deployments** -- Progressive traffic shift with configurable stages (1% -> 5% -> 25% -> 50% -> 100%), auto-evaluation every 30s, health-based auto-rollback, KV-Store persistence, leader failover recovery
- **Blue-green deployments** -- Atomic traffic switchover (~100ms via single Rabia round), drain period, instant switch-back for rollback, 2x resource usage during transition
- **A/B testing** -- Deterministic traffic split by request context (header hash, cookie hash, header match, percentage), ScopedValue-based variant propagation through invoke chains, per-variant metrics collection
- **Deployment strategy coordinator** -- Mutual exclusion (one strategy per artifact), unified routing lookup for all strategies
- **HTTP version-aware routing** -- AppHttpServer checks deployment strategy routing before serving locally, forwards to remote node when weighted decision routes to other version
- **Blueprint deployment config** -- Optional `[deployment]` TOML section for strategy selection and configuration

### Changed
- **Deployment event aggregator — KV-Store driven** — deployment events (STARTED/COMPLETED/FAILED) now derived from `NodeArtifactKey` KV-Store notifications instead of manually injected local messages. All nodes see all deployment events. Deployment duration tracked from LOAD→ACTIVE, node join-to-first-deployment timing included
- **Jackson 3.1.0 LTS** — bumped from 3.0.3, annotations from 2.20 to 2.21
- **JBCT review compliance** — SharedScheduler for canary evaluation (was shutdownNow), AtomicBoolean for SliceInvoker.stop(), immutable FailoverContext collections, AB→Ab rename (acronym-as-word), factory methods for all value objects, Option for null policy, deployment audit logging via AuditLog, void helper suppressions
- **Role-aware unified AetherNode** — merged WorkerNode into AetherNode. Single `aether-node.jar` binary for both CORE and WORKER roles. Consensus observer mode (receives Decisions without voting), `ForwardingClusterNode` for transparent KV write forwarding, `SwitchableClusterNode` for runtime role switching. WORKER→CORE promotion supported. `aether/worker` module eliminated — components ported to `aether/node` and `aether-metrics`
- **Quorum fix for mixed clusters** — when `coreMax > 0`, consensus quorum calculated against core node count only (not total nodes including workers)
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
- **Multi-phase allocation double-write** — `tryAllocate()` now optimistically tracks allocations in `sliceStates`, preventing Phase 2/3 of `issueScaleUpCommands` from re-allocating nodes already assigned in Phase 1 (async `cluster.apply()` hadn't committed yet)
- **Blueprint deletion deallocation** — `handleAppBlueprintRemoval()` now issues deallocation commands before removing artifacts from `blueprints` map; previously deferred to `onSliceTargetRemove` which couldn't find the artifacts because they were already removed
- **SliceState ACTIVATING timeout** — test expected 60s but actual was 90s (aligned after activation timeout changes)
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
  - **Schema migration prep**: Blueprint artifacts carry `schema/` migration scripts (root `schema/*.sql` maps to `[database]`, subdirectories `schema/<name>/*.sql` map to `[database.<name>]`). End-to-end execution via DatasourceConnectionProvider
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
