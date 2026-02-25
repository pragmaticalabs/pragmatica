# Aether Architecture Diagrams

Visual diagrams for understanding Aether's distributed runtime architecture.

---

## 1. Cluster Topology

```mermaid
graph TB
    subgraph External["External Access"]
        CLI["aether CLI"]
        Agent["AI Agent"]
        Client["HTTP Clients"]
    end

    subgraph Cluster["Aether Cluster (Rabia Consensus)"]
        N1["Node 1 &bull; LEADER<br/>Slices: A, B<br/>:8080 mgmt &bull; :8081 app &bull; :8090 cluster"]
        N2["Node 2<br/>Slices: A, C<br/>:8080 mgmt &bull; :8081 app &bull; :8090 cluster"]
        N3["Node 3<br/>Slices: B, C<br/>:8080 mgmt &bull; :8081 app &bull; :8090 cluster"]
    end

    CLI -->|Management API| N1
    Agent -->|Management API| N1
    Client -->|App HTTP| N1
    Client -->|App HTTP| N2
    Client -->|App HTTP| N3

    N1 <-->|Consensus + Gossip| N2
    N2 <-->|Consensus + Gossip| N3
    N1 <-->|Consensus + Gossip| N3

    style N1 fill:#e1f5fe,stroke:#0288d1
    style N2 fill:#f5f5f5,stroke:#616161
    style N3 fill:#f5f5f5,stroke:#616161
```

**Key points:**
- Every node runs the same components; **leader** additionally activates ClusterDeploymentManager, MetricsAggregator, and ControlLoop
- Consensus (Rabia) replicates KV-Store state across all nodes
- Metrics flow via gossip (push-based, 1s interval) -- zero consensus I/O
- Any node can receive HTTP traffic; requests are routed or forwarded to the node hosting the target slice
- Leader re-election is instant on departure

---

## 2. Node Components

```mermaid
graph TB
    subgraph Ports["Network Interfaces"]
        P_APP[":8081 App HTTP"]
        P_MGMT[":8080 Management"]
        P_CLUSTER[":8090 Cluster"]
    end

    subgraph HTTP["HTTP Layer"]
        AHS["AppHttpServer<br/>Route matching, forwarding,<br/>security validation"]
        MS["ManagementServer<br/>60+ REST endpoints<br/>WebSocket: dashboard, status, events"]
    end

    subgraph Invoke["Invocation"]
        SI["SliceInvoker<br/>Local/remote dispatch,<br/>load balancing, retry"]
        IH["InvocationHandler<br/>Incoming remote calls"]
        IC["InvocationContext<br/>ScopedValue: requestId,<br/>depth, sampled, principal"]
        DAI["DynamicAspectInterceptor<br/>Runtime LOG/METRICS toggle"]
    end

    subgraph Slices["Slice Container"]
        SS["SliceStore<br/>Classloader isolation,<br/>state machine"]
        SR["SliceRegistry<br/>Deployed slice definitions"]
        SCL["SliceClassLoader<br/>Child-first per-slice"]
        SA["Slice A"]
        SB["Slice B"]
    end

    subgraph Deploy["Deployment"]
        CDM["ClusterDeploymentManager<br/>LEADER ONLY<br/>Blueprint allocation,<br/>auto-healing"]
        NDM["NodeDeploymentManager<br/>Local lifecycle execution"]
        RUM["RollingUpdateManager<br/>Version traffic shifting,<br/>health guardrails"]
        RBM["RollbackManager<br/>Auto-rollback on failure"]
    end

    subgraph State["Consensus State"]
        KV[("KV-Store<br/>(Rabia)<br/>Blueprints, SliceState,<br/>Routes, Endpoints,<br/>Aspects, Alerts,<br/>Subscriptions, Config")]
        KNR["KVNotificationRouter<br/>Change event dispatch"]
        LM["LeaderManager<br/>Election, failover"]
    end

    subgraph Network["Cluster Network"]
        CN["ClusterNetwork<br/>Netty TCP, TLS optional"]
        TM["TopologyManager<br/>Node discovery, quorum"]
        MR["MessageRouter<br/>Consensus, invocation,<br/>metrics, DHT, forwarding"]
    end

    subgraph DHT_["Distributed Hash Table"]
        DHT["DHTNode<br/>Consistent hash ring<br/>1024 partitions, 150 vnodes"]
        AS["ArtifactStore<br/>Maven-compatible repository"]
        DC["DHTCacheBackend<br/>Distributed cache"]
        AE["DHTAntiEntropy<br/>Digest exchange, repair"]
        RB["DHTRebalancer<br/>Re-replicate on departure"]
    end

    subgraph Observe["Observability"]
        MC["MetricsCollector<br/>CPU, heap, GC, invocations"]
        MAG["MetricsAggregator<br/>LEADER ONLY<br/>Cluster-wide aggregation"]
        AM["AlertManager<br/>Thresholds, active alerts"]
        CEA["ClusterEventAggregator<br/>Ring buffer, 11 event types"]
    end

    subgraph Scaling["Auto-Scaling"]
        CL["ControlLoop<br/>1s evaluation cycle"]
        DTC["DecisionTreeController<br/>CPU/latency thresholds"]
        TTM["TTMManager<br/>ONNX predictive scaling<br/>(optional)"]
    end

    subgraph Messaging["Pub-Sub & Scheduling"]
        TSR["TopicSubscriptionRegistry<br/>Subscriber discovery"]
        TP["TopicPublisher<br/>Fan-out via SliceInvoker"]
        STM["ScheduledTaskManager<br/>Interval + cron execution"]
    end

    subgraph Resources["Resource Provisioning (SPI)"]
        RP["ResourceProvider<br/>DB, HTTP client,<br/>interceptors, config"]
    end

    %% Port connections
    P_APP --> AHS
    P_MGMT --> MS
    P_CLUSTER --> CN

    %% HTTP to invocation
    AHS --> SI
    AHS -->|Forward| MR
    IH --> SI
    SI --> DAI --> SS
    SS --> SA & SB

    %% Management to subsystems
    MS --> CDM & NDM & MC & AM & CEA & CL

    %% Deployment flow
    CDM --> KV
    KV --> KNR --> NDM
    NDM --> SS
    NDM --> RUM
    RUM --> RBM

    %% Consensus and network
    CN --> MR
    MR --> KV & IH & MC & DHT
    TM --> LM

    %% DHT
    DHT --> AS & DC
    DHT --> AE & RB

    %% Metrics and scaling
    MC -.->|Gossip| MAG
    MAG --> CL
    CL --> DTC
    DTC -.-> TTM
    CL -->|Scale command| CDM

    %% Messaging
    TSR --> TP --> SI
    STM --> SI

    %% Resources
    RP --> SS

    %% Styling
    style CDM fill:#4caf50,color:#fff
    style MAG fill:#ffeb3b
    style KV fill:#f3e5f5
    style SA fill:#e8f5e9
    style SB fill:#e8f5e9
    style P_APP fill:#bbdefb
    style P_MGMT fill:#bbdefb
    style P_CLUSTER fill:#bbdefb
```

**Component categories:**

| Category | Leader-only | Every node |
|----------|------------|------------|
| **Deployment** | ClusterDeploymentManager | NodeDeploymentManager, RollingUpdateManager, RollbackManager |
| **Metrics** | MetricsAggregator | MetricsCollector, AlertManager, ClusterEventAggregator |
| **Scaling** | ControlLoop (executes) | ControlLoop (evaluates), DecisionTreeController, TTMManager |
| **State** | -- | KV-Store (Rabia), LeaderManager, KVNotificationRouter |
| **Network** | -- | ClusterNetwork, TopologyManager, MessageRouter |
| **HTTP** | -- | AppHttpServer, ManagementServer |
| **Invocation** | -- | SliceInvoker, InvocationHandler, DynamicAspectInterceptor |
| **Storage** | -- | DHTNode, ArtifactStore, DHTCacheBackend, AntiEntropy, Rebalancer |
| **Messaging** | -- | TopicSubscriptionRegistry, TopicPublisher, ScheduledTaskManager |
| **Container** | -- | SliceStore, SliceRegistry, per-slice ClassLoaders |
| **Resources** | -- | SPI-based: DB, HTTP client, interceptors, config |

**Communication paths:**
- **Consensus** (Rabia via ClusterNetwork) -- KV-Store replication, strong consistency
- **Gossip** (MetricsPing/Pong via MessageRouter) -- metrics collection, no consensus overhead
- **HTTP forwarding** (via MessageRouter) -- cross-node request routing
- **DHT** (via MessageRouter) -- artifact storage and cache, quorum R/W
- **KV notifications** (local, via KVNotificationRouter) -- state change reactions

---

## 2. Request Flow

```mermaid
sequenceDiagram
    participant Client
    participant Router as HTTP Router<br/>(Any Node)
    participant ER as EndpointRegistry
    participant Slice as Target Slice<br/>(Local or Remote)
    participant RemoteNode as Remote Node

    Client->>Router: HTTP Request<br/>POST /api/orders
    Router->>ER: Find endpoint for<br/>"order-service:processOrder"
    ER-->>Router: Endpoint list<br/>[Node2:inst1, Node4:inst2]

    alt Local Slice Available
        Router->>Slice: Direct invocation
        Slice-->>Router: Result
    else Remote Slice
        Router->>RemoteNode: Forward request<br/>(MessageRouter)
        RemoteNode->>Slice: Local invocation
        Slice-->>RemoteNode: Result
        RemoteNode-->>Router: Response
    end

    Router-->>Client: HTTP Response
```

**Key Points:**
- **Any node** can receive external HTTP requests
- **EndpointRegistry** provides service discovery with load balancing
- **Local preference** - route to local slice if available
- **Transparent remoting** - caller doesn't know if slice is local or remote

---

## 3. Deployment & Scaling Flow

```mermaid
sequenceDiagram
    participant Op as Operator/AI
    participant API as Management API
    participant KV as Consensus KV-Store
    participant CDM as ClusterDeploymentManager<br/>(Leader)
    participant NDM as NodeDeploymentManager<br/>(Each Node)
    participant SS as SliceStore

    Op->>API: Deploy blueprint<br/>{slice: "order-service", instances: 3}
    API->>KV: Write blueprint<br/>blueprints/production
    KV-->>CDM: Blueprint change event

    CDM->>CDM: Allocation decision<br/>(round-robin across nodes)

    loop For each allocated node
        CDM->>KV: Write slice state<br/>slices/node-2/order-service → LOAD
        KV-->>NDM: Slice state change
        NDM->>SS: Load slice artifact
        SS-->>NDM: Loaded
        NDM->>KV: Update state → LOADED

        NDM->>KV: Request ACTIVATE
        KV-->>NDM: State change
        NDM->>SS: Activate slice
        SS-->>NDM: Active
        NDM->>KV: Update state → ACTIVE
        NDM->>KV: Register endpoints
    end

    CDM->>API: Deployment complete
    API-->>Op: Success
```

**Scaling Flow (AI-Driven):**

```mermaid
sequenceDiagram
    participant MC as MetricsCollector<br/>(All Nodes)
    participant MA as MetricsAggregator<br/>(Leader)
    participant CC as Cluster Controller<br/>(Leader)
    participant KV as Consensus KV-Store
    participant CDM as ClusterDeploymentManager

    loop Every 1 second
        MA->>MC: MetricsPing
        MC-->>MA: MetricsPong<br/>{cpu, calls, latency}
    end

    MA->>CC: Aggregated metrics snapshot

    CC->>CC: Evaluate patterns<br/>• CPU trending up<br/>• Latency increasing<br/>• Historical patterns

    CC->>KV: Update blueprint<br/>{instances: 3 → 5}

    KV-->>CDM: Blueprint change
    CDM->>CDM: Allocate 2 new instances
    Note over CDM: Normal deployment flow follows
```

**Key Points:**
- **Blueprint** is the desired state, **KV-Store** is the source of truth
- **ClusterDeploymentManager** (leader) allocates instances to nodes
- **NodeDeploymentManager** (each node) executes local slice lifecycle
- **Metrics flow continuously** without touching consensus
- **Controller evaluates** and can modify blueprints automatically

---

## 4. AI Autonomy Layers

```mermaid
graph TB
    subgraph Layer4["Layer 4: User"]
        User["Human Operator"]
        CLI["aether CLI"]
        Dashboard["Dashboard"]
    end

    subgraph Layer3["Layer 3: Large LLM (Cloud)"]
        LLM["Claude / GPT"]
        L3Decisions["Strategic Decisions<br/>• Architecture changes<br/>• Multi-cloud migration<br/>• Capacity planning"]
        L3Freq["Frequency: Minutes to Hours"]
    end

    subgraph Layer2["Layer 2: Small LLM (Local)"]
        SLM["Local Model<br/>(Ollama, etc.)"]
        L2Decisions["Tactical Decisions<br/>• Pattern recognition<br/>• Anomaly detection<br/>• Predictive scaling"]
        L2Freq["Frequency: Seconds to Minutes"]
    end

    subgraph Layer1["Layer 1: Decision Tree"]
        DT["DecisionTreeController"]
        L1Decisions["Reactive Decisions<br/>• Threshold-based scaling<br/>• Health checks<br/>• Immediate responses"]
        L1Freq["Frequency: Milliseconds"]
        Required["⚠️ REQUIRED<br/>Cluster survives with only this"]
    end

    subgraph Cluster["Aether Cluster"]
        Metrics["Metrics Stream"]
        Events["Cluster Events"]
        Actions["Blueprint Changes<br/>Node Actions"]
    end

    User --> CLI
    User --> Dashboard
    CLI --> LLM
    Dashboard --> LLM

    LLM --> L3Decisions
    L3Decisions --> SLM

    SLM --> L2Decisions
    L2Decisions --> DT

    DT --> L1Decisions
    L1Decisions --> Actions

    Metrics --> DT
    Metrics --> SLM
    Metrics --> LLM
    Events --> DT
    Events --> SLM
    Events --> LLM

    Actions --> Cluster
    Cluster --> Metrics
    Cluster --> Events

    style Layer1 fill:#c8e6c9
    style Required fill:#ffeb3b
    style Layer2 fill:#fff3e0
    style Layer3 fill:#e3f2fd
    style Layer4 fill:#fce4ec
```

**Escalation & Degradation:**

```mermaid
graph LR
    subgraph Normal["Normal Operation"]
        direction TB
        N1["Problem detected"]
        N2["Layer 1 handles<br/>(if simple)"]
        N3["Escalate to Layer 2<br/>(if complex)"]
        N4["Escalate to Layer 3<br/>(if strategic)"]
        N5["Escalate to User<br/>(if uncertain)"]
        N1 --> N2 --> N3 --> N4 --> N5
    end

    subgraph Degraded["Graceful Degradation"]
        direction TB
        D1["LLM unavailable"]
        D2["SLM takes over"]
        D3["SLM unavailable"]
        D4["Decision Tree takes over"]
        D5["Cluster continues operating"]
        D1 --> D2
        D3 --> D4 --> D5
    end

    style Degraded fill:#fff3e0
```

**Key Points:**
- **Layer 1 is mandatory** - cluster operates with decision tree alone
- **Higher layers are optional** - add intelligence, not dependency
- **Problems escalate up** - simple → complex → strategic → human
- **Decisions flow down** - strategic guidance → tactical execution → immediate action
- **Graceful degradation** - if any layer fails, lower layers continue

---

## 5. Slice Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> LOAD: Deploy command

    LOAD --> LOADING: NDM starts download
    LOADING --> LOADED: Artifact ready
    LOADING --> FAILED: Download error

    LOADED --> ACTIVATE: Activation requested
    ACTIVATE --> ACTIVATING: NDM starts slice
    ACTIVATING --> ACTIVE: Slice running
    ACTIVATING --> FAILED: Startup error

    ACTIVE --> DEACTIVATE: Scale down / Update
    DEACTIVATE --> DEACTIVATING: NDM stops slice
    DEACTIVATING --> LOADED: Slice stopped

    LOADED --> UNLOAD: Cleanup
    FAILED --> UNLOAD: Cleanup
    UNLOAD --> UNLOADING: NDM removes
    UNLOADING --> [*]: Removed

    note right of ACTIVE: Endpoints registered<br/>Processing requests
    note right of LOADED: Ready to activate<br/>No endpoints
    note right of FAILED: Error logged<br/>Manual intervention may be needed
```

---

## Rendering These Diagrams

These Mermaid diagrams can be rendered using:

1. **GitHub** - Native Mermaid support in markdown files
2. **Mermaid Live Editor** - https://mermaid.live
3. **VS Code** - Mermaid preview extensions
4. **Export to PNG/SVG** - Via Mermaid CLI or live editor

For the architect call, recommend using Mermaid Live Editor to export high-quality PNGs.
