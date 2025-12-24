# Aether Architecture Overview

This document provides a comprehensive technical overview of the Aether AI-driven distributed runtime architecture.

**See [vision-and-goals.md](vision-and-goals.md) for the complete vision and design principles.**

## Core Concepts

### Slice

A deployable unit of functionality packaged as Maven artifact implementing the `Slice` interface.

**Two Types (unified management)**:

- **Service Slices**: Multiple entry points (traditional microservice style)
- **Lean Slices**: Single entry point (use case or event handler)

From runtime perspective, both types are identical - same lifecycle, same communication mechanism, same management.

### Blueprint

Desired configuration specifying which slices to deploy and how many instances. Created by:

- Human operators via CLI/API
- AI based on observed metrics and learned patterns

### Consensus KV-Store

Single source of truth for all **persistent** cluster state: slice deployments, endpoint registrations, blueprints.
**Note**: Metrics do NOT flow through KV-Store (zero consensus I/O for metrics).
Provided by local cluster module (Rabia consensus implementation).

### Cluster Controller

Pluggable component making topology decisions. Can be Decision Tree, Small LLM, or Large LLM (or layered combination).
Observes metrics + events, produces blueprint changes and node actions:

- Predictive scaling (before load increases)
- Second-level scaling (start/stop nodes)
- Complex deployments (rolling, canary, blue/green, multi-cloud migration)

**See [metrics-and-control.md](metrics-and-control.md) for complete specification.**

## Component Architecture

```
                    ┌────────────────────────────────┐
                    │   External Controller (Optional)│
                    │   - Small LLM (local/cloud)    │
                    │   - Large LLM (cloud)          │
                    └───────────▲────────────────────┘
                                │ Metrics + Events
                                │ Decisions
                 ┌──────────────┴─────────────────────┐
                 │                                     │
┌─ Node A (LEADER)┴────────────┐  ┌─ Node B ─────────┐  ┌─ Node C ─────────┐
│ Cluster Controller           │  │                   │  │                   │
│ Metrics Aggregator           │  │                   │  │                   │
│ ClusterEventBus              │  │                   │  │                   │
│ ClusterDeploymentManager     │  │ ClusterDeployMgr  │  │ ClusterDeployMgr  │
│ (Active)                     │  │ (Dormant)         │  │ (Dormant)         │
│ NodeDeploymentManager        │  │ NodeDeploymentMgr │  │ NodeDeploymentMgr │
│ EndpointRegistry             │  │ EndpointRegistry  │  │ EndpointRegistry  │
│ SliceStore                   │  │ SliceStore        │  │ SliceStore        │
│ MetricsCollector             │  │ MetricsCollector  │  │ MetricsCollector  │
└──────────────────────────────┘  └───────────────────┘  └───────────────────┘
            │                              │                       │
            │  ┌───────────────────────────┴───────────────────────┘
            │  │  Every 1 sec: MetricsUpdate (push to leader)
            │  │  Every 1 sec: ClusterMetricsSnapshot (broadcast from leader)
            │  │
            └──┴──────────── Consensus KV-Store ──────────────────
                     (State, Blueprints, Endpoints - NO metrics)
```

## Core Components

**See [metrics-and-control.md](metrics-and-control.md) for detailed specifications of metrics and controller components.
**

### Cluster Controller

**Status**: ❌ Not Implemented
**Location**: TBD (likely in `node/` module)
**Runs On**: Leader node only

Pluggable component making topology decisions:

- Evaluates at configured frequency (1 sec for decision tree, 2-60 sec for LLMs)
- Receives: Current metrics, historical window, cluster events, topology, blueprints
- Produces: Blueprint changes, node actions, reasoning

**Implementations**:

- DecisionTreeController - Deterministic rules
- SmallLLMController - Local pattern learning
- LargeLLMController - Cloud-based strategic planning
- LayeredController - Combines multiple controllers

### MetricsAggregator

**Status**: ❌ Not Implemented
**Location**: TBD (likely in `node/` module)
**Runs On**: Leader node only

Aggregates metrics from all nodes and broadcasts cluster-wide snapshot:

- Receives MetricsUpdate from all nodes (via MessageRouter)
- Aggregates into ClusterMetricsSnapshot every second
- Broadcasts snapshot to all nodes
- Maintains 2-hour sliding window for pattern detection

**Key Design**: Zero consensus I/O, all via MessageRouter.

### MetricsCollector

**Status**: ❌ Not Implemented
**Location**: TBD (likely in `node/` module)
**Runs On**: All nodes

Collects local metrics and pushes to leader:

- **Node CPU Usage**: Per-node CPU utilization (0.0-1.0)
- **Calls Per Entry Point**: Request count per entry point per cycle
- **Total Call Duration**: Aggregate processing time per cycle

Pushes MetricsUpdate to leader every 1 second via MessageRouter.

### ClusterEventBus

**Status**: ❌ Not Implemented
**Location**: TBD (likely in `common/` or `node/` module)
**Runs On**: All nodes

Distributes cluster events to interested components:

- Node lifecycle events (joined, left, failed)
- Leadership changes
- Slice lifecycle events
- Blueprint changes
- KV-Store commits

Events delivered via MessageRouter, buffered in memory for recent history.

### SliceStore

**Status**: ❌ Not Implemented  
**Location**: `slice/src/main/java/org/pragmatica/aether/slice/manager/SliceStore.java`

Manages the complete slice lifecycle on individual nodes:

- **Load**: Download and prepare slice for execution
- **Activate**: Start slice and make it available for requests
- **Deactivate**: Stop slice but keep it loaded
- **Unload**: Remove slice from memory completely

Supports both legacy single-step loading and new multi-step lifecycle.

### NodeDeploymentManager

**Status**: ❌ Not Implemented
**Location**: `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java`

Handles slice lifecycle transitions on individual nodes by:

- Watching KV-Store for slice state changes specific to this node
- Coordinating with SliceStore for actual slice operations
- Managing timeouts and error handling for each lifecycle step
- Updating slice state back to consensus KV-Store

**Current TODO items**:

- Link consensus integration (line 234: "TODO: link with consensus")
- Move timeouts to SliceStore (lines 152-156)
- Implement EndpointRegistry integration (line 190)

### ClusterDeploymentManager

**Status**: ❌ Not Implemented  
**Design**: Complete

Cluster-wide orchestration component that:

- Runs on every node but only activates when node becomes leader
- Watches blueprint changes in KV-Store
- Decides slice instance allocation across cluster nodes
- Handles automatic rebalancing when nodes join/leave
- Performs reconciliation to ensure desired state matches actual state

**Integration**: Uses `LeaderNotification.LeaderChange` messages from the local cluster module's LeaderManager.

### EndpointRegistry

**Status**: ❌ Not Implemented  
**Design**: Complete

Pure event-driven component that:

- Watches KV-Store endpoint events (no active synchronization)
- Maintains local cache of all cluster endpoints
- Provides endpoint discovery for remote slice calls
- Supports load balancing strategies for endpoint selection

**Note**: Slices automatically publish/unpublish endpoints via consensus - no manual coordination needed.

## Deployment Flow

### Controller-Driven Blueprint Updates

1. **MetricsCollector** (all nodes) pushes metrics to leader every 1 second
2. **MetricsAggregator** (leader) aggregates and broadcasts ClusterMetricsSnapshot
3. **ClusterEventBus** delivers events to controller
4. **Cluster Controller** (leader) evaluates metrics + events at configured frequency
5. **Cluster Controller** produces blueprint changes and node actions
6. **Leader** applies blueprint changes to KV-Store
7. **ClusterDeploymentManager** (leader) detects blueprint changes
8. **Allocation Engine** decides instance distribution across nodes
9. **Slice state commands** written directly to `slices/{node-id}/{artifact}` with LOAD state
10. **NodeDeploymentManager** (each node) watches its slice-node-keys and executes local lifecycle

### Human-Initiated Blueprint Publication

1. **Operator** publishes blueprint via CLI/API to `blueprints/{name}` in KV-Store
2. Flow continues from step 7 above (ClusterDeploymentManager detects change)

**Note**: ClusterDeploymentManager translates blueprint instance counts directly into slice state commands on specific
nodes. No separate allocation storage layer needed.

### Slice Lifecycle States

```
[*] → LOAD → LOADING → LOADED → ACTIVATE → ACTIVATING → ACTIVE
                ↓           ↑         ↓
            FAILED ←-------- + --------→ DEACTIVATE → DEACTIVATING  
                ↓                              ↓
            UNLOAD ←----------------------- LOADED
                ↓
         UNLOADING → [*]
```

### Leadership and Reconciliation

- **Leadership**: Determined by local cluster module's LeaderManager (first node in topology)
- **Reconciliation**: Leader performs state reconciliation on activation
- **Failover**: Automatic when leader node fails or leaves cluster

## KV-Store Schema

### Blueprint Schema

```
blueprints/{blueprint-name} → {
  "slices": [
    {
      "artifact": "org.example:slice:1.0.0",
      "instances": 3
    }
  ],
  "timestamp": 1234567890
}
```

### Slice State Schema

```
slices/{node-id}/{group-id}:{artifact-id}:{version} → {
  "state": "ACTIVE",
  "timestamp": 1234567890,
  "version": 1
}
```

**Note**: ClusterDeploymentManager writes directly to slice state keys when allocating instances to nodes. No
intermediate allocation layer needed.

### Endpoint Schema

```
endpoints/{group-id}:{artifact-id}:{version}/{method-name}:{instance} → {
  "node-id": "node-1",
  "instance": 1,
  "state": "ACTIVE",
  "timestamp": 1234567890
}
```

### Metrics (NOT in KV-Store)

**Metrics flow via MessageRouter only, never touch KV-Store.**

See [metrics-and-control.md](metrics-and-control.md) for message definitions:

- `MetricsUpdate` (node → leader, every 1 sec)
- `ClusterMetricsSnapshot` (leader → all nodes, every 1 sec)

## AI Integration Architecture

**See [ai-integration.md](ai-integration.md) for complete specification.**

### Layered Autonomy

```
┌─────────────────────────────────────────────────────────┐
│  Layer 4: User (CLI, Dashboard)                         │
├─────────────────────────────────────────────────────────┤
│  Layer 3: LLM (Claude, GPT) - Minutes/Hours             │
├─────────────────────────────────────────────────────────┤
│  Layer 2: SLM (Local model) - Seconds/Minutes           │
├─────────────────────────────────────────────────────────┤
│  Layer 1: Decision Tree - Milliseconds                  │
│  *** REQUIRED: Cluster survives with only this ***      │
└─────────────────────────────────────────────────────────┘
```

### External Access

```
┌─────────────────┐  ┌─────────────────┐
│   CLI Module    │  │  Agent (Direct) │
│                 │  │                 │
│ aether blueprint│  │ HTTP REST API   │
│ aether slice    │  │ Management API  │
│ aether cluster  │  │ Metrics Stream  │
└─────────────────┘  └─────────────────┘
         │                     │
         └─────────┬───────────┘
                   │
         ┌─────────────────┐
         │ Management API  │
         │                 │
         │ /api/v1/deploy  │
         │ /api/v1/scale   │
         │ /api/v1/status  │
         └─────────────────┘
                   │
         ┌─────────────────┐
         │ Core Components │
         │                 │
         │ ClusterDeployMgr│
         │ NodeDeployMgr   │
         │ EndpointRegistry│
         │ SliceStore      │
         └─────────────────┘
```

### Design Principles

- **Layer 1 is mandatory**: All other layers are optional enhancements
- **Graceful degradation**: If LLM unavailable, SLM handles; if SLM unavailable, decision tree handles
- **No MCP**: Agents interact directly with Management API (simpler, more reliable)
- **Escalation flow**: Problems flow up, decisions flow down

### Management API Capabilities

- **Blueprint Management**: Deploy, scale, undeploy slices
- **Cluster Monitoring**: Status, metrics, node list
- **Metrics Stream**: SSE stream for real-time monitoring
- **Health Checks**: Node and slice health

## Development Roadmap

### Phase 1: Foundation ✅

1. SliceKVSchema with structured keys ✅
2. ClusterDeploymentManager with allocation ✅
3. CLI with REPL and batch modes ✅
4. EndpointRegistry as passive KV-Store watcher ✅

### Phase 2: Core Functionality ✅

1. Blueprint CRUD operations ✅
2. Round-robin allocation strategy ✅
3. Reconciliation engine ✅
4. HTTP Router for external requests ✅
5. Management API ✅

### Phase 3: AI Integration (Current)

1. Decision tree controller (Layer 1) ✅
2. CLI polish and agent API documentation (in progress)
3. SLM integration (Layer 2) - planned
4. LLM integration (Layer 3) - planned

## Implementation Notes

### Consensus Integration

- Uses local cluster module (Rabia consensus protocol)
- MessageRouter pattern for component communication
- KV-Store operations for all persistent state
- LeaderManager for deterministic leadership

### Error Handling

- Promise-based async operations throughout
- Timeout handling at appropriate component levels
- Graceful degradation when leader unavailable
- State recovery through reconciliation

### Testing Strategy

- Follows established patterns from existing codebase
- Result<T> testing with onSuccess/onFailure patterns
- Integration tests for cluster scenarios
- Unit tests for individual component logic

## Questions for Future Discussion

1. **Reconciliation Strategies**: Should we implement configurable reconciliation policies (conservative vs aggressive)?

2. **Allocation Algorithms**: What specific AI/ML techniques should drive instance placement decisions?

3. **Remote Call Routing**: How should we implement efficient cross-slice communication?

4. **Resource Management**: Should we add CPU/memory constraints to allocation decisions?

5. **Monitoring Integration**: What metrics and observability features are most critical?