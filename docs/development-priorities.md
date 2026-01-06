# Development Priorities

## Current Status (v0.7.2)

Release 0.7.2 focuses on **production readiness** with comprehensive E2E testing and documentation.

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

### Deployment Features
- **Rolling Updates** - Two-stage deploy/route model
- **Weighted Routing** - Traffic distribution during updates
- **Blueprint Parser** - Standard TOML format
- **Docker Infrastructure** - Separate images for node and forge

### Examples & Testing
- **Order Domain Demo** - 5-slice order domain example
- **Aether Forge** - Local development environment with dashboard
- **Comprehensive E2E Suite** - 80 tests across 12 test classes
  - Cluster formation, deployment, rolling updates
  - Node failure, chaos, network partition
  - Management API, metrics, controller
  - Bootstrap, graceful shutdown

### Documentation
- **CLI Reference** - Complete command documentation
- **Management API** - Full HTTP API reference
- **Runbooks** - Deployment, scaling, troubleshooting
- **Developer Guides** - Slice development, migration

## Current Priorities

### HIGH PRIORITY

1. **Production Validation**
   - Run full E2E test suite in CI
   - Fix any failing tests
   - Performance benchmarking

2. **Release 0.7.2**
   - Final documentation review
   - CHANGELOG finalization
   - Create release branch

### MEDIUM PRIORITY

3. **CLI Polish**
   - Improve command feedback
   - Better error messages
   - Add more diagnostic commands

4. **SharedLibraryClassLoader Optimization**
   - Test with more complex dependencies
   - Improve compatibility detection
   - Performance tuning

### FUTURE (Infrastructure Services)

See [infrastructure-services.md](infrastructure-services.md) for full vision.

5. **Distributed Hash Map Foundation**
   - Consistent hashing implementation
   - Pluggable storage engines
   - Partition management via SMR

6. **Artifact Repository**
   - Maven protocol subset
   - Deploy/resolve operations
   - Bootstrap: bundled → self-hosted

### FUTURE (AI Integration)

7. **SLM Integration (Layer 2)**
   - Local model integration (Ollama)
   - Pattern learning
   - Anomaly detection

8. **LLM Integration (Layer 3)**
   - Claude/GPT API integration
   - Complex reasoning workflows
   - Multi-cloud decision support

## Deprecated

- **MCP Server** - Replaced by direct agent API (see [metrics-and-control.md](metrics-and-control.md))

## Implementation Approach

Focus on stability and production readiness:

1. E2E tests prove all features work correctly
2. CLI must be reliable for human operators
3. Agent API must be well-documented
4. Decision tree must handle all common cases
5. Only then add SLM/LLM layers

See [metrics-and-control.md](metrics-and-control.md) for controller architecture.

## Test Coverage Summary

| Category | Test Classes | Tests |
|----------|--------------|-------|
| Cluster Formation | ClusterFormationE2ETest | 4 |
| Slice Deployment | SliceDeploymentE2ETest | 6 |
| Rolling Updates | RollingUpdateE2ETest | 6 |
| Node Failure | NodeFailureE2ETest | 6 |
| Chaos Testing | ChaosE2ETest | 5 |
| Management API | ManagementApiE2ETest | 19 |
| Slice Invocation | SliceInvocationE2ETest | 9 |
| Metrics | MetricsE2ETest | 6 |
| Controller | ControllerE2ETest | 7 |
| Bootstrap | BootstrapE2ETest | 4 |
| Graceful Shutdown | GracefulShutdownE2ETest | 4 |
| Network Partition | NetworkPartitionE2ETest | 4 |
| **Total** | **12 classes** | **80 tests** |
