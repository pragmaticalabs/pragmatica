# Aether: Vision and Goals

## Executive Summary

**Aether** is an AI-driven distributed runtime environment for Java applications that enables predictive scaling,
intelligent orchestration, and seamless multi-cloud deployment without requiring changes to business logic.

## The Problem

Traditional distributed systems face three fundamental challenges:

1. **Reactive Scaling**: Systems scale only after load increases, leading to degraded performance during traffic spikes
2. **Manual Operations**: Complex tasks like rolling updates, canary deployments, and cloud migrations require extensive
   manual orchestration
3. **Infrastructure Lock-in**: Applications become tightly coupled to specific cloud providers or orchestration
   platforms

## The Solution

Aether provides an intelligent runtime that:

- **Predicts** resource needs by learning traffic patterns
- **Automates** complex deployment operations through AI decision-making
- **Abstracts** infrastructure differences through unified slice deployment model

### Core Principle: Convergence to Desired State

The runtime performs one primary function: **continuously reconcile actual deployment state with desired state stored in
consensus KV-Store**.

The AI observes metrics, makes decisions, and updates desired state. The runtime executes changes transparently across
the cluster.

## Slice-Based Deployment Model

Applications are deployed as **slices** - independently scalable units with well-defined entry points.

### Slice Types

#### Service Slices

Traditional microservice-style components with multiple entry points.

**Characteristics**:

- Multiple entry points (methods/APIs)
- Stateless or externally-persisted state
- Suitable for CRUD operations, API gateways, data services

**Example Use Cases**:

- User authentication service
- Product catalog API
- Payment gateway adapter

#### Lean Slices

Single-purpose components handling one use case or event type end-to-end.

**Characteristics**:

- Single entry point
- Encapsulates complete business use case (DDD-style)
- Minimal inter-slice communication
- Event handlers in traditional EDA

**Example Use Cases**:

- "Register New User" use case
- "Process Order Payment" workflow
- "Inventory Level Changed" event handler

### Unified Management

**From runtime's perspective, slice types are identical**:

- Both use atomic inter-slice communication
- Both expose entry points (one or many)
- Both follow same lifecycle (LOAD → ACTIVATE → ACTIVE)
- Both managed through same consensus mechanism

The distinction exists only at the design/architecture level, not in runtime behavior.

## Inter-Slice Communication

All slice-to-slice calls are **atomic and reliable**:

- Calls either complete successfully or fail cleanly
- No partial states or lost messages
- Runtime guarantees delivery through consensus-backed transport
- Single unified mechanism regardless of slice type

This allows developers to write business logic without worrying about distributed failure modes.

## Cluster Controller Architecture

**See [metrics-and-control.md](metrics-and-control.md) for complete specification.**

### Controller Concept

The **Cluster Controller** makes all topology decisions. It's a pluggable component that can be:

- **Decision Tree**: Deterministic rules, evaluated every second
- **Small Local LLM**: Pattern learning, evaluated every 2-5 seconds
- **Large Cloud LLM**: Strategic planning, evaluated every 30-60 seconds

Controllers can be **layered** for hybrid intelligence (decision tree + LLM).

### Controller Responsibilities

1. **Predictive Scaling**
    - Learn traffic patterns over time
    - Scale slice instances BEFORE load increases
    - Prevent performance degradation through anticipation

2. **Second-Level Scaling**
    - Start/stop compute nodes dynamically
    - Choose deployment environments (cloud provider, region)
    - Optimize cost vs. performance trade-offs

3. **Complex Deployment Operations**
    - Rolling updates without downtime
    - Canary deployments with automatic rollback
    - Blue/green deployments across environments
    - Multi-cloud migration without stopping processing

### What Controllers Do NOT Do:

- Request routing (handled by runtime)
- Real-time load balancing
- Individual transaction decisions

### Controller Input

Controllers receive:

- **Current metrics**: Latest ClusterMetricsSnapshot
- **Historical metrics**: 2-hour sliding window
- **Cluster events**: Node joins/leaves, leadership changes, slice lifecycle, blueprint changes, KV-Store commits
- **Current topology**: Nodes, slices, blueprints

### Controller Output

Controllers produce:

- **Blueprint changes**: Scale/deploy/remove/update slices
- **Node actions**: Start/stop nodes, migrate slices
- **Reasoning**: Explanation of decisions (for transparency)

### Layered Strategy

Controllers can be composed in layers:

```
Layer 1: Decision Tree (every 1 sec) → Fast reactive
Layer 2: Small LLM (every 2-5 sec) → Pattern validation
Layer 3: Large LLM (every 30-60 sec) → Strategic planning
```

Each layer can veto or modify decisions from lower layers.

### Controller Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      External AI                             │
│  (Small local LLM or Large cloud-based LLM)                 │
└──────────────────────────▲──────────────────────────────────┘
                           │
                    Shaped Metrics
                           │
┌──────────────────────────┴──────────────────────────────────┐
│                    Leader Node                               │
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │  Metrics Aggregation & Shaping                     │    │
│  │  - Aggregate metrics from all nodes                │    │
│  │  - Perform pre-processing                          │    │
│  │  - Reduce token usage for AI                       │    │
│  └────────────────────────────────────────────────────┘    │
│                           │                                  │
│                           ▼                                  │
│  ┌────────────────────────────────────────────────────┐    │
│  │  Agentic Interface                                 │    │
│  │  - Send shaped metrics to AI                       │    │
│  │  - Receive AI decisions                            │    │
│  │  - Translate to blueprint changes                  │    │
│  └────────────────────────────────────────────────────┘    │
│                           │                                  │
│                           ▼                                  │
│  ┌────────────────────────────────────────────────────┐    │
│  │  ClusterDeploymentManager                          │    │
│  │  - Update desired state in KV-Store                │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

**Key Design Points**:

- Only leader node communicates with AI
- AI is external (even for small models)
- Leader shapes metrics to minimize token costs
- AI decisions translate to KV-Store updates
- Layered autonomy: decision tree (required) → SLM → LLM → user (all optional)
- See [ai-integration.md](ai-integration.md) for architecture details

## Metrics Collection Strategy

**See [metrics-and-control.md](metrics-and-control.md) for complete specification.**

### Core Metrics (Minimal Set)

1. **Node CPU Usage** - Per-node CPU utilization (0.0 to 1.0)
2. **Calls Per Entry Point** - Request count per slice entry point per cycle
3. **Total Call Duration** - Aggregate time spent processing per entry point per cycle

**Collection Frequency**: Every 1 second

### Collection & Distribution Architecture

**Goal**: Zero consensus I/O, fast leader recovery, all nodes have cluster-wide view

**Strategy: Push to Leader + Broadcast Aggregated State**

```
Every 1 Second:
1. All nodes push MetricsUpdate to leader (via MessageRouter)
2. Leader aggregates metrics
3. Leader broadcasts ClusterMetricsSnapshot to all nodes
```

**Key Benefits**:

- ✅ **Zero consensus I/O** - Metrics never touch KV-Store
- ✅ **Fast failover** - New leader has recent snapshot (< 2 sec old)
- ✅ **Cluster-wide visibility** - Every node knows cluster state
- ✅ **Minimal overhead** - ~400 bytes/sec per node
- ✅ **Sliding window** - Leader maintains 2-hour history in memory

**On Leader Failover**:

- New leader uses last received ClusterMetricsSnapshot
- If stale (> 2 sec), requests fresh snapshot from all nodes
- Recovery time: 1-2 RTTs (~10-100 ms)
- Data loss: 1-2 update cycles (acceptable)

## Control Loop

The system operates through continuous reconciliation:

```
┌─────────────────────────────────────────────────────────────┐
│                 Metrics Collection (All Nodes)               │
│  Every 1 sec: Push MetricsUpdate to leader                  │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              Leader - Metrics Aggregation                    │
│  Aggregate + broadcast ClusterMetricsSnapshot to all nodes  │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│         Leader - Cluster Controller Evaluation               │
│  - Decision Tree (1 sec) OR                                  │
│  - Small LLM (2-5 sec) OR                                    │
│  - Large LLM (30-60 sec) OR                                  │
│  - Layered combination                                       │
│                                                              │
│  Input: Metrics + Events + Topology + Blueprints            │
│  Output: Blueprint changes + Node actions                    │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│         Leader - Update Desired State (KV-Store)             │
│  Translate controller decisions to blueprint updates         │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│            ClusterDeploymentManager (Leader)                 │
│  Watch blueprints, allocate instances                        │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│            NodeDeploymentManager (All Nodes)                 │
│  Execute lifecycle operations locally                        │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    Actual State                              │
│  Slices running on nodes, metrics collected                  │
└───────────────────────────┴─────────────────────────────────┘
                            │
                            └──────── (loop back to top)
```

## Key Design Principles

### 1. Convergence, Not Commands

The runtime continuously drives actual state toward desired state. No imperative commands - only declarative
configuration.

### 2. Controller as Strategic, Not Tactical

Controllers make high-level topology decisions (seconds to minutes), not millisecond routing decisions.

### 3. Consensus as Single Source of Truth

All persistent state (desired configuration, actual state, metrics) flows through KV-Store.

### 4. Transparency to Business Logic

Slices written using JBCT patterns have no awareness of distribution, scaling, or failure handling.

### 5. Predictive Over Reactive

Learn patterns, anticipate needs, prevent problems rather than respond to them.

## Non-Goals (Out of Scope)

- **Request routing**: Handled by runtime, not exposed to operators
- **Service mesh features**: No sidecar proxies, no manual circuit breakers
- **Container orchestration**: Aether manages slices, not containers (though slices may run in containers)
- **General-purpose compute**: Optimized for slice-based Java applications

## Success Criteria

### For Developers

- Write business logic in pure JBCT patterns
- No distributed systems concerns in code
- Deploy slices without infrastructure knowledge

### For Operators

- AI handles scaling decisions
- No manual intervention for routine operations
- Transparent multi-cloud deployment

### For System

- Predictive scaling prevents performance degradation
- Zero-downtime deployments (rolling, canary, blue/green)
- Seamless cloud migration while processing continues

## Open Questions (To Be Resolved During Development)

1. **Controller Feedback Loop**: How do controllers learn from deployment outcomes?
2. **Safety Bounds**: What limits prevent controllers from making destructive decisions?
3. **Human Override**: When/how can operators override controller decisions?
4. **Cost Modeling**: How do controllers balance performance vs. infrastructure cost?
5. **Decision Tree Rules**: What is the syntax/format for defining decision tree rules?
6. **Event Retention**: How long should event history be kept in memory?

---

This document serves as the **north star** for all architecture and implementation decisions.
