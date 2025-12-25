# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
