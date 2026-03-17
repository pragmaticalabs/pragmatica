# Networking and Transport

This document describes the cluster transport layer, topology management, and message routing.

## Network Architecture

```mermaid
graph TB
    subgraph Node["Aether Node"]
        subgraph Ports["Network Interfaces"]
            APP[":8081 App HTTP"]
            MGMT[":8080 Management"]
            CLUSTER[":8090 Cluster"]
        end

        CN["ClusterNetwork<br/>(Netty TCP)"]
        TM["TopologyManager"]
        MR["MessageRouter"]

        CLUSTER --> CN
        CN --> TM
        CN --> MR
    end

    subgraph Peers["Peer Nodes"]
        P1["Node B :8090"]
        P2["Node C :8090"]
    end

    CN <-->|"TCP (optional TLS)"| P1
    CN <-->|"TCP (optional TLS)"| P2

    MR -->|"Consensus"| Rabia["RabiaEngine"]
    MR -->|"Invocation"| IH["InvocationHandler"]
    MR -->|"Metrics"| MC["MetricsCollector"]
    MR -->|"DHT"| DHT["DHTNode"]
    MR -->|"HTTP Forward"| FWD["AppHttpServer"]
    MR -->|"SWIM"| SWIM["SwimProtocol"]
```

## ClusterNetwork

Netty-based TCP transport for all inter-node communication.

### Connection Management

```mermaid
sequenceDiagram
    participant A as Node A
    participant B as Node B

    A->>B: TCP connect to :8090
    B-->>A: Connection established

    A->>B: Handshake (NodeId, capabilities)
    B-->>A: Handshake response

    Note over A,B: Connection ready for messages

    loop Heartbeat
        A->>B: Ping
        B-->>A: Pong
    end

    alt Connection lost
        A->>A: Reconnect with backoff
        Note over A: Exponential backoff<br/>with jitter
    end
```

- Peer list configured at startup (no external service registry)
- Automatic reconnection with exponential backoff
- Heartbeat-based liveness detection

### Quorum Detection

```mermaid
graph TB
    TM["TopologyManager"] --> Check["Connected peers >= (N/2)+1?"]

    Check -->|"Yes"| Quorum["QUORUM PRESENT<br/>Consensus active"]
    Check -->|"No"| NoQuorum["QUORUM LOST<br/>Consensus paused,<br/>state persisted"]
```

A 5-node cluster tolerates 2 simultaneous failures. When quorum is lost, consensus stops and the node persists its state. When quorum is restored, the node synchronizes and resumes.

## MessageRouter

Multiplexes all inter-node messages over the single TCP connection. Each message type is routed to its handler:

| Message Type | Handler | Description |
|-------------|---------|-------------|
| Consensus messages | RabiaEngine | Rabia protocol votes and batches |
| InvocationRequest/Response | InvocationHandler | Cross-node slice calls |
| MetricsPing/Pong | MetricsCollector | Leader-to-node metrics exchange |
| DHT messages | DHTNode | Artifact storage operations |
| HttpForward messages | AppHttpServer | HTTP request forwarding |
| SWIM messages | SwimProtocol | Worker group membership |

### Routing Pattern

```java
// Targeted delivery - not broadcast
router.route(targetNodeId, message);

// Async delivery - non-blocking
router.routeAsync(() -> new ReactionMessage(...));
```

The MessageRouter is a targeted delivery system, not a broadcast bus. Each message specifies its destination node.

## Envelope Format

All inter-node messages are wrapped in an `Envelope`:

```mermaid
graph LR
    subgraph Envelope
        Header["Header<br/>version, type,<br/>sender, correlationId"]
        Payload["Payload<br/>(Fury-serialized message)"]
    end
```

| Field | Description |
|-------|-------------|
| `version` | Protocol version for compatibility |
| `type` | Message type discriminator |
| `sender` | Source NodeId |
| `correlationId` | Request-response correlation |
| `payload` | Fury-serialized message body |

Envelope versioning allows protocol evolution without breaking existing nodes.

## Topology Management

```mermaid
sequenceDiagram
    participant New as New Node
    participant Peer1 as Peer 1
    participant Peer2 as Peer 2
    participant Peer3 as Peer 3

    New->>Peer1: Connect
    New->>Peer2: Connect
    New->>Peer3: Connect

    New->>Peer1: Request state snapshot
    New->>Peer2: Request state snapshot
    New->>Peer3: Request state snapshot

    Peer1-->>New: Snapshot (version 42)
    Peer2-->>New: Snapshot (version 42)
    Peer3-->>New: Snapshot (version 41)

    New->>New: Select most recent (v42)
    New->>New: Restore local state
    New->>New: Join consensus

    Note over New,Peer3: Node is now active member
```

### Node Discovery

Nodes discover each other via configured peer list. No external service registry (Consul, etcd, etc.) needed. On startup:
1. Connect to known peers
2. Request state snapshot from cluster
3. Restore local state from most recent snapshot
4. Join consensus and begin accepting work

## SWIM Protocol (Worker Groups)

For worker groups outside the core consensus layer, Aether uses SWIM (Scalable Weakly-consistent Infection-style Membership) for failure detection:

```mermaid
sequenceDiagram
    participant A as Node A
    participant B as Node B (target)
    participant C as Node C (delegate)

    A->>B: UDP Ping
    alt B responds
        B-->>A: Ack
        Note over A: B is alive
    else B silent
        A->>C: Ping-Req(B)
        C->>B: UDP Ping
        alt B responds to C
            B-->>C: Ack
            C-->>A: Ack
            Note over A: B is alive
        else B silent to C too
            C-->>A: No Ack
            Note over A: B suspected
        end
    end
```

### SWIM Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `period` | 1s | Probe interval |
| `probeTimeout` | 500ms | Wait for Ack |
| `indirectProbes` | 3 | PingReq targets |
| `suspectTimeout` | 5s | SUSPECT -> FAULTY |
| `maxPiggyback` | 8 | Updates per message |

### SWIM Properties

| Property | Value |
|----------|-------|
| Detection | O(1) per node per round |
| Dissemination | Piggybacked on ping/ack messages |
| Transport | UDP (Netty `NioDatagramChannel`) |
| Encryption | AES-256-GCM (see [10-security.md](10-security.md)) |
| Incarnation counter | Refutes false suspicions (higher = more recent) |
| Member states | ALIVE -> SUSPECT -> FAULTY |

### Piggybacked Dissemination

Membership changes (join, leave, suspect, confirm) are piggybacked on existing ping/ack messages rather than broadcast separately. This provides O(log N) dissemination with zero additional network messages.

## Related Documents

- [01-consensus.md](01-consensus.md) - Rabia protocol that uses this transport
- [05-worker-pools.md](05-worker-pools.md) - SWIM protocol for worker group membership
- [10-security.md](10-security.md) - mTLS and gossip encryption
