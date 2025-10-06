# Aether Architecture Overview

This document provides a comprehensive overview of the Aether distributed slice management system architecture, based on planning and design decisions made during development.

## Core Concepts

### Slice
A deployable unit of functionality that can be loaded, activated, and managed by the Aether cluster. Slices are packaged as Maven artifacts and implement the `Slice` interface.

### Blueprint
A desired configuration document that specifies which slices should be deployed and how many instances of each slice should be running in the cluster.

### Consensus KV-Store
The central source of truth for all cluster state, including slice deployments, endpoint registrations, and blueprints. Provided by pragmatica-lite cluster module.

## Component Architecture

```
┌─ Node A ──────────────────┐  ┌─ Node B ──────────────────┐  ┌─ Node C ──────────────────┐
│ ClusterDeploymentManager  │  │ ClusterDeploymentManager  │  │ ClusterDeploymentManager  │
│ (LEADER - Active)         │  │ (Follower - Dormant)      │  │ (Follower - Dormant)      │
│ NodeDeploymentManager     │  │ NodeDeploymentManager     │  │ NodeDeploymentManager     │
│ EndpointRegistry          │  │ EndpointRegistry          │  │ EndpointRegistry          │
│ MCP Server                │  │ MCP Server                │  │ MCP Server                │
│ SliceStore                │  │ SliceStore                │  │ SliceStore                │
└───────────────────────────┘  └───────────────────────────┘  └───────────────────────────┘
            │                              │                              │
            └──────────────── Consensus KV-Store ─────────────────────────┘
                                     │
                              ┌─────────────┐
                              │ MCP Agent   │
                              │ (External)  │  
                              └─────────────┘
```

## Core Components

### SliceStore
**Status**: ✅ Implemented  
**Location**: `slice/src/main/java/org/pragmatica/aether/slice/manager/SliceStore.java`

Manages the complete slice lifecycle on individual nodes:
- **Load**: Download and prepare slice for execution
- **Activate**: Start slice and make it available for requests  
- **Deactivate**: Stop slice but keep it loaded
- **Unload**: Remove slice from memory completely

Supports both legacy single-step loading and new multi-step lifecycle.

### NodeDeploymentManager  
**Status**: ✅ Implemented  
**Location**: `slice/src/main/java/org/pragmatica/aether/cluster/NodeDeploymentManager.java`

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

**Integration**: Uses `LeaderNotification.LeaderChange` messages from pragmatica-lite's LeaderManager.

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

### Blueprint Publication
1. **CLI/MCP** publishes blueprint to `blueprints/{name}` in KV-Store
2. **ClusterDeploymentManager** (leader) detects blueprint changes  
3. **Allocation Engine** decides instance distribution across nodes
4. **Instance assignments** published to `allocations/{artifact}/{node-id}` 
5. **NodeDeploymentManager** on each node handles local slice lifecycle

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
- **Leadership**: Determined by pragmatica-lite LeaderManager (first node in topology)
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

### Endpoint Schema
```
endpoints/{group-id}:{artifact-id}:{version}/{entry-point-id}:{instance} → {
  "node-id": "node-1",
  "instance": 1,
  "state": "ACTIVE", 
  "timestamp": 1234567890
}
```

### Allocation Schema
```
allocations/{artifact}/{node-id} → {
  "assigned": 2,
  "timestamp": 1234567890
}
```

## MCP Integration Architecture

### Three-Layer Design
```
┌─────────────────┐  ┌─────────────────┐
│   CLI Module    │  │   MCP Module    │
│                 │  │                 │  
│ aether blueprint│  │ JSON-RPC        │
│ aether slice    │  │ WebSocket/HTTP  │ 
│ aether cluster  │  │ MCP Protocol    │
└─────────────────┘  └─────────────────┘
         │                     │
         └─────────┬───────────┘
                   │
         ┌─────────────────┐
         │ Command Handlers│
         │                 │
         │ BlueprintHandler│
         │ SliceHandler    │  
         │ ClusterHandler  │
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
- **Perfect Parity**: CLI and MCP provide identical functionality
- **Unified Handlers**: Single command handler implementation for both interfaces
- **Protocol Agnostic**: Core logic independent of CLI/MCP protocol details
- **Single Agent**: Only one MCP agent can connect to cluster at a time

### MCP Capabilities
- **Blueprint Management**: Create, read, update, delete blueprints
- **Cluster Monitoring**: Real-time events, slice status, cluster health
- **Node Management**: Start/stop/restart nodes (future)
- **Event Streaming**: Notifications for significant cluster changes

## Development Roadmap

### Phase 1: Foundation (MVP)
1. Extended SliceKVSchema for blueprints and allocations
2. ClusterDeploymentManager skeleton with LeaderNotification integration
3. Basic blueprint CLI commands (publish, get, list)  
4. EndpointRegistry as passive KV-Store watcher

### Phase 2: Core Functionality  
1. Blueprint CRUD operations in command handlers
2. Simple round-robin allocation strategy
3. Basic reconciliation engine
4. MCP server with JSON-RPC over WebSocket/HTTP

### Phase 3: Advanced Features
1. AI-based allocation strategies (decision trees)
2. Automatic rebalancing on node failures
3. Remote slice invocation via consensus transport
4. Comprehensive monitoring and metrics
5. MCP agent connection management

## Implementation Notes

### Consensus Integration
- Uses existing pragmatica-lite cluster infrastructure
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