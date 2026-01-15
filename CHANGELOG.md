# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **MethodHandle API** - Performance optimization for repeated slice invocations
  - New `MethodHandle<R, T>` interface for pre-parsed artifact/method (response type first per convention)
  - `SliceInvokerFacade.methodHandle()` factory method
  - Eliminates per-call parsing overhead (4 regex + 5 allocations)
  - Old `invoke(String, String, Object, Class)` method deprecated
- **RFC 7807 Problem Details** - Standardized HTTP error responses
  - `ProblemDetail` record with type, title, status, detail, instance fields
  - Mandatory `requestId` extension for request tracing
  - `application/problem+json` content type for all error responses
  - Covers handler errors, HttpError failures, and 404 not found

## [0.7.4] - 2026-01-13

### Changed
- **infra-slices module split** - Split into 16 independent modules for granular dependencies
  - infra-cache, infra-database, infra-blob, infra-secrets, infra-outbox
  - infra-statemachine, infra-aspect, infra-config, infra-streaming
  - infra-http, infra-server, infra-feature, infra-lock
  - infra-pubsub, infra-ratelimit, infra-scheduler

### Added
- **Maven Central Publishing** - Publishing configuration for public modules
  - Apache 2.0 license for: slice-api, slice-annotations, infra-api, and all 16 infra-* modules
  - GPG signing and source/javadoc generation via release profile
  - `scripts/publish.sh` for manual publishing workflow
  - README.md documentation for each publishable module
- **HttpServerSlice** - HTTP server infrastructure slice
  - Wraps pragmatica-lite's NettyHttpServer with Slice lifecycle
  - Route handling via http-routing module integration
  - Configurable port, timeouts, content length
  - JBCT-compliant sealed error hierarchy
- **CacheService extensions** - Extended cache functionality
  - Batch operations: getMulti, setMulti, deleteMulti
  - Counter operations: increment, incrementBy, decrement
  - Pattern operations: keys, deletePattern
  - Statistics: CacheStats with hit/miss tracking
  - Configuration: CacheConfig with eviction policies
  - JBCT-compliant error types (CacheServiceError)

## [0.7.3] - 2026-01-10

### Added
- **Production Readiness Features**
  - Request ID propagation through inter-slice invocations (KSUID-based)
  - Multi-instance failover - tries all endpoints before failing
  - Slice failure alerting - CRITICAL alerts when all instances fail
  - Webhook alert forwarding with retry (`AlertForwarder`)
  - Automatic rollback on persistent slice failure (`RollbackManager`)
  - Previous version tracking in KV-Store for rollback support
- **Configurable Load Runner** - TOML-driven load testing for Forge
  - Pattern generators: `${uuid}`, `${random:PATTERN}`, `${range:MIN-MAX}`, `${choice:A,B,C}`, `${seq:START}`
  - Per-target rate limiting, optional duration, path/body templates
  - Dashboard "Load Testing" tab with config upload, controls, live metrics
  - API endpoints: `/api/load/config`, `/api/load/start|stop|pause|resume`, `/api/load/status`
- **New Configuration Types**
  - `RollbackConfig` - automatic rollback policy (cooldown, max rollbacks)
  - `AlertConfig` - alert thresholds and webhook configuration
  - `SliceConfig` - slice-specific configuration
  - `RepositoryType` - artifact repository type enumeration

### Changed
- **JBCT compliance fixes** - 102 issues resolved across codebase
  - 8 error types wrapped in sealed hierarchies
  - 6 value object factories now return `Result<T>`
  - Thread safety fixes (CopyOnWriteArrayList, AtomicReference)
  - Resource cleanup improvements (ByteBuf, OrtSession)
  - Test method naming standardized to `method_scenario_expectation`
  - Complex lambdas extracted to named methods
- **jbct-maven-plugin** - Re-enabled (version 0.4.7)
- **JBCT compliance refactoring** - Load generator code now fully JBCT-compliant
  - Factory methods on all pattern generators (`uuidGenerator()`, `randomGenerator()`, etc.)
  - Sealed interfaces for utility classes (`LoadConfigLoader`, `PatternParser`)
  - Error types renamed to past-tense (`ParseFailed`, `ValidationFailed`, `FileReadFailed`)
  - Manual null checks replaced with `Option.option().filter().toResult()` pattern
  - Imperative loops replaced with `Result.allOf()` + streams
  - `.isFailure()` checks replaced with monadic `flatMap`/`map` chains
  - Complex lambdas extracted to named methods
  - `Void` return replaced with `whenComplete()` pattern

## [0.7.2] - 2026-01-06

### Added
- **Introduction article** - Comprehensive article presenting Aether to developers (`docs/articles/aether-introduction.md`)

### Fixed
- **Rolling Update Manager** - Multiple critical fixes
  - State restoration race condition - no longer restores state in constructor before KVStore is ready
  - Added 30-second timeouts to all KV-Store operations to prevent hangs
  - P99 latency now correctly uses histogram percentile instead of average
  - MANUAL cleanup policy now properly preserves old version for manual removal
- **Leader checks** - Rolling update POST handlers now properly verify leader status before execution
- **API endpoint fixes** - Comprehensive and derived metrics endpoints use correct field names
- **E2E test fix** - `RollingUpdateE2ETest` now uses correct `artifactBase` field (was `artifact`)
- **EventLoopMetricsCollector** - Now properly wired to receive Netty EventLoopGroups from Server after cluster starts

### Changed
- **pragmatica-lite** - Updated to version 0.9.9 with `Server.bossGroup()`/`workerGroup()` accessors
- **TTMConfig** - Now defaults to disabled (`enabled=false`) - requires explicit opt-in
- **RollingUpdateState** - Removed unused `VALIDATING` state and `approveRouting()` method
  - Simplified state machine: `ROUTING → COMPLETING` (was `ROUTING → VALIDATING → COMPLETING`)
  - Updated documentation to reflect simplified flow

### Removed
- **MinuteAggregator.recordEvent()** - Dead code removed; event counting uses EventPublisher fallback

## [0.7.1] - 2026-01-03

### Added
- **Comprehensive E2E test suite** - 80 E2E tests across 12 test classes proving production readiness
  - `ClusterFormationE2ETest` (4 tests) - Cluster bootstrap, quorum formation, leader election
  - `SliceDeploymentE2ETest` (6 tests) - Deploy, scale, undeploy, blueprints
  - `RollingUpdateE2ETest` (6 tests) - Two-stage updates, traffic shifting
  - `NodeFailureE2ETest` (6 tests) - Failure modes, recovery
  - `ChaosE2ETest` (5 tests) - Resilience under adverse conditions
  - `ManagementApiE2ETest` (19 tests) - Status, metrics, thresholds, alerts, controller endpoints
  - `SliceInvocationE2ETest` (9 tests) - Route handling, error cases, request distribution
  - `MetricsE2ETest` (6 tests) - Metrics collection, Prometheus format, distribution
  - `ControllerE2ETest` (7 tests) - Configuration, status, leader behavior
  - `BootstrapE2ETest` (4 tests) - Node restart, state recovery, rolling restart
  - `GracefulShutdownE2ETest` (4 tests) - Peer detection, slice handling, leader failover
  - `NetworkPartitionE2ETest` (4 tests) - Quorum behavior, partition healing, consistency
- **Enhanced AetherNodeContainer** - 20+ new API methods for E2E testing
  - Metrics API: `getPrometheusMetrics()`, `getInvocationMetrics()`, `getSlowInvocations()`
  - Threshold API: `getThresholds()`, `setThreshold()`, `deleteThreshold()`
  - Alert API: `getActiveAlerts()`, `getAlertHistory()`, `clearAlerts()`
  - Controller API: `getControllerConfig()`, `setControllerConfig()`, `triggerControllerEvaluation()`
  - Rolling Update API: `startRollingUpdate()`, `getRollingUpdates()`, `setRollingUpdateRouting()`
  - Slice invocation: `invokeSlice()`, `invokeGet()`, `invokePost()`, `getRoutes()`
- **Weighted endpoint routing integration** - `SliceInvoker` now uses weighted routing during rolling updates
  - Checks for active rolling update via `RollingUpdateManager`
  - Uses `EndpointRegistry.selectEndpointWithRouting()` for traffic distribution
  - Falls back to simple round-robin when no update active
- **Alert threshold persistence** - Thresholds now persist to KV-Store with consensus replication
  - `AlertThresholdKey` - KV-Store key for threshold entries (`alert-threshold/{metric}`)
  - `AlertThresholdValue` - Threshold configuration with warning/critical values
  - Automatic sync across cluster nodes via KVStoreNotification
  - Thresholds survive node restarts
- **DELETE /thresholds/{metric}** - Remove alert thresholds via REST API
- **Per-artifact invocation metrics filtering** - Query parameters for `/invocation-metrics`
  - `?artifact=<partial>` - Filter by artifact (partial match)
  - `?method=<name>` - Filter by method name (exact match)
- **Threshold strategy introspection** - `GET /invocation-metrics/strategy` endpoint
  - Returns current strategy type and configuration
  - Supports Fixed, Adaptive, PerMethod, Composite strategies
- **E2E rolling update tests** - Fixed and enabled `RollingUpdateE2ETest`
  - `AetherNodeContainer.get()` and `post()` helpers for HTTP operations
- **Forge management ports** - Each Forge node now exposes management API (ports 5150+)
  - `ForgeCluster` enables management on all nodes
  - `NodeStatus` includes `mgmtPort` field
  - Dashboard can link to per-node management endpoints
- **Integration tests** - New integration tests without mocks
  - `ManagementApiIT` - Tests management API with shared 3-node cluster
  - `EndpointRegistryIT` - Tests weighted routing for rolling updates

### Changed
- **AlertManager** - Now requires `RabiaNode` and `KVStore` for persistence
  - `setThreshold()` returns `Promise<Unit>` (consensus operation)
  - `removeThreshold()` returns `Promise<Unit>` (consensus operation)
  - Added `onKvStoreUpdate()` and `onKvStoreRemove()` for cluster sync
- **ThresholdStrategy** - Added getter methods for introspection
  - `Adaptive`: `minThresholdNs()`, `maxThresholdNs()`, `multiplier()`
  - `PerMethod`: `defaultThresholdNs()`
- **Documentation** - Repositioned Forge as local development environment
  - Updated README.md Forge section
  - Updated Forge Guide with management port documentation
  - Clarified chaos testing as one feature among many

## [0.6.5] - 2026-01-02

### Added
- **API completeness** - All endpoints now return real data (no stubs)
- **Rolling Update Manager** - Full implementation with KV-Store persistence
  - `RollingUpdateManagerImpl` - Orchestrates rolling updates via consensus
  - Two-stage model: deploy (0% traffic) then route (gradual traffic shift)
  - Health-based auto-progression with configurable thresholds
  - Manual approval option for critical updates
- **Invocation Metrics API** - Per-method metrics exposed via REST
  - `GET /invocation-metrics` - All method metrics with counts, latencies
  - `GET /invocation-metrics/slow` - Slow invocation details
- **Controller Configuration API** - Runtime-configurable scaling thresholds
  - `ControllerConfig` record with CPU, call rate, and interval settings
  - `GET /controller/config` - Get current configuration
  - `POST /controller/config` - Update thresholds at runtime
  - `GET /controller/status` - Controller status
  - `POST /controller/evaluate` - Force evaluation cycle
- **Alert Configuration API** - Enhanced alert management
  - `GET /alerts/active` - Active alerts only
  - `GET /alerts/history` - Alert history only
  - `POST /alerts/clear` - Clear active alerts
  - `POST /thresholds` - Set alert thresholds
- **CLI Commands** - New commands for all APIs
  - `invocation-metrics` - View per-method metrics
  - `controller config/status/evaluate` - Manage controller
  - `alerts list/active/history/clear` - Manage alerts
  - `thresholds list/set` - Manage thresholds
- **API Documentation** - Comprehensive HTTP API reference
  - `docs/api/management-api.md` - Full endpoint documentation
  - `docs/api/examples/curl-examples.sh` - Shell script examples
  - `docs/api/examples/python-client.py` - Python client example

### Changed
- **pragmatica-lite 0.9.4** - Updated with Hello handshake protocol and helloTimeout support
- **Networking layer cleanup** - Removed duplicate NettyClusterNetwork, Handler, Encoder, Decoder from aetherx
- **Use pragmatica-lite networking** - Now uses `org.pragmatica.consensus.net.netty.NettyClusterNetwork` from pragmatica-lite
- **Use pragmatica-lite topology** - Now uses `org.pragmatica.consensus.topology.TcpTopologyManager` and `TopologyConfig` from pragmatica-lite
- **DecisionTreeController** - Now accepts ControllerConfig, supports runtime configuration updates
- **AlertManager** - Added `removeThreshold()`, `getAllThresholds()`, `clearAlerts()`, `activeAlertCount()` methods
- **AetherNode interface** - Added `invocationMetrics()`, `controller()`, `rollingUpdateManager()`, `endpointRegistry()` methods

### Removed
- `cluster/net/netty/` package - Duplicate of pragmatica-lite implementation
- `cluster/topology/ip/TcpTopologyManager` - Now provided by pragmatica-lite
- `cluster/topology/ip/TopologyConfig` - Now provided by pragmatica-lite (with helloTimeout support)

## [0.6.4] - 2026-01-01

### Added
- **Docker container infrastructure** - Separate Dockerfiles for aether-node and aether-forge with Alpine base
- **docker-compose.yml** - 3-node cluster configuration with health checks and optional Forge profile
- **E2E testing module** - Testcontainers-based E2E tests for cluster formation, deployment, and chaos scenarios
- **AetherNodeContainer** - Testcontainer wrapper with API helpers for E2E tests
- **AetherCluster** - Multi-node cluster helper for managing N-node clusters in tests
- **CI workflow enhancements** - E2E tests job (main or `[e2e]` tag) and Docker build/push to ghcr.io
- **Rolling update system** - Two-stage deployment model (deploy then route) for zero-downtime updates
  - `ArtifactBase` - Version-agnostic artifact identifier for rolling updates
  - `RollingUpdateState` - State machine with 10 states (PENDING → COMPLETED/ROLLED_BACK/FAILED)
  - `VersionRouting` - Ratio-based traffic routing between versions (e.g., 1:3 = 25% new)
  - `HealthThresholds` - Configurable error rate and latency thresholds for auto-progression
  - `CleanupPolicy` - IMMEDIATE, GRACE_PERIOD (5min), or MANUAL cleanup of old versions
  - `RollingUpdateManager` - Interface for start/adjust/approve/complete/rollback operations
- **Weighted endpoint routing** - `EndpointRegistry.selectEndpointWithRouting()` with weighted round-robin
- **Rolling update API endpoints** - REST API for rolling update management
  - `POST /rolling-update/start` - Start new rolling update
  - `GET /rolling-updates` - List active updates
  - `GET /rolling-update/{id}` - Get update status
  - `POST /rolling-update/{id}/routing` - Adjust traffic ratio
  - `POST /rolling-update/{id}/approve` - Manual approval
  - `POST /rolling-update/{id}/complete` - Complete update
  - `POST /rolling-update/{id}/rollback` - Rollback to old version
  - `GET /rolling-update/{id}/health` - Version health metrics
- **Rolling update CLI commands** - `aether update` command group
  - `update start <artifact> <version>` - Start rolling update with health thresholds
  - `update status <id>` - Get update status
  - `update list` - List active updates
  - `update routing <id> -r <ratio>` - Adjust traffic routing
  - `update approve/complete/rollback <id>` - Update lifecycle operations
  - `update health <id>` - View version health metrics
- **KV schema extensions** - `VersionRoutingKey`, `RollingUpdateKey`, `VersionRoutingValue`, `RollingUpdateValue`
- **Observability metrics** - Micrometer integration with Prometheus endpoint
  - `GET /metrics/prometheus` - Prometheus-format metrics scrape endpoint
  - `ObservabilityRegistry` - Central registry for metrics with JVM/process metrics
  - `AetherMetrics` - Pre-configured metrics for slice invocations, consensus, deployments
  - JVM metrics - memory, GC, threads, classloaders via Micrometer binders

### Changed
- **pragmatica-lite 0.9.3** - Updated with consensus observability support

### Fixed
- **RabiaNode protocol message routing** - Added routes for RabiaProtocolMessage types (Propose, Vote, Decision, SyncRequest/Response, NewBatch) to RabiaEngine
- **TestCluster QuorumStateNotification** - Added missing route for QuorumStateNotification to RabiaEngine in test infrastructure

## [0.6.3] - 2026-01-01

### Added
- **Production dashboard** - Real-time cluster monitoring at `/dashboard` with Alpine.js and Chart.js
- **WebSocket metrics streaming** - 1-second broadcast of cluster metrics via `/ws/dashboard`
- **Alert management** - Configurable thresholds with in-memory alert tracking
- **Dashboard tabs** - Metrics, Slices, History, and Alerts views
- **New API endpoints** - `/slices/status`, `/invocation-metrics`, `/thresholds`, `/alerts`
- **TLS support** - Added `withTls()` to AetherNodeConfig, propagated to ManagementServer, HttpRouter, and cluster network
- **Enhanced /health endpoint** - Returns status (healthy/degraded/unhealthy), quorum, nodeCount, sliceCount
- **RingBuffer utility** - Thread-safe ring buffer with O(1) add and fixed memory footprint
- **Operational runbooks** - Added docs/runbooks/ with incident-response, scaling, troubleshooting, deployment guides

### Changed
- **pragmatica-lite 0.9.2** - Updated to latest version with breaking API changes
- **Consensus module** - Migrated to pragmatica-lite consensus module (removed 30+ duplicate files)
- **Package reorganization** - All consensus-related imports moved from `org.pragmatica.cluster.*` to `org.pragmatica.consensus.*`
- **TlsConfig/NodeAddress** - Now use pragmatica-lite `org.pragmatica.net.tcp` package
- **ID generation** - Switched from ULID to KSUID for correlation IDs (pragmatica-lite alignment)
- **Route registration** - Centralized in node assembly, removed `configure()` methods from components
- **Verify API** - Updated to new `Verify.ensure(value, predicate, pattern, cause)` signature
- **MetricsCollector** - Use RingBuffer instead of CopyOnWriteArrayList for historical metrics (7200 capacity)
- **HttpMethod.fromString()** - Returns `Option<HttpMethod>` instead of throwing
- **PathPattern.compile()** - Returns `Result<PathPattern>` instead of throwing
- **SliceManifest.readManifest()** - Returns `Result<Manifest>` instead of throwing
- **Main.parsePeerAddress()** - Returns `Optional<NodeInfo>` instead of throwing
- **DHTNode.create()** - Renamed to `DHTNode.dhtNode()` (JBCT naming convention)
- **SliceRegistry.create()** - Renamed to `SliceRegistry.sliceRegistry()` (JBCT naming convention)

### Removed
- **common module** - Replaced by pragmatica-lite modules (messaging, dht, consensus, utility, net)
- **Duplicate consensus files** - Now provided by pragmatica-lite consensus module
- **Duplicate network types** - Now use pragmatica-lite versions

### Fixed
- **LeaderManagerTest** - Updated to use deterministic node IDs for reliable test ordering

## [0.6.2] - 2025-12-29

### Added
- **Deployment metrics** - Track slice deployment timing with full/net deployment time and transition latencies
- **DeploymentMetricsCollector** - Collect and aggregate deployment metrics across cluster with leader-based broadcast
- **DeploymentMetricsScheduler** - Periodic deployment metrics distribution (5-second interval)
- **Enhanced /metrics endpoint** - Now includes both load and deployment metrics in JSON response
- **slice-annotations module** - Minimal module with `@Slice` annotation for compile-time type safety
- **SliceBridge interface** - Node-Slice communication using byte[] boundary for classloader isolation
- **FrameworkClassLoader** - ClassLoader with Platform ClassLoader parent for framework class isolation
- **SliceBridgeImpl** - Bridge implementation with Fury serialization at boundary
- **DependencyFile [api] section** - Support for typed slice dependencies in dependency files
- **Historical metrics sliding window** - 2-hour sliding window for pattern detection in MetricsCollector
- **BlueprintService** - Wired into AetherNode for application blueprint management
- **Typed Slice API design documentation** - Comprehensive design for compile-time type-safe slice APIs
- **jbct-cli task specification** - Detailed implementation requirements for annotation processor and maven plugin

### Changed
- **InvocationHandler** - Now uses SliceBridge instead of InternalSlice
- **SliceInvoker.invokeLocal()** - Updated to use SliceBridge byte[] interface
- **NodeDeploymentManager** - Creates SliceBridgeImpl for slice registration
- **NodeDeploymentManager** - Default to Fury serializer when none configured
- **SharedDependencyLoader** - Process [api] section dependencies into SharedLibraryClassLoader
- **AetherNode** - Wire FrameworkClassLoader with graceful fallback
- **InvocationMetricsCollector** - Renamed factory methods to follow naming convention

### Fixed
- Remove RuntimeException in DependencyFile and SliceDependencies (use Result.lift pattern)
- Remove empty MCP module from build

### Removed
- **InternalSlice** - Replaced by SliceBridgeImpl with cleaner byte[] boundary
- **mcp module** - Deprecated, replaced by direct agent API

## [0.6.0] - 2025-12-27

### Added
- **BackendSimulation framework** - Latency and failure injection for testing
- **DataGenerator framework** - Configurable test data generation
- **Invocation metrics system** - Per-entry-point metrics with adaptive thresholds
- **Automatic route cleanup** - Routes removed when last slice instance deactivates
- **Realistic inventory mode** - Stock depletion and refill simulation
- **SimulatorConfig** - Configuration API for Forge simulator
- **OrderRepository** - Cross-slice order visibility in demos

### Changed
- Updated to Pragmatica Lite 0.9.0
- Replaced `Causes.forValue` with `forOneValue` pattern
- Improved memory handling and resource cleanup in invocation layer
- Enhanced LoadGenerator with per-entry-point rate control
- Project renamed to "Pragmatica Aether Distributed Runtime"
- License changed to Business Source License 1.1

### Fixed
- Memory leaks in invocation layer
- Allow hyphens in GroupId validation
- Endpoint publishing for remote slice invocation
- Simulator code polish for production readiness

### Removed
- JBCT documentation (use `/jbct` skill instead)

## [0.5.0] - 2025-12-25

### Added
- **Distributed Hash Table (DHT)** - Foundation for infrastructure services
  - ConsistentHashRing with 1024 partitions and virtual nodes
  - DHTNode for local operations, DHTClient for remote operations
  - MemoryStorageEngine with pluggable storage backend
  - Quorum-based reads/writes with configurable replication
- **HTTP Route Self-Registration** - Dynamic route management via KV-Store
  - RouteKey/RouteValue schema for cluster-wide route storage
  - RouteRegistry with idempotent registration and conflict detection
  - SliceRoute API for slices to declare routes
  - Backward compatible with blueprint-defined routes
- **Artifact Repository Service** - Maven-compatible artifact storage
  - ArtifactStore with DHT-backed chunked storage (64KB chunks)
  - MavenProtocolHandler for GET/PUT operations
  - ArtifactRepoSlice implementing Slice interface
  - CLI commands: `artifact deploy`, `artifact list`, `artifact versions`
- **infra-services module** - Infrastructure service slices
- **C-level resilience demo** with visual dashboard
  - D3.js cluster topology visualization (fixed blinking on poll)
  - Real-time metrics charts with Chart.js
  - Per-node JVM metrics display (toggleable CPU/heap)
  - HTTP-based load generator for realistic request simulation
  - Chaos operations: kill node, kill leader, rolling restart

### Changed
- Demo-order slices migrated to route self-registration
- HttpRouter tries dynamic routes first, falls back to static routes
- Updated to Java 25 EA in CI

## [0.4.0] - 2025-12-22

### Added
- **HTTP Router** - Route external HTTP requests to slice methods
  - PathPattern for URL pattern matching with path variables
  - RouteMatcher for route selection across routing sections
  - BindingResolver for parameter binding (PathVar, QueryVar, Header, Body)
  - Protocol filtering (http/https only)
- **Routing types** - Route, RouteTarget, RoutingSection, Binding, BindingSource
- **HttpRouterSetup** in AetherNodeConfig for HTTP router configuration
- **invokeLocal** method in SliceInvoker for same-node invocation without network round-trip
- **demo-order module** - Complete order domain demo application
  - order-domain: Value objects (OrderId, ProductId, CustomerId, Quantity, Money, Currency, OrderStatus)
  - inventory-service: Stock checking and reservation (InventoryServiceSlice)
  - pricing-service: Price lookup and order total calculation (PricingServiceSlice)
  - place-order: PlaceOrderSlice use case with inter-slice calls
  - get-order-status: GetOrderStatusSlice use case
  - cancel-order: CancelOrderSlice use case with stock release
- **HTTP router unit tests** - PathPatternTest (11 tests), RouteMatcherTest (6 tests)
- **HTTP router integration tests** - 3 tests in AetherNodeIT

### Changed
- Moved routing classes to org.pragmatica.aether.slice.routing package

## [0.3.0] - 2025-12-21

### Added
- **AetherNode** - Complete node assembly wiring all components together
- **AetherNodeConfig** - Node configuration with topology, protocol, and management port settings
- **AetherNode integration tests** - Cluster formation, consensus, and replication tests
- **ClusterDeploymentManager** - Leader-based allocation, reconciliation, scale up/down
- **EndpointRegistry** - Round-robin load balancing for slice endpoints
- **Blueprint DSL parser** - Parse blueprint definitions from text format
- **SliceStore implementation** - Complete slice lifecycle management
- **SliceClassLoader** - Isolated class loading for slices
- **DependencyResolver** - Resolve slice dependencies with cycle detection
- **SliceRegistry** - Track loaded slice instances
- **Manifest-based slice discovery** - Discover slices via MANIFEST.MF
- **MetricsCollector** - Per-node JVM and call metrics collection
- **MetricsScheduler** - Leader-driven ping-pong metrics distribution
- **DecisionTreeController** - Programmatic scaling rules (CPU-based)
- **ControlLoop** - Leader-only control evaluation loop
- **SliceInvoker** - Client-side inter-slice invocation with retry support
- **InvocationHandler** - Server-side slice method dispatch
- **ManagementServer** - HTTP API for cluster management (status, nodes, slices, metrics, deploy, scale, undeploy)
- **AetherCli** - Command-line interface with REPL and batch modes

### Changed
- Replaced `EntryPoint` with `SliceMethod` for type-safe method definitions
- Updated to Pragmatica Lite 0.8.4
- Applied JBCT compliance fixes (Result<Unit>, functional patterns)

### Fixed
- Option timeout handling in SliceState using Option<TimeSpan>
- LoadedSlice.slice() now returns Slice directly instead of Result<Slice>

## [0.2.0] - 2025-11-01

### Added
- Core slice lifecycle states and transitions
- Artifact type system (GroupId, ArtifactId, Version)
- KV-Store schema (AetherKey, AetherValue)
- Rabia consensus implementation
- Leader manager for deterministic leader selection
- NodeDeploymentManager implementation
- Example slice implementation (StringProcessorSlice)

## [0.1.0] - 2025-10-01

### Added
- Initial project structure
- Slice API definitions
- Basic cluster module with Rabia protocol
