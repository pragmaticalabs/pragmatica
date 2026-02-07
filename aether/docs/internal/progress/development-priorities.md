# Development Priorities

## Current Status (v0.15.0)

Release 0.15.0 focuses on **monorepo consolidation** and **production readiness** with real-time dashboard, cluster-wide observability, and HTTP performance.

## Completed ✅

### Core Infrastructure
- **Request ID Propagation** - ScopedValue-based context with ServiceLoader propagation hook in Promise
- **SliceInvoker Immediate Retry** - Event-driven retry on node departure (matches AppHttpServer pattern)
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

### Observability & Control
- **Metrics Collection** - Per-node CPU/JVM metrics at 1-second intervals
- **Cluster-Wide Metrics Broadcast** - MetricsPing carries full cluster snapshot; every node has complete picture
- **Invocation Metrics** - Per-method call tracking with percentiles
- **Prometheus Endpoint** - Standard metrics export format
- **Alert Thresholds** - Persistent threshold configuration via consensus
- **Controller Configuration** - Runtime-configurable scaling thresholds
- **Decision Tree Controller** - Programmatic scaling rules
- **TTM Predictive Scaling** - ONNX-based traffic prediction and scaling recommendations
- **DeploymentMap** - Event-driven slice-to-node index with cluster-wide deployment visibility
- **EMA Latency Smoothing** - 5-second effective window for stable dashboard metrics

### Dashboard & Real-Time
- **WebSocket Push** - Zero-polling dashboard via `/ws/status` with polling fallback
- **Dashboard Deployments** - Cluster-wide deployment data (artifact, state, per-instance status)
- **Forge WebSocket** - StatusWebSocketHandler/Publisher for Forge dashboard
- **Node WebSocket** - DashboardWebSocketHandler/Publisher for management dashboard
- **Production Logging** - H2 SQL suppression, debug overrides removed from fat jars

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
- **Secrets** - `infra-secrets` management (in-memory)
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

### Documentation
- **CLI Reference** - Complete command documentation
- **Management API** - Full HTTP API reference
- **Runbooks** - Deployment, scaling, troubleshooting
- **Developer Guides** - Slice development, migration
- **Slice Lifecycle** - Hook semantics, materialization, execution order

---

## Next Up

### HIGH PRIORITY - Cluster Operations

1. **Dynamic Aspect** ← recommended next
   - Implement `DynamicAspect`, the aspect that can be dynamically switched between different modes - None, Log, Metrics, Log+Metrics.
   - Implement `DynamicAspectManager`, which keeps registry of all instances of DynamicAspect active at node keyed by the class name+method name to which they are applied.
   - Provide API to manage these instances across the cluster
   - Wire to dashboard with convenient UI
   - **Why next:** Builds on WebSocket dashboard, invocation metrics, and KV-store consensus patterns already in place. Same pattern as AlertManager. Immediate operational value for production debugging.

2. **Dynamic Configuration via KV Store**
   - Expose most configuration in consensus KV store
   - Nodes automatically pick up configuration changes
   - No restart required for config updates
   - **Why second:** Generalizes the proven AlertManager/threshold KV pattern to all configuration. Prerequisite for many other features.

3. **Dependency Lifecycle Management**
   - Block manual unload while dependents are ACTIVE
   - Graceful degradation on dependency failure (calls fail, slice handles it)
   - Dependency graph tracking in KV store
   - Clear error reporting with dependency chain visualization
   - **Why third:** Correctness gap — currently possible to break running slices by unloading dependencies.

4. **DB Connector Infrastructure**
   - Database connectivity layer for slices
   - Connection pooling and transaction management
   - Support for common databases (PostgreSQL, MySQL, etc.)

5. **External Secrets Management Integration**
   - HashiCorp Vault integration
   - AWS Secrets Manager / Azure Key Vault support
   - Current: in-memory `infra-secrets` implementation exists

6. **Cloud Provider Adapters (NodeLifecycleManager)**
   - Implement `NodeLifecycleManager.executeAction(NodeAction)`
   - Cloud provider adapters: AWS, GCP, Azure
   - Execute `StartNode`, `StopNode`, `MigrateSlices` decisions from controller
   - Node auto-discovery and registration
   - Node pool support: core (on-demand) vs elastic (spot) pools
   - Instance type parameter for spot/on-demand selection
   - **Enables:** Spot Instance Support (#17), Expense Tracking (#18)

### MEDIUM PRIORITY - Infrastructure Services

7. **Distributed Saga Orchestration**
   - Long-running transaction orchestration (saga pattern)
   - Durable state transitions with compensation on failure
   - Differs from local state machine - coordinates across multiple slices
   - Automatic retry, timeout, and dead-letter handling
   - Visualization of in-flight sagas and their states

8. **Disruption Budget**
   - Minimum healthy instances during rolling updates and node failures
   - Configurable per slice or blueprint
   - Controller respects budget before scaling down or migrating
   - Prevents cascading failures during maintenance

9. **Forge Script - Scenario Language**
   - DSL for defining load/chaos test scenarios
   - Reusable scenario libraries
   - CI/CD integration for automated testing
   - Note: Paid tier feature

10. **Placement Hints**
    - Affinity/anti-affinity rules for slice placement
    - Spread: distribute instances across nodes/zones
    - Co-locate: place related slices on same node
    - Zone-aware scheduling for high availability

### LOWER PRIORITY - Security & Operations

11. **TLS Certificate Management**
    - Certificate provisioning and rotation
    - Mutual TLS between nodes
    - Integration with external CA or self-signed

12. **Canary & Blue-Green Deployment Strategies**
    - Current: Rolling updates with weighted routing exist
    - Add explicit canary deployment with automatic rollback on error threshold
    - Add blue-green deployment with instant switchover
    - A/B testing support with traffic splitting by criteria

13. **Topology in KV Store**
    - Leader maintains cluster topology in consensus KV store
    - Best-effort updates on membership changes
    - Enables external observability without direct node queries

14. **RBAC for Management API**
    - Role-based access control for operations
    - Predefined roles: admin, operator, viewer
    - Per-endpoint authorization rules
    - Audit logging for sensitive operations

### FUTURE

15. **LLM Integration (Layer 3)**
    - Claude/GPT API integration
    - Complex reasoning workflows
    - Multi-cloud decision support

16. **Mini-Kafka (Message Streaming)**
    - Ordered message streaming with partitions (differs from pub/sub)
    - In-memory storage (initial implementation)
    - Consumer group coordination
    - Retention policies

17. **Cross-Slice Transaction Support (2PC)**
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

18. **Spot Instance Support for Elastic Scaling**
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
    **Prerequisite:** Cloud Provider Adapters (#6)

19. **Cluster Expense Tracking**
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
    **Prerequisite:** Cloud Provider Adapters (#6)

---

## Infra Development

Infrastructure slices requiring distributed implementations:

| Service | In-Memory | Distributed | Notes |
|---------|:---------:|:-----------:|-------|
| infra-cache | ✅ | ❌ | KV-backed distributed cache |
| infra-pubsub | ✅ | ❌ | Cross-node message delivery |
| infra-scheduler | ✅ | ❌ | Leader-coordinated scheduling |
| infra-statemachine | ✅ | ❌ | Consensus state transitions |
| infra-lock | ✅ | ❌ | Consensus-backed locking |
| infra-ratelimit | ✅ | ❌ | Shared counters across nodes |
| infra-secrets | ✅ | ❌ | → External Secrets (#5) |
| infra-config | ✅ | ❌ | → Dynamic Config (#2) |
| infra-database | ✅ | N/A | → DB Connector (#4) |
| infra-http | ✅ | N/A | HTTP client, no distributed needed |
| infra-aspect | ✅ | N/A | → Dynamic Aspect (#1) |

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
