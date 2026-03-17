# Aether Architecture Overview

This document is the entry point for the Aether architecture documentation. It describes the system at a high level and links to detailed documents for each subsystem.

## What is Aether?

Aether is a distributed application platform for Java. Business logic is packaged as **slices** - Java interfaces annotated with `@Slice` - and the runtime handles distribution, replication, routing, failure recovery, and scaling transparently.

Aether occupies the space between a monolith and microservices. You write code as if it's a monolith - direct method calls, no network boilerplate. The runtime distributes it across a cluster of nodes.

## System Architecture

```mermaid
graph TB
    subgraph External["External Access"]
        CLI["aether CLI<br/>37 commands"]
        Agent["AI Agent / Dashboard"]
        Client["HTTP Clients"]
    end

    subgraph Cluster["Aether Cluster"]
        subgraph N1["Node 1 (Leader)"]
            CDM1["ClusterDeploymentManager ✦"]
            MAG1["MetricsAggregator ✦"]
            CL1["ControlLoop ✦"]
            Common1["SliceStore, EndpointRegistry,<br/>KVStore, DHTNode, SliceInvoker"]
        end

        subgraph N2["Node 2"]
            Common2["SliceStore, EndpointRegistry,<br/>KVStore, DHTNode, SliceInvoker"]
        end

        subgraph N3["Node 3"]
            Common3["SliceStore, EndpointRegistry,<br/>KVStore, DHTNode, SliceInvoker"]
        end
    end

    CLI -->|Management API| N1
    Agent -->|Management API| N1
    Client -->|App HTTP :8081| N1
    Client -->|App HTTP :8081| N2
    Client -->|App HTTP :8081| N3

    N1 <-->|"Rabia Consensus + Gossip"| N2
    N2 <-->|"Rabia Consensus + Gossip"| N3
    N1 <-->|"Rabia Consensus + Gossip"| N3

    style N1 fill:#e1f5fe,stroke:#0288d1
    style N2 fill:#f5f5f5,stroke:#616161
    style N3 fill:#f5f5f5,stroke:#616161
```

**✦ Leader-only components** - activated on the leader node, dormant on others. Leadership is deterministic (first node in sorted topology) and re-election is near-instant.

## Core Concepts

| Concept | Description |
|---------|-------------|
| **Slice** | Deployable unit - a Java interface annotated with `@Slice`. Two types: Service (multiple entry points) and Lean (single entry point). Identical at runtime. |
| **Blueprint** | Desired configuration - which slices to deploy, how many instances. TOML format, applied via CLI or API. |
| **KV-Store** | Consensus-replicated state machine. Single source of truth for all persistent cluster state. |
| **Consensus** | Rabia protocol - leaderless CFT. Any node can propose, agreement via two-round voting with fast path. |
| **DHT** | Distributed hash table for artifact storage. Consistent hashing, quorum R/W, anti-entropy repair. |

## Component Map

Every node runs the same components. Leader-only components activate when a node becomes leader.

| Category | Components | Leader-only |
|----------|-----------|-------------|
| **State** | KVStore (Rabia), LeaderManager, KVNotificationRouter | - |
| **Deployment** | NodeDeploymentManager, RollingUpdateManager, RollbackManager | ClusterDeploymentManager |
| **Invocation** | SliceInvoker, InvocationHandler, DynamicAspectInterceptor | - |
| **HTTP** | AppHttpServer, HttpRouteRegistry, ManagementServer | - |
| **Slices** | SliceStore, SliceRegistry, per-slice ClassLoaders | - |
| **Network** | ClusterNetwork (Netty), TopologyManager, MessageRouter | - |
| **Storage** | DHTNode, ArtifactStore, DHTCacheBackend, AntiEntropy, Rebalancer | - |
| **Observability** | MetricsCollector, AlertManager, ClusterEventAggregator | MetricsAggregator |
| **Scaling** | DecisionTreeController, TTMManager | ControlLoop |
| **Messaging** | TopicSubscriptionRegistry, TopicPublisher, ScheduledTaskManager | - |
| **Resources** | ResourceProvider (SPI: DB, HTTP client, config) | - |

## Communication Paths

```mermaid
graph LR
    subgraph Paths["Communication Channels"]
        direction TB
        C["Consensus<br/>(Rabia via TCP)"]
        G["Gossip<br/>(MetricsPing/Pong)"]
        H["HTTP Forwarding<br/>(via MessageRouter)"]
        D["DHT Operations<br/>(via MessageRouter)"]
        K["KV Notifications<br/>(local events)"]
    end

    C --> |"KV-Store replication<br/>Strong consistency"| State["Cluster State"]
    G --> |"Metrics collection<br/>Zero consensus I/O"| Metrics["Observability"]
    H --> |"Cross-node request routing<br/>Retry with failover"| Routing["Request Routing"]
    D --> |"Artifact storage/retrieval<br/>Quorum R/W"| Storage["Artifact Storage"]
    K --> |"State change reactions<br/>Event-driven"| Local["Local Components"]
```

## Network Interfaces

Each node exposes three ports:

| Port | Purpose | Protocol |
|------|---------|----------|
| `:8081` | Application HTTP | HTTP/1.1 - serves slice endpoints, forwards to remote nodes |
| `:8080` | Management API | HTTP/1.1 - 30+ REST endpoints, WebSocket dashboard |
| `:8090` | Cluster | TCP (Netty) - consensus, gossip, DHT, invocation forwarding |

## Key Design Principles

1. **Event-driven state management** - KV-Store changes emit notifications. No polling, no periodic reconciliation for core state.
2. **Local-first routing** - requests served by local slice when available, forwarded only when necessary.
3. **Consensus for state, gossip for metrics** - persistent state goes through Rabia; metrics bypass consensus entirely.
4. **Deterministic leadership** - leader is first node in sorted topology. No election protocol overhead.
5. **Layered autonomy** - Layer 1 (decision tree) is mandatory, Layers 2-3 (ML/LLM) are optional enhancements.

## Two-Layer Topology (v0.20.0+)

```mermaid
graph TB
    subgraph Core["Core Layer (Rabia Consensus)"]
        C1["Core Node 1"]
        C2["Core Node 2"]
        C3["Core Node 3"]
        C4["Core Node 4"]
        C5["Core Node 5"]
    end

    subgraph WG1["Worker Group Alpha"]
        G1["Governor"]
        W1["Worker 1"]
        W2["Worker 2"]
    end

    subgraph WG2["Worker Group Beta"]
        G2["Governor"]
        W3["Worker 3"]
        W4["Worker 4"]
    end

    C1 <--> C2
    C2 <--> C3
    C3 <--> C4
    C4 <--> C5
    C5 <--> C1

    C1 -.->|"SWIM gossip"| G1
    C3 -.->|"SWIM gossip"| G2
    G1 --> W1
    G1 --> W2
    G2 --> W3
    G2 --> W4

    style Core fill:#e1f5fe
    style WG1 fill:#e8f5e9
    style WG2 fill:#e8f5e9
```

- **Core nodes** (5-9): Run Rabia consensus, manage cluster state, host infrastructure slices
- **Worker groups**: SWIM-based gossip, deterministic governor (lowest NodeId), execute application slices
- **Scaling**: Core handles control plane; workers scale horizontally to 10K+ nodes

See [05-worker-pools.md](05-worker-pools.md) for details.

## Architecture Documents

| Document | Description |
|----------|-------------|
| [01-consensus.md](01-consensus.md) | Rabia protocol, KV-Store state machine, leader election |
| [02-deployment.md](02-deployment.md) | Blueprint lifecycle, CDM allocation, slice state machine |
| [03-invocation.md](03-invocation.md) | Slice invocation, routing, retry, pub/sub, scheduled tasks |
| [04-networking.md](04-networking.md) | Cluster transport, mTLS, gossip encryption, SWIM |
| [05-worker-pools.md](05-worker-pools.md) | Two-layer topology, governors, SWIM protocol, scaling |
| [06-http-routing.md](06-http-routing.md) | HTTP request routing, forwarding, route self-registration |
| [07-observability.md](07-observability.md) | Metrics pipeline, alerting, dynamic aspects, Prometheus |
| [08-scaling.md](08-scaling.md) | Decision tree controller, TTM predictor, control loop |
| [09-storage.md](09-storage.md) | DHT, artifact repository, consistent hashing, anti-entropy |
| [10-security.md](10-security.md) | mTLS, gossip encryption, RBAC, API keys |
| [11-slice-container.md](11-slice-container.md) | ClassLoader isolation, dependency materialization, lifecycle hooks |
| [12-management.md](12-management.md) | CLI, Management API, Forge simulator, dashboard |

## Performance Characteristics (v0.20.0)

Measured on URL Shortener demo, 5-node Forge on single laptop:

| Metric | Value |
|--------|-------|
| Cold start to serving traffic | ~9.7 seconds |
| Leader re-election | ~2ms |
| Node replacement to routes available | ~150ms |
| Request failures during chaos | 0.039% (124 / 314,758) |
| Steady-state latency at 5,000 req/s | 0.5ms |
| Throughput (full distributed simulation) | 10K req/s |
