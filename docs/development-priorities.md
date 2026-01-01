# Development Priorities

## Current Status (v0.6.4)

Most foundational work is complete. Priorities have shifted to polish and AI integration.

## Completed ✅

- **Structured Keys** - KV schema foundation
- **Consensus Integration** - Distributed operations working
- **ClusterDeploymentManager** - Cluster orchestration
- **EndpointRegistry** - Service discovery
- **NodeDeploymentManager** - Node-level slice management
- **HTTP Router** - External request routing with route self-registration
- **Management API** - Cluster control endpoints
- **CLI** - REPL and batch modes
- **Order Domain Demo** - 5-slice order domain example
- **Aether Forge** - Cluster simulator with visual dashboard
- **TOML Blueprint Parser** - Standard TOML format for blueprints
- **Automatic Route Cleanup** - Routes removed on last slice instance deactivation

## Current Priorities

### HIGH PRIORITY

1. **CLI Polish**
   - Improve command feedback
   - Add more diagnostic commands
   - Better error messages

2. **Agent API Documentation**
   - Document Management API endpoints
   - Create agent integration guide
   - Provide examples for direct API access

3. **Decision Tree Tuning**
   - Improve scaling rules
   - Add more health checks
   - Better failure recovery

### MEDIUM PRIORITY

4. **SharedLibraryClassLoader Optimization**
   - Test with more complex dependencies
   - Improve compatibility detection
   - Performance tuning

5. **Metrics Enhancement**
   - Add more slice-level metrics
   - Improve historical data storage
   - Better visualization support

### FUTURE (Infrastructure Services)

See [infrastructure-services.md](infrastructure-services.md) for full vision.

6. **Distributed Hash Map Foundation**
   - Consistent hashing implementation
   - Pluggable storage engines
   - Partition management via SMR

7. **Artifact Repository**
   - Maven protocol subset
   - Deploy/resolve operations
   - Bootstrap: bundled → self-hosted

8. **HTTP Routing Service**
   - Self-registration API
   - Remove routing from blueprint

### FUTURE (AI Integration)

9. **SLM Integration (Layer 2)**
   - Local model integration (Ollama)
   - Pattern learning
   - Anomaly detection

10. **LLM Integration (Layer 3)**
    - Claude/GPT API integration
    - Complex reasoning workflows
    - Multi-cloud decision support

## Deprecated

- **MCP Server** - Replaced by direct agent API (see [metrics-and-control.md](metrics-and-control.md))

## Implementation Approach

Focus on stability and documentation before adding AI layers:

1. CLI must be reliable for human operators
2. Agent API must be well-documented
3. Decision tree must handle all common cases
4. Only then add SLM/LLM layers

See [metrics-and-control.md](metrics-and-control.md) for controller architecture.
