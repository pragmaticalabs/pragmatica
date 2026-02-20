# Development Priorities

## Current Status (v0.17.0)

Release 0.17.0 continues production hardening with bug fixes and documentation updates.

## Completed ✅

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
- **Management API** - Complete cluster control endpoints (30+ endpoints)
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
- **Dynamic Aspects** - Runtime-togglable per-method LOG/METRICS/LOG_AND_METRICS modes via `DynamicAspectRegistry`, REST API (`/api/aspects`), KV-store consensus sync, and `DynamicAspectInterceptor` wired into both local and remote invocation paths
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
- **`DatabaseConnectorConfig`** — Rich config with JDBC/R2DBC URL overrides, pool settings, properties
- **`DatabaseConnectorError`** — Sealed error types, `@Sql` qualifier annotation

---

## Next Up

### HIGH PRIORITY - Observability & Operations

1. **Unified Invocation Observability** ← recommended next — [RFC-0010](../../../../docs/rfc/RFC-0010-unified-invocation-observability.md)
   - Single invocation tree: tracing (timing/flow) + depth-logging (input/output) + metrics
   - Automatic instrumentation at dependency boundaries — zero business logic logging code
   - Depth-based verbosity control: adjust detail level dynamically per method via KV store
   - SLF4J bridge: projects depth → traditional severity levels for standard log consumers
   - Ring buffer for dashboard + structured logging for persistence
   - Management API: `/api/traces`, `/api/traces/{requestId}`, `/api/observability/depth`
   - Foundation exists: request ID propagation, InvocationTimingContext, DynamicAspectInterceptor
   - Supersedes RFC-0009 (Request Tracing)
   - **Separate design decisions:** redaction strategy, dashboard UI, serialization limits, sampling

2. **Dependency Lifecycle Management**
   - Block manual unload while dependents are ACTIVE
   - Graceful degradation on dependency failure (calls fail, slice handles it)
   - Dependency graph tracking in KV store
   - Clear error reporting with dependency chain visualization
   - **Why important:** Correctness gap — currently possible to break running slices by unloading dependencies.

3. **Cloud Integration**
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
   - **Enables:** Spot Instance Support (#15), Expense Tracking (#16)

4. **New Resources**
   - **Pub/Sub Resource** — `@ResourceQualifier(type=Publisher/Subscriber.class)` for topic-based messaging. Parameter annotation injects Publisher/Subscriber handle. Config: topic name, serialization format. In-memory impl for Forge, pluggable backend for production (Kafka, Redis Streams, etc.).
   - **Scheduled Invocation Resource** — `@ResourceQualifier(type=Scheduler.class)` on method triggers periodic/cron-based invocation. Config: cron expression, timezone, initial delay. In-memory timer for Forge, cluster-aware scheduling for production (leader-only or distributed).
   - **Why important:** Completes the resource provisioning story — most slice applications need messaging and scheduled tasks.

### HIGH PRIORITY - Dashboard & UI

5. **Dynamic Aspect Dashboard UI**
   - Wire `DynamicAspectRegistry` data to dashboard with convenient UI for toggling per-method aspect modes
   - Backend REST API (`/api/aspects`) and KV-store sync already implemented
   - Smallest UI task — good starting point for establishing dashboard patterns

6. **Invocation Observability Dashboard Tab**
   - "Requests" tab: table view with timestamp, requestId, caller → callee, depth, duration, status
   - Click-to-expand tree view showing invocation depth with input/output at each level
   - Waterfall view for multi-hop request visualization
   - Filters: time range, slice, method, status, depth range
   - Auto-refresh via existing WebSocket push
   - See [RFC-0010](../../../../docs/rfc/RFC-0010-unified-invocation-observability.md) for data model and API
   - Depends on: Unified Invocation Observability (#1) backend

7. **Log Level Management UI**
   - Per-package log level controls in dashboard
   - Current effective levels display
   - Backend ready: `/api/logging/levels` endpoints implemented

### MEDIUM PRIORITY

8. **Disruption Budget**
    - Minimum healthy instances during rolling updates and node failures
    - Configurable per slice or blueprint
    - Controller respects budget before scaling down or migrating
    - Prevents cascading failures during maintenance

9. **Placement Hints**
    - Affinity/anti-affinity rules for slice placement
    - Spread: distribute instances across nodes/zones
    - Co-locate: place related slices on same node
    - Zone-aware scheduling for high availability

### LOWER PRIORITY - Security & Operations

10. **TLS Certificate Management**
    - Certificate provisioning and rotation
    - Mutual TLS between nodes
    - Integration with external CA or self-signed

11. **Canary & Blue-Green Deployment Strategies**
    - Current: Rolling updates with weighted routing exist
    - Add explicit canary deployment with automatic rollback on error threshold
    - Add blue-green deployment with instant switchover
    - A/B testing support with traffic splitting by criteria

12. **Topology in KV Store**
    - Leader maintains cluster topology in consensus KV store
    - Best-effort updates on membership changes
    - Enables external observability without direct node queries

13. **RBAC for Management API**
    - Role-based access control for operations
    - Predefined roles: admin, operator, viewer
    - Per-endpoint authorization rules
    - Audit logging for sensitive operations

14. **Configurable Rate Limiting per HTTP Route**
    - Per-route rate limiting configuration in blueprint or management API
    - Token bucket or sliding window algorithm
    - Configurable limits: requests/second, burst size
    - 429 Too Many Requests response with Retry-After header
    - Cluster-aware: distributed counters via consensus or per-node local limits
    - Note: `infra-ratelimit` exists for slice-internal use; this is for external HTTP routes

### Hetzner Cloud

**Testing (near-term):**
- Cloud cluster formation test — code committed (`545300ce`), **not yet executed against real infrastructure**
- Load balancer test — create LB, add servers as targets, verify traffic distribution. LoadBalancerProvider SPI ready.
- Node failure + re-election — kill servers, verify quorum maintained and new leader elected
- Network partition — manipulate Hetzner firewall rules to block traffic between nodes
- Slice deployment — deploy echo-slice via management API, verify ACTIVE on all nodes
- Pre-baked snapshots — Hetzner image with Java + JAR for instant boot (~30s vs ~3min)

**Production (longer-term):**
- Multi-region cluster — nodes in fsn1 + nbg1 + hel1, test consensus over higher-latency links
- Cloud-native discovery — Hetzner label-based peer discovery (eliminates static peer list)
- Disaster recovery — terminate majority, bring up replacements, verify state recovery via Rabia sync
- Cost tracking — timestamps + actual cost per test run as test metric

### FUTURE

15. **Spot Instance Support for Elastic Scaling**
    - Cost-optimized scaling using cloud spot/preemptible instances
    - 60-90% cost savings for traffic spike handling

    **Architecture:**
    ```
    Core Pool (on-demand)     Elastic Pool (spot)
    ┌─────┬─────┬─────┐      ┌─────┬─────┬─────┐
    │Node1│Node2│Node3│      │Node4│Node5│Node6│  ← traffic spike
    └─────┴─────┴─────┘      └─────┴─────┴─────┘
       Always running           Come and go
       Baseline capacity        Overflow only
    ```

    **Design:**
    - Core pool: on-demand instances, always running, sized for normal traffic
    - Elastic pool: spot instances, scaled for traffic spikes only
    - Spot termination = normal node departure (no special handling)
    - All slices eligible for spot nodes (no per-slice annotation needed)

    **Configuration:**
    ```toml
    [cluster.pools.core]
    min = 3
    max = 5
    type = "on-demand"

    [cluster.pools.elastic]
    min = 0
    max = 20
    type = "spot"
    fallback = "on-demand"  # configurable: if spot unavailable
    ```

    **TTM/LLM Instance Type Selection (consideration):**
    Instance type choice as optimization target, not just config:

    | Prediction | Instance Choice | Rationale |
    |------------|-----------------|-----------|
    | Short spike (< 1hr) | Spot | Cheap, likely survives |
    | Long spike (> 1hr) | On-demand | Stability worth cost |
    | Recurring pattern (daily peak) | Spot | Predictable window |
    | Unknown/anomaly | On-demand | Safety first |

    Additional criteria TTM/LLM could consider:
    - Time of day (spot prices fluctuate)
    - Current spot price (real-time from cloud API)
    - Historical interruption rate per instance type
    - Upcoming known events (prefer on-demand for releases/campaigns)

    **Complexity:** Low - just configuration and cloud API flag
    **Prerequisite:** Cloud Integration (#3)

16. **Cluster Expense Tracking**

    - Real-time cost visibility for cluster operations
    - Enables cost-aware scaling decisions

    **Data Sources:**
    - AWS Cost Explorer API
    - GCP Billing API
    - Azure Cost Management API

    **Metrics:**
    - Total cluster cost (hourly/daily/monthly)
    - Cost per node
    - Cost per slice (derived from node allocation)
    - Cost per request (derived)
    - Spot savings: actual spend vs equivalent on-demand
    - Projected cost at current scale

    **Value:**
    - ROI visibility: "Aether saved $X this month via spot instances"
    - Cost-aware scaling: "this spike costs $Y/hour"
    - Budget alerts: "approaching monthly limit"
    - Chargeback support: cost attribution per slice/team

    **Integration Points:**
    - Dashboard: cost widgets and trends
    - Alerts: budget threshold notifications
    - Controller: cost as scaling factor (optional)
    - TTM/LLM: cost optimization recommendations

    **Complexity:** Medium - cloud billing APIs have quirks, data aggregation needed
    **Prerequisite:** Cloud Integration (#3)

17. **LLM Integration (Layer 3)**
    - Claude/GPT API integration
    - Complex reasoning workflows
    - Multi-cloud decision support

18. **Mini-Kafka (Message Streaming)**
    - Ordered message streaming with partitions (differs from pub/sub)
    - In-memory storage (initial implementation)
    - Consumer group coordination
    - Retention policies

19. **Cross-Slice Transaction Support (2PC)**
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

20. **Distributed Saga Orchestration**
    - Long-running transaction orchestration (saga pattern)
    - Durable state transitions with compensation on failure
    - Differs from local state machine — coordinates across multiple slices
    - Automatic retry, timeout, and dead-letter handling
    - Visualization of in-flight sagas and their states

21. **Forge Script - Scenario Language**
    - DSL for defining load/chaos test scenarios
    - Reusable scenario libraries
    - CI/CD integration for automated testing
    - Note: Paid tier feature

---

## Infra Development

All infrastructure modules transition to unified `@ResourceQualifier(type, config)` pattern. See #4 for remaining items.

| Module | Target | Annotation position | Status |
|--------|--------|---------------------|--------|
| infra-database | `@ResourceQualifier(type=DatabaseConnector.class)` | Parameter | **Done** — `@Sql` qualifier, JDBC/R2DBC/jOOQ |
| infra-cache | `@ResourceQualifier(type=Cache.class)` | Method (wraps) | **Done** — in-memory + DHT + tiered cache interceptors |
| infra-ratelimit | `@ResourceQualifier(type=RateLimiter.class)` | Method (wraps) | **Done** — rate-limit interceptor |
| infra-http | `@ResourceQualifier(type=HttpClient.class)` | Parameter | **Done** — `@Http` qualifier, JSON API |
| infra-scheduler | `@ResourceQualifier(type=Scheduler.class)` | Method (triggers) | **Next** — see #4 |
| infra-pubsub | `@ResourceQualifier(type=Publisher/Subscriber.class)` | Parameter / Method | **Next** — see #4 |
| infra-statemachine | Lightweight builder DSL in core | — | Business logic, not a provisioned resource |
| infra-config | **Remove** | — | Dynamic Configuration via KV store covers this |
| infra-aspect | **Keep** — Fn1 composition utilities | — | `Aspects.withCaching()`, `@Key`, etc. |

**Dropped:** infra-server, infra-streaming, infra-outbox, infra-blob, infra-feature

---

## Deprecated

- **MCP Server** - Replaced by direct agent API (see [metrics-control.md](../../contributors/metrics-control.md))

---

## Implementation Approach

Focus on stability and production readiness:

1. E2E tests prove all features work correctly
2. CLI must be reliable for human operators
3. Agent API must be well-documented
4. Decision tree must handle all common cases
5. Only then add LLM layer

See [metrics-control.md](../../contributors/metrics-control.md) for controller architecture.
