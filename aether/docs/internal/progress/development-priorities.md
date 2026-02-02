# Development Priorities

## Current Status (v0.15.0)

Release 0.15.0 focuses on **monorepo consolidation** and **production readiness** with improved logging, blueprint CLI, and startup diagnostics.

## Completed ✅

### Core Infrastructure
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
- **Distributed Cache** - `infra-cache` with consistent hashing
- **Pub/Sub** - `infra-pubsub` topic-based messaging
- **State Machine** - `infra-statemachine` for local state transitions
- **Artifact Repository** - Maven protocol subset, deploy/resolve operations
- **Database** - `infra-database` abstraction
- **Blob Storage** - `infra-blob` for binary objects
- **Secrets** - `infra-secrets` management
- **Rate Limiter** - `infra-ratelimit` for throttling
- **Distributed Lock** - `infra-lock` for coordination
- **Feature Flags** - `infra-feature` for feature toggles
- **Scheduler** - `infra-scheduler` for timed tasks
- **Outbox** - `infra-outbox` for transactional messaging

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

1. **Request ID Propagation (Distributed Tracing)**
   - Current gap: HTTP requestId doesn't bridge to InvocationContext for local slice calls
   - Need: Reliable async context propagation that works across thread boundaries
   - Consider: Context-aware Promise or explicit context parameter passing
   - Should cover: HTTP entry → local slice → inter-slice calls (local & remote)
   - Goal: Full request correlation across all hops without thread-local limitations

2. **Topology in KV Store**
   - Leader maintains cluster topology in consensus KV store
   - Best-effort updates on membership changes
   - Enables external observability without direct node queries

3. **Dynamic Configuration via KV Store**
   - Expose most configuration in consensus KV store
   - Nodes automatically pick up configuration changes
   - No restart required for config updates

4. **Dependency Lifecycle Management**
   - Handle dependency removal while dependent slice is ACTIVE
   - Options when dependency becomes unavailable:
     - **Cascade deactivation** - Automatically deactivate dependent slices
     - **Graceful degradation** - Mark dependency calls as failing, let slice handle it
     - **Blocking** - Prevent dependency undeployment while dependents are ACTIVE
   - Dependency graph tracking in KV store
   - Clear error reporting with dependency chain visualization
   - Consider: Should slices declare "required" vs "optional" dependencies?

### MEDIUM PRIORITY - Infrastructure Services

5. **Mini-Kafka (Message Streaming)**
   - Ordered message streaming with partitions (differs from pub/sub)
   - In-memory storage (initial implementation)
   - Consumer group coordination
   - Retention policies

6. **Distributed Saga Orchestration**
   - Long-running transaction orchestration (saga pattern)
   - Durable state transitions with compensation on failure
   - Differs from local state machine - coordinates across multiple slices
   - Automatic retry, timeout, and dead-letter handling
   - Visualization of in-flight sagas and their states

### LOWER PRIORITY - Security & Operations

7. **TLS Certificate Management**
   - Certificate provisioning and rotation
   - Mutual TLS between nodes
   - Integration with external CA or self-signed

8. **External Secrets Management Integration**
   - HashiCorp Vault integration
   - AWS Secrets Manager / Azure Key Vault support
   - Current: in-memory `infra-secrets` implementation exists

9. **Canary & Blue-Green Deployment Strategies**
    - Current: Rolling updates with weighted routing exist
    - Add explicit canary deployment with automatic rollback on error threshold
    - Add blue-green deployment with instant switchover
    - A/B testing support with traffic splitting by criteria

### FUTURE - AI Integration

10. **LLM Integration (Layer 3)**
   - Claude/GPT API integration
   - Complex reasoning workflows
   - Multi-cloud decision support

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
