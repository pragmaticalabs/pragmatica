# Aether Architecture Diagrams

Visual diagrams for understanding Aether's distributed runtime architecture.

---

## 1. Runtime Topology (5-Node Cluster)

```mermaid
graph TB
    subgraph External["External Access"]
        CLI["aether CLI"]
        API["Management API<br/>/api/v1/*"]
        Agent["AI Agent"]
    end

    subgraph Cluster["Aether Cluster"]
        subgraph Node1["Node 1 (LEADER)"]
            CDM1["ClusterDeploymentManager<br/>⚡ ACTIVE"]
            NDM1["NodeDeploymentManager"]
            MC1["MetricsCollector"]
            MA["MetricsAggregator"]
            CC["Cluster Controller"]
            ER1["EndpointRegistry"]
            S1A["Slice A"]
            S1B["Slice B"]
        end

        subgraph Node2["Node 2"]
            CDM2["ClusterDeploymentManager<br/>💤 DORMANT"]
            NDM2["NodeDeploymentManager"]
            MC2["MetricsCollector"]
            ER2["EndpointRegistry"]
            S2A["Slice A"]
            S2C["Slice C"]
        end

        subgraph Node3["Node 3"]
            CDM3["ClusterDeploymentManager<br/>💤 DORMANT"]
            NDM3["NodeDeploymentManager"]
            MC3["MetricsCollector"]
            ER3["EndpointRegistry"]
            S3B["Slice B"]
            S3C["Slice C"]
        end

        subgraph Node4["Node 4"]
            CDM4["ClusterDeploymentManager<br/>💤 DORMANT"]
            NDM4["NodeDeploymentManager"]
            MC4["MetricsCollector"]
            ER4["EndpointRegistry"]
            S4A["Slice A"]
            S4C["Slice C"]
        end

        subgraph Node5["Node 5"]
            CDM5["ClusterDeploymentManager<br/>💤 DORMANT"]
            NDM5["NodeDeploymentManager"]
            MC5["MetricsCollector"]
            ER5["EndpointRegistry"]
            S5B["Slice B"]
        end

        KV[("Consensus KV-Store<br/>(Rabia Protocol)<br/>Blueprints, State, Endpoints")]
    end

    CLI --> API
    Agent --> API
    API --> Node1

    MC2 -.->|MetricsPong| MA
    MC3 -.->|MetricsPong| MA
    MC4 -.->|MetricsPong| MA
    MC5 -.->|MetricsPong| MA
    MA -.->|MetricsPing| MC2
    MA -.->|MetricsPing| MC3
    MA -.->|MetricsPing| MC4
    MA -.->|MetricsPing| MC5

    Node1 <--> KV
    Node2 <--> KV
    Node3 <--> KV
    Node4 <--> KV
    Node5 <--> KV

    style Node1 fill:#e1f5fe
    style CC fill:#ffeb3b
    style MA fill:#ffeb3b
    style CDM1 fill:#4caf50,color:#fff
    style KV fill:#f3e5f5
```

**Key Points:**
- **Leader node** runs active ClusterDeploymentManager, MetricsAggregator, and Cluster Controller
- **All nodes** run NodeDeploymentManager, MetricsCollector, EndpointRegistry
- **Consensus KV-Store** holds persistent state (blueprints, slice states, endpoints)
- **Metrics flow via MessageRouter** (dotted lines) - zero consensus I/O for metrics
- **Slices distributed** across nodes based on blueprint requirements

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
