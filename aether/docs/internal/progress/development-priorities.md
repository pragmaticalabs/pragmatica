# Development Priorities

## Current Status (v0.15.0)

Release 0.15.0 focuses on **monorepo consolidation** and **production readiness** with improved logging, blueprint CLI, and startup diagnostics.

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

### Observability & Control
- **Metrics Collection** - Per-node CPU/JVM metrics at 1-second intervals
- **Invocation Metrics** - Per-method call tracking with percentiles
- **Prometheus Endpoint** - Standard metrics export format
- **Alert Thresholds** - Persistent threshold configuration via consensus
- **Controller Configuration** - Runtime-configurable scaling thresholds
- **Decision Tree Controller** - Programmatic scaling rules
- **TTM Predictive Scaling** - ONNX-based traffic prediction and scaling recommendations

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

## Future Work

### HIGH PRIORITY - Cluster Operations

1. **DB Connector Infrastructure**
   - Database connectivity layer for slices
   - Connection pooling and transaction management
   - Support for common databases (PostgreSQL, MySQL, etc.)

2. **Dynamic Configuration via KV Store**
   - Expose most configuration in consensus KV store
   - Nodes automatically pick up configuration changes
   - No restart required for config updates

3. **External Secrets Management Integration**
   - HashiCorp Vault integration
   - AWS Secrets Manager / Azure Key Vault support
   - Current: in-memory `infra-secrets` implementation exists

4. **Cloud Provider Adapters (NodeLifecycleManager)**
   - Implement `NodeLifecycleManager.executeAction(NodeAction)`
   - Cloud provider adapters: AWS, GCP, Azure
   - Execute `StartNode`, `StopNode`, `MigrateSlices` decisions from controller
   - Node auto-discovery and registration

5. **Dependency Lifecycle Management**
   - Block manual unload while dependents are ACTIVE
   - Graceful degradation on dependency failure (calls fail, slice handles it)
   - Dependency graph tracking in KV store
   - Clear error reporting with dependency chain visualization

### MEDIUM PRIORITY - Infrastructure Services

6. **Distributed Saga Orchestration**
   - Long-running transaction orchestration (saga pattern)
   - Durable state transitions with compensation on failure
   - Differs from local state machine - coordinates across multiple slices
   - Automatic retry, timeout, and dead-letter handling
   - Visualization of in-flight sagas and their states

7. **Disruption Budget**
   - Minimum healthy instances during rolling updates and node failures
   - Configurable per slice or blueprint
   - Controller respects budget before scaling down or migrating
   - Prevents cascading failures during maintenance

8. **Forge Script - Scenario Language**
   - DSL for defining load/chaos test scenarios
   - Reusable scenario libraries
   - CI/CD integration for automated testing
   - Note: Paid tier feature

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

### FUTURE

14. **LLM Integration (Layer 3)**
    - Claude/GPT API integration
    - Complex reasoning workflows
    - Multi-cloud decision support

15. **Mini-Kafka (Message Streaming)**
    - Ordered message streaming with partitions (differs from pub/sub)
    - In-memory storage (initial implementation)
    - Consumer group coordination
    - Retention policies

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
| infra-secrets | ✅ | ❌ | → External Secrets (#3) |
| infra-config | ✅ | ❌ | → Dynamic Config (#2) |
| infra-database | ✅ | N/A | → DB Connector (#1) |
| infra-http | ✅ | N/A | HTTP client, no distributed needed |
| infra-aspect | ✅ | N/A | Decorators, no distributed needed |

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
